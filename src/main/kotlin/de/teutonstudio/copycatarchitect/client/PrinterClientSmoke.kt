package de.teutonstudio.copycatarchitect.client

import com.mojang.math.Axis
import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.integration.CreativeCatalog
import de.teutonstudio.copycatarchitect.server.*
import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions
import net.createmod.ponder.foundation.PonderIndex
import net.createmod.ponder.foundation.PonderScene
import net.createmod.ponder.foundation.registration.PonderLocalization
import net.createmod.ponder.foundation.registration.PonderSceneRegistry
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.GLFW

/** Opt-in acceptance that needs loaded client resources, but no world or network connection. */
object PrinterClientSmoke {
    private var ticks=0
    private var failed=false
    private lateinit var shape:ShapeScreen
    private lateinit var view:PrinterView
    private lateinit var model:PrinterModelScreen
    private var original=""
    private var peekCapture=0L
    private val mouseMove by lazy {
        MouseHandler::class.java.getDeclaredMethod("onMove",java.lang.Long.TYPE,java.lang.Double.TYPE,java.lang.Double.TYPE).apply { isAccessible=true }
    }
    private fun fixture()=Geometry.validate(Form(id="printer_client",name="Zinc and gold print",
        groups=listOf(MaterialGroup(),MaterialGroup("cap","Gold","frame",1)),
        graph=NodeGraph(nodes=listOf(
            GraphNode("slab",part=Part("copycats:copycat_slab"),x=20,y=20),
            GraphNode("angle",NodeKind.ROTATION,x=20,y=180,rotationAxis=RotationAxis.Z,quarterTurns=1),
            GraphNode("rotate",NodeKind.ROTATE,listOf("slab","angle"),x=250,y=150),
            GraphNode("xor",NodeKind.SYMMETRIC_DIFFERENCE,listOf("slab","rotate"),inputGroups=listOf("body","cap"),x=480,y=20),
            GraphNode("result",NodeKind.OUTPUT,listOf("xor"),x=710,y=20)))))
    private fun screenshot(mc:Minecraft,name:String) {
        Screenshot.grab(mc.gameDirectory,"architect-printer-$name.png",mc.mainRenderTarget) { println(it.string) }
    }
    private fun button(screen:Screen,key:String)=screen.children().filterIsInstance<Button>().single { it.message.string==ui(key) }
    private fun click(screen:Screen,button:Button) {
        check(button.x>=0 && button.y>=0 && button.x+button.width<=screen.width && button.y+button.height<=screen.height)
        check(screen.mouseClicked(button.x+button.width/2.0,button.y+button.height/2.0,0))
        screen.mouseReleased(button.x+button.width/2.0,button.y+button.height/2.0,0)
    }
    private fun pointer(mc:Minecraft,x:Double,y:Double) {
        val screen=checkNotNull(mc.screen)
        screen.mouseMoved(x,y)
        val rawX=x*mc.window.screenWidth/screen.width;val rawY=y*mc.window.screenHeight/screen.height
        GLFW.glfwSetCursorPos(mc.window.window,rawX,rawY)
        // GLFW may omit cursor callbacks for background windows. Exercise Minecraft's actual
        // callback as well: GameRenderer reads MouseHandler coordinates for render-time hover.
        mouseMove.invoke(mc.mouseHandler,mc.window.window,rawX,rawY)
        check(kotlin.math.abs(mc.mouseHandler.xpos()-rawX)<0.001 && kotlin.math.abs(mc.mouseHandler.ypos()-rawY)<0.001) {
            "The real mouse callback must update render-time pointer coordinates"
        }
    }
    private fun verifyGraph() {
        val editor=checkNotNull(shape.kilaGraph) { "The printer must use the real native graph editor" }
        val geometry=checkNotNull(editor.outputPortType("slab"));val rotation=checkNotNull(editor.outputPortType("angle"))
        check(geometry.friendlyName==ui("port_copycat") && rotation.friendlyName==ui("port_rotation"))
        check(geometry==editor.inputPortType("rotate",0) && rotation==editor.inputPortType("rotate",1) && geometry!=rotation)
        check(editor.rotationButtonPoint("slab",1)==null) { "A Copycat source must not expose embedded rotation controls" }
        check(!button(shape,"printer_print").active) { "Printing without a connected player must be disabled" }
        check(Json.encode(shape.form)==original && view.slots.size==7)
    }
    private fun navigation() {
        val before=Json.encode(shape.form)
        val history=button(shape,"undo").active to button(shape,"redo").active
        repeat(3) { shape.openGraph(true);shape.openGraph(false) }
        check(Json.encode(shape.form)==before && history==(button(shape,"undo").active to button(shape,"redo").active)) {
            "Changing printer presentations must preserve the graph and document history"
        }
    }
    private fun draftHistory() {
        val before=shape.printerCommand("printerPrint")
        val changed=view.copy(form=view.form.copy(name="Restored printer settings"),
            surfaces=mapOf("body" to "minecraft:oak_planks","cap" to "minecraft:glass"),turn=3)
        shape.loadPrinterDraft(changed)
        val after=shape.printerCommand("printerPrint")
        check(Json.decode<Form>(after.payload).name==changed.form.name && after.surfaces==changed.surfaces && after.turn==3)
        click(shape,button(shape,"undo"))
        check(shape.printerCommand("printerPrint")==before) { "Undo must restore the complete printer draft, coatings and placement turn" }
        click(shape,button(shape,"redo"))
        check(shape.printerCommand("printerPrint")==after) { "Redo must restore the complete imported printer draft" }
        click(shape,button(shape,"undo"))
        check(shape.printerCommand("printerPrint")==before && Json.encode(shape.form)==original)
    }
    private fun catalogAndPonder(mc:Minecraft) {
        val tab=CreativeCatalog.TAB.get()
        check(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab)==CreativeCatalog.ID)
        val sections=CreativeCatalog.sections()
        check(sections.keys.toList()==CreativeCatalogSection.entries)
        val procedural=sections.getValue(CreativeCatalogSection.PROCEDURAL)
        check(procedural.size==5 && procedural[0].`is`(Architect.GUN.get()) && procedural[4].`is`(Architect.TABLE_ITEM.get()))
        val native=sections.getValue(CreativeCatalogSection.BASIC)
        check(Adapters.all.keys.all { id -> native.any { BuiltInRegistries.ITEM.getKey(it.item).toString()==id } }) {
            "The grouped creative tab must retain every supported native Copycat shape"
        }
        val saved=sections.getValue(CreativeCatalogSection.SAVED)
        check(saved.isNotEmpty() && saved.all { it.`is`(Architect.COMPOSITE_ITEM.get()) && PrinterStack.tag(it).getString("form_definition").isNotEmpty() })
        val sample=saved.first()
        check(mc.itemRenderer.getModel(sample,null,null,0).isCustomRenderer && IClientItemExtensions.of(sample).customRenderer is CompositeFormItemRenderer) {
            "Saved creative and recipe-viewer entries must use their dynamic form renderer"
        }
        CreativeCatalogClient.arrange(tab)
        val arranged=tab.displayItems.toList()
        check(arranged.size%9==0 && arranged.take(9).all { it.isEmpty } && arranged[9].`is`(Architect.GUN.get()))
        check(arranged.chunked(9).count { row -> row.all { it.isEmpty } }==CreativeCatalogSection.entries.size) {
            "Each creative section must have its own non-collectible empty header row"
        }
        check(saved.all { item -> arranged.any { ItemStack.isSameItemSameComponents(it,item) } })
        check(PonderIndex.streamPlugins().anyMatch { it is ArchitectPonderPlugin }) { "The Architect Ponder plugin must be registered" }
        val scenes=PonderIndex.getSceneAccess()
        for(item in listOf(Architect.GUN.get(),CartridgeRegistry.EMPTY.get(),Architect.CARTRIDGE.get(),CartridgeRegistry.CREATIVE.get(),Architect.TABLE_ITEM.get()))
            check(scenes.doScenesExistForId(BuiltInRegistries.ITEM.getKey(item))) { "Missing registered Ponder scene for ${BuiltInRegistries.ITEM.getKey(item)}" }
        val tutorials=listOf(Architect.GUN.get() to "printer",Architect.CARTRIDGE.get() to "cartridges",Architect.TABLE_ITEM.get() to "table")
        for((item,name) in tutorials) {
            val id=BuiltInRegistries.ITEM.getKey(item)
            val entry=scenes.registeredEntries.first { it.key==id && it.value.namespace==Architect.ID }.value
            val schematic=entry.schematicLocation
            check(mc.resourceManager.getResource(ResourceLocation.fromNamespaceAndPath(schematic.namespace,"ponder/${schematic.path}.nbt")).isPresent) { "Ponder schematic is missing for $name" }
            // Ponder explicitly supports composition with a null world for scene and text validation.
            // Rendering and executing its world instructions require an actual client level.
            val composed=PonderSceneRegistry.compileScene(PonderLocalization(),entry,null)
            // In pinned Ponder 1.0.82 the schedule has no public accessor. Duration is computed
            // only by begin()/onScheduled, so it is still zero after worldless composition.
            val schedule=PonderScene::class.java.getDeclaredField("schedule").apply { isAccessible=true }.get(composed) as List<*>
            val expected=ResourceLocation.fromNamespaceAndPath(Architect.ID,name)
            val diagnostic="id=${composed.id}, expected=$expected, instructions=${schedule.size}, durationBeforeBegin=${composed.totalTime}, title=${composed.title}"
            check(composed.id==expected && schedule.size>=16 && composed.title.isNotBlank()) {
                "The complete $name storyboard must compose its instruction plan: $diagnostic"
            }
            println("ARCHITECT PONDER COMPOSED $name: $diagnostic")
        }
        println("ARCHITECT CATALOG CLIENT: grouped tab, complete basic shapes, four header rows, saved-form icons, Ponder mappings and all three actual storyboard compositions verified; Ponder world rendering is outside this worldless run")
    }
    fun tick():Boolean {
        if(failed) return false
        val mc=Minecraft.getInstance()
        if(ticks==0 && (mc.screen==null || mc.overlay!=null)) return false
        ticks++;var acted=true
        try {
            when(ticks) {
                10 -> {
                    check(mc.player==null && mc.level==null) { "Printer UI acceptance must start without a world" }
                    mc.options.guiScale().set(1);mc.resizeDisplay()
                    val form=fixture();val surfaces=mapOf("body" to "","cap" to "minecraft:gold_block")
                    val slots=listOf(CartridgeView("copycat_architect:cartridge",192),CartridgeView("copycat_architect:cartridge",256),
                        CartridgeView("copycat_architect:empty_cartridge",0),CartridgeView(),CartridgeView("copycat_architect:creative_cartridge",Int.MAX_VALUE,true),
                        CartridgeView("copycat_architect:cartridge",128),CartridgeView("copycat_architect:empty_cartridge",0))
                    view=PrinterView(P(1,1,1),"printer-client-session",form,surfaces,0,slots,true)
                    val library=Library().apply { seed();history+=PrintRecord(id="printer-preview",form=form,surfaces=surfaces,time=1_700_000_000_000) }
                    ClientState.snapshot=Snapshot(library=library,printer=view)
                    shape=ShapeScreen(printerSession=view).apply { graphMode=true;graphInspector=false;selectShapeNode("xor") }
                    original=Json.encode(shape.form);mc.setScreen(shape);pointer(mc,shape.width-30.0,shape.height-12.0)
                }
                25 -> { navigation();draftHistory();checkNotNull(shape.kilaGraph).fit();verifyGraph();catalogAndPonder(mc) }
                27 -> {
                    val command=shape.printerCommand("printerPrint")
                    check(command.surfaces["cap"]=="minecraft:gold_block") { "An imported non-preset coating must remain assigned" }
                    click(shape,button(shape,"groups"))
                    check(shape.groupMode && !shape.graphMode && shape.printerCommand("printerPrint")==command) {
                        "The real Groups button must preserve the printer's definition, session and coatings"
                    }
                    check(!button(shape,"printer_print").active && shape.children().filterIsInstance<Button>().none { it.message.string==ui("equip") }) {
                        "Printer group controls must retain Print and omit the legacy Equip action"
                    }
                }
                32 -> screenshot(mc,"groups-normal")
                33 -> { click(shape,button(shape,"node_graph"));check(shape.graphMode && !shape.groupMode);verifyGraph() }
                35 -> {
                    check(PrinterDisplayCapture.isEditorActive(view.session) && PrinterDisplayCapture.width in 1..512 && PrinterDisplayCapture.height in 1..512) {
                        "The actual printer editor must have a bounded GPU display capture"
                    }
                    check(!PrinterDisplayCapture.isEditorActive("another-printer-session"))
                    screenshot(mc,"graph-normal")
                }
                40 -> pointer(mc,shape.width-271.0,12.0)
                45 -> {
                    check(WorldPeek.active) {
                        "Rendering the real hovered eye must reveal the world: pointer=${mc.mouseHandler.xpos()},${mc.mouseHandler.ypos()}, screen=${shape.width}x${shape.height}"
                    }
                    check(Json.encode(shape.form)==original);peekCapture=PrinterDisplayCapture.serial;screenshot(mc,"eye-world-peek")
                }
                48 -> check(PrinterDisplayCapture.serial==peekCapture) { "The hovered eye must freeze the display image before revealing the world" }
                50 -> pointer(mc,shape.width-30.0,shape.height-12.0)
                55 -> {
                    check(!WorldPeek.active && PrinterDisplayCapture.serial>peekCapture) { "Moving away from the eye must restore the editor and its GPU mirror" }
                }
                60 -> { mc.options.guiScale().set(2);mc.resizeDisplay();shape.openGraph(false);pointer(mc,shape.width-20.0,shape.height-12.0) }
                70 -> { checkNotNull(shape.kilaGraph).fit();verifyGraph() }
                80 -> screenshot(mc,"graph-compact")
                90 -> shape.openGraph(true)
                100 -> { check(Json.encode(shape.form)==original);screenshot(mc,"inspector-compact") }
                110 -> { mc.setScreen(PrinterStorageScreen(shape));WorldPeek.active=false }
                120 -> { check(ClientState.snapshot.printer?.slots?.size==7);click(checkNotNull(mc.screen),button(checkNotNull(mc.screen),"ps_saved")) }
                130 -> screenshot(mc,"storage-compact")
                135 -> click(checkNotNull(mc.screen),button(checkNotNull(mc.screen),"ps_history"))
                140 -> { mc.options.guiScale().set(1);mc.resizeDisplay() }
                150 -> screenshot(mc,"storage-normal")
                160 -> {
                    model=PrinterModelScreen(view);mc.setScreen(model)
                    check(!PrinterDisplayCapture.isEditorActive(view.session) && PrinterDisplayCapture.hasCapture(view.session)) {
                        "Closed editors must use the world graph fallback; the visual fixture retains the last captured UI"
                    }
                }
                175 -> screenshot(mc,"frame-and-ui-display")
                185 -> model.front=true
                200 -> screenshot(mc,"frame-interior-preview")
                210 -> {
                    check(Json.encode(shape.form)==original)
                    println("ARCHITECT PRINTER CLIENT SMOKE PASSED: native typed graph, complete draft undo/redo, normal and compact printer controls, real eye hover and frozen GPU capture, seven-slot storage, actual block model, letterboxed UI display and translucent interior preview verified")
                    mc.stop()
                }
                else -> acted=false
            }
        } catch(e:Throwable) {
            failed=true;System.err.println("ARCHITECT PRINTER CLIENT SMOKE FAILED at tick $ticks: ${e.message}");e.printStackTrace()
            screenshot(mc,"failure-tick$ticks");throw IllegalStateException("Printer client smoke failed at tick $ticks",e)
        }
        return acted
    }

    /** Uses the registered block model and block entity renderer in a world-independent scene. */
    private class PrinterModelScreen(private val view:PrinterView):Screen(Component.literal("Block printer")) {
        var front=false
        private val entity=PrinterEntity(BlockPos.ZERO,Architect.PRINTER.get().defaultBlockState()).apply {
            preview=view.form;surfaces=view.surfaces;turn=view.turn;session=view.session
        }
        override fun render(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
            val mc=Minecraft.getInstance();val state=entity.blockState
            g.fill(0,0,width,height,0xff172322.toInt());g.fill(0,0,width,28,0xff354a49.toInt())
            g.drawCenteredString(font,Component.translatable("block.copycat_architect.block_printer"),width/2,10,0xfff0d49a.toInt())
            val renderer=checkNotNull(mc.blockEntityRenderDispatcher.getRenderer(entity)) { "The world printer renderer must be registered" }
            check(renderer is PrinterRenderer)
            val model=mc.blockRenderer.getBlockModel(state)
            check(model.getQuads(state,null,RandomSource.create(42)).size>24) { "Printer resources must contain the actual frame and display model" }
            val panelWidth=(width-48)/2;val panelHeight=height-82;val top=44
            for(index in 0..1) {
                val left=16+index*(panelWidth+16)
                g.fill(left,top,left+panelWidth,top+panelHeight,0xff203031.toInt());g.renderOutline(left,top,panelWidth,panelHeight,0xff526e69.toInt())
                g.enableScissor(left+1,top+1,left+panelWidth-1,top+panelHeight-1)
                g.flush();val pose=g.pose();pose.pushPose()
                pose.translate(left+panelWidth/2.0,top+panelHeight/2.0,200.0)
                val scale=minOf(panelWidth,panelHeight)*if(index==1) 0.76f else 0.58f
                pose.scale(scale,-scale,scale)
                pose.mulPose(Axis.XP.rotationDegrees(if(index==1) 90f else if(front) 8f else 28f))
                pose.mulPose(Axis.YP.rotationDegrees(if(index==1) 0f else if(front) 15f else 35f))
                pose.translate(-0.5,-0.5,-0.5)
                mc.blockRenderer.renderSingleBlock(state,pose,g.bufferSource(),15728880,OverlayTexture.NO_OVERLAY)
                renderer.render(entity,delta,pose,g.bufferSource(),15728880,OverlayTexture.NO_OVERLAY)
                check(PrinterDisplayCapture.drawRetained(view.session,pose,g.bufferSource())) {
                    "The isolated display model must show pixels captured from the actual printer editor"
                }
                g.flush();pose.popPose();g.disableScissor()
            }
            g.drawCenteredString(font,view.form.name,width/2,height-24,0xffd8d4b9.toInt())
            g.renderItem(CompositeFormItem.stack(view.form,view.surfaces,0),16,height-26)
            g.renderItem(CompositeFormItem.stack(view.form,emptyMap(),1),38,height-26)
        }
    }
}
