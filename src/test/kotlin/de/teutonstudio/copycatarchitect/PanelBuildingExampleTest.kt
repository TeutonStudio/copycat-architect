package de.teutonstudio.copycatarchitect

import com.google.gson.Gson
import de.teutonstudio.copycatarchitect.domain.*
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class PanelBuildingExampleTest {
    private val example=PanelBuildingExample
    private val plan by lazy { example.plan() }
    private val rects=listOf(Rect(width=4,length=4))
    private val base by lazy { BuildingGraphCompiler.compile(example.building,example.forms,example.modules).storeys.single().base }

    @Test fun `panel house keeps all 64 interior blocks usable and all twelve edges trimmed`() {
        assertEquals(188,plan.placements.size)
        assertEquals(188,plan.placements.map { it.pos }.distinct().size)
        val interior=buildSet { for(x in 0..3) for(z in 0..3) for(y in 0..3) add(P(x,base+y,z)) }
        assertEquals(64,interior.size)
        assertTrue(interior.all { it in plan.clear })
        assertTrue(plan.placements.none { it.pos in interior })
        assertEquals(36,plan.placements.count { it.component=="floor" })
        assertEquals(36,plan.placements.count { it.component=="ceiling" })
        val corners=RoomGeometry.corners(rects)
        for(corner in corners.keys) for(y in 0..3) {
            val p=plan.placements.single { it.pos==corner+P(0,base+y,0) }
            assertEquals("trim",p.component)
            assertEquals(setOf("copycats:copycat_vertical_slice"),p.parts.map { it.source }.toSet())
            assertEquals(64,Geometry.solid(p.parts).count(),"Each corner must be a 2 x 2 /16 vertical rail, not a copied panel")
            assertEquals(mapOf("minecraft:oak_planks" to 1),p.surfaceItems())
            val box=Geometry.solid(p.parts).boxes().single()
            assertEquals(16,box.maxY-box.y)
            assertEquals(2,box.maxX-box.x);assertEquals(2,box.maxZ-box.z)
            assertEquals(if(corner.x<0) 14 else 0,box.x)
            assertEquals(if(corner.z<0) 14 else 0,box.z)
        }
        for(edge in RoomGeometry.edges(rects)) for(pos in edge.cells) for(y in listOf(0,3)) {
            val p=plan.placements.single { it.pos==pos+P(0,base+y,0) }
            val trim=Geometry.resolve(p.parts,p.groups).materialSolids(p.material,p.surfaces).getValue("minecraft:oak_planks")
            val b=trim.boxes().single()
            assertEquals(64,trim.count(),"Every upper and lower facade edge has a continuous 16 x 2 x 2 rail")
            assertEquals(if(y==0) 0 else 14,b.y)
            assertEquals(2,b.maxY-b.y)
        }
    }

    @Test fun `all wall directions use three sixteenth panels and two sixteenth edge mounted glass`() {
        var windows=0;var panels=0
        for(edge in RoomGeometry.edges(rects)) for(pos in edge.cells) for(y in 0..3) {
            val p=plan.placements.single { it.pos==pos+P(0,base+y,0) }
            val resolved=Geometry.resolve(p.parts,p.groups)
            val glass=resolved.materialSolids(p.material,p.surfaces)["minecraft:glass"]
            val solid=resolved.solid
            val expectedThickness=if(glass==null) 3 else 2
            val values=mutableSetOf<Int>()
            solid.voxels { x,_,z -> values+=if(edge.direction%2==0) z else x }
            assertEquals(expectedThickness,values.size)
            assertEquals(if(edge.direction==0 || edge.direction==3) (16-expectedThickness until 16).toSet() else (0 until expectedThickness).toSet(),values)
            assertTrue(p.parts.none { it.source=="copycats:copycat_flat_pane" })
            if(glass!=null) {
                windows++
                assertEquals(512,solid.count(),"The window contains no opaque panel behind its glass")
                assertEquals(288,glass.count(),"A 12 x 12 clear pane remains inside the two-voxel frame")
                assertTrue(p.parts.any { it.source=="copycats:copycat_layer" && it.size==1 })
                assertFalse(values.any { it==7 || it==8 },"Windows must sit on the wall edge, not in the block center")
            } else {
                panels++
                assertEquals(768,solid.count())
                assertTrue(p.parts.any { it.source=="create:copycat_panel" })
            }
        }
        assertEquals(16,windows);assertEquals(48,panels)
    }

    data class Bundle(val forms:Map<String,Form>,val modules:Map<String,Module>,val buildings:Map<String,Building>)
    @Test fun `documented example imports with its forms modules graph and exact material bill`() {
        val gson=Gson()
        val bundle=gson.fromJson(File("examples/panel-building/library.json").readText(),Bundle::class.java)
        val b=bundle.buildings.getValue("panel_house@1")
        assertEquals(example.forms,bundle.forms.mapValues { Geometry.validate(it.value) })
        assertEquals(example.modules,bundle.modules)
        assertEquals(example.building,b)
        assertEquals(plan,Generator.generate(b,bundle.forms,bundle.modules))
        assertEquals(plan,Generator.generate(gson.fromJson(gson.toJson(b),Building::class.java),bundle.forms,bundle.modules))
        assertEquals(33024,plan.raw)
        assertEquals(mapOf("minecraft:oak_planks" to 136,"minecraft:stone_bricks" to 48,"minecraft:glass" to 16,"minecraft:deepslate_tiles" to 36),plan.materials)
    }
}
