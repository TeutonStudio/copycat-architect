package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.Json
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.Button

/** Exercises the actual inspector controls and document history in an isolated form. */
internal object UnionGroupSmoke {
    private lateinit var previous:ShapeScreen
    private lateinit var shape:ShapeScreen
    private var previousJson=""
    private var previousScale=1
    private var fixtureJson=""
    private var separatedJson=""
    private var kind=NodeKind.UNION
    private val screenshotPrefix get()=if(kind==NodeKind.UNION) "union" else "symmetric-difference"

    private fun fixture(kind:NodeKind)=Form(id=kind.name.lowercase()+"_group_smoke",name=ui("node_"+kind.name.lowercase())+" input materials",
        groups=listOf(MaterialGroup(),MaterialGroup("cap","Wood cap","frame",1)),
        graph=NodeGraph(nodes=listOf(
            GraphNode("lower",part=Part("copycats:copycat_slab"),x=20,y=20),
            GraphNode("upper",part=Part("copycats:copycat_slab",top=true),x=20,y=180),
            GraphNode("join",kind,listOf("lower","upper"),inputGroups=listOf("cap","cap"),x=260,y=60),
            GraphNode("result",NodeKind.OUTPUT,listOf("join"),x=500,y=60))))

    fun begin(mc:Minecraft,original:ShapeScreen) {
        previous=original;previousJson=Json.encode(original.form);previousScale=mc.options.guiScale().get()
        beginFixture(mc,NodeKind.UNION)
    }
    private fun beginFixture(mc:Minecraft,next:NodeKind) {
        kind=next;separatedJson=""
        mc.options.guiScale().set(1);mc.resizeDisplay()
        shape=ShapeScreen().apply { form=fixture(kind);graphMode=true;graphInspector=true;selectShapeNode("join") }
        fixtureJson=Json.encode(shape.form);mc.setScreen(shape)
        val checked=Geometry.validate(shape.form)
        check(Geometry.solid(checked.parts).count()==4096) { "$kind must preserve the full cube formed by disjoint slabs" }
    }
    private fun inspector()=checkNotNull(shape.graphCanvas)
    private fun join()=checkNotNull(shape.form.graph).nodes.single { it.id=="join" }
    private fun click(point:Pair<Double,Double>) {
        check(point.first in 0.0..<shape.width.toDouble() && point.second in 0.0..<shape.height.toDouble()) {
            "${kind.name} material control must be visible: $point in ${shape.width} x ${shape.height}"
        }
        check(shape.mouseClicked(point.first,point.second,0));shape.mouseReleased(point.first,point.second,0)
    }
    private fun press(key:String) {
        val button=shape.children().filterIsInstance<Button>().single { it.message.string==ui(key) }
        check(button.active) { "${kind.name} material history needs an active $key button" }
        click(button.x+button.width/2.0 to button.y+button.height/2.0)
    }
    private fun reveal(point:Pair<Double,Double>) {
        val canvas=inspector()
        val target=point.second.coerceIn(canvas.y+55.0,canvas.y+canvas.height-12.0)
        if(point.second!=target) check(shape.mouseScrolled(canvas.x+canvas.width/2.0,canvas.y+canvas.height-8.0,0.0,(target-point.second)/21.0))
    }
    private fun groupedRoundtrip() {
        check(Json.encode(shape.form)==fixtureJson);check(inspector().hasUniformGroupWarning())
        reveal(checkNotNull(inspector().materialModePoint(true)))
        click(checkNotNull(inspector().materialModePoint(true)))
        check(join().materialMode==MaterialMode.GROUPED && join().materialGroup=="cap") {
            "Grouped must retain the common separated input material instead of falling back to Body"
        }
        check(!inspector().hasUniformGroupWarning())
        val groupedJson=Json.encode(shape.form)
        val checked=Geometry.validate(shape.form)
        val resolved=Geometry.resolve(checked.parts,checked.groups)
        check(resolved.solid.count()==4096 && resolved.owned.keys==setOf("cap")) { "$kind grouped output must preserve both disjoint slabs under the common material" }
        press("undo");check(Json.encode(shape.form)==fixtureJson) { "One Undo must restore the exact separated $kind document" }
        press("redo");check(Json.encode(shape.form)==groupedJson) { "One Redo must restore the exact grouped $kind document" }
        press("undo");check(Json.encode(shape.form)==fixtureJson)
    }
    private fun assignFirstInput() {
        check(Json.encode(shape.form)==fixtureJson)
        reveal(checkNotNull(inspector().materialGroupPoint(0)))
        click(checkNotNull(inspector().materialGroupPoint(0)))
        check(inspector().hasOpenMenu()) { "The $kind input group must open its actual dropdown" }
        check(shape.keyPressed(268,0,0)) // Home: inherit, then the Body group.
        check(shape.keyPressed(264,0,0));check(shape.keyPressed(257,0,0))
        check(!inspector().hasOpenMenu() && join().inputGroups==listOf("body","cap"))
        check(!inspector().hasUniformGroupWarning()) { "Distinct input materials must hide the redundant grouping warning" }
        val checked=Geometry.validate(shape.form);val owned=Geometry.resolve(checked.parts,checked.groups).owned
        check(owned.getValue("body").count()==2048 && owned.getValue("cap").count()==2048)
        separatedJson=Json.encode(shape.form)
        press("undo");check(Json.encode(shape.form)==fixtureJson)
        press("redo");check(Json.encode(shape.form)==separatedJson)
    }
    private fun showWarning() {
        check(inspector().hasUniformGroupWarning())
        // The warning follows the last assignment and the input-count controls.
        val warningTop=checkNotNull(inspector().materialGroupPoint(1)).second+38.0
        val canvas=inspector();val desired=canvas.y+53.0
        if(warningTop>canvas.y+canvas.height-35.0)
            check(shape.mouseScrolled(canvas.x+canvas.width/2.0,canvas.y+canvas.height-8.0,0.0,(desired-warningTop)/21.0))
    }
    private fun screenshot(mc:Minecraft,name:String) {
        check(!inspector().hasOpenMenu())
        Screenshot.grab(mc.gameDirectory,"architect-kilagraph-$name.png",mc.mainRenderTarget) { println(it.string) }
    }
    fun tick(mc:Minecraft,elapsed:Int):Boolean {
        check(mc.screen===shape) { "$kind acceptance must retain its independent form screen" }
        val stage=if(elapsed<=90) elapsed else elapsed-90
        when(stage) {
            10 -> { showWarning();screenshot(mc,"$screenshotPrefix-uniform-warning") }
            20 -> groupedRoundtrip()
            30 -> assignFirstInput()
            40 -> screenshot(mc,"$screenshotPrefix-separated-input-groups")
            45 -> { press("undo");check(Json.encode(shape.form)==fixtureJson) }
            50 -> { mc.options.guiScale().set(2);mc.resizeDisplay();shape.openGraph(true) }
            60 -> showWarning()
            65 -> screenshot(mc,"compact-$screenshotPrefix-uniform-warning")
            70 -> groupedRoundtrip()
            80 -> assignFirstInput()
            85 -> screenshot(mc,"compact-$screenshotPrefix-separated-input-groups")
            90 -> {
                check(Json.encode(shape.form)==separatedJson)
                press("undo");check(Json.encode(shape.form)==fixtureJson)
                println("ARCHITECT $kind GROUPS: real separated-input dropdowns, uniform-group warning at normal and compact scale, grouped material inheritance, full disjoint-slab geometry, exact atomic Undo/Redo and isolated fixture verified")
                if(kind==NodeKind.UNION) beginFixture(mc,NodeKind.SYMMETRIC_DIFFERENCE) else {
                    mc.options.guiScale().set(previousScale);mc.resizeDisplay();mc.setScreen(previous)
                    check(Json.encode(previous.form)==previousJson) { "Union and symmetric-difference acceptance changed the original form" }
                    return true
                }
            }
        }
        return false
    }
}
