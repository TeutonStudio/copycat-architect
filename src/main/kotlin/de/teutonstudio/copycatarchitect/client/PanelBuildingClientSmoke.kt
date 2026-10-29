package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.*
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.joml.Quaternionf
import org.joml.Vector3f

/** Real native graph and textured building acceptance, opt-in with -PpanelBuildingSmoke. */
object PanelBuildingClientSmoke {
    private var ticks=0
    private var failed=false
    private lateinit var editor:BuildingScreen
    private lateinit var scene:PanelScene
    private lateinit var plan:Plan
    private var buildingJson=""
    private var planJson=""

    private fun screenshot(mc:Minecraft,name:String) {
        Screenshot.grab(mc.gameDirectory,"architect-panel-building-$name.png",mc.mainRenderTarget) { println(it.string) }
    }
    private fun verifyGraph(expectNative:Boolean=true) {
        val graph=checkNotNull(editor.building.graph)
        val storey=graph.nodes.single { it.id=="storey" }
        val ports=BuildingPorts.of(storey,true)
        val corner=ports.single { it.key=="corner" }
        check(corner.source.isNotEmpty() && corner.accepts==setOf(BuildingKind.COPYCAT)) {
            "The optional corner input must be connected to its own native Copycat source"
        }
        check(graph.nodes.single { it.id==corner.source }.kind==BuildingKind.COPYCAT)
        if(expectNative) {
            val native=checkNotNull(editor.kilaBuildingGraph) { "The example must use the actual native building editor" }
            val slot=ports.indexOf(corner)
            check(native.outputPortType(corner.source)==native.inputPortType(storey.id,slot) && native.inputPortType(storey.id,slot)?.friendlyName==ui("port_copycat"))
            check(native.inputPortPoint(storey.id,slot)!=null && native.outputPortPoint(corner.source)!=null) {
                "The real native graph must expose the corner cable at both ends"
            }
        }
        check(graph.nodes.any { it.kind==BuildingKind.PATTERN && it.inputs.size==4 && it.inputs[0]==it.inputs[3] && it.inputs[1]==it.inputs[2] && it.inputs[0]!=it.inputs[1] }) {
            "Panel facades must assemble their actual edge/window/window/edge wall pattern"
        }
        val compiled=checkNotNull(editor.buildingGraphCanvas).result().getOrThrow()
        check(compiled.plan==plan) { "The actual building editor must compile the same complete example plan" }
        check(Json.encode(editor.building)==buildingJson)
    }
    private fun verifyInterior() {
        val inside=plan.clear.filter { it.x in 0 until PanelBuildingExample.WIDTH && it.z in 0 until PanelBuildingExample.DEPTH }
        val base=inside.minOf { it.y }
        val expected=buildSet {
            for(x in 0 until PanelBuildingExample.WIDTH) for(z in 0 until PanelBuildingExample.DEPTH)
                for(y in base until base+PanelBuildingExample.HEIGHT) add(P(x,y,z))
        }
        check(inside.toSet()==expected && plan.placements.none { it.pos in expected }) {
            "All 4x4x4 interior cells must remain available above the external floor and below the external ceiling"
        }
        check(plan.placements.map { it.pos }.distinct().size==plan.placements.size)
        val parts=plan.placements.flatMap { it.parts }
        check(parts.any { it.source=="create:copycat_panel" } && parts.any { it.source=="copycats:copycat_layer" && it.size==1 })
        check(parts.none { it.source=="copycats:copycat_flat_pane" }) { "The windows must use thin mounted layers, not centered panes" }
        for(piece in parts) Solid.part(piece)
        val corners=plan.placements.filter { it.pos.x in listOf(-1,PanelBuildingExample.WIDTH) && it.pos.z in listOf(-1,PanelBuildingExample.DEPTH) && it.pos.y in base until base+PanelBuildingExample.HEIGHT }
        check(corners.size==4*PanelBuildingExample.HEIGHT && corners.all { p -> p.parts.any { it.source=="copycats:copycat_vertical_slice" && it.size==1 } }) {
            "Each of the four vertical building edges must use the explicit thin corner post"
        }
        check(corners.all { Geometry.solid(it.parts).count()==64 }) { "A corner post must stay 2x2/16 through its whole height" }
    }
    fun tick():Boolean {
        if(failed) return false
        val mc=Minecraft.getInstance()
        if(ticks==0 && (mc.screen==null || mc.overlay!=null)) return false
        ticks++;var acted=true
        try {
            when(ticks) {
                10 -> {
                    check(mc.level==null && mc.player==null) { "Panel building acceptance starts without a world" }
                    mc.options.guiScale().set(1);mc.resizeDisplay()
                    val example=PanelBuildingExample
                    val library=Library().apply { seed();forms.putAll(example.forms);modules.putAll(example.modules);buildings["${example.building.id}@${example.building.version}"]=example.building }
                    ClientState.snapshot=Snapshot(library=library)
                    plan=example.plan();planJson=Json.encode(plan)
                    editor=BuildingScreen().apply { building=example.building;graphMode=true;graphInspector=false;selectBuildingNode("storey") }
                    buildingJson=Json.encode(editor.building);verifyInterior();mc.setScreen(editor)
                }
                25 -> { checkNotNull(editor.kilaBuildingGraph).fit();verifyGraph() }
                35 -> screenshot(mc,"graph-normal")
                45 -> { editor.selectBuildingNode("storey");editor.openBuildingGraph(true) }
                55 -> { verifyGraph(false);screenshot(mc,"corner-inspector-normal") }
                70 -> { mc.options.guiScale().set(2);mc.resizeDisplay();editor.openBuildingGraph(false) }
                85 -> { checkNotNull(editor.kilaBuildingGraph).fit();verifyGraph() }
                95 -> screenshot(mc,"graph-compact")
                105 -> { editor.selectBuildingNode("storey");editor.openBuildingGraph(true) }
                115 -> { verifyGraph(false);screenshot(mc,"corner-inspector-compact") }
                125 -> {
                    mc.options.guiScale().set(1);mc.resizeDisplay();scene=PanelScene(plan);mc.setScreen(scene)
                }
                145 -> screenshot(mc,"exterior-35")
                155 -> scene.yaw=215f
                175 -> screenshot(mc,"exterior-215")
                185 -> { scene.yaw=35f;scene.cutaway=true }
                205 -> screenshot(mc,"interior-furnished-cutaway")
                225 -> {
                    check(Json.encode(editor.building)==buildingJson && Json.encode(PanelBuildingExample.plan())==planJson)
                    println("ARCHITECT PANEL BUILDING CLIENT SMOKE PASSED: actual native graph and typed corner cable, normal/compact graph and inspectors, four 3/16 panel facades with 2/16 layer windows, four thin corner posts, two exterior views and an unchanged-plan cutaway with three real native interior blocks verified")
                    mc.stop()
                }
                else -> acted=false
            }
        } catch(e:Throwable) {
            failed=true;System.err.println("ARCHITECT PANEL BUILDING CLIENT SMOKE FAILED at tick $ticks: ${e.message}");e.printStackTrace()
            screenshot(mc,"failure-tick$ticks");throw IllegalStateException("Panel building client smoke failed at tick $ticks",e)
        }
        return acted
    }

    /** Cutaway and furniture are render-only overlays; neither mutates the fixture or its air cells. */
    private class PanelScene(private val plan:Plan):Screen(Component.literal("Panel building")) {
        var yaw=35f
        var cutaway=false
        private val floor=plan.clear.filter { it.x in 0 until PanelBuildingExample.WIDTH && it.z in 0 until PanelBuildingExample.DEPTH }.minOf { it.y }
        private val furniture=listOf(
            P(1,floor,1) to Blocks.CRAFTING_TABLE.defaultBlockState(),
            P(2,floor,1) to Blocks.FURNACE.defaultBlockState(),
            P(2,floor,2) to Blocks.CHEST.defaultBlockState())
        init {
            check(furniture.map { it.first }.distinct().size==3 && furniture.all { it.first in plan.clear && plan.placements.none { p -> p.pos==it.first } })
        }
        override fun render(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
            g.fill(0,0,width,height,0xff15212a.toInt());g.fill(0,0,width,34,0xff233b48.toInt())
            g.drawCenteredString(font,Component.literal(if(cutaway) "Innenraum · Werkbank, Ofen und Truhe" else "Panelgebäude · Außenansicht ${yaw.toInt()}°"),width/2,12,0xffffcc83.toInt())
            val lo=P(plan.placements.minOf { it.pos.x },plan.placements.minOf { it.pos.y },plan.placements.minOf { it.pos.z })
            val hi=P(plan.placements.maxOf { it.pos.x }+1,plan.placements.maxOf { it.pos.y }+1,plan.placements.maxOf { it.pos.z }+1)
            val sx=(hi.x-lo.x).toFloat();val sy=(hi.y-lo.y).toFloat();val sz=(hi.z-lo.z).toFloat()
            val q=Quaternionf().rotationX(Math.toRadians(25.0).toFloat()).rotateY(Math.toRadians(yaw.toDouble()).toFloat())
            val corners=buildList { for(a in listOf(-sx/2,sx/2)) for(b in listOf(-sy/2,sy/2)) for(c in listOf(-sz/2,sz/2)) add(q.transform(Vector3f(a,b,c))) }
            val scale=minOf((width-48)/(corners.maxOf { it.x }-corners.minOf { it.x }),(height-100)/(corners.maxOf { it.y }-corners.minOf { it.y }))*0.88f
            val cells=if(!cutaway) plan.placements else plan.placements.filter { p ->
                p.component !in setOf("roof","ceiling") && !(p.component in setOf("wall","corner","trim") && (p.pos.x==-1 || p.pos.z==PanelBuildingExample.DEPTH))
            }
            g.flush();val pose=g.pose();pose.pushPose();pose.translate(width/2.0,(height+4)/2.0,180.0)
            pose.scale(scale,-scale,scale);pose.mulPose(q);pose.translate(-(hi.x+lo.x)/2.0,-(hi.y+lo.y)/2.0,-(hi.z+lo.z)/2.0)
            val mc=Minecraft.getInstance()
            for(p in cells) {
                pose.pushPose();pose.translate(p.pos.x.toDouble(),p.pos.y.toDouble(),p.pos.z.toDouble())
                if(p.block.isEmpty()) Meshes.draw(p.parts,p.material,pose,g.bufferSource(),15728880,groups=p.groups,surfaces=p.surfaces)
                else block(mc,g,Economy.nativeState(p.block))
                pose.popPose()
            }
            if(cutaway) for((pos,state) in furniture) {
                pose.pushPose();pose.translate(pos.x.toDouble(),pos.y.toDouble(),pos.z.toDouble());block(mc,g,state);pose.popPose()
            }
            g.flush();pose.popPose()
            g.drawCenteredString(font,Component.literal("Panel 3/16 · Layerfenster 2/16 · Kantenleisten 2×2/16 · innen 4×4×4 frei"),width/2,height-39,0xffc5d7db.toInt())
            g.drawCenteredString(font,Component.literal(if(cutaway) "Schnittdarstellung: Dach, Decke und zwei Vorderwände ausgeblendet; Bauplan unverändert" else "Vollständiger Bauplan mit Boden und Decke außerhalb des Innenraums"),width/2,height-23,0xff92aaa9.toInt())
        }
        private fun block(mc:Minecraft,g:GuiGraphics,state:BlockState) {
            // Vanilla dispatches animated block models such as chests through their real BEWLR.
            mc.blockRenderer.renderSingleBlock(state,g.pose(),g.bufferSource(),15728880,OverlayTexture.NO_OVERLAY)
        }
    }
}
