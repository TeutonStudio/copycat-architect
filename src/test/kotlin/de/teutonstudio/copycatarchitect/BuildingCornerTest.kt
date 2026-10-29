package de.teutonstudio.copycatarchitect

import com.google.gson.Gson
import de.teutonstudio.copycatarchitect.domain.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class BuildingCornerTest {
    private val forms=listOf(
        Form(id="full",parts=listOf(Part("copycats:copycat_block"))),
        Form(id="panel",parts=listOf(Part("create:copycat_panel",tilt=1))),
        Form(id="post",parts=listOf(Part("copycats:copycat_vertical_slice",size=1)))
    ).associateBy { "${it.id}@${it.version}" }
    private val rectangles=listOf(Rect(width=2,length=2))
    private val rotations=mapOf(P(-1,0,-1) to 0,P(2,0,-1) to 1,P(2,0,2) to 2,P(-1,0,2) to 3)
    private fun graph(direct:Boolean=false,explicit:Boolean=true):BuildingGraph {
        val initial=BuildingGraph.starter()
        val wall=initial.nodes.first { it.id=="wall" }.copy(module="",form="panel@1",length=2,height=2)
        val enclosure=initial.nodes.first { it.id==if(direct) "storey" else "room" }.copy(
            height=2,rectangles=rectangles,walls=RoomGeometry.edges(rectangles).associate { it.id to "wall" },
            floor="floor",ceiling="ceiling",corner=if(explicit) "corner_copycat" else "",
            inputs=if(direct) emptyList() else initial.nodes.first { it.id=="room" }.inputs,
            storeyMode=if(direct) "walls" else "rooms")
        val assembled=initial.replace(wall).replace(enclosure)
        return assembled.copy(nodes=assembled.nodes+
            BuildingNode("corner_copycat",BuildingKind.COPYCAT,form="post@1",role="trim"))
    }
    private fun plan(graph:BuildingGraph,direct:Boolean=false):Plan {
        val evaluation=BuildingGraphCompiler.Evaluation(graph,Building().palette,forms,emptyMap())
        return if(direct) evaluation.storey("storey").plan else evaluation.room("room").plan
    }
    private fun positions(graph:BuildingGraph)=graph.nodes.map { DiagramPosition(it.id,it.x,it.y) }
    private fun wires(graph:BuildingGraph)=graph.nodes.flatMap { n -> BuildingPorts.of(n,true).mapNotNull { p ->
        p.source.takeIf(String::isNotEmpty)?.let { DiagramConnection(it,n.id,p.key) }
    } }

    @Test fun `optional corner source is typed and reaches all four inner corner rotations`() {
        for(direct in listOf(false,true)) {
            val graph=graph(direct);val enclosure=graph.nodes.first { it.id==if(direct) "storey" else "room" }
            assertEquals(setOf(BuildingKind.COPYCAT),BuildingPorts.of(enclosure).single { it.key=="corner" }.accepts)
            assertFails { graph.connect(enclosure.id,"corner","wall") }
            val plan=plan(graph,direct)
            assertEquals(8,plan.placements.count { it.component=="trim" })
            for((corner,turn) in rotations) for(y in 0..1) {
                val post=plan.placements.single { it.pos==corner+P(0,y,0) }
                assertEquals("trim",post.component)
                assertEquals(Building().palette.getValue("trim"),post.material)
                assertEquals(turn,post.parts.single().turn)
                val expected=Solid.part(Part("copycats:copycat_vertical_slice",size=1,turn=turn))
                assertEquals(64,Geometry.solid(post.parts).count())
                assertEquals(expected.key(),Geometry.solid(post.parts).key())
            }
            assertTrue(RoomGeometry.cells(rectangles).all { p -> (0..1).all { y -> p+P(0,y,0) in plan.clear } })
            assertTrue(plan.placements.none { it.pos in plan.clear })
        }
        assertTrue(BuildingPorts.of(BuildingGraph.starter().nodes.first { it.id=="storey" }).none { it.key=="corner" })
    }

    @Test fun `corner requires an incident solid wall and keeps empty wall heights open`() {
        val graph=graph();val room=graph.nodes.first { it.id=="room" }
        val north=RoomGeometry.edges(rectangles).single { it.direction==0 }
        val oneWall=graph.replace(room.copy(walls=mapOf(north.id to "wall")))
        val plan=plan(oneWall)
        assertEquals(4,plan.placements.count { it.component=="trim" })
        assertTrue(plan.placements.filter { it.component=="trim" }.all { it.pos.z==-1 })
        for(corner in rotations.keys.filter { it.z==2 }) for(y in 0..1) assertTrue(corner+P(0,y,0) in plan.clear)
        assertTrue(this.plan(graph.replace(room.copy(walls=emptyMap()))).placements.none { it.component=="trim" })
    }

    @Test fun `legacy automatic panel corners merge both compatible wall faces`() {
        val graph=graph(explicit=false);val plan=plan(graph)
        for((corner,_) in rotations) for(y in 0..1) {
            val joint=plan.placements.single { it.pos==corner+P(0,y,0) }
            assertEquals("wall",joint.component)
            assertEquals(1392,Geometry.solid(joint.parts).count())
            val adjacent=RoomGeometry.corners(rectangles).getValue(corner)
            val a=plan.placements.single { it.pos==adjacent.first+P(0,y,0) }
            val b=plan.placements.single { it.pos==adjacent.second+P(0,y,0) }
            assertEquals(Geometry.solid(a.parts).union(Geometry.solid(b.parts)).key(),Geometry.solid(joint.parts).key())
        }
        val room=graph.nodes.first { it.id=="room" };val wall=graph.nodes.first { it.id=="wall" }
        val other=wall.copy(id="other_wall",role="frame")
        val mixed=graph.copy(nodes=graph.nodes+other).replace(room.copy(walls=RoomGeometry.edges(rectangles).associate { it.id to if(it.direction==0) other.id else wall.id }))
        assertEquals(768,Geometry.solid(this.plan(mixed).placements.single { it.pos==P(-1,0,-1) }.parts).count())
    }

    @Test fun `corner disconnect and source removal restore valid automatic joints`() {
        for(direct in listOf(false,true)) {
            val graph=graph(direct);val target=if(direct) "storey" else "room"
            val disconnected=graph.connect(target,"corner","")
            val removed=graph.remove("corner_copycat")
            assertEquals("",disconnected.nodes.first { it.id==target }.corner)
            assertEquals("",removed.nodes.first { it.id==target }.corner)
            assertEquals(plan(disconnected,direct),plan(removed,direct))
            assertTrue(plan(disconnected,direct).placements.none { it.component=="trim" })
            assertEquals(1392,Geometry.solid(plan(disconnected,direct).placements.single { it.pos==P(-1,0,-1) }.parts).count())
        }
    }

    @Test fun `corner diagram projection roundtrip deletion and disconnect preserve optional semantics`() {
        for(direct in listOf(false,true)) {
            val graph=graph(direct);val target=if(direct) "storey" else "room"
            assertEquals(graph,DiagramEdits.building(graph,positions(graph),wires(graph)))
            val disconnected=DiagramEdits.building(graph,positions(graph),wires(graph).filter { it.target!=target || it.port!="corner" })
            val removed=DiagramEdits.building(graph,positions(graph).filter { it.id!="corner_copycat" },wires(graph).filter { it.source!="corner_copycat" })
            assertEquals("",disconnected.nodes.first { it.id==target }.corner)
            assertEquals("",removed.nodes.first { it.id==target }.corner)
            assertEquals(plan(disconnected,direct),plan(removed,direct))
            val reconnected=DiagramEdits.building(disconnected,positions(disconnected),wires(disconnected)+DiagramConnection("corner_copycat",target,"corner"))
            assertEquals(graph,reconnected)
        }
    }

    @Test fun `legacy JSON without optional corner retains defaults and full block geometry`() {
        val gson=Gson();val starter=BuildingGraph.starter()
        val json=gson.toJsonTree(starter).asJsonObject
        json.getAsJsonArray("nodes").forEach { it.asJsonObject.remove("corner") }
        val restored=gson.fromJson(json,BuildingGraph::class.java)
        assertEquals(starter,restored)
        val old=BuildingGraphCompiler.Evaluation(restored,Building().palette,forms,emptyMap()).room("room").plan
        for(corner in RoomGeometry.corners(listOf(Rect(width=3,length=3))).keys)
            assertEquals(4096,Geometry.solid(old.placements.single { it.pos==corner }.parts).count())
        val explicit=graph();assertEquals(explicit,gson.fromJson(gson.toJson(explicit),BuildingGraph::class.java))
    }
}
