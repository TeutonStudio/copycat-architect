package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.Json
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import kotlin.math.roundToInt

/** Independent real-widget acceptance for the explicit Copycat -> building assembly template. */
internal object AssemblyGraphSmoke {
    private lateinit var previous:ShapeScreen
    private lateinit var building:BuildingScreen
    private var previousJson=""
    private var previousScale=1
    private var fixtureJson=""
    private var draft=""
    private var wireBefore=""
    private var wireAfter=""

    fun begin(mc:Minecraft,original:ShapeScreen) {
        previous=original;previousJson=Json.encode(original.form);previousScale=mc.options.guiScale().get()
        mc.options.guiScale().set(1);mc.resizeDisplay()
        building=BuildingScreen().apply {
            building=Building(schema=2,id="assembly_smoke",name="Copycat assembly",graph=BuildingGraph.assembledStarter())
            graphMode=true;graphInspector=false
            selectBuildingNode("storey")
        }
        fixtureJson=Json.encode(building.building)
        mc.setScreen(building)
        check(checkNotNull(building.building.graph).nodes.single { it.id=="storey" }.storeyMode=="walls")
        checkNotNull(building.buildingGraphCanvas).result().getOrThrow()
    }

    private fun graph()=checkNotNull(building.building.graph)
    private fun node(id:String)=graph().nodes.single { it.id==id }
    private fun editor()=checkNotNull(building.kilaBuildingGraph)
    private fun slot(id:String,key:String)=BuildingPorts.of(node(id),true).indexOfFirst { it.key==key }.also { check(it>=0) }
    private fun button(key:String)=building.children().filterIsInstance<Button>().single { it.message.string==ui(key) }
    private fun click(screen:Screen,point:Pair<Double,Double>,mouseButton:Int=0) {
        check(point.first>=0 && point.first<screen.width && point.second>=0 && point.second<screen.height) {
            "Assembly control must be on screen: $point in ${screen.width} x ${screen.height}"
        }
        check(screen.mouseClicked(point.first,point.second,mouseButton)) { "Actual assembly widget must receive the click" }
        screen.mouseReleased(point.first,point.second,mouseButton)
    }
    private fun press(key:String) {
        val control=button(key);check(control.active) { "$key must be available for assembly editing" }
        click(building,control.x+control.width/2.0 to control.y+control.height/2.0)
    }
    private fun restored()=check(Json.encode(building.building)==fixtureJson) { "Assembly actions must restore the complete starter document" }
    private fun screenshot(mc:Minecraft,name:String) {
        check(!checkNotNull(building.buildingGraphCanvas).hasOpenMenu())
        check(building.kilaBuildingGraph?.hasOpenMenu()!=true)
        Screenshot.grab(mc.gameDirectory,"architect-kilagraph-$name.png",mc.mainRenderTarget) { println(it.string) }
    }

    private fun nativeWire(source:String,target:String,key:String) {
        val native=editor();val from=checkNotNull(native.outputPortPoint(source));val to=checkNotNull(native.inputPortPoint(target,slot(target,key)))
        native.mouseMoved(from.first,from.second);native.mouseClicked(from.first,from.second,0)
        native.mouseDragged(to.first,to.second,0,to.first-from.first,to.second-from.second)
        native.mouseMoved(to.first,to.second);native.mouseReleased(to.first,to.second,0)
    }
    private fun portTypes() {
        val native=editor();val copy=checkNotNull(native.outputPortType("wall_copycat"));val wall=checkNotNull(native.outputPortType("wall"))
        check(copy.friendlyName==ui("port_copycat") && wall.friendlyName==ui("bg_segment")) {
            "Native building quick-info must identify Copycats and wall segments"
        }
        for(id in listOf("wall","floor","ceiling","roof")) check(native.inputPortType(id,slot(id,"in:0"))==copy) {
            "The actual $id geometry socket must accept Copycats"
        }
        for(direction in 0..3) check(native.inputPortType("roof",slot("roof","wall:$direction"))==wall) {
            "Roof edge sockets must accept wall segments separately from Copycats"
        }
        for(port in BuildingPorts.of(node("storey"))) if(port.key.startsWith("wall:")) check(native.inputPortType("storey",slot("storey",port.key))==wall)
        check(copy!=wall && native.inputPortType("storey",slot("storey","floor"))!=wall)
    }
    private fun openInsertionMenu() {
        restored()
        val native=editor()
        // Choose the clear gap between the source and assembly columns, below the floor node.
        val a=native.graphPoint(native.x.toDouble(),native.y.toDouble())
        val b=native.graphPoint(native.x+100.0,native.y+100.0)
        val point=native.x+(230-a.first)*100.0/(b.first-a.first) to native.y+(350-a.second)*100.0/(b.second-a.second)
        click(building,point,1)
        check(native.hasOpenMenu()) { "Actual native context menu must open for a new wall segment" }
    }
    private fun insertSegment() {
        val native=editor();val leaf=checkNotNull(native.menuItemPoint(ui("bg_segment")))
        click(building,leaf)
        val added=graph().nodes.single { n -> BuildingGraph.assembledStarter().nodes.none { it.id==n.id } }
        check(added.kind==BuildingKind.SEGMENT && added.inputs.isEmpty())
        draft=added.id;wireBefore=Json.encode(building.building)
    }
    private fun wireSegment() {
        portTypes()
        val native=editor();val copy=checkNotNull(native.outputPortType("wall_copycat"))
        check(native.inputPortType(draft,0)==copy)
        val history=button("undo").active to button("redo").active
        nativeWire("wall",draft,"in:0")
        check(Json.encode(building.building)==wireBefore && history==(button("undo").active to button("redo").active)) {
            "A native wall-segment cable must be rejected at a Copycat input without editing history"
        }
        nativeWire("wall_copycat",draft,"in:0")
        check(node(draft).inputs==listOf("wall_copycat")) { "Real native dragging must wire a Copycat into the newly inserted segment" }
        wireAfter=Json.encode(building.building)
    }
    private fun disconnectSegment() {
        val native=editor();val from=checkNotNull(native.outputPortPoint("wall_copycat"));val to=checkNotNull(native.inputPortPoint(draft,0))
        val point=(from.first+to.first)/2 to (from.second+to.second)/2
        native.mouseMoved(point.first,point.second);native.mouseClicked(point.first,point.second,0);native.mouseReleased(point.first,point.second,0)
        press("remove")
        check(Json.encode(building.building)==wireBefore && node(draft).inputs.isEmpty()) {
            "Native cable removal must trim the optional Copycat tail instead of leaving a required blank slot"
        }
        press("undo");check(Json.encode(building.building)==wireAfter)
        press("redo");check(Json.encode(building.building)==wireBefore)
        press("undo");check(Json.encode(building.building)==wireAfter)
        press("undo");check(Json.encode(building.building)==wireBefore)
    }

    /** Match the shared inspector's public node geometry, including scrolling and side preview. */
    private fun inspectorPoint(localX:Double,localY:Double):Pair<Double,Double> {
        val canvas=checkNotNull(building.buildingGraphCanvas);val selected=node(building.buildingGraphView.selected)
        val panel=if(canvas.width>=600) minOf(420,(canvas.width*0.48).roundToInt()) else canvas.width
        val width=canvas.nodeWidth(selected);val scale=minOf(1.0,(panel-28.0)/width).coerceAtLeast(0.2)
        return canvas.x+(panel-width*scale)/2+localX*scale to canvas.y+42-building.buildingGraphView.inspectorScroll+localY*scale
    }
    private fun body(id:String)=45+BuildingPorts.of(node(id),true).size*19+if(node(id).kind==BuildingKind.STOREY) 22 else 0
    private fun reveal(localY:Double) {
        val canvas=checkNotNull(building.buildingGraphCanvas);val point=inspectorPoint(60.0,localY)
        val target=point.second.coerceIn(canvas.y+47.0,canvas.y+canvas.height-12.0)
        if(target!=point.second) check(canvas.mouseScrolled(canvas.x+canvas.width/2.0,canvas.y+canvas.height-8.0,0.0,(target-point.second)/25.0))
        val visible=inspectorPoint(60.0,localY)
        check(visible.second>=canvas.y+35 && visible.second<canvas.y+canvas.height) { "Scrolled assembly control must stay below the sticky selector" }
    }
    private fun sameFloorConnection() {
        restored();val canvas=checkNotNull(building.buildingGraphCanvas)
        val localY=36.0+slot("storey","floor")*19
        reveal(localY);click(building,inspectorPoint(150.0,localY))
        check(canvas.hasOpenMenu()) { "The direct storey floor must expose its real typed connection chooser" }
        check(building.keyPressed(268,0,0));check(building.keyPressed(264,0,0));check(building.keyPressed(257,0,0))
        check(!canvas.hasOpenMenu());restored()
    }
    private fun rectangleWidth(mc:Minecraft) {
        restored();val localY=body("storey")+52.0
        reveal(localY);click(building,inspectorPoint(60.0,localY))
        val prompt=checkNotNull(mc.screen);check(prompt!==building) { "Direct wall-storey width must open the numeric editor" }
        check(prompt.children().filterIsInstance<EditBox>().single().value=="3")
        check(prompt.charTyped('4',0));check(prompt.keyPressed(257,0,0));check(mc.screen===building)
        check(node("storey").rectangles.single().width==4 && node("storey").storeyMode=="walls") {
            "The actual width control must edit the direct storey's footprint"
        }
        press("undo");restored()
    }

    fun tick(mc:Minecraft,elapsed:Int):Boolean {
        check(mc.screen===building) { "Assembly acceptance must stay in its independent building fixture" }
        when(elapsed) {
            10 -> { editor().fit();portTypes() }
            15 -> screenshot(mc,"assembly-building-native")
            20 -> openInsertionMenu()
            25 -> { val native=editor();val branch=checkNotNull(native.menuItemPoint(ui("add_node")));native.mouseMoved(branch.first,branch.second) }
            30 -> insertSegment()
            35 -> editor().fit()
            40 -> wireSegment()
            45 -> disconnectSegment()
            50 -> { press("undo");restored();building.selectBuildingNode("storey");building.openBuildingGraph(true) }
            55 -> sameFloorConnection()
            60 -> rectangleWidth(mc)
            65 -> screenshot(mc,"assembly-storey-controls")
            70 -> { building.selectBuildingNode("roof");building.openBuildingGraph(true) }
            75 -> screenshot(mc,"assembly-roof-controls")
            80 -> { restored();mc.options.guiScale().set(2);mc.resizeDisplay();building.openBuildingGraph(false) }
            85 -> { editor().fit();portTypes() }
            90 -> screenshot(mc,"compact-assembly-building-native")
            95 -> { building.selectBuildingNode("storey");building.openBuildingGraph(true) }
            100 -> screenshot(mc,"compact-assembly-storey-connections")
            105 -> reveal(body("storey")+52.0)
            110 -> screenshot(mc,"compact-assembly-storey-controls")
            115 -> { building.selectBuildingNode("roof");building.openBuildingGraph(true) }
            120 -> reveal(36.0+slot("roof","wall:2")*19)
            125 -> screenshot(mc,"compact-assembly-roof-connections")
            130 -> reveal(body("roof")+52.0)
            135 -> screenshot(mc,"compact-assembly-roof-controls")
            145 -> {
                restored();mc.options.guiScale().set(previousScale);mc.resizeDisplay();mc.setScreen(previous)
                check(Json.encode(previous.form)==previousJson) { "Independent assembly acceptance changed the original shape document" }
                println("ARCHITECT ASSEMBLY GRAPH: explicit Copycat template, semantic native port types, native segment insertion, typed wiring/rejection, cable removal with trimmed optional inputs, atomic Undo/Redo, direct wall-storey connection and width controls, normal/compact building and roof inspectors verified")
                return true
            }
        }
        return false
    }
}
