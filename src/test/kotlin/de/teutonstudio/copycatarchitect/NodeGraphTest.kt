package de.teutonstudio.copycatarchitect

import de.teutonstudio.copycatarchitect.domain.*
import com.google.gson.Gson
import kotlin.test.*
import org.junit.jupiter.api.Test

class NodeGraphTest {
    private val full=Part("copycats:copycat_block")
    private val slab=Part("copycats:copycat_slab")
    private fun graph(kind:NodeKind,parts:List<Part>)=NodeGraph(nodes=parts.mapIndexed { i,p -> GraphNode("in$i",part=p) }+
        GraphNode("op",kind,parts.indices.map { "in$it" })+GraphNode("result",NodeKind.OUTPUT,listOf("op")))
    @Test fun `union intersection and difference have exact visible and collision volume`() {
        val layer=Part("copycats:copycat_layer",size=2,tilt=1)
        val a=Solid.part(slab);val b=Solid.part(layer)
        val union=Geometry.solid(GraphCompiler.compile(graph(NodeKind.UNION,listOf(slab,layer))))
        val cut=Geometry.solid(GraphCompiler.compile(graph(NodeKind.INTERSECTION,listOf(slab,layer))))
        val difference=Geometry.solid(GraphCompiler.compile(graph(NodeKind.DIFFERENCE,listOf(slab,layer))))
        for(x in 0..15) for(y in 0..15) for(z in 0..15) {
            assertEquals(a.has(x,y,z)||b.has(x,y,z),union.has(x,y,z))
            assertEquals(a.has(x,y,z)&&b.has(x,y,z),cut.has(x,y,z))
            assertEquals(a.has(x,y,z)&&!b.has(x,y,z),difference.has(x,y,z))
        }
        assertEquals(512,cut.count());assertEquals(1536,difference.count())
    }
    @Test fun `multi input intersection and union are commutative but difference is ordered`() {
        val shapes=listOf(full,slab,Part("copycats:copycat_layer",size=2,tilt=1))
        for(kind in listOf(NodeKind.UNION,NodeKind.INTERSECTION)) assertEquals(Geometry.solid(GraphCompiler.compile(graph(kind,shapes))).key(),Geometry.solid(GraphCompiler.compile(graph(kind,shapes.reversed()))).key())
        assertEquals(2048,Geometry.solid(GraphCompiler.compile(graph(NodeKind.DIFFERENCE,listOf(full,slab)))).count())
        assertFails { GraphCompiler.compile(graph(NodeKind.DIFFERENCE,listOf(slab,full))) }
    }
    @Test fun `nested cutters never reappear and shared sources are paid once`() {
        val g=NodeGraph(nodes=listOf(GraphNode("a",part=full),GraphNode("b",part=slab),GraphNode("c",part=Part("copycats:copycat_vertical_slice",size=2)),
            GraphNode("subtract",NodeKind.DIFFERENCE,listOf("a","b")),GraphNode("join",NodeKind.UNION,listOf("subtract","c","subtract")),GraphNode("result",NodeKind.OUTPUT,listOf("join"))))
        val parts=GraphCompiler.compile(g)
        assertEquals(2,parts.size);assertTrue(parts.none { it.source==slab.source });assertEquals(288,Extrusion.cost(parts))
        val solid=Geometry.solid(parts);assertFalse(solid.has(0,0,0));assertTrue(solid.has(15,0,15));assertTrue(solid.has(0,15,0))
    }
    @Test fun `difference and intersection inherit materials from their first geometry input`() {
        val groups=listOf(MaterialGroup(),MaterialGroup("cap","Glass","glass",1))
        val b=slab.copy(group="cap")
        val diff=Geometry.resolve(GraphCompiler.compile(graph(NodeKind.DIFFERENCE,listOf(full,b)),groups),groups)
        assertEquals(setOf("body"),diff.owned.keys)
        val intersection=Geometry.resolve(GraphCompiler.compile(graph(NodeKind.INTERSECTION,listOf(full,b)),groups),groups)
        assertEquals(setOf("body"),intersection.owned.keys);assertEquals(2048,intersection.solid.count())
        val reversed=Geometry.resolve(GraphCompiler.compile(graph(NodeKind.INTERSECTION,listOf(b,full)),groups),groups)
        assertEquals(setOf("cap"),reversed.owned.keys);assertEquals(intersection.solid.key(),reversed.solid.key())
    }
    @Test fun `clipped geometry rotates with source and survives JSON and normalization`() {
        val g=graph(NodeKind.DIFFERENCE,listOf(full,Part("copycats:copycat_vertical_slice",size=3)))
        val form=Geometry.validate(Form(graph=g));val json=Gson().toJson(form)
        val restored=Geometry.validate(Gson().fromJson(json,Form::class.java))
        assertEquals(form,restored);val solid=Geometry.solid(form.parts)
        for(q in 0..3) assertEquals(solid.rotate(q).key(),Geometry.solid(Geometry.rotate(restored.parts,q)).key())
        assertEquals(form.parts,Geometry.normalized(form.parts));assertEquals(256,Extrusion.cost(form.parts))
    }
    @Test fun `server recomputes client snapshots and rejects forged masks without a graph`() {
        val g=graph(NodeKind.DIFFERENCE,listOf(full,slab))
        val a=Geometry.validate(Form(graph=g,parts=listOf(full)))
        val b=Geometry.validate(Form(graph=g,parts=emptyList()))
        assertEquals(a,b);assertEquals(2048,Geometry.solid(a.parts).count())
        assertFails { Geometry.validate(a.copy(graph=null)) }
        assertFails { GraphCompiler.compile(g.replace(g.nodes[0].copy(part=a.parts[0]))) }
        assertFails { Solid.part(slab.copy(cutMask=Solid.part(full).mask())) }
    }
    @Test fun `cycles bad references wrong ports and disconnected result are rejected`() {
        val g=graph(NodeKind.UNION,listOf(slab,full))
        assertFails { GraphCompiler.compile(g.replace(g.nodes.first { it.id=="op" }.copy(inputs=listOf("op","in0")))) }
        assertFails { GraphCompiler.compile(g.replace(g.nodes.first { it.id=="op" }.copy(inputs=listOf("missing","in0")))) }
        assertFails { GraphCompiler.compile(g.replace(g.nodes.first { it.id=="op" }.copy(inputs=listOf("result","in0")))) }
        assertFails { GraphCompiler.compile(g.replace(g.nodes.first { it.id=="op" }.copy(inputs=listOf("in0")))) }
        assertFails { GraphCompiler.compile(NodeGraph()) }
        assertFails { GraphCompiler.compile(g.copy(nodes=g.nodes+g.nodes[0])) }
        assertFails { GraphCompiler.compile(g.copy(nodes=g.nodes+GraphNode("other",NodeKind.OUTPUT,listOf("in0")))) }
    }
    @Test fun `unused drafts do not affect the result but their cycles are rejected`() {
        val g=NodeGraph.fromParts(listOf(full))
        val unused=GraphNode("unused",NodeKind.UNION,listOf("",""))
        assertEquals(listOf(full),GraphCompiler.compile(g.copy(nodes=g.nodes+unused)))
        assertFails { GraphCompiler.compile(g.copy(nodes=g.nodes+unused.copy(inputs=listOf("unused","")))) }
        val removed=g.remove("source0");assertEquals(listOf(""),removed.nodes.single().inputs)
    }
    @Test fun `list conversion supports all 32 inputs with bounded fan in`() {
        val parts=List(32) { Part(turn=it%4,size=it%8+1) }
        val graph=NodeGraph.fromParts(parts)
        assertTrue(graph.nodes.all { it.inputs.size<=16 })
        assertEquals(Geometry.solid(parts).key(),Geometry.solid(GraphCompiler.compile(graph)).key())
    }
    private val materialGroups=listOf(MaterialGroup(),MaterialGroup("cap","Oak","frame",1))
    @Test fun `every operation can group its full result or preserve input groups`() {
        for(kind in listOf(NodeKind.UNION,NodeKind.INTERSECTION,NodeKind.DIFFERENCE,NodeKind.SYMMETRIC_DIFFERENCE)) {
            val g=graph(kind,listOf(full,slab.copy(group="cap")))
            val separate=GraphCompiler.compile(g,materialGroups)
            val grouped=g.replace(g.nodes.first { it.id=="op" }.copy(materialMode=MaterialMode.GROUPED,materialGroup="cap"))
            val assigned=GraphCompiler.compile(grouped,materialGroups)
            assertEquals(Geometry.solid(separate).key(),Geometry.solid(assigned).key())
            assertEquals(setOf("cap"),Geometry.resolve(assigned,materialGroups).owned.keys)
            assertEquals(separate,GraphCompiler.compile(grouped.replace(grouped.nodes.first { it.id=="op" }.copy(materialMode=MaterialMode.SEPARATED)),materialGroups))
        }
    }
    @Test fun `shared source keeps distinct branch materials and one paid recipe`() {
        val graph=NodeGraph(nodes=listOf(GraphNode("source",part=full),GraphNode("mask",part=slab),
            GraphNode("bottom",NodeKind.INTERSECTION,listOf("source","mask"),materialMode=MaterialMode.GROUPED,materialGroup="cap"),
            GraphNode("top",NodeKind.DIFFERENCE,listOf("source","mask")),
            GraphNode("join",NodeKind.UNION,listOf("top","bottom")),GraphNode("result",NodeKind.OUTPUT,listOf("join"))))
        val parts=GraphCompiler.compile(graph,materialGroups)
        val resolved=Geometry.resolve(parts,materialGroups)
        assertEquals(4096,resolved.solid.count());assertEquals(2048,resolved.owned.getValue("body").count());assertEquals(2048,resolved.owned.getValue("cap").count())
        assertEquals(256,Extrusion.cost(parts,materialGroups))
        assertEquals(128,Extrusion.cost(parts,materialGroups,Extrusion.outputs+(full.source to 2)))
        assertEquals(mapOf("minecraft:stone" to 1,"minecraft:oak_planks" to 1),Placement(parts=parts,groups=materialGroups,surfaces=mapOf("cap" to "minecraft:oak_planks")).surfaceItems())
        for(q in 0..3) assertEquals(256,Extrusion.cost(Geometry.rotate(parts,q),materialGroups))
    }
    @Test fun `outer grouping overrides nested groups without changing boolean volume`() {
        val graph=NodeGraph(nodes=listOf(GraphNode("body",part=full),GraphNode("slab",part=slab),
            GraphNode("cut",NodeKind.DIFFERENCE,listOf("body","slab"),materialMode=MaterialMode.GROUPED,materialGroup="cap"),
            GraphNode("join",NodeKind.UNION,listOf("cut","slab"),materialMode=MaterialMode.GROUPED,materialGroup="body"),
            GraphNode("result",NodeKind.OUTPUT,listOf("join"))))
        val resolved=Geometry.resolve(GraphCompiler.compile(graph,materialGroups),materialGroups)
        assertEquals(setOf("body"),resolved.owned.keys);assertEquals(4096,resolved.solid.count())
    }
    @Test fun `preview filters resolved ownership without changing costs or revealing hidden source geometry`() {
        val parts=listOf(full,Part("copycats:copycat_layer",top=true,group="cap"))
        val form=Geometry.validate(Form(parts=parts,groups=materialGroups))
        val before=Extrusion.cost(form.parts,form.groups);val shape=Geometry.solid(form.parts).key()
        assertEquals(form.parts,Geometry.previewParts(form.parts,form.groups,""))
        assertEquals(3584,Geometry.solid(Geometry.previewParts(form.parts,form.groups,"body")).count())
        assertEquals(512,Geometry.solid(Geometry.previewParts(form.parts,form.groups,"cap")).count())
        assertEquals(before,Extrusion.cost(form.parts,form.groups));assertEquals(shape,Geometry.solid(form.parts).key())
        val hidden=form.copy(groups=materialGroups.map { it.copy(priority=1-it.priority) })
        assertTrue(Geometry.previewParts(hidden.parts,hidden.groups,"cap").isEmpty())
        assertFails { Geometry.previewParts(form.parts,form.groups,"missing") }
    }
    @Test fun `modes targets and output selection persist while legacy nodes default to separated`() {
        val g=graph(NodeKind.UNION,listOf(full,slab.copy(group="cap")))
        val changed=g.replace(g.nodes.first { it.id=="op" }.copy(materialMode=MaterialMode.GROUPED,materialGroup="cap"))
            .replace(g.nodes.first { it.kind==NodeKind.OUTPUT }.copy(previewGroup="cap"))
        val form=Geometry.validate(Form(graph=changed,groups=materialGroups))
        val restored=Geometry.validate(Gson().fromJson(Gson().toJson(form),Form::class.java));assertEquals(form,restored)
        val old=Gson().fromJson("""{"schema":1,"nodes":[{"id":"a","kind":"INPUT"},{"id":"b","kind":"INPUT"},{"id":"join","kind":"UNION","inputs":["a","b"]},{"id":"result","kind":"OUTPUT","inputs":["join"]}]}""",NodeGraph::class.java)
        assertEquals(MaterialMode.SEPARATED,old.nodes[2].materialMode);assertTrue(old.nodes.last().previewGroup.isEmpty())
        assertEquals(1,GraphCompiler.compile(old).size)
        assertFails { GraphCompiler.compile(changed.replace(changed.nodes[2].copy(materialGroup="missing")),materialGroups) }
        assertFails { GraphCompiler.compile(changed.replace(changed.nodes.last().copy(previewGroup="missing")),materialGroups) }
    }
    @Test fun `symmetric difference keeps exactly the odd number of overlapping inputs`() {
        val shapes=listOf(full,slab.copy(group="cap"),Part("copycats:copycat_vertical_slice",size=3))
        val solids=shapes.map(Solid::part)
        val compiled=GraphCompiler.compile(graph(NodeKind.SYMMETRIC_DIFFERENCE,shapes),materialGroups)
        val actual=Geometry.solid(compiled)
        for(x in 0..15) for(y in 0..15) for(z in 0..15)
            assertEquals(solids.count { it.has(x,y,z) }%2==1,actual.has(x,y,z))
        val reversed=GraphCompiler.compile(graph(NodeKind.SYMMETRIC_DIFFERENCE,shapes.reversed()),materialGroups)
        assertEquals(actual.key(),Geometry.solid(reversed).key())
        assertEquals(setOf("body"),Geometry.resolve(GraphCompiler.compile(graph(NodeKind.SYMMETRIC_DIFFERENCE,listOf(full,slab.copy(group="cap"))),materialGroups),materialGroups).owned.keys)
        assertFails { GraphCompiler.compile(graph(NodeKind.SYMMETRIC_DIFFERENCE,listOf(full,full))) }
    }
    @Test fun `separated union assigns a material to each input and detects equivalent grouping`() {
        val g=graph(NodeKind.UNION,listOf(full,slab))
        val join=g.nodes.first { it.id=="op" }
        assertTrue(GraphCompiler.uniformSeparatedGroups(g,join,materialGroups))
        val assigned=join.copy(inputGroups=listOf("body","cap"))
        val separated=g.replace(assigned)
        assertFalse(GraphCompiler.uniformSeparatedGroups(separated,assigned,materialGroups))
        val resolved=Geometry.resolve(GraphCompiler.compile(separated,materialGroups),materialGroups)
        assertEquals(2048,resolved.owned.getValue("body").count());assertEquals(2048,resolved.owned.getValue("cap").count())
        val same=assigned.copy(inputGroups=listOf("cap","cap"))
        assertTrue(GraphCompiler.uniformSeparatedGroups(g.replace(same),same,materialGroups))
        assertEquals(setOf("cap"),Geometry.resolve(GraphCompiler.compile(g.replace(same),materialGroups),materialGroups).owned.keys)
        assertFails { GraphCompiler.compile(g.replace(join.copy(inputGroups=listOf("body","missing"))),materialGroups) }
        assertFails { GraphCompiler.compile(g.replace(join.copy(inputGroups=listOf("body","cap","body"))),materialGroups) }
    }
    @Test fun `union inheritance warning follows nested group assignments and preserves branch recipes`() {
        val g=NodeGraph(nodes=listOf(GraphNode("source",part=full),GraphNode("mask",part=slab),
            GraphNode("bottom",NodeKind.INTERSECTION,listOf("source","mask")),
            GraphNode("top",NodeKind.DIFFERENCE,listOf("source","mask")),
            GraphNode("join",NodeKind.UNION,listOf("top","bottom"),inputGroups=listOf("body","cap")),
            GraphNode("outer",NodeKind.UNION,listOf("join","bottom")),GraphNode("result",NodeKind.OUTPUT,listOf("outer"))))
        val join=g.nodes.first { it.id=="join" };val outer=g.nodes.first { it.id=="outer" }
        assertEquals(setOf("body","cap"),GraphCompiler.inputMaterialGroups(g,outer,0,materialGroups))
        assertFalse(GraphCompiler.uniformSeparatedGroups(g,outer,materialGroups))
        assertEquals(256,Extrusion.cost(GraphCompiler.compile(g,materialGroups),materialGroups))
        val grouped=join.copy(materialMode=MaterialMode.GROUPED,materialGroup="body")
        assertTrue(GraphCompiler.uniformSeparatedGroups(g.replace(grouped),outer,materialGroups))
    }
    @Test fun `symmetric difference separates surviving input groups and groups them explicitly`() {
        val g=graph(NodeKind.SYMMETRIC_DIFFERENCE,listOf(slab,slab.copy(offset=P(0,4,0))))
        val operation=g.nodes.first { it.id=="op" }
        assertTrue(GraphCompiler.uniformSeparatedGroups(g,operation,materialGroups))
        val assigned=operation.copy(inputGroups=listOf("body","cap"))
        val separated=g.replace(assigned)
        assertEquals(setOf("body"),GraphCompiler.inputMaterialGroups(separated,assigned,0,materialGroups))
        assertEquals(setOf("cap"),GraphCompiler.inputMaterialGroups(separated,assigned,1,materialGroups))
        assertFalse(GraphCompiler.uniformSeparatedGroups(separated,assigned,materialGroups))
        val parts=GraphCompiler.compile(separated,materialGroups)
        val resolved=Geometry.resolve(parts,materialGroups)
        assertEquals(2048,resolved.solid.count());assertEquals(1024,resolved.owned.getValue("body").count());assertEquals(1024,resolved.owned.getValue("cap").count())
        assertEquals("body",resolved.owner(0,0,0));assertNull(resolved.owner(0,4,0));assertEquals("cap",resolved.owner(0,8,0))
        assertEquals(256,Extrusion.cost(parts,materialGroups))
        val uniform=assigned.copy(inputGroups=listOf("cap","cap"))
        assertTrue(GraphCompiler.uniformSeparatedGroups(g.replace(uniform),uniform,materialGroups))
        val grouped=assigned.copy(materialMode=MaterialMode.GROUPED,materialGroup="cap")
        val groupedParts=GraphCompiler.compile(g.replace(grouped),materialGroups)
        assertFalse(GraphCompiler.uniformSeparatedGroups(g.replace(grouped),grouped,materialGroups))
        assertEquals(setOf("cap"),Geometry.resolve(groupedParts,materialGroups).owned.keys)
        assertEquals(resolved.solid.key(),Geometry.solid(groupedParts).key())
        assertEquals(GraphCompiler.compile(g.replace(uniform),materialGroups),groupedParts)
        val parent=GraphNode("outer",NodeKind.UNION,listOf("op","in0"))
        val nested=separated.copy(nodes=separated.nodes.dropLast(1)+parent+separated.nodes.last().copy(inputs=listOf("outer")))
        assertEquals(setOf("body","cap"),GraphCompiler.inputMaterialGroups(nested,parent,0,materialGroups))
        assertFalse(GraphCompiler.uniformSeparatedGroups(nested,parent,materialGroups))
    }
    @Test fun `rotation node rotates boolean masks around every world axis without losing their recipe`() {
        val base=graph(NodeKind.DIFFERENCE,listOf(full,Part("copycats:copycat_vertical_slice",size=3)))
        val original=Geometry.solid(GraphCompiler.compile(base))
        for(axis in listOf(RotationAxis.X,RotationAxis.Y,RotationAxis.Z)) for(q in 0..3) {
            val rotated=base.copy(nodes=base.nodes.dropLast(1)+
                GraphNode("rotate",NodeKind.ROTATE,listOf("op",""),rotationAxis=axis,quarterTurns=q)+base.nodes.last().copy(inputs=listOf("rotate")))
            val parts=GraphCompiler.compile(rotated)
            assertEquals(original.rotate(axis,q).key(),Geometry.solid(parts).key())
            assertEquals(full.source,parts.single().source);assertEquals(256,Extrusion.cost(parts))
            val form=Geometry.validate(Form(graph=rotated))
            assertEquals(form,Geometry.validate(Gson().fromJson(Gson().toJson(form),Form::class.java)))
        }
    }
    @Test fun `rotation value input overrides inspector and rejects geometry rotation type mismatches`() {
        val source=Part("copycats:copycat_flat_pane",discCut=2)
        val g=NodeGraph(nodes=listOf(GraphNode("source",part=source),
            GraphNode("angle",NodeKind.ROTATION,rotationAxis=RotationAxis.Y,quarterTurns=2),
            GraphNode("rotate",NodeKind.ROTATE,listOf("source","angle"),rotationAxis=RotationAxis.X,quarterTurns=1),
            GraphNode("result",NodeKind.OUTPUT,listOf("rotate"))))
        val parts=GraphCompiler.compile(g)
        assertEquals(Solid.part(source).rotate(RotationAxis.Y,2).key(),Geometry.solid(parts).key())
        assertEquals(Extrusion.cost(listOf(source)),Extrusion.cost(parts))
        assertEquals(source,g.nodes.first().part)
        val rotate=g.nodes.first { it.id=="rotate" }
        assertFails { GraphCompiler.compile(g.replace(rotate.copy(inputs=listOf("angle","")))) }
        assertFails { GraphCompiler.compile(g.replace(rotate.copy(inputs=listOf("source","source")))) }
        assertFails { GraphCompiler.compile(g.replace(g.nodes.last().copy(inputs=listOf("angle")))) }
        assertFails { GraphCompiler.compile(g.replace(rotate.copy(quarterTurns=4))) }
        assertFalse(GraphCompiler.uniformSeparatedGroups(g,rotate))
    }
    @Test fun `checked in union and rotation examples compile and survive the server JSON codec`() {
        fun load(name:String):Form=Geometry.validate(de.teutonstudio.copycatarchitect.server.Json.decode(java.io.File("examples/$name.json").readText()))
        val materials=load("nodegraph-material-groups")
        val resolved=Geometry.resolve(materials.parts,materials.groups)
        assertEquals(4096,resolved.solid.count());assertEquals(2048,resolved.owned.getValue("cap").count())
        assertEquals(2048,resolved.owned.getValue("body").count());assertEquals(256,Extrusion.cost(materials.parts,materials.groups))
        val rotated=load("nodegraph-rotation-xor")
        assertEquals(setOf(NodeKind.INPUT,NodeKind.ROTATION,NodeKind.ROTATE,NodeKind.SYMMETRIC_DIFFERENCE,NodeKind.OUTPUT),rotated.graph!!.nodes.map { it.kind }.toSet())
        assertEquals(2048,Geometry.solid(rotated.parts).count());assertEquals(256,Extrusion.cost(rotated.parts,rotated.groups))
        val rotatedGroups=Geometry.resolve(rotated.parts,rotated.groups).owned
        assertEquals(1024,rotatedGroups.getValue("body").count());assertEquals(1024,rotatedGroups.getValue("cap").count())
        for(form in listOf(materials,rotated)) {
            val encoded=de.teutonstudio.copycatarchitect.server.Json.encode(form)
            assertEquals(form,Geometry.validate(de.teutonstudio.copycatarchitect.server.Json.decode<Form>(encoded)))
        }
    }
    @Test fun `legacy schemas still load and editable graph example is reproducible`() {
        assertEquals(3,Geometry.validate(Form(schema=1,parts=listOf(full))).schema)
        val g=graph(NodeKind.DIFFERENCE,listOf(full,Part("copycats:copycat_vertical_slice",size=3)))
        val placed=g.copy(nodes=g.nodes.map { n -> when(n.id) {
            "in0"->n.copy(x=20,y=20);"in1"->n.copy(x=20,y=185);"op"->n.copy(x=260,y=65);else->n.copy(x=490,y=65)
        } })
        val example=Form(id="graph_cut",name="Notched Copycat",parts=emptyList(),graph=placed)
        java.io.File("build/examples").mkdirs();java.io.File("build/examples/nodegraph-form.json").writeText(Gson().toJson(example))
        assertEquals(3520,Geometry.solid(Geometry.validate(example).parts).count())
    }
}
