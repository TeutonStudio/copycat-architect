package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.*
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen

/** Real client acceptance: native KilaGraph input, the shared inspectors and lossless navigation. */
object KilaGraphSmoke {
    private fun groupForm()=Form(id="multi_material",name="Stone + wood",parts=listOf(Part("copycats:copycat_block"),Part("copycats:copycat_layer",top=true,group="cap")),groups=listOf(MaterialGroup(),MaterialGroup("cap","Cap","trim",1)))
    private fun verifyGroups() {
        val f=groupForm();val faces=Meshes.mesh(f.parts,groups=f.groups)
        val tiles=mutableSetOf<String>()
        for(face in faces) {
            val axis=face.direction.axis.ordinal;val uv=(0..2).filter { it!=axis }
            val minU=(face.vertices.minOf { it[uv[0]] }*16).toInt();val maxU=(face.vertices.maxOf { it[uv[0]] }*16).toInt()
            val minV=(face.vertices.minOf { it[uv[1]] }*16).toInt();val maxV=(face.vertices.maxOf { it[uv[1]] }*16).toInt()
            val plane=(face.vertices[0][axis]*16).toInt()
            for(u in minU until maxU) for(v in minV until maxV) check(tiles.add("${face.direction}:$plane:$u:$v")) { "Coincident material surface" }
        }
        check(tiles.size==1536) { "Union must have exactly the exterior of one full cube" }
        check(faces.filter { it.direction==net.minecraft.core.Direction.UP }.all { it.group=="cap" })
        val back=f.groups.map { it.copy(priority=1-it.priority) }
        check(Meshes.mesh(f.parts,groups=back).all { it.group=="body" })
        val glass=Meshes.mesh(f.parts,groups=f.groups,transparent=setOf("cap"))
        check(glass.any { it.group=="body" && it.direction==net.minecraft.core.Direction.UP && it.vertices.all { v -> v[1]==14/16f } })
        println("ARCHITECT GROUP MESH: unique exterior faces, foreground switch and glass interface verified")
    }
    private var extraStage=0
    private var extraStart=475
    private var ticks=0
    private var failed=false
    private lateinit var library:Library
    private lateinit var shape:ShapeScreen
    private lateinit var building:BuildingScreen
    private lateinit var classic:Building
    private var shapeBefore=""
    private var wireBefore=""
    private var wireAfter=""
    private var buildingBefore=""
    private var classicPlan:Plan?=null
    private var contextJson=""
    private var contextPosition=0 to 0

    private fun shapeFixture():Form {
        val graph=NodeGraph(nodes=listOf(
            GraphNode("body",part=Part("copycats:copycat_block"),x=20,y=20),
            GraphNode("slab",part=Part("copycats:copycat_slab",group="cap"),x=20,y=200),
            GraphNode("cut",NodeKind.DIFFERENCE,listOf("body","slab"),x=270,y=80,materialMode=MaterialMode.GROUPED,materialGroup="cap"),
            GraphNode("draft",NodeKind.UNION,listOf("",""),x=270,y=290),
            GraphNode("angle",NodeKind.ROTATION,x=20,y=385,quarterTurns=1),
            GraphNode("rotate",NodeKind.ROTATE,listOf("cut",""),x=520,y=270),
            GraphNode("result",NodeKind.OUTPUT,listOf("rotate"),x=520,y=80,previewGroup="cap")))
        return Form(id="kila_shape",version=7,name="Native KilaGraph shape",graph=graph,
            groups=listOf(MaterialGroup(),MaterialGroup("cap","Custom timber","custom_role",4)))
    }
    private fun buildingFixture():Building {
        val initial=BuildingGraph.starter()
        val graph=initial.copy(nodes=initial.nodes+BuildingNode("draft",BuildingKind.PATTERN,"Unused draft",20,720,inputs=listOf("","")))
        return Building(schema=2,id="kila_building",version=8,name="Native KilaGraph building",graph=graph,
            palette=Building().palette+("custom_role" to "minecraft:oak_planks"))
    }
    private fun click(screen:Screen,x:Double,y:Double,button:Int=0) {
        screen.mouseClicked(x,y,button);screen.mouseReleased(x,y,button)
    }
    private fun checkOnScreen(screen:Screen,point:Pair<Double,Double>) {
        check(point.first>=0 && point.first<screen.width && point.second>=0 && point.second<screen.height) {
            "Native menu target must remain on screen: $point in ${screen.width} x ${screen.height}"
        }
    }
    private fun button(screen:Screen,key:String)=screen.children().filterIsInstance<Button>().first { it.message.string==ui(key) }
    private fun press(screen:Screen,key:String) {
        val control=button(screen,key);check(control.active) { "$key should be enabled" }
        click(screen,control.x+control.width/2.0,control.y+control.height/2.0)
    }
    private fun screenshot(mc:Minecraft,name:String) {
        Screenshot.grab(mc.gameDirectory,"architect-kilagraph-$name.png",mc.mainRenderTarget) { println(it.string) }
    }
    private fun typeName(screen:Screen) {
        val input=screen.children().filterIsInstance<EditBox>().first()
        click(screen,input.x+input.width-5.0,input.y+8.0)
        check(screen.charTyped('!',0)) { "Pending name must accept real character input" }
    }
    private fun shapeToggles() {
        val json=Json.encode(shape.form)
        val undo=button(shape,"undo").active
        val redo=button(shape,"redo").active
        repeat(4) { shape.openGraph(true);shape.openGraph(false) }
        check(Json.encode(shape.form)==json) { "Shape presentation toggles changed the document" }
        check(button(shape,"undo").active==undo && button(shape,"redo").active==redo) { "Shape presentation toggles changed undo/redo availability" }
        check(shape.kilaGraph!=null) { "Shape must use the actual KilaGraph bridge" }
    }
    private fun buildingToggles() {
        val json=Json.encode(building.building)
        val undo=button(building,"undo").active
        val redo=button(building,"redo").active
        repeat(4) { building.openBuildingGraph(true);building.openBuildingGraph(false) }
        check(Json.encode(building.building)==json) { "Building presentation toggles changed the document" }
        check(button(building,"undo").active==undo && button(building,"redo").active==redo) { "Building presentation toggles changed undo/redo availability" }
        check(building.kilaBuildingGraph!=null) { "Building must use the actual KilaGraph bridge" }
    }
    private fun navigation(editor:KilaGraphEditor,state:KilaGraphState,unchanged:()->Boolean) {
        val x=editor.x+editor.width-25.0;val y=editor.y+editor.height-25.0
        val zoom=state.zoom
        editor.mouseMoved(x,y)
        editor.mouseScrolled(x,y,0.0,1.0)
        check(state.zoom!=zoom) { "Native scrolling must change the view zoom" }
        val panX=state.offsetX;val panY=state.offsetY
        editor.mouseClicked(x,y,2)
        editor.mouseDragged(x-35,y-22,2,-35.0,-22.0)
        editor.mouseReleased(x-35,y-22,2)
        check(state.offsetX!=panX || state.offsetY!=panY) { "Native background drag must pan the view" }
        check(unchanged()) { "Native pan/zoom changed the saved document" }
        editor.fit()
        check(unchanged()) { "Native fit changed the saved document" }
    }
    private fun shapeDomainEdit() {
        val original=shape.form;shapeBefore=Json.encode(original)
        val graph=original.graph!!;val node=graph.nodes.first { it.id=="rotate" }
        shape.commitShapeGraph(graph.replace(node.copy(quarterTurns=1)))
        val edited=Json.encode(shape.form)
        check(edited!=shapeBefore && button(shape,"undo").active)
        shapeToggles();press(shape,"undo")
        check(Json.encode(shape.form)==shapeBefore) { "Shape undo must restore every document field" }
        shapeToggles();press(shape,"redo")
        check(Json.encode(shape.form)==edited) { "Shape redo must restore every document field" }
        press(shape,"undo")
    }
    private fun nativeWire(editor:KilaGraphEditor,source:String,target:String,slot:Int) {
        val from=editor.outputPortPoint(source) ?: error("Native output port missing: $source")
        val to=editor.inputPortPoint(target,slot) ?: error("Native input port missing: $target:$slot")
        editor.mouseMoved(from.first,from.second)
        editor.mouseClicked(from.first,from.second,0)
        editor.mouseDragged(to.first,to.second,0,to.first-from.first,to.second-from.second)
        editor.mouseMoved(to.first,to.second)
        editor.mouseReleased(to.first,to.second,0)
    }
    private fun shapeNativePortTypes() {
        val editor=checkNotNull(shape.kilaGraph)
        val geometry=checkNotNull(editor.outputPortType("body"))
        val rotation=checkNotNull(editor.outputPortType("angle"))
        check(geometry.friendlyName==ui("port_copycat") && rotation.friendlyName==ui("port_rotation")) {
            "Native quick-info must name Copycat geometry and rotation instead of String"
        }
        check(geometry==editor.inputPortType("rotate",0) && rotation==editor.inputPortType("rotate",1) && geometry!=rotation) {
            "Actual native ports must distinguish Copycat geometry and rotation values"
        }
        check(editor.rotationButtonPoint("body",1)==null && editor.rotationButtonPoint("rotate",1)!=null && editor.rotationButtonPoint("angle",1)!=null) {
            "Rotation controls belong to the rotation nodes"
        }
        val json=Json.encode(shape.form);val history=button(shape,"undo").active to button(shape,"redo").active
        nativeWire(editor,"angle","draft",0)
        nativeWire(editor,"body","rotate",1)
        check(Json.encode(shape.form)==json && history==(button(shape,"undo").active to button(shape,"redo").active)) {
            "Incompatible geometry/rotation cables must be rejected without changing the document or history"
        }
        nativeWire(editor,"angle","rotate",1)
        check(shape.form.graph!!.nodes.single { it.id=="rotate" }.inputs[1]=="angle") {
            "A native rotation cable must reach the optional rotation input"
        }
        check(Geometry.validate(shape.form).parts.isNotEmpty()) { "A connected rotation value must compile" }
        press(shape,"undo");check(Json.encode(shape.form)==json) { "Rotation wiring must undo atomically" }
        println("ARCHITECT KILAGRAPH PORTS: semantic native quick-info, explicit rotation controls, incompatible cable rejection and typed rotation wiring verified")
    }
    private fun shapeNativeWire() {
        wireBefore=Json.encode(shape.form);val parts=Geometry.validate(shape.form).parts
        nativeWire(checkNotNull(shape.kilaGraph),"body","draft",0)
        check(shape.form.graph!!.nodes.first { it.id=="draft" }.inputs[0]=="body") { "Actual KilaGraph port drag must create a domain connection" }
        check(Geometry.validate(shape.form).parts==parts) { "An unused draft wire changed the result" }
        wireAfter=Json.encode(shape.form)
    }
    private fun shapeNativeDisconnect() {
        val editor=checkNotNull(shape.kilaGraph)
        val from=checkNotNull(editor.outputPortPoint("body"));val to=checkNotNull(editor.inputPortPoint("draft",0))
        val x=(from.first+to.first)/2;val y=(from.second+to.second)/2
        editor.mouseMoved(x,y);editor.mouseClicked(x,y,0);editor.mouseReleased(x,y,0)
        press(shape,"remove")
        check(Json.encode(shape.form)==wireBefore) { "Selecting a native cable and Remove must disconnect only that cable" }
        press(shape,"undo");check(Json.encode(shape.form)==wireAfter)
        press(shape,"undo");check(Json.encode(shape.form)==wireBefore)
        press(shape,"redo");check(Json.encode(shape.form)==wireAfter)
        press(shape,"undo");check(Json.encode(shape.form)==wireBefore)
        println("ARCHITECT KILAGRAPH WIRING: actual port drag, native cable selection/removal and shared undo/redo verified")
    }
    private fun shapeNativeCycle() {
        val json=Json.encode(shape.form);val undo=button(shape,"undo").active
        nativeWire(checkNotNull(shape.kilaGraph),"cut","cut",0)
        check(Json.encode(shape.form)==json && button(shape,"undo").active==undo) { "A native cycle must be rejected without a partial edit" }
    }
    private fun shapeNativeDrag() {
        val before=shape.form;val json=Json.encode(before)
        val editor=shape.kilaGraph!!
        val point=editor.nodeHeaderPoint("cut") ?: error("Native cut-node title must be laid out")
        editor.mouseClicked(point.first,point.second,0)
        editor.mouseDragged(point.first+28,point.second+16,0,28.0,16.0)
        editor.mouseReleased(point.first+28,point.second+16,0)
        val original=before.graph!!.nodes.first { it.id=="cut" };val moved=shape.form.graph!!.nodes.first { it.id=="cut" }
        check(moved.x!=original.x || moved.y!=original.y) { "Actual KilaGraph header drag must commit node movement" }
        check(GraphCompiler.compile(before.graph!!,before.groups)==GraphCompiler.compile(shape.form.graph!!,shape.form.groups)) { "Moving a native node changed geometry or material provenance" }
        press(shape,"undo");check(Json.encode(shape.form)==json) { "Native drag must use shared document undo" }
        press(shape,"redo");check(shape.form.graph!!.nodes.first { it.id=="cut" }==moved)
        press(shape,"undo");shape.openGraph(true)
        println("ARCHITECT KILAGRAPH SHAPE: actual native drag, pan, zoom, fit, lossless split switching, pending name, draft preservation and shared undo/redo verified")
    }
    private fun newGroupEntry(mc:Minecraft) {
        val json=Json.encode(shape.form);val count=shape.form.groups.size
        shape.selectShapeNode("draft");shape.openGraph(true)
        val inspector=checkNotNull(shape.graphCanvas)
        val point=checkNotNull(inspector.materialGroupPoint(0)) { "Separated Union must offer a group for every input" }
        checkOnScreen(shape,point)
        click(shape,point.first,point.second)
        check(inspector.keyPressed(269,0,0))
        check(inspector.keyPressed(257,0,0))
        val prompt=checkNotNull(mc.screen)
        check(prompt!==shape) { "New group choice must open a name field" }
        "  Smoke timber group  ".forEach { check(prompt.charTyped(it,0)) }
        check(prompt.keyPressed(257,0,0));check(mc.screen===shape)
        val created=shape.form.groups.single { it.name=="Smoke timber group" }
        check(shape.form.groups.size==count+1)
        check(shape.form.graph!!.nodes.first { it.id=="draft" }.inputGroups[0]==created.id)
        press(shape,"undo")
        check(Json.encode(shape.form)==json) { "Creating and assigning a named group must undo atomically" }
        shape.openGraph(true)
        println("ARCHITECT KILAGRAPH GROUP ENTRY: per-input separated Union group chooser, New group name field and atomic assignment/undo verified")
    }
    private fun buildingDomainEdit() {
        val before=building.building;buildingBefore=Json.encode(before)
        val graph=before.graph!!;val node=graph.nodes.first { it.id=="roof" }
        building.commitBuildingGraph(graph.replace(node.copy(overhang=1)))
        val edited=Json.encode(building.building)
        check(edited!=buildingBefore)
        Generator.generate(building.building,library.forms,library.modules)
        buildingToggles();press(building,"undo")
        check(Json.encode(building.building)==buildingBefore) { "Building undo must restore every document field" }
        buildingToggles();press(building,"redo")
        check(Json.encode(building.building)==edited) { "Building redo must restore every document field" }
        press(building,"undo")
    }
    private fun buildingNativeTypeRejection() {
        val json=Json.encode(building.building);val undo=button(building,"undo").active
        nativeWire(checkNotNull(building.kilaBuildingGraph),"floor","room",0)
        check(Json.encode(building.building)==json && button(building,"undo").active==undo) { "Native incompatible wall/floor connections must be rejected atomically" }
    }
    private fun buildingNativeDrag() {
        val before=building.building;val json=Json.encode(before);val plan=Generator.generate(before,library.forms,library.modules)
        val editor=building.kilaBuildingGraph!!
        val point=editor.nodeHeaderPoint("room") ?: error("Native room-node title must be laid out")
        editor.mouseClicked(point.first,point.second,0)
        editor.mouseDragged(point.first+24,point.second+14,0,24.0,14.0)
        editor.mouseReleased(point.first+24,point.second+14,0)
        val original=before.graph!!.nodes.first { it.id=="room" };val moved=building.building.graph!!.nodes.first { it.id=="room" }
        check(moved.x!=original.x || moved.y!=original.y) { "Actual KilaGraph room header drag must commit movement" }
        check(plan==Generator.generate(building.building,library.forms,library.modules)) { "Moving a building node changed the generated plan" }
        press(building,"undo");check(Json.encode(building.building)==json)
        press(building,"redo");check(building.building.graph!!.nodes.first { it.id=="room" }==moved)
        press(building,"undo");building.openBuildingGraph(true)
        println("ARCHITECT KILAGRAPH BUILDING: native drag, pan, zoom, split switching, named palette role, pending name and exact shared undo/redo verified")
    }
    private fun classicToggles() {
        val json=Json.encode(classic)
        repeat(4) { building.openBuildingGraph(true);building.openBuildingGraph(false) }
        check(Json.encode(building.building)==json) { "Classic building navigation must preserve schema, holes, patterns, trim and name" }
        check(building.building.graph==null)
        check(Generator.generate(building.building,library.forms,library.modules)==classicPlan)
        check(building.kilaBuildingGraph!=null) { "Classic buildings must also have a KilaGraph alternative" }
        println("ARCHITECT KILAGRAPH CLASSIC: repeated native/control switching preserves complete JSON and exact generated plan without conversion")
    }

    fun tick():Boolean {
        if(failed) return false
        val mc=Minecraft.getInstance()
        if(ticks==0 && (mc.screen==null || mc.overlay!=null)) return false
        ticks++
        var acted=true
        try {
            when(ticks) {
                10 -> {
                    mc.options.guiScale().set(1);mc.resizeDisplay()
                    verifyGroups();library=Library().apply { seed() };ClientState.snapshot=Snapshot(library=library)
                    shape=ShapeScreen().apply { form=shapeFixture();graphMode=true;graphInspector=false;surfaceMaterials["cap"]=4 }
                    mc.setScreen(shape)
                }
                25 -> { check(shape.kilaGraph!=null);shape.kilaGraph!!.fit() }
                30 -> screenshot(mc,"shape-native")
                35 -> {
                    typeName(shape);check(shape.form.name.endsWith("!"));shapeToggles()
                    shapeDomainEdit()
                    EditorValueInputSmoke.verify(mc)
                    check(mc.screen===shape)
                }
                45 -> {
                    val json=Json.encode(shape.form)
                    navigation(shape.kilaGraph!!,shape.kilaGraphState) { Json.encode(shape.form)==json }
                }
                48 -> shapeNativePortTypes()
                50 -> shapeNativeWire()
                51 -> shapeNativeDisconnect()
                53 -> shapeNativeCycle()
                55 -> shapeNativeDrag()
                60 -> newGroupEntry(mc)
                61 -> shape.openGraph(false)
                64 -> CopycatSelectionSmoke.verify(mc,shape)
                65 -> screenshot(mc,"shape-inspector")
                70 -> {
                    val json=Json.encode(shape.form);shape.groupMode=true;shape.graphMode=false;shape.resize(mc,mc.window.guiScaledWidth,mc.window.guiScaledHeight)
                    check(Json.encode(shape.form)==json)
                }
                75 -> screenshot(mc,"groups-text")
                80 -> {
                    shape=ShapeScreen().apply { form=Form(schema=1,id="old_form",version=6,name="Legacy unconverted shape",parts=listOf(Part(),Part(turn=1)));graphMode=true }
                    mc.setScreen(shape)
                }
                90 -> { shapeToggles();check(shape.form.schema==1 && shape.form.graph==null);shape.kilaGraph!!.fit() }
                95 -> screenshot(mc,"legacy-shape")
                100 -> {
                    building=BuildingScreen().apply { building=buildingFixture();graphMode=true;graphInspector=false }
                    mc.setScreen(building)
                }
                115 -> { check(building.kilaBuildingGraph!=null);building.kilaBuildingGraph!!.fit() }
                120 -> screenshot(mc,"building-native")
                125 -> {
                    typeName(building);check(building.building.name.endsWith("!"));buildingToggles()
                    buildingDomainEdit()
                }
                135 -> {
                    val json=Json.encode(building.building)
                    navigation(building.kilaBuildingGraph!!,building.kilaBuildingGraphState) { Json.encode(building.building)==json }
                }
                140 -> buildingNativeTypeRejection()
                145 -> buildingNativeDrag()
                155 -> screenshot(mc,"building-inspector")
                160 -> {
                    classic=library.buildings.getValue("demo@1").copy(id="classic_lossless",version=11,name="Classic L house",holes=listOf(P(2,0,2)))
                    classicPlan=Generator.generate(classic,library.forms,library.modules)
                    building=BuildingScreen().apply { building=classic }
                    mc.setScreen(building);building.openBuildingGraph(false)
                }
                175 -> { classicToggles();building.kilaBuildingGraph!!.fit() }
                185 -> screenshot(mc,"classic-building-graph")
                190 -> building.openBuildingGraph(true)
                200 -> screenshot(mc,"classic-building-controls")
                205 -> {
                    check(Json.encode(building.building)==Json.encode(classic))
                    mc.options.guiScale().set(2);mc.resizeDisplay()
                    shape=ShapeScreen().apply { form=shapeFixture();graphMode=true;graphInspector=false }
                    shapeBefore=Json.encode(shape.form);mc.setScreen(shape)
                }
                215 -> { shapeToggles();check(Json.encode(shape.form)==shapeBefore) }
                225 -> shape.kilaGraph!!.fit()
                230 -> screenshot(mc,"compact-shape-native")
                235 -> { shape.graphView.selected="body";shape.openGraph(true);check(Json.encode(shape.form)==shapeBefore) }
                245 -> screenshot(mc,"compact-shape-controls")
                255 -> {
                    building=BuildingScreen().apply { building=buildingFixture();graphMode=true;graphInspector=false }
                    buildingBefore=Json.encode(building.building);mc.setScreen(building)
                }
                265 -> { buildingToggles();check(Json.encode(building.building)==buildingBefore) }
                275 -> building.kilaBuildingGraph!!.fit()
                280 -> screenshot(mc,"compact-building-native")
                285 -> { building.buildingGraphView.selected="room";building.openBuildingGraph(true);check(Json.encode(building.building)==buildingBefore) }
                295 -> screenshot(mc,"compact-building-controls")
                300 -> {
                    mc.options.guiScale().set(1);mc.resizeDisplay()
                    shape=ShapeScreen().apply { form=shapeFixture();graphMode=true;graphInspector=false }
                    mc.setScreen(shape)
                }
                310 -> shape.kilaGraph!!.fit()
                320 -> {
                    val editor=checkNotNull(shape.kilaGraph)
                    val x=editor.x+editor.width-8.0;val y=editor.y+editor.height-8.0
                    contextJson=Json.encode(shape.form);contextPosition=editor.graphPoint(x,y)
                    click(shape,x,y,1)
                    check(editor.hasOpenMenu()) { "Native right click must open the KilaGraph context menu" }
                }
                325 -> {
                    val editor=checkNotNull(shape.kilaGraph)
                    val branch=editor.menuItemPoint(ui("add_node")) ?: error("Native Insert submenu must be laid out")
                    check(branch.first>editor.x+editor.width || branch.second>editor.y+editor.height) {
                        "Edge menu must extend outside the canvas to exercise popup routing"
                    }
                    editor.mouseMoved(branch.first,branch.second)
                }
                330 -> {
                    val editor=checkNotNull(shape.kilaGraph)
                    val category=editor.menuItemPoint(ui("copycat_group_basic")) ?: error("Native Copycat category must be laid out")
                    checkOnScreen(shape,category)
                    editor.mouseMoved(category.first,category.second)
                }
                335 -> screenshot(mc,"context-edge-submenu")
                340 -> {
                    val editor=checkNotNull(shape.kilaGraph)
                    val first=Adapters.all.values.first()
                    val item=editor.menuItemPoint(ui(first.name)) ?: error("Native insertion leaf must be laid out")
                    checkOnScreen(shape,item)
                    click(shape,item.first,item.second)
                    val original=Json.decode<Form>(contextJson)
                    val added=shape.form.graph!!.nodes.single { n -> original.graph!!.nodes.none { it.id==n.id } }
                    check(added.kind==NodeKind.INPUT && added.part.source==first.source)
                    check((added.x to added.y)==contextPosition) { "Native context insertion must use clicked graph coordinates" }
                    press(shape,"undo");check(Json.encode(shape.form)==contextJson)
                    println("ARCHITECT KILAGRAPH CONTEXT: actual edge right click, native popup outside canvas, nested source insertion at transformed position and shared Undo verified")
                }
                345 -> {
                    val editor=checkNotNull(shape.kilaGraph)
                    click(shape,editor.x+editor.width-8.0,editor.y+editor.height-8.0,1)
                    check(editor.hasOpenMenu())
                }
                346 -> {
                    val inspector=checkNotNull(shape.graphCanvas)
                    click(shape,inspector.x+35.0,inspector.y+18.0)
                    check(shape.kilaGraph?.hasOpenMenu()==false)
                    check(!inspector.hasOpenMenu()) { "Dismissing a native menu must not open the inspector selector behind it" }
                    check(Json.encode(shape.form)==contextJson)
                }
                355 -> {
                    shape=ShapeScreen().apply { form=shapeFixture();graphMode=true;graphInspector=false }
                    shapeBefore=Json.encode(shape.form);mc.setScreen(shape)
                }
                365 -> shape.kilaGraph!!.fit()
                375 -> {
                    val point=checkNotNull(shape.kilaGraph!!.sourceButtonPoint("body"))
                    click(shape,point.first,point.second)
                    check(mc.screen is CopycatSelectionScreen)
                }
                390 -> {
                    check(mc.screen is CopycatSelectionScreen)
                    screenshot(mc,"copycat-inventory")
                }
                400 -> {
                    checkNotNull(mc.screen).onClose()
                    mc.options.guiScale().set(2);mc.resizeDisplay()
                    shape.selectShapeNode("body");shape.openGraph(true)
                    val inspector=checkNotNull(shape.graphCanvas)
                    click(shape,inspector.x+30.0,inspector.y+71.0)
                    check(mc.screen is CopycatSelectionScreen)
                }
                415 -> {
                    check(mc.screen is CopycatSelectionScreen)
                    screenshot(mc,"compact-copycat-inventory")
                }
                425 -> {
                    checkNotNull(mc.screen).onClose()
                    check(mc.screen===shape && Json.encode(shape.form)==shapeBefore)
                    shape.openGraph(false)
                }
                435 -> shape.kilaGraph!!.fit()
                445 -> {
                    val editor=checkNotNull(shape.kilaGraph)
                    val x=editor.x+editor.width-8.0;val y=editor.y+editor.height-8.0
                    contextJson=Json.encode(shape.form);contextPosition=editor.graphPoint(x,y)
                    click(shape,x,y,1)
                    check(editor.hasOpenMenu()) { "Compact native right click must open the context menu" }
                }
                450 -> {
                    val editor=checkNotNull(shape.kilaGraph)
                    val branch=editor.menuItemPoint(ui("add_node")) ?: error("Compact native Insert submenu must be laid out")
                    checkOnScreen(shape,branch)
                    editor.mouseMoved(branch.first,branch.second)
                }
                455 -> {
                    val editor=checkNotNull(shape.kilaGraph)
                    val category=editor.menuItemPoint(ui("copycat_group_half_layers")) ?: error("Compact half-layer category must be laid out")
                    checkOnScreen(shape,category)
                    editor.mouseMoved(category.first,category.second)
                }
                465 -> screenshot(mc,"compact-context-last-source")
                470 -> {
                    val editor=checkNotNull(shape.kilaGraph)
                    val last=Adapters.all.values.last()
                    val item=editor.menuItemPoint(ui(last.name)) ?: error("Last Copycat insertion leaf must be reachable in the compact menu")
                    checkOnScreen(shape,item)
                    click(shape,item.first,item.second)
                    val original=Json.decode<Form>(contextJson)
                    val added=shape.form.graph!!.nodes.single { n -> original.graph!!.nodes.none { it.id==n.id } }
                    check(added.kind==NodeKind.INPUT && added.part.source==last.source)
                    check((added.x to added.y)==contextPosition) { "Compact context insertion must use clicked graph coordinates" }
                    press(shape,"undo");check(Json.encode(shape.form)==contextJson && contextJson==shapeBefore)
                    println("ARCHITECT KILAGRAPH COMPACT CONTEXT: grouped native menu, on-screen last Copycat, transformed insertion position and shared Undo verified")
                }
                475 -> {
                    check(mc.screen===shape && Json.encode(shape.form)==shapeBefore)
                    DiscRotationSmoke.begin(mc,shape)
                }
                else -> acted=false
            }
            if(ticks>=475) {
                acted=(ticks-extraStart)%5==0
                when(extraStage) {
                0 -> if(DiscRotationSmoke.tick(mc,ticks-475)) {
                    check(mc.screen===shape && Json.encode(shape.form)==shapeBefore)
                    extraStage=1;extraStart=ticks;UnionGroupSmoke.begin(mc,shape)
                }
                1 -> if(UnionGroupSmoke.tick(mc,ticks-extraStart)) {
                    check(mc.screen===shape && Json.encode(shape.form)==shapeBefore)
                    extraStage=2;extraStart=ticks;AssemblyGraphSmoke.begin(mc,shape)
                }
                2 -> if(AssemblyGraphSmoke.tick(mc,ticks-extraStart)) {
                    check(mc.screen===shape && Json.encode(shape.form)==shapeBefore)
                    println("ARCHITECT KILAGRAPH SMOKE PASSED: native semantic ports, explicit rotations, Union input groups and warnings, Copycat building assembly, compact inspectors, cable edits, source inventory and document undo/redo accepted")
                    mc.stop()
                }
                }
            }
        } catch(e:Throwable) {
            failed=true
            System.err.println("ARCHITECT KILAGRAPH SMOKE FAILED at tick $ticks: ${e.message}")
            e.printStackTrace();screenshot(mc,"failure-tick$ticks")
            throw IllegalStateException("KilaGraph smoke failed at tick $ticks",e)
        }
        return acted
    }
}
