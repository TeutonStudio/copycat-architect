package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.Json
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen

/** Staged real-widget coverage: native widgets need render frames after every document rebuild. */
internal object DiscRotationSmoke {
    private lateinit var previous:ShapeScreen
    private lateinit var shape:ShapeScreen
    private var previousJson=""
    private var previousScale=1
    private var fixtureJson=""
    private var material=0
    private var surfaces:Map<String,Int> = emptyMap()

    private fun fixture()=Form(id="disc_rotation",version=9,name="Centered quarter disc",
        groups=listOf(MaterialGroup(),MaterialGroup("cap","Disc surface","custom_trim",4)),
        graph=NodeGraph(nodes=listOf(
            GraphNode("disc",part=Part(BaseShapes.DISC_SOURCE,tilt=1,group="cap",discCut=2),x=20,y=65),
            GraphNode("rotate",NodeKind.ROTATE,listOf("disc",""),x=250,y=65),
            GraphNode("result",NodeKind.OUTPUT,listOf("rotate"),x=490,y=65,previewGroup="cap"))))

    fun begin(mc:Minecraft,original:ShapeScreen) {
        previous=original;previousJson=Json.encode(original.form);previousScale=mc.options.guiScale().get()
        mc.options.guiScale().set(1);mc.resizeDisplay()
        shape=ShapeScreen().apply {
            form=fixture();graphMode=true;graphInspector=false;material=2
            surfaceMaterials["body"]=3;surfaceMaterials["cap"]=4
            selectShapeNode("rotate")
        }
        material=shape.material;surfaces=shape.surfaceMaterials.toMap();fixtureJson=Json.encode(shape.form)
        mc.setScreen(shape)
        check(rotationNode().rotationAxis==RotationAxis.NORMAL) { "The rotation node must default to the mounted face normal" }
        check(checkNotNull(shape.kilaGraph).rotationButtonPoint("disc",1)==null) { "Copycat source nodes must not expose rotation controls" }
    }

    private fun click(screen:Screen,point:Pair<Double,Double>) {
        check(point.first>=0 && point.first<screen.width && point.second>=0 && point.second<screen.height) {
            "Disc rotation control must be on screen: $point in ${screen.width} x ${screen.height}"
        }
        check(screen.mouseClicked(point.first,point.second,0)) { "Actual disc rotation control must receive the click" }
        screen.mouseReleased(point.first,point.second,0)
    }
    private fun rotationNode()=checkNotNull(shape.form.graph).nodes.single { it.id=="rotate" }
    private fun button(key:String)=shape.children().filterIsInstance<Button>().single { it.message.string==ui(key) }
    private fun history()=listOf(button("undo").active,button("redo").active)
    private fun press(key:String) {
        val button=button(key);check(button.active) { "$key must be available for disc rotation" }
        click(shape,button.x+button.width/2.0 to button.y+button.height/2.0)
    }
    private fun materialsUnchanged() {
        check(shape.material==material && shape.surfaceMaterials==surfaces) { "Rotating Copycat geometry changed its selected surface items" }
    }
    private fun restored() {
        check(Json.encode(shape.form)==fixtureJson) { "Disc rotation fixture must restore every document field between clicks" }
        materialsUnchanged()
    }
    private fun screenshot(mc:Minecraft,name:String) {
        check(!checkNotNull(shape.graphCanvas).hasOpenMenu()) { "Rotation screenshots must not cover the controls with an axis menu" }
        Screenshot.grab(mc.gameDirectory,"architect-kilagraph-$name.png",mc.mainRenderTarget) { println(it.string) }
    }

    private fun nativeRotation(quarterTurns:Int) {
        restored()
        val editor=checkNotNull(shape.kilaGraph)
        val point=checkNotNull(editor.rotationButtonPoint("rotate",quarterTurns)) { "Native disc rotation button was not laid out" }
        check(point.first>=editor.x && point.first<editor.x+editor.width && point.second>=editor.y && point.second<editor.y+editor.height) {
            "Native disc rotation must remain inside the visible canvas"
        }
        verifyRotation(RotationAxis.NORMAL,quarterTurns,point)
    }
    private fun verifyRotation(axis:RotationAxis,quarterTurns:Int,point:Pair<Double,Double>) {
        val before=shape.form;val graph=checkNotNull(before.graph);val node=rotationNode()
        val source=graph.nodes.single { it.id=="disc" }
        val turns=Math.floorMod(node.quarterTurns+quarterTurns,4)
        val expected=before.copy(graph=graph.replace(node.copy(rotationAxis=axis,quarterTurns=turns)))
        val expectedPart=checkNotNull(PartRotations.rotate(source.part,axis,turns)) { "Centered disc must support $axis / $turns" }
        check(expected!=before) { "Quarter-disc rotation must produce an observable edit" }
        click(shape,point)
        check(shape.form==expected) { "Real $axis / $quarterTurns control must only edit the explicit rotation node" }
        check(shape.form.graph!!.nodes.single { it.id=="disc" }==source) {
            "Rotation must retain the entire Copycat source, including quarter fraction and material group"
        }
        val parts=Geometry.validate(shape.form).parts
        check(Geometry.solid(parts).key()==Solid.part(expectedPart).key()) { "Clicked rotation differs from the expected disc geometry" }
        if(axis==RotationAxis.NORMAL) check(parts.all { PartRotations.normal(it)==PartRotations.normal(source.part) }) {
            "A normal-axis spin must keep the mounted disc plane and signed normal"
        }
        materialsUnchanged()
        press("undo");check(shape.form==before) { "One Undo must restore the complete document before $axis / $quarterTurns" }
        press("redo");check(shape.form==expected) { "One Redo must restore the complete rotated document" }
        press("undo");check(shape.form==before);materialsUnchanged()
    }

    /** Keep both complete rows clear of the sticky node selector and the bottom screen toolbar. */
    private fun revealInspectorRotations() {
        val inspector=checkNotNull(shape.graphCanvas)
        val point=checkNotNull(inspector.rotationButtonPoint(rotationNode().rotationAxis,1))
        val target=point.second.coerceIn(inspector.y+75.0,inspector.y+inspector.height-12.0)
        if(point.second!=target) check(shape.mouseScrolled(inspector.x+inspector.width/2.0,inspector.y+inspector.height-8.0,0.0,(target-point.second)/21.0)) {
            "The actual inspector must scroll to its rotation controls"
        }
        val axis=checkNotNull(inspector.rotationAxisPoint())
        val angle=checkNotNull(inspector.rotationButtonPoint(rotationNode().rotationAxis,1))
        check(axis.second-9>=inspector.y+42 && angle.second+9<=inspector.y+inspector.height) {
            "Axis selector and angle buttons must be wholly visible below the sticky selector: $axis / $angle"
        }
        check(axis.second+9<angle.second-9) { "Axis selector must not overlap its angle buttons" }
        materialsUnchanged()
    }
    private fun chooseAxis(axis:RotationAxis) {
        revealInspectorRotations()
        val inspector=checkNotNull(shape.graphCanvas)
        val before=shape.form;val node=rotationNode();val beforeHistory=history()
        val expected=before.copy(graph=checkNotNull(before.graph).replace(node.copy(rotationAxis=axis)))
        click(shape,checkNotNull(inspector.rotationAxisPoint()))
        check(inspector.hasOpenMenu()) { "Actual axis button must open its dropdown" }
        check(shape.keyPressed(268,0,0)) // Home; choose from the real dropdown order.
        repeat(RotationAxis.entries.indexOf(axis)) { check(shape.keyPressed(264,0,0)) }
        check(shape.keyPressed(257,0,0))
        check(!inspector.hasOpenMenu() && rotationNode().rotationAxis==axis && shape.form==expected) {
            "Keyboard axis choice must persist $axis in the rotation node"
        }
        if(expected==before) check(history()==beforeHistory) { "Reselecting an axis must not create an Undo entry" } else {
            press("undo");check(shape.form==before) { "Axis selection must undo atomically" }
            press("redo");check(shape.form==expected) { "Axis selection must redo atomically" }
        }
        materialsUnchanged()
    }
    private fun inspectorRotations(axis:RotationAxis) {
        restored()
        chooseAxis(axis)
        for(turns in listOf(-1,2,1)) {
            revealInspectorRotations()
            val inspector=checkNotNull(shape.graphCanvas)
            verifyRotation(axis,turns,checkNotNull(inspector.rotationButtonPoint(axis,turns)))
        }
        if(axis!=RotationAxis.NORMAL) press("undo")
        restored()
    }

    /** Called after KilaGraphSmoke's previous acceptance steps, once per client tick. */
    fun tick(mc:Minecraft,elapsed:Int):Boolean {
        check(mc.screen===shape) { "Disc rotation must stay in its independent ShapeScreen fixture" }
        when(elapsed) {
            10 -> checkNotNull(shape.kilaGraph).fit()
            20 -> screenshot(mc,"disc-native-rotations")
            25 -> nativeRotation(-1)
            35 -> nativeRotation(2)
            45 -> nativeRotation(1)
            55 -> { restored();shape.openGraph(true) }
            65 -> { revealInspectorRotations();screenshot(mc,"disc-axis-controls") }
            70 -> inspectorRotations(RotationAxis.NORMAL)
            80 -> inspectorRotations(RotationAxis.X)
            90 -> inspectorRotations(RotationAxis.Y)
            100 -> inspectorRotations(RotationAxis.Z)
            110 -> {
                restored();mc.options.guiScale().set(2);mc.resizeDisplay();shape.openGraph(false)
                check(shape.width>=400 && shape.width<500 && shape.height>=230 && shape.height<300) { "Compact disc smoke must exercise the 427 x 240 class of viewport" }
            }
            120 -> checkNotNull(shape.kilaGraph).fit()
            130 -> screenshot(mc,"compact-disc-native-rotations")
            140 -> { restored();shape.openGraph(true) }
            145 -> revealInspectorRotations()
            150 -> screenshot(mc,"compact-disc-axis-controls")
            155 -> inspectorRotations(RotationAxis.NORMAL)
            165 -> inspectorRotations(RotationAxis.X)
            175 -> inspectorRotations(RotationAxis.Y)
            185 -> inspectorRotations(RotationAxis.Z)
            195 -> {
                restored();mc.options.guiScale().set(previousScale);mc.resizeDisplay();mc.setScreen(previous)
                check(Json.encode(previous.form)==previousJson) { "Independent rotation acceptance changed the original document" }
                println("ARCHITECT DISC ROTATION: explicit rotation node, native -90/180/+90 normal spins, fixed mounted plane, persisted NORMAL/X/Y/Z keyboard axis choices, real inspector angle clicks at normal and compact scale, unchanged source/fraction/material, exact atomic Undo/Redo and unobscured scrolled controls verified")
                return true
            }
        }
        return false
    }
}
