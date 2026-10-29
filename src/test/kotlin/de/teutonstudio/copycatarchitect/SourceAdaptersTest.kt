package de.teutonstudio.copycatarchitect

import de.teutonstudio.copycatarchitect.domain.*
import com.google.gson.Gson
import org.junit.jupiter.api.Test
import kotlin.test.*

class SourceAdaptersTest {
    @Test fun `extended sources survive saved form round trips with their state and recipe`() {
        val gson=Gson()
        for(a in Adapters.all.values) {
            val p=Part(a.source,turn=3,top=true,size=a.max,tilt=if(a.tiltable) 1 else 0)
            val form=Geometry.validate(Form(id=a.name,parts=listOf(p)))
            val restored=Geometry.validate(gson.fromJson(gson.toJson(form),Form::class.java))
            assertEquals(form,restored)
            assertEquals(p.sourceProperties(),restored.parts.single().sourceProperties())
            assertEquals(256/a.output*(if(a.layered) a.max else 1),Extrusion.cost(restored.parts))
        }
    }

    @Test fun `centered beam and pane keep a clear rim in every axis`() {
        val beam=Part("copycats:copycat_beam")
        val pane=Part("copycats:copycat_flat_pane")
        assertEquals(listOf(Box(4,0,4,12,16,12)),Solid.part(beam).boxes())
        assertEquals(listOf(Box(0,7,0,16,9,16)),Solid.part(pane).boxes())
        for(p in listOf(beam,pane)) for(tilt in 0..3) for(turn in 0..3) {
            val oriented=p.copy(tilt=tilt,turn=turn)
            assertEquals(Solid.part(p).count(),Solid.part(oriented).count())
            assertFalse(Solid.part(oriented).has(0,0,0))
            assertTrue(oriented.sourceProperties().getValue("axis") in listOf("x","y","z"))
        }
        assertEquals("z",beam.copy(tilt=1).sourceProperties()["axis"])
        assertEquals("x",pane.copy(tilt=1,turn=1).sourceProperties()["axis"])
    }

    @Test fun `eight independently selected bytes partition a full block`() {
        val bytes=(0..1).flatMap { top -> (0..3).map { turn -> Part("copycats:copycat_byte",turn=turn,top=top==1) } }
        assertEquals(8,bytes.map { Solid.part(it).key() }.distinct().size)
        assertEquals(4096,Geometry.solid(bytes).count())
        assertEquals(256,Extrusion.cost(bytes))
        for(p in bytes) {
            assertEquals(512,Solid.part(p).count())
            assertEquals(1,p.sourceProperties().values.count { it=="true" })
        }
        assertEquals("true",bytes.first().sourceProperties()["bottom_northwest"])
        assertEquals("true",Part("copycats:copycat_byte",tilt=1).sourceProperties()["top_northwest"])
    }

    @Test fun `four byte panels reconstruct the panel and charge the original cutting cost`() {
        val quadrants=(0..3).map { Part("copycats:copycat_byte_panel",turn=it) }
        assertEquals(Solid.part(Part("create:copycat_panel")).key(),Geometry.solid(quadrants).key())
        assertEquals(64,Extrusion.cost(quadrants))
        assertEquals(4,quadrants.map { p -> p.sourceProperties().filterValues { it=="true" }.keys.single() }.distinct().size)
        for(p in quadrants) for(top in listOf(false,true)) for(tilt in 0..3) {
            val q=p.copy(top=top,tilt=tilt)
            assertEquals(192,Solid.part(q).count())
            assertEquals(1,q.sourceProperties().values.count { it=="true" })
        }
        assertEquals("down",quadrants.first().sourceProperties()["facing"])
        assertEquals("north",quadrants.first().copy(tilt=1).sourceProperties()["facing"])
    }

    @Test fun `steps and half panels use their actual attached shape instead of the item display shape`() {
        val step=Part("create:copycat_step")
        val vertical=Part("copycats:copycat_vertical_step")
        val half=Part("copycats:copycat_half_panel")
        assertEquals(listOf(Box(0,0,8,16,8,16)),Solid.part(step).boxes())
        assertEquals(listOf(Box(8,0,8,16,16,16)),Solid.part(vertical).boxes())
        assertEquals(listOf(Box(0,0,8,16,3,16)),Solid.part(half).boxes())
        assertEquals("west",step.copy(turn=1).sourceProperties()["facing"])
        assertEquals("top",step.copy(top=true).sourceProperties()["half"])
        assertEquals(mapOf("facing" to "up","offset" to "north"),half.copy(top=true,turn=2).sourceProperties())
        for(p in listOf(step,vertical)) assertFails { Solid.part(p.copy(tilt=1)) }
        assertEquals(384,Solid.part(half.copy(tilt=1)).count())
    }

    @Test fun `half layers preserve thickness independent sides and zinc cost`() {
        for(id in listOf("half_layer","vertical_half_layer","stacked_half_layer")) {
            val source="copycats:copycat_$id"
            for(size in 1..8) {
                val p=Part(source,size=size)
                assertEquals(256*size,Solid.part(p).count())
                assertEquals(16*size,Extrusion.cost(listOf(p)))
                assertEquals(size.toString(),p.sourceProperties()["negative_layers"])
                assertEquals("0",p.sourceProperties()["positive_layers"])
            }
        }
        val a=Part("copycats:copycat_half_layer",size=3)
        assertEquals("x",a.copy(turn=1).sourceProperties()["axis"])
        assertEquals("3",a.copy(turn=1).sourceProperties()["positive_layers"])
        assertEquals("top",a.copy(top=true).sourceProperties()["half"])
        assertEquals("3",Part("copycats:copycat_stacked_half_layer",top=true,size=3).sourceProperties()["positive_layers"])
    }
}
