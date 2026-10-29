package de.teutonstudio.copycatarchitect

import de.teutonstudio.copycatarchitect.domain.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class DiagramEditsTest {
    private fun shapePositions(g:NodeGraph)=g.nodes.map { DiagramPosition(it.id,it.x,it.y) }
    private fun shapeWires(g:NodeGraph)=g.nodes.flatMap { n -> n.inputs.mapIndexedNotNull { i,id -> id.takeIf(String::isNotEmpty)?.let { DiagramConnection(it,n.id,i.toString()) } } }
    private fun positions(g:BuildingGraph)=g.nodes.map { DiagramPosition(it.id,it.x,it.y) }
    private fun wires(g:BuildingGraph)=g.nodes.flatMap { n -> BuildingPorts.of(n,true).mapNotNull { p -> p.source.takeIf(String::isNotEmpty)?.let { DiagramConnection(it,n.id,p.key) } } }

    @Test fun `shape projection roundtrip preserves every property and boolean operation`() {
        val initial=NodeGraph.fromParts(listOf(Part("copycats:copycat_block"),Part("copycats:copycat_slab")))
        val operator=initial.nodes.single { it.kind==NodeKind.UNION }
        val g=initial.replace(operator.copy(kind=NodeKind.DIFFERENCE,materialMode=MaterialMode.GROUPED,materialGroup="body"))
        assertEquals(g,DiagramEdits.shape(g,DEFAULT_GROUPS,shapePositions(g),shapeWires(g)))
        val changed=DiagramEdits.shape(g,DEFAULT_GROUPS,shapePositions(g).map { it.copy(x=it.x+12) },shapeWires(g))
        assertEquals(g.nodes.map { it.copy(x=it.x+12) },changed.nodes)
        assertEquals(GraphCompiler.compile(g),GraphCompiler.compile(changed))
    }
    @Test fun `diagram rejects missing protected node duplicate ports and cycle atomically`() {
        val g=NodeGraph.fromParts(listOf(Part(),Part(turn=1)))
        val p=shapePositions(g);val w=shapeWires(g);val join=g.nodes.single { it.kind==NodeKind.UNION }
        assertFails { DiagramEdits.shape(g,DEFAULT_GROUPS,p.filter { it.id!="result" },w.filter { it.target!="result" }) }
        assertFails { DiagramEdits.shape(g,DEFAULT_GROUPS,p,w+w.first()) }
        assertFails { DiagramEdits.shape(g,DEFAULT_GROUPS,p,w.filter { it.target!=join.id || it.port!="0" }+DiagramConnection(join.id,join.id,"0")) }
        assertEquals(g,DiagramEdits.shape(g,DEFAULT_GROUPS,p,w))
    }
    @Test fun `building projection does not materialize default walls offsets or spare sockets`() {
        val starter=BuildingGraph.starter();val room=starter.nodes.single { it.kind==BuildingKind.ROOM }
        val g=starter.replace(room.copy(walls=emptyMap()))
        assertEquals(g,DiagramEdits.building(g,positions(g),wires(g)))
        val moved=DiagramEdits.building(g,positions(g).map { if(it.id=="room") it.copy(x=999) else it },wires(g))
        assertEquals(g.replace(room.copy(walls=emptyMap(),x=999)),moved)
    }
    @Test fun `building reconnect can add basement and a separately positioned room`() {
        val g=BuildingGraph.starter();var w=wires(g)+DiagramConnection("storey","building","level:-1")+DiagramConnection("room","storey","in:1")
        val changed=DiagramEdits.building(g,positions(g),w)
        assertEquals(setOf(-1,0),changed.nodes.single { it.kind==BuildingKind.BUILDING }.levels.keys)
        assertEquals(listOf(P(),P(4,0,0)),changed.nodes.single { it.kind==BuildingKind.STOREY }.offsets)
        w=wires(changed).filter { it.target!="building" || it.port!="level:-1" }
        assertEquals(mapOf(0 to "storey"),DiagramEdits.building(changed,positions(changed),w).nodes.single { it.kind==BuildingKind.BUILDING }.levels)
    }
    @Test fun `building typed connections deletion and protected sources are validated`() {
        val g=BuildingGraph.starter()
        assertFails { DiagramEdits.building(g,positions(g),wires(g).filter { it.target!="room" || it.port!="floor" }+DiagramConnection("roof","room","floor")) }
        assertFails { DiagramEdits.building(g,positions(g).filter { it.id!="empty_wall" },wires(g)) }
        val removed=DiagramEdits.building(g,positions(g).filter { it.id!="roof" },wires(g).filter { it.source!="roof" })
        assertTrue(removed.nodes.none { it.id=="roof" })
        assertEquals("",removed.nodes.single { it.kind==BuildingKind.BUILDING }.roof)
    }
    @Test fun `assembly diagram adds typed Copycat inputs and disconnects optional roof walls`() {
        val g=BuildingGraph.assembledStarter()
        assertEquals(g,DiagramEdits.building(g,positions(g),wires(g)))
        val changed=DiagramEdits.building(g,positions(g),wires(g).filter { it.target!="roof" || it.port!="wall:0" }+DiagramConnection("roof_copycat","floor","in:1"))
        assertEquals(listOf("wall_copycat","roof_copycat"),changed.nodes.first { it.id=="floor" }.inputs)
        assertFalse(changed.nodes.first { it.id=="roof" }.walls.containsKey("0"))
        assertFails { DiagramEdits.building(g,positions(g),wires(g)+DiagramConnection("wall","floor","in:1")) }
        val removed=DiagramEdits.building(g,positions(g).filter { it.id!="wall_copycat" },wires(g).filter { it.source!="wall_copycat" })
        assertEquals(emptyList(),removed.nodes.first { it.id=="wall" }.inputs)
    }
    @Test fun `deleting Copycat wires or sources trims only optional input tails`() {
        val original=BuildingGraph.starter();val a=BuildingNode("copy_a",BuildingKind.COPYCAT);val b=a.copy(id="copy_b")
        val targets=setOf("wall","floor","ceiling","roof")
        val g=original.copy(nodes=original.nodes.map { if(it.id in targets) it.copy(inputs=listOf(a.id,b.id)) else it }+a+b)
        val disconnected=DiagramEdits.building(g,positions(g),wires(g).filter { it.target !in targets || it.port!="in:1" })
        assertTrue(disconnected.nodes.filter { it.id in targets }.all { it.inputs==listOf(a.id) })
        val removed=DiagramEdits.building(g,positions(g).filter { it.id!=b.id },wires(g).filter { it.source!=b.id })
        assertTrue(removed.nodes.filter { it.id in targets }.all { it.inputs==listOf(a.id) })
        val allDisconnected=DiagramEdits.building(g,positions(g),wires(g).filter { it.target !in targets || !it.port.startsWith("in:") })
        assertTrue(allDisconnected.nodes.filter { it.id in targets }.all { it.inputs.isEmpty() })
        val gap=DiagramEdits.building(g,positions(g),wires(g).filter { it.target!="wall" || it.port!="in:0" })
        assertEquals(listOf("",b.id),gap.nodes.first { it.id=="wall" }.inputs)
    }
}
