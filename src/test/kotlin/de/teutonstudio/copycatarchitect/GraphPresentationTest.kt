package de.teutonstudio.copycatarchitect

import com.google.gson.Gson
import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.Library
import org.junit.jupiter.api.Test
import kotlin.test.*

class GraphPresentationTest {
    @Test fun `opening a shape graph preserves the complete original document`() {
        val groups=listOf(MaterialGroup(),MaterialGroup("custom","Custom group","custom_role",5))
        val original=Form(schema=1,id="legacy",version=7,name="Legacy form",parts=listOf(
            Part("copycats:copycat_block"),Part("copycats:copycat_slab",top=true,group="custom")),groups=groups)
        val before=Gson().toJson(original)
        val originalGeometry=Geometry.resolve(original.parts,groups)
        val originalCost=Extrusion.cost(original.parts,groups)
        repeat(8) {
            val graph=GraphPresentation.shape(original)
            assertEquals(original.parts,graph.nodes.filter { it.kind==NodeKind.INPUT }.map { it.part })
            val compiled=GraphCompiler.compile(graph,groups)
            assertEquals(originalGeometry.visualKey("minecraft:stone",mapOf("custom" to "minecraft:glass")),Geometry.resolve(compiled,groups).visualKey("minecraft:stone",mapOf("custom" to "minecraft:glass")))
            assertEquals(originalCost,Extrusion.cost(compiled,groups))
        }
        assertNull(original.graph)
        assertEquals(before,Gson().toJson(original))
    }

    @Test fun `native graph presentations preserve disconnected drafts and every node property`() {
        val graph=NodeGraph.fromParts(listOf(Part())).copy(nodes=NodeGraph.fromParts(listOf(Part())).nodes+
            GraphNode("draft",NodeKind.DIFFERENCE,listOf("",""),x=-73,y=415,previewGroup="body"))
        val form=Form(graph=graph)
        assertSame(graph,GraphPresentation.shape(form))
        val buildingGraph=BuildingGraph.starter().copy(nodes=BuildingGraph.starter().nodes+BuildingNode("unused",BuildingKind.PATTERN,inputs=listOf("","")))
        assertSame(buildingGraph,GraphPresentation.building(Building(schema=2,graph=buildingGraph)))
    }

    @Test fun `unsupported clipped shape snapshots can still be displayed without stripping provenance`() {
        val clipped=Part("copycats:copycat_block",cutMask=Solid.part(Part("copycats:copycat_slab")).mask(),recipeSource="receipt")
        val original=Form(parts=listOf(clipped))
        assertEquals(clipped,GraphPresentation.shape(original).nodes.first { it.kind==NodeKind.INPUT }.part)
        assertNull(original.graph)
        assertEquals(listOf(clipped),original.parts)
        // Displaying these snapshots must not relax the saved graph's provenance checks.
        assertFails { GraphCompiler.compile(GraphPresentation.shape(original)) }
    }

    @Test fun `classic building presentation never substitutes a starter or changes its generated plan`() {
        val library=Library().apply { seed() }
        val original=library.buildings.getValue("demo@1").copy(id="classic",version=9,name="Original classic building",holes=listOf(P(2,0,2)))
        val before=Gson().toJson(original)
        val plan=Generator.generate(original,library.forms,library.modules)
        repeat(8) {
            val projection=GraphPresentation.building(original)
            BuildingGraphCompiler.structure(projection)
            assertEquals(original.name,projection.nodes.single { it.kind==BuildingKind.BUILDING }.name)
            assertTrue(projection.nodes.none { it.kind==BuildingKind.ROOM })
            assertEquals(plan,Generator.generate(original,library.forms,library.modules))
        }
        assertNull(original.graph)
        assertEquals(before,Gson().toJson(original))
    }
}
