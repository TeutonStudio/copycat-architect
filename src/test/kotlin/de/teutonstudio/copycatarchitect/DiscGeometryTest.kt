package de.teutonstudio.copycatarchitect

import com.google.gson.Gson
import de.teutonstudio.copycatarchitect.domain.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class DiscGeometryTest {
    private val source=BaseShapes.DISC_SOURCE
    private fun disc(cut:Int=0)=Part(source=source,discCut=cut)

    @Test fun `centered disc family retains real pane items and stable selection keys`() {
        assertEquals(18,Adapters.all.size)
        assertEquals(20,BaseShapes.all.size)
        assertEquals(20,BaseShapes.all.map { it.id }.distinct().size)
        val family=(0..2).map { BaseShapes.find(source,it) }
        assertEquals(listOf(source,"$source#half_disc","$source#quarter_disc"),family.map { it.id })
        assertEquals(listOf("flat_pane","half_disc","quarter_disc"),family.map { it.name })
        assertEquals(family,BaseShapes.all.dropWhile { it.source!=source }.take(3))
        family.forEach { shape ->
            assertEquals(shape,BaseShapes.get(shape.id))
            assertEquals(source,shape.part("glass").source)
            assertEquals("glass",shape.part("glass").group)
            assertEquals(shape.discCut,shape.part().discCut)
            assertEquals(mapOf("axis" to "y"),shape.part().sourceProperties())
        }
    }

    @Test fun `whole half and quarter discs use the same centered two pixel plane`() {
        assertEquals(listOf(Box(0,7,0,16,9,16)),Solid.part(disc()).boxes())
        assertEquals(listOf(Box(0,7,8,16,9,16)),Solid.part(disc(1)).boxes())
        assertEquals(listOf(Box(0,7,0,8,9,8)),Solid.part(disc(2)).boxes())
        assertEquals(listOf(512,256,128),(0..2).map { Solid.part(disc(it)).count() })
        for(axis in listOf(RotationAxis.X,RotationAxis.Y,RotationAxis.Z)) for(cut in 0..2) {
            val rotated=assertNotNull(PartRotations.rotate(disc(cut),axis,1))
            assertEquals(Solid.part(disc(cut)).rotate(axis,1).key(),Solid.part(rotated).key())
            assertEquals(32 shr cut,Extrusion.cost(listOf(rotated)))
        }
    }

    @Test fun `two halves and four quarters reconstruct every centered disc mounting`() {
        for(top in listOf(false,true)) for(tilt in 0..3) for(turn in 0..3) {
            val whole=disc().copy(top=top,tilt=tilt,turn=turn)
            val halves=listOf(0,2).map { whole.copy(discCut=1,spin=it) }
            val quarters=(0..3).map { whole.copy(discCut=2,spin=it) }
            assertEquals(Solid.part(whole).key(),Geometry.solid(halves).key())
            assertEquals(Solid.part(whole).key(),Geometry.solid(quarters).key())
            assertEquals(4,quarters.map { Solid.part(it).key() }.distinct().size)
            assertEquals(32,Extrusion.cost(listOf(whole)))
            assertEquals(32,Extrusion.cost(halves))
            assertEquals(32,Extrusion.cost(quarters))
            assertEquals(Placement(parts=listOf(whole)).visualKey(),Placement(parts=quarters).visualKey())
            assertEquals(Placement(parts=listOf(whole)).surfaceItems(),Placement(parts=quarters).surfaceItems())
        }
    }

    @Test fun `normal rotation remains in its mounted plane and follows the local disc spin`() {
        val axes=mutableSetOf<String>()
        for(top in listOf(false,true)) for(tilt in 0..3) for(turn in 0..3) {
            val quarter=disc(2).copy(top=top,tilt=tilt,turn=turn)
            val normal=PartRotations.normal(quarter)
            val quarters=(0..3).map { q -> assertNotNull(PartRotations.rotate(quarter,RotationAxis.NORMAL,q)) }
            quarters.forEach { assertEquals(normal,PartRotations.normal(it)) }
            val expected=quarter.copy(spin=if(top) 3 else 1)
            assertEquals(Solid.part(expected).key(),Solid.part(quarters[1]).key())
            assertEquals(4,quarters.map { Solid.part(it).key() }.distinct().size)
            assertEquals(Solid.part(quarter.copy(discCut=0)).key(),Geometry.solid(quarters).key())
            axes+=quarter.sourceProperties().getValue("axis")
        }
        assertEquals(setOf("x","y","z"),axes)
    }

    @Test fun `axis rotations preserve offsets clipped masks materials and close after four turns`() {
        val original=disc(2).copy(offset=P(1,1,1),group="glass",recipeSource="original")
        val clipped=Solid.part(original).intersect(Solid.part(Part("copycats:copycat_byte",top=true)))
        assertTrue(clipped.count() in 1 until Solid.part(original).count())
        val part=original.copy(cutMask=clipped.mask())
        for(axis in RotationAxis.entries) {
            var rotated=part
            repeat(4) { rotated=assertNotNull(PartRotations.rotate(rotated,axis,1)) }
            assertEquals(Solid.part(part).key(),Solid.part(rotated).key())
            assertEquals(part.offset,rotated.offset)
            assertEquals(part.group,rotated.group)
            assertEquals(part.recipeSource,rotated.recipeSource)
            assertEquals(PartRotations.normal(part),PartRotations.normal(rotated))
            val forward=assertNotNull(PartRotations.rotate(part,axis,1))
            val backward=assertNotNull(PartRotations.rotate(forward,axis,-1))
            assertEquals(Solid.part(part).key(),Solid.part(backward).key())
        }
    }

    @Test fun `rotation rejects unavailable native orientations but permits valid upside down states`() {
        val stairs=Part("copycats:copycat_stairs")
        val turned=assertNotNull(PartRotations.rotate(stairs,RotationAxis.X,1))
        assertEquals(Solid.part(stairs).rotate(RotationAxis.X,1).key(),Solid.part(turned).key())
        assertEquals(0,turned.tilt)
        assertFalse(turned.top)
        assertEquals("north",turned.sourceProperties()["facing"])
        assertNull(PartRotations.rotate(stairs,RotationAxis.Z,-1))
        assertNull(PartRotations.rotate(Part("copycats:copycat_vertical_step"),RotationAxis.X,1))
        val inverted=assertNotNull(PartRotations.rotate(stairs,RotationAxis.X,2))
        assertEquals(Solid.part(stairs).rotate(RotationAxis.X,2).key(),Solid.part(inverted).key())
        assertEquals(0,inverted.tilt)
        assertTrue(inverted.top)
        assertNotNull(PartRotations.rotate(Part("copycats:copycat_block"),RotationAxis.X,1))
        assertNull(PartRotations.rotate(disc(2).copy(offset=P(15,0,0)),RotationAxis.X,1))
    }

    @Test fun `native half panels expose all mounted halves and encode vertical offsets correctly`() {
        val parts=buildList {
            for(top in listOf(false,true)) for(tilt in 0..3) for(turn in 0..3) for(spin in 0..3) {
                add(Part("copycats:copycat_half_panel",turn=turn,top=top,tilt=tilt,spin=spin))
            }
        }
        assertEquals(24,parts.map { Solid.part(it).key() }.distinct().size)
        assertEquals(24,parts.map { it.sourceProperties() }.distinct().size)
        assertEquals(6,parts.map { it.sourceProperties().getValue("facing") }.distinct().size)
        for(group in parts.groupBy { Solid.part(it).key() }.values) assertEquals(1,group.map { it.sourceProperties() }.distinct().size)
        val vertical=Part("copycats:copycat_half_panel",tilt=1)
        assertEquals(mapOf("facing" to "north","offset" to "north"),vertical.sourceProperties())
        assertEquals(mapOf("facing" to "north","offset" to "west"),vertical.copy(spin=1).sourceProperties())
    }

    @Test fun `graph union of four normal rotated quarters has full disc geometry and cost`() {
        for(tilt in listOf(0,1)) for(turn in 0..3) {
            val quarter=disc(2).copy(tilt=tilt,turn=turn)
            val inputs=(0..3).map { q -> GraphNode("quarter$q",part=assertNotNull(PartRotations.rotate(quarter,RotationAxis.NORMAL,q))) }
            val graph=NodeGraph(nodes=inputs+GraphNode("union",NodeKind.UNION,inputs.map { it.id })+GraphNode("result",NodeKind.OUTPUT,listOf("union")))
            val compiled=GraphCompiler.compile(graph)
            assertEquals(Solid.part(quarter.copy(discCut=0)).key(),Geometry.solid(compiled).key())
            assertEquals(32,Extrusion.cost(compiled))
            assertTrue(compiled.all { it.source==source && it.discCut==2 })
        }
    }

    @Test fun `material ownership partitions the centered disc without gaps or extra source costs`() {
        val groups=listOf(MaterialGroup(),MaterialGroup("glass","Glass","glass",1))
        val halves=listOf(disc(1),disc(1).copy(spin=2,group="glass"))
        val resolved=Geometry.resolve(halves,groups)
        assertEquals(Solid.part(disc()).key(),resolved.solid.key())
        assertEquals(256,resolved.owned.getValue("body").count())
        assertEquals(256,resolved.owned.getValue("glass").count())
        assertEquals(0,resolved.owned.getValue("body").intersect(resolved.owned.getValue("glass")).count())
        assertEquals(32,Extrusion.cost(halves,groups))
        assertEquals(mapOf("minecraft:stone" to 1,"minecraft:glass" to 1),Placement(parts=halves,groups=groups,surfaces=mapOf("glass" to "minecraft:glass")).surfaceItems())
    }

    @Test fun `disc fractions validate their source rotation and integral recipe share`() {
        assertFails { Solid.part(disc(3)) }
        assertFails { Solid.part(disc(-1)) }
        assertFails { Solid.part(disc(2).copy(spin=4)) }
        assertFails { Solid.part(Part("copycats:copycat_byte",discCut=1)) }
        val recipes=Adapters.all.mapValues { it.value.output }
        assertEquals(1,Extrusion.cost(listOf(disc(2)),recipes=recipes+(source to 64)))
        assertEquals(1,Extrusion.cost(listOf(disc(1)),recipes=recipes+(source to 128)))
        assertFails { Extrusion.cost(listOf(disc(2)),recipes=recipes+(source to 128)) }
        assertFails { Extrusion.cost(listOf(disc(1)),recipes=recipes+(source to 3)) }
    }

    @Test fun `old saved parts default to whole shape and zero local spin`() {
        val gson=Gson()
        val previous=Part("copycats:copycat_flat_pane",turn=2,top=true,tilt=1)
        val oldJson=gson.toJsonTree(previous).asJsonObject.apply { remove("spin");remove("discCut") }
        val restored=gson.fromJson(oldJson,Part::class.java)
        assertEquals(0,restored.spin)
        assertEquals(0,restored.discCut)
        assertEquals(previous,restored)
        assertEquals(Solid.part(previous).key(),Solid.part(restored).key())
        val changed=disc(2).copy(turn=3,tilt=1,spin=2)
        assertEquals(changed,gson.fromJson(gson.toJson(changed),Part::class.java))
    }
}
