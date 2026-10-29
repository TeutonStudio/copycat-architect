package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.Economy
import de.teutonstudio.copycatarchitect.server.Json
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.world.item.ItemStack

/** Real screen events cover both source buttons, cancellation and atomic document edits. */
internal object CopycatSelectionSmoke {
    private fun click(screen:Screen,point:Pair<Double,Double>) {
        check(screen.mouseClicked(point.first,point.second,0))
        screen.mouseReleased(point.first,point.second,0)
    }
    private fun history(shape:ShapeScreen)=listOf("undo","redo").map { key ->
        shape.children().filterIsInstance<Button>().single { it.message.string==ui(key) }.active
    }
    private fun press(shape:ShapeScreen,key:String) {
        val button=shape.children().filterIsInstance<Button>().single { it.message.string==ui(key) }
        check(button.active) { "$key must be available for Copycat selection" }
        click(shape,button.x+button.width/2.0 to button.y+button.height/2.0)
    }
    private fun picker(mc:Minecraft)=mc.screen as? CopycatSelectionScreen
        ?: error("The source button must open the Minecraft inventory picker")
    private fun inspectorPicker(mc:Minecraft,shape:ShapeScreen,id:String):CopycatSelectionScreen {
        shape.selectShapeNode(id);shape.openGraph(true)
        val inspector=checkNotNull(shape.graphCanvas)
        click(shape,inspector.x+30.0 to inspector.y+71.0)
        return picker(mc)
    }
    private fun select(screen:CopycatSelectionScreen,shape:BaseShape) {
        click(screen,screen.slotPoint(shape.source,shape.discCut) ?: error("Missing Copycat inventory slot: ${shape.id}"))
    }

    /** The caller first gives the native graph several render frames to lay out its source button. */
    fun verify(mc:Minecraft,shape:ShapeScreen) {
        check(mc.screen===shape)
        val original=shape.form
        val node=checkNotNull(original.graph).nodes.first { it.kind==NodeKind.INPUT }
        val originalJson=Json.encode(original)
        val initialHistory=history(shape)
        Adapters.all.keys.forEach { source ->
            check(!ItemStack(Economy.item(source)).isEmpty) { "Copycat icon must use its registered item: $source" }
        }

        val native=checkNotNull(shape.kilaGraph)
        click(shape,native.sourceButtonPoint(node.id) ?: error("Native source button was not laid out"))
        val cancelled=picker(mc)
        BaseShapes.all.forEach { check(cancelled.slotPoint(it.source,it.discCut)!=null) { "Every supported shape needs an inventory slot" } }
        check(Json.encode(shape.form)==originalJson && history(shape)==initialHistory)
        check(cancelled.keyPressed(256,0,0))
        check(mc.screen===shape && Json.encode(shape.form)==originalJson && history(shape)==initialHistory) {
            "Escape from the native source picker changed the document or its history"
        }

        select(inspectorPicker(mc,shape,node.id),BaseShapes.find(node.part.source,node.part.discCut))
        check(mc.screen===shape && Json.encode(shape.form)==originalJson && history(shape)==initialHistory) {
            "Selecting the existing Copycat must not create an edit"
        }

        BaseShapes.all.forEach { target ->
            // A valid oriented layer makes resetting source-specific fields observable.
            // Use a panel when selecting the layer so every catalog entry changes source.
            val baseline=if(target.source=="copycats:copycat_layer") "create:copycat_panel" else "copycats:copycat_layer"
            val preparedNode=node.copy(part=node.part.copy(source=baseline,turn=1,top=true,
                size=if(baseline=="copycats:copycat_layer") 2 else 1,offset=P(2,0,0),tilt=1,spin=1,group=original.groups.last().id))
            Solid.part(preparedNode.part)
            shape.commitShapeGraph(original.graph!!.replace(preparedNode))
            val prepared=shape.form
            select(inspectorPicker(mc,shape,node.id),target)
            check(mc.screen===shape)
            val expected=prepared.copy(graph=prepared.graph!!.replace(preparedNode.copy(
                part=preparedNode.part.copy(source=target.source,discCut=target.discCut,size=1,offset=P(),tilt=0,spin=0))))
            val changed=shape.form
            check(changed==expected) { "Selecting $target changed unrelated node, material or document fields" }
            Solid.part(changed.graph!!.nodes.first { it.id==node.id }.part)
            press(shape,"undo")
            check(shape.form==prepared) { "One Undo must restore the complete previous Copycat before $target" }
            press(shape,"redo")
            check(shape.form==changed) { "One Redo must restore the selected Copycat: $target" }
            press(shape,"undo")
            press(shape,"undo")
            check(Json.encode(shape.form)==originalJson) { "The $target fixture must restore the original document" }
        }

        fun keyboardSelection(source:BaseShape,vararg keys:Int,verifyCurrentFocus:Boolean=false) {
            val before=shape.form
            val beforeHistory=history(shape)
            val keyboard=inspectorPicker(mc,shape,node.id)
            keys.forEach { check(keyboard.keyPressed(it,0,0)) }
            keyboard.resize(mc,mc.window.guiScaledWidth,mc.window.guiScaledHeight)
            check(mc.screen===keyboard && shape.form==before && history(shape)==beforeHistory) {
                "Navigating or resizing the inventory picker must leave the document untouched"
            }
            check(keyboard.keyPressed(257,0,0))
            val current=before.graph!!.nodes.first { it.id==node.id }
            val expected=if(source.source==current.part.source && source.discCut==current.part.discCut) before else before.copy(graph=before.graph.replace(current.copy(
                part=current.part.copy(source=source.source,discCut=source.discCut,size=1,offset=P(),tilt=0,spin=0))))
            check(mc.screen===shape && shape.form==expected) { "Keyboard selection must choose $source after a resize" }
            if(verifyCurrentFocus) {
                val selectedHistory=history(shape)
                val currentPicker=inspectorPicker(mc,shape,node.id)
                currentPicker.resize(mc,mc.window.guiScaledWidth,mc.window.guiScaledHeight)
                check(currentPicker.keyPressed(257,0,0))
                check(mc.screen===shape && shape.form==expected && history(shape)==selectedHistory) {
                    "The selected last Copycat must retain initial focus through reopening and resize"
                }
            }
            if(source.source!=current.part.source || source.discCut!=current.part.discCut) {
                press(shape,"undo")
                check(shape.form==before) { "Keyboard selection must use the same atomic document history" }
            } else check(history(shape)==beforeHistory) { "Selecting the current Copycat must preserve history" }
        }
        val sources=BaseShapes.all
        keyboardSelection(sources[1],268,262) // Home, Right
        if(sources.size>9) keyboardSelection(sources[9],268,264) // Home, Down to the second row
        keyboardSelection(BaseShapes.find(BaseShapes.DISC_SOURCE,2),268,*IntArray(sources.indexOf(BaseShapes.find(BaseShapes.DISC_SOURCE,2))) { 262 })
        keyboardSelection(sources.last(),269,verifyCurrentFocus=true) // End
        shape.selectShapeNode(node.id);shape.openGraph(true)
        check(Json.encode(shape.form)==originalJson)
        println("ARCHITECT COPYCAT PICKER: native and inspector buttons, all ${sources.size} real item slots and source resets, Escape, unchanged selection, atomic Undo/Redo, keyboard rows, last-entry focus and resize verified")
    }
}
