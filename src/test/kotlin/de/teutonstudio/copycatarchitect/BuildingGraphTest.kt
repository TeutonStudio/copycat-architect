package de.teutonstudio.copycatarchitect

import de.teutonstudio.copycatarchitect.domain.*
import com.google.gson.Gson
import org.junit.jupiter.api.Test
import kotlin.test.*

class BuildingGraphTest {
    private val forms=mapOf("full@1" to Form(id="full",parts=listOf(Part("copycats:copycat_block")) ))
    private fun building(graph:BuildingGraph=BuildingGraph.starter())=Building(schema=2,graph=graph)
    private fun eval(graph:BuildingGraph)=BuildingGraphCompiler.Evaluation(graph,Building().palette,forms,emptyMap())
    private fun plan(graph:BuildingGraph=BuildingGraph.starter())=Generator.generate(building(graph),forms,emptyMap())
    @Test fun `nested patterns flatten A BC D with exact fixed dimensions`() {
        var g=BuildingGraph.starter()
        val inputs=listOf("A","B","C","D").mapIndexed { i,id -> BuildingNode(id,BuildingKind.SEGMENT,module="",length=i+1) }
        val bc=BuildingNode("BC",BuildingKind.PATTERN,inputs=listOf("B","C"));val all=BuildingNode("ABCD",BuildingKind.PATTERN,inputs=listOf("A","BC","D"))
        g=g.copy(nodes=g.nodes+inputs+bc+all)
        val result=eval(g).segment("ABCD")
        assertEquals(10,result.length);assertEquals(4,result.height);assertEquals(40,result.cells.size)
        assertEquals((0..9).toSet(),result.cells.map { it.cell.pos.x }.toSet())
        assertFails { eval(g.replace(inputs[0].copy(height=3))).segment("ABCD") }
    }
    @Test fun `union of rectangles has exact perimeter and stable wall connections`() {
        val rects=listOf(Rect(width=4,length=3),Rect(2,2,4,3))
        val footprint=RoomGeometry.cells(rects);assertEquals(22,footprint.size)
        val edges=RoomGeometry.edges(rects);assertEquals(22,edges.sumOf { it.length })
        assertTrue(edges.all { e -> e.cells.none { it in footprint } })
        assertEquals(edges,RoomGeometry.edges(rects.reversed()))
    }
    @Test fun `empty wall adapts to all room wall dimensions without resource items`() {
        val g=BuildingGraph.starter();val room=g.nodes.first { it.id=="room" }.copy(rectangles=listOf(Rect(width=7,length=5)),walls=emptyMap(),height=9)
        val r=eval(g.replace(room)).room("room")
        assertEquals(9,r.usableHeight);assertTrue(r.plan.placements.none { it.component=="wall" });assertTrue(r.plan.materials.keys.none { it=="minecraft:air" })
        assertTrue(r.plan.clear.any { it.x==-1 && it.y==8 });assertTrue(r.plan.placements.none { it.block=="minecraft:air" })
        assertFails { eval(g.replace(room.copy(walls=RoomGeometry.edges(room.rectangles).associate { it.id to "wall" }))).room("room") }
    }
    @Test fun `floor and ceiling boundary occupancy reserve first and highest cells`() {
        val g=BuildingGraph.starter();val floor=g.nodes.first { it.id=="floor" };val ceiling=g.nodes.first { it.id=="ceiling" }
        val room=eval(g).room("room");assertEquals(4,room.usableHeight)
        val both=eval(g.replace(floor.copy(occupied=true)).replace(ceiling.copy(occupied=true))).room("room")
        assertEquals(2,both.usableHeight)
        assertTrue(P(1,0,1) !in both.plan.clear && P(1,3,1) !in both.plan.clear)
        assertTrue(both.plan.placements.any { it.pos==P(1,0,1) && it.component=="floor" })
        assertTrue(both.plan.placements.any { it.pos==P(1,3,1) && it.component=="ceiling" })
    }
    @Test fun `carpet consumes a native item on a structural layer and blocks the bottom cell`() {
        val g=BuildingGraph.starter();val floor=g.nodes.first { it.id=="floor" }.copy(thickness=2,occupied=true,finish="minecraft:white_carpet")
        val room=eval(g.replace(floor)).room("room")
        assertEquals(3,room.usableHeight);assertEquals(9,room.plan.materials["minecraft:white_carpet"])
        assertTrue(room.plan.placements.any { it.pos==P(1,0,1) && it.block=="minecraft:white_carpet" })
        assertTrue(room.plan.placements.any { it.pos==P(1,-1,1) && it.block.isEmpty() })
        assertFails { eval(g.replace(floor.copy(thickness=1))) }
    }
    @Test fun `storey offsets place rooms with shared walls exactly once and reject overlap`() {
        val g=BuildingGraph.starter();val story=g.nodes.first { it.id=="storey" }.copy(inputs=listOf("room","room"),offsets=listOf(P(),P(4,0,0)))
        val s=eval(g.replace(story)).storey("storey")
        assertEquals(18,s.plan.clear.count { it.y==1 && it.z in 0..2 && it.x in 0..6 })
        assertEquals(s.plan.placements.size,s.plan.placements.map { it.pos }.distinct().size)
        assertFails { eval(g.replace(story.copy(offsets=listOf(P(),P(2,0,0))))).storey("storey") }
    }
    @Test fun `upper and lower floors stack and shared plates are paid once`() {
        val g=BuildingGraph.starter();val output=g.nodes.first { it.id=="building" }.copy(levels=mapOf(-1 to "storey",0 to "storey",1 to "storey"))
        val compiled=BuildingGraphCompiler.compile(building(g.replace(output)),forms,emptyMap())
        assertEquals(listOf(-1,0,1),compiled.storeys.map { it.level });assertEquals(listOf(1,6,11),compiled.storeys.map { it.base })
        assertEquals(0,compiled.plan.placements.minOf { it.pos.y });assertEquals(compiled.plan.placements.size,compiled.plan.placements.map { it.pos }.distinct().size)
        assertTrue(compiled.plan.placements.none { it.pos in compiled.plan.clear })
        assertEquals(25,compiled.plan.placements.count { it.pos.y==5 })
    }
    @Test fun `building sockets exist only while wiring or connected and compact after disconnect`() {
        var g=BuildingGraph.starter();var output=g.nodes.first { it.id=="building" }
        assertEquals(setOf("roof","level:0"),BuildingPorts.of(output).map { it.key }.toSet())
        assertTrue(BuildingPorts.of(output,true).any { it.key=="level:-1" })
        g=g.connect("building","level:1","storey").connect("building","level:-1","storey")
        output=g.nodes.first { it.id=="building" };assertEquals(setOf(-1,0,1),output.levels.keys)
        g=g.connect("building","level:2","storey").connect("building","level:1","")
        assertEquals(setOf(-1,0,1),g.nodes.first { it.id=="building" }.levels.keys)
        assertTrue(BuildingPorts.of(g.nodes.first { it.id=="building" }).none { it.key=="level:2" })
    }
    @Test fun `typed connections cycles missing inputs and wrong heights are rejected`() {
        val g=BuildingGraph.starter()
        assertFails { g.connect("room","floor","wall") };assertFails { g.connect("storey","in:0","storey") }
        assertFails { plan(g.connect("room","floor","")) }
        assertFails { BuildingGraphCompiler.structure(g.copy(nodes=g.nodes+g.nodes.first())) }
        assertFails { plan(g.replace(g.nodes.first { it.id=="wall" }.copy(height=3))) }
    }
    @Test fun `arbitrary orthogonal footprints have a single valid gable roof in both axes`() {
        val g=BuildingGraph.starter();val room=g.nodes.first { it.id=="room" }.copy(rectangles=listOf(Rect(width=3,length=3),Rect(0,3,2,3)),walls=emptyMap())
        for(ridge in 0..1) {
            val roof=g.nodes.first { it.id=="roof" }.copy(roofType="gable",ridge=ridge,overhang=1)
            val p=plan(g.replace(room).replace(roof))
            assertTrue(p.placements.any { it.component=="roof" });assertTrue(p.placements.any { it.component=="gable" })
            assertEquals(p.placements.size,p.placements.map { it.pos }.distinct().size)
        }
    }
    @Test fun `moving room rectangles preserves directional wall assignments`() {
        val graph=BuildingGraph.starter();val room=graph.nodes.first { it.id=="room" }
        val moved=RoomGeometry.reshape(room,listOf(Rect(3,2,3,3)))
        assertEquals(4,moved.walls.size);assertTrue(moved.walls.values.all { it=="wall" })
        assertEquals(4,eval(graph.replace(moved)).room("room").usableHeight)
    }
    @Test fun `native air module cells are openings and carpets reject unsupported bases`() {
        val graph=BuildingGraph.starter();val source=graph.nodes.first { it.id=="wall" }.copy(module="opening@1")
        val module=Module(id="opening",cells=(0..2).flatMap { x -> (0..3).map { y -> Cell(P(x,y,0),block="minecraft:air") } })
        val p=BuildingGraphCompiler.Evaluation(graph.replace(source),Building().palette,forms,mapOf(module.key to module)).room("room").plan
        assertTrue(p.placements.none { it.component=="wall" });assertTrue(p.materials.keys.none { it=="minecraft:air" })
        val floor=graph.nodes.first { it.id=="floor" }.copy(form="slab@1",finish="minecraft:white_carpet",thickness=2)
        assertFails { BuildingGraphCompiler.Evaluation(graph.replace(floor),Building().palette,forms+("slab@1" to Form(parts=listOf(Part("copycats:copycat_slab")))),emptyMap()).room("room") }
    }
    @Test fun `empty wall gaps inherit the fixed pattern height`() {
        val g=BuildingGraph.starter();val a=BuildingNode("tall",BuildingKind.SEGMENT,module="",length=2,height=7)
        val pattern=BuildingNode("with_gap",BuildingKind.PATTERN,inputs=listOf("tall","empty_wall","tall"))
        val result=eval(g.copy(nodes=g.nodes+a+pattern)).segment("with_gap")
        assertEquals(5,result.length);assertEquals(7,result.height)
        assertEquals(7,result.cells.count { it.cell.pos.x==2 && it.cell.empty })
    }
    @Test fun `occupied interior layers do not overwrite empty walls when stacking rooms`() {
        val graph=BuildingGraph.starter();val room=graph.nodes.first { it.id=="room" }.copy(walls=emptyMap())
        val floor=graph.nodes.first { it.id=="floor" }.copy(occupied=true)
        val output=graph.nodes.first { it.id=="building" }.copy(levels=mapOf(-1 to "storey",0 to "storey",1 to "storey"))
        val g=graph.replace(room).replace(floor).replace(output)
        val compiled=BuildingGraphCompiler.compile(building(g),forms,emptyMap())
        assertEquals(listOf(0,5,10),compiled.storeys.map { it.base })
        assertTrue(compiled.plan.placements.none { it.pos in compiled.plan.clear })
        val ceiling=graph.nodes.first { it.id=="ceiling" }.copy(occupied=true)
        assertTrue(plan(g.replace(ceiling)).placements.none { it.component=="wall" })
    }
    @Test fun `delivered basement example compiles to the accepted native carpet building`() {
        val b=Gson().fromJson(java.io.File("examples/building-graph-basements.json").readText(),Building::class.java)
        val compiled=BuildingGraphCompiler.compile(b,forms,emptyMap())
        assertEquals(603,compiled.plan.placements.size);assertEquals(54,compiled.plan.materials["minecraft:white_carpet"])
        assertEquals(listOf(-1,0,1),compiled.storeys.map { it.level })
        assertEquals(3,BuildingGraphCompiler.Evaluation(b.graph!!,b.palette,forms,emptyMap()).segment("wall").length)
    }
    @Test fun `graph persists with module versions and produces reproducible examples`() {
        val b=building();val text=Gson().toJson(b);val restored=Gson().fromJson(text,Building::class.java)
        assertEquals(b,restored);assertEquals(plan(),Generator.generate(restored,forms,emptyMap()))
        java.io.File("build/examples").mkdirs();java.io.File("build/examples/building-graph.json").writeText(text)
    }
    @Test fun `Copycat ports assemble one or several definitions into fixed wall segments`() {
        val copy=BuildingNode("copy",BuildingKind.COPYCAT,form="full@1")
        val second=copy.copy(id="second",role="frame")
        var g=BuildingGraph.starter().copy(nodes=BuildingGraph.starter().nodes+copy+second)
        assertEquals(setOf(BuildingKind.COPYCAT),BuildingPorts.of(g.nodes.first { it.id=="wall" },true).single().accepts)
        g=g.connect("wall","in:0","copy").connect("wall","in:1","second")
        val segment=eval(g).segment("wall")
        assertEquals(12,segment.cells.size)
        assertEquals("wall",segment.cells.single { it.cell.pos==P() }.cell.role)
        assertEquals("frame",segment.cells.single { it.cell.pos==P(1,0,0) }.cell.role)
        assertEquals("frame",segment.cells.single { it.cell.pos==P(0,1,0) }.cell.role)
        assertFails { g.connect("wall","in:2","floor") }
        assertFails { g.connect("empty_wall","in:0","copy") }
        assertFails { g.connect("copy","floor","floor") }
        assertFails { eval(g.remove("copy")).segment("wall") }
    }
    @Test fun `walls floor and ceiling assemble a storey directly with the same shell as a room`() {
        val g=BuildingGraph.starter();val room=g.nodes.first { it.id=="room" };val old=eval(g).storey("storey")
        val direct=g.nodes.first { it.id=="storey" }.copy(storeyMode="walls",walls=room.walls,floor=room.floor,ceiling=room.ceiling,inputs=emptyList())
        val changed=g.replace(direct);val current=eval(changed).storey("storey")
        assertEquals(old,current)
        assertTrue(BuildingPorts.of(direct,true).any { it.key=="floor" })
        assertTrue(BuildingPorts.of(direct,true).none { it.key.startsWith("in:") })
        assertFails { changed.connect("storey","floor","wall") }
        assertFails { eval(changed.connect("storey","ceiling","")).storey("storey") }
    }
    @Test fun `Copycat source materials reach plates and roof and carpet validates connected geometry`() {
        val source=BuildingNode("plate_copy",BuildingKind.COPYCAT,role="frame")
        var g=BuildingGraph.starter().copy(nodes=BuildingGraph.starter().nodes+source)
        g=g.connect("floor","in:0","plate_copy").connect("ceiling","in:0","plate_copy").connect("roof","in:0","plate_copy")
        val p=plan(g)
        assertTrue(p.placements.filter { it.component in setOf("floor","ceiling","roof") }.all { it.material==Building().palette.getValue("frame") })
        val floor=g.nodes.first { it.id=="floor" }.copy(thickness=2,finish="minecraft:white_carpet")
        val slab=source.copy(form="slab@1")
        assertFails { BuildingGraphCompiler.Evaluation(g.replace(floor).replace(slab),Building().palette,forms+("slab@1" to Form(parts=listOf(Part("copycats:copycat_slab")))),emptyMap()).room("room") }
    }
    @Test fun `explicit Copycats and wall segments produce a shaped gable roof`() {
        val g=BuildingGraph.assembledStarter();val p=plan(g)
        assertTrue(p.placements.any { it.component=="wall" })
        assertTrue(p.placements.filter { it.component=="roof" }.all { it.parts.single().source=="copycats:copycat_block" })
        assertEquals(8,p.placements.count { it.component=="gable" })
        assertEquals(p.placements.size,p.placements.map { it.pos }.distinct().size)
        val roof=g.nodes.first { it.id=="roof" }
        assertEquals(setOf(BuildingKind.COPYCAT),BuildingPorts.of(roof).first { it.key=="in:0" }.accepts)
        assertEquals(setOf(BuildingKind.SEGMENT,BuildingKind.PATTERN),BuildingPorts.of(roof).first { it.key=="wall:0" }.accepts)
        val wrong=g.nodes.first { it.id=="gable_wall" }.copy(length=4)
        assertFails { plan(g.replace(wrong)) }
        val grouped=roof.copy(materialMode=MaterialMode.GROUPED)
        val custom=g.nodes.first { it.id=="roof_copycat" }.copy(role="frame")
        assertTrue(plan(g.replace(grouped).replace(custom)).placements.filter { it.component=="roof" }.all { it.material==Building().palette.getValue("roof") })
    }
    @Test fun `explicit empty gables and flat roof parapets use segment geometry`() {
        val g=BuildingGraph.assembledStarter();val roof=g.nodes.first { it.id=="roof" }
        val open=g.connect("roof","wall:0","empty_wall").connect("roof","wall:2","empty_wall")
        assertTrue(plan(open).placements.none { it.component=="gable" })
        assertTrue(plan(open).clear.any { it.y>=6 })
        val flat=plan(g.replace(roof.copy(roofType="flat",walls=mapOf("0" to "gable_wall"))))
        assertEquals(35,flat.placements.count { it.component=="roof" })
        val disconnected=g.connect("roof","wall:0","")
        assertFalse(disconnected.nodes.first { it.id=="roof" }.walls.containsKey("0"))
        assertEquals(8,plan(disconnected).placements.count { it.component=="gable" })
    }
    @Test fun `assembly example persists all typed stages and compiles`() {
        val b=Gson().fromJson(java.io.File("examples/building-graph-assembly.json").readText(),Building::class.java)
        assertEquals(plan(BuildingGraph.assembledStarter()).placements,Generator.generate(b,forms,emptyMap()).placements)
        val restored=Gson().fromJson(Gson().toJson(building(BuildingGraph.assembledStarter())),Building::class.java)
        assertEquals("walls",restored.graph!!.nodes.first { it.id=="storey" }.storeyMode)
        assertEquals(plan(BuildingGraph.assembledStarter()),Generator.generate(restored,forms,emptyMap()))
    }
    @Test fun `optional Copycat tails disappear after disconnect or source deletion`() {
        val original=BuildingGraph.starter();val a=BuildingNode("copy_a",BuildingKind.COPYCAT);val b=a.copy(id="copy_b")
        val targets=setOf("wall","floor","ceiling","roof")
        val g=original.copy(nodes=original.nodes.map { if(it.id in targets) it.copy(inputs=listOf(a.id,b.id)) else it }+a+b)
        for(target in targets) {
            val disconnected=g.connect(target,"in:1","")
            val node=disconnected.nodes.first { it.id==target }
            assertEquals(listOf(a.id),node.inputs)
            assertTrue(BuildingPorts.of(node,true).any { it.key=="in:1" && it.source.isEmpty() })
            assertTrue(plan(disconnected).placements.isNotEmpty())
            val empty=disconnected.connect(target,"in:0","")
            assertTrue(empty.nodes.first { it.id==target }.inputs.isEmpty())
            assertTrue(plan(empty).placements.isNotEmpty())
        }
        val removed=g.remove(b.id)
        assertTrue(removed.nodes.filter { it.id in targets }.all { it.inputs==listOf(a.id) })
        assertTrue(plan(removed).placements.isNotEmpty())
        assertEquals(listOf("",b.id),g.connect("wall","in:0","").nodes.first { it.id=="wall" }.inputs)
        assertFails { plan(g.connect("wall","in:0","")) }
    }
}
