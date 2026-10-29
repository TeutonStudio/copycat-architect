package de.teutonstudio.copycatarchitect

import de.teutonstudio.copycatarchitect.domain.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class DomainTest {
    private val full=Form(id="full",parts=listOf(Part("copycats:copycat_block")))
    private val forms=mapOf("full@1" to full,"inner@1" to Form(id="inner",parts=listOf(Part(),Part(turn=1))))
    private fun module(id:String,w:Int=1,h:Int=4,variable:Boolean=false)=Module(id=id,name=id,width=w,height=h,cells=(0 until w).flatMap { x -> (0 until h).map { Cell(P(x,it,0),flexible=variable) } },minWidth=w,maxWidth=if(variable) 4 else w)
    private val modules=mapOf("S" to module("S",3),"I" to module("I"),"A" to module("A",2),"B" to module("B",3),"F" to module("F"),"V" to module("V",1,variable=true))
    @Test fun `inner corner union and part removal preserve empty space`() {
        val parts=listOf(Part(),Part(turn=1));val s=Geometry.solid(parts)
        assertEquals(120,s.count());assertTrue(s.has(0,0,15));assertTrue(s.has(0,0,0));assertFalse(s.has(8,0,8))
        assertEquals(64,Geometry.solid(parts.take(1)).count())
        assertEquals(120,Geometry.solid(Geometry.rotate(parts,1)).count())
        assertEquals(120,Geometry.solid(parts.map { it.copy(top=true) }).count())
        assertTrue(Geometry.solid(parts.map { it.copy(top=true) }).has(0,15,15))
    }
    @Test fun `duplicates and contained parts do not survive normalization`() {
        assertEquals(listOf(Part()),Geometry.normalized(listOf(Part(),Part())))
        assertEquals(full.parts,Geometry.normalized(listOf(Part(),full.parts[0])))
        assertEquals(full.parts,Geometry.normalized(listOf(full.parts[0],Part())))
        assertEquals(256,Extrusion.cost(listOf(Part(),full.parts[0])))
        assertEquals(16,Extrusion.cost(listOf(Part(),Part())))
    }
    @Test fun `overlarge translations and unsupported adapters are rejected`() {
        assertFails { Geometry.normalized(listOf(Part(offset=P(0,-1,0)))) }
        assertFails { Geometry.normalized(listOf(Part("copycats:door"))) }
        assertFails { Geometry.normalized(List(33) { Part() }) }
    }
    @Test fun `all quarter rotations close and preserve collision volume`() {
        for(a in Adapters.all.values) {
            val p=Part(a.source);assertEquals(Solid.part(p).key(),Geometry.solid(Geometry.rotate(listOf(p),4)).key())
            for(q in 0..3) assertEquals(Solid.part(p).count(),Solid.part(p.copy(turn=q)).count())
        }
    }
    @Test fun `interlude 11 and 12 have exactly two inner gaps`() {
        val p=Pattern(sequence=listOf("S"),interlude="I",filler="F")
        val eleven=Patterns.distribute(11,p,modules)
        assertEquals(listOf("S","I","S","I","S"),eleven.spans.map { it.key });assertEquals(0,eleven.remainder)
        val twelve=Patterns.distribute(12,p,modules)
        assertEquals(1,twelve.remainder);assertEquals("F",twelve.spans.last().key)
    }
    @Test fun `zero segments one segment and strict remainder`() {
        val p=Pattern(sequence=listOf("S"),interlude="I",filler="F")
        assertEquals(listOf("F","F"),Patterns.distribute(2,p,modules).spans.map { it.key })
        assertEquals(listOf("S"),Patterns.distribute(3,p,modules).spans.map { it.key })
        assertFails { Patterns.distribute(4,p.copy(remainder=Remainder.EXACT),modules) }
        assertFails { Patterns.distribute(4,p.copy(remainder=Remainder.ADJUST),modules) }
    }
    @Test fun `repeat only and complete sequence cycles with deterministic centered odd rest`() {
        assertEquals(listOf("S","S","S"),Patterns.distribute(9,Pattern(sequence=listOf("S"),mode=0,remainder=Remainder.EXACT),modules).spans.map { it.key })
        val p=Pattern(sequence=listOf("A","B"),mode=2,filler="F",remainder=Remainder.CENTER)
        assertEquals(listOf("F","A","B","A","B","F","F"),Patterns.distribute(13,p,modules).spans.map { it.key })
        assertEquals(listOf("A","B","A"),Patterns.distribute(7,p.copy(partial=true,remainder=Remainder.EXACT),modules).spans.map { it.key })
        assertEquals(listOf("F","A","B","F"),Patterns.distribute(7,p.copy(start="F",end="F",remainder=Remainder.EXACT),modules).spans.map { it.key })
    }
    @Test fun `variable interludes grow only in explicit flexible modules`() {
        val p=Pattern(sequence=listOf("S"),interlude="V",remainder=Remainder.ADJUST)
        val d=Patterns.distribute(12,p,modules)
        assertEquals(listOf(3,2,3,1,3),d.spans.map { it.width });assertEquals(12,d.spans.sumOf { it.width })
    }
    @Test fun `conflict resolver deduplicates and protects openings`() {
        val r=Resolver();val p=Placement(P(),full.parts)
        r.add(p);r.add(p);assertEquals(1,r.plan(emptyList()).placements.size);assertEquals(256,r.plan(emptyList()).raw)
        assertFails { r.clear(P()) }
        val a=Resolver();a.clear(P());assertFails { a.add(p) }
        assertFails { r.add(p.copy(material="minecraft:glass")) }
    }
    @Test fun `compatible trim merges only when explicitly allowed`() {
        val r=Resolver();r.add(Placement(P(),listOf(Part())));r.add(Placement(P(),listOf(Part(turn=1))),true)
        assertEquals(120,Geometry.solid(r.plan(emptyList()).placements.single().parts).count())
    }
    private fun building(w:Int=9,l:Int=7,roof:String="gable",ridge:Int=0)=Building(wings=listOf(Rect(width=w,length=l)),patterns=List(4) { Pattern(sequence=listOf("F"),mode=0,remainder=Remainder.EXACT) },roof=roof,ridge=ridge,trimForm="")
    @Test fun `multistory shared slabs occur once with distinct top ceiling and roof`() {
        val p=Generator.generate(building(roof="flat"),forms,modules)
        assertEquals(9*7*3,p.placements.count { it.component in listOf("floor","ceiling") })
        assertEquals(p.placements.size,p.placements.map { it.pos }.distinct().size)
        assertEquals(9*7,p.placements.count { it.component=="roof" })
    }
    @Test fun `gable both orientations even odd widths overhangs and closure`() {
        for(w in listOf(8,9)) for(ridge in 0..1) for(overhang in 0..2) {
            val b=building(w=w,ridge=ridge).copy(overhang=overhang)
            val p=Generator.generate(b,forms,modules)
            assertTrue(p.placements.any { it.component=="gable" });assertTrue(p.placements.any { it.component=="roof" })
            val topCeiling=p.placements.filter { it.component=="ceiling" }.maxOf { it.pos.y }
            assertTrue(p.placements.filter { it.component=="roof" }.minOf { it.pos.y }>topCeiling)
            assertEquals(p.placements.size,p.placements.map { it.pos }.distinct().size)
            assertEquals(p,Generator.generate(b,forms,modules))
        }
    }
    @Test fun `L footprint has connected plates and a single roof skin`() {
        val b=building().copy(wings=listOf(Rect(width=11,length=9),Rect(0,9,5,6)),trimForm="inner@1")
        val p=Generator.generate(b,forms,modules)
        assertEquals(129,p.placements.count { it.component=="floor" })
        assertEquals(p.placements.size,p.placements.map { it.pos }.distinct().size)
        assertTrue(p.placements.any { it.component=="corner" && it.pos.x==4 && it.pos.z==8 })
        assertFails { Generator.generate(b.copy(ridge=1),forms,modules) }
    }
    @Test fun `wall openings persist and all directions rotate module shapes`() {
        val m=module("F").copy(cells=listOf(Cell(P(0,0,0)),Cell(P(0,1,0),empty=true),Cell(P(0,2,0),empty=true),Cell(P(0,3,0))))
        val p=Generator.generate(building(),forms,modules+("F" to m))
        assertTrue(p.clear.isNotEmpty());assertTrue(p.clear.none { c -> p.placements.any { it.pos==c } })
        assertEquals(4,p.placements.filter { it.component=="wall" }.map { it.parts.single().turn }.distinct().size)
    }
    @Test fun `missing references and unsupported roofs fail before job creation`() {
        assertFails { Generator.generate(building().copy(floorForm="missing"),forms,modules) }
        assertFails { Generator.generate(building().copy(roof="hip"),forms,modules) }
        assertFails { Generator.generate(building().copy(ceilingThickness=2),forms,modules) }
    }
    @Test fun `wall and ceiling panels retain source state and exact grid alignment`() {
        val floor=Part("create:copycat_panel")
        val wall=floor.copy(tilt=1)
        assertEquals(768,Solid.part(wall).count())
        assertEquals("south",wall.sourceProperties()["facing"])
        assertEquals("down",floor.copy(top=true).sourceProperties()["facing"])
        assertTrue(Solid.part(wall).has(8,8,0));assertFalse(Solid.part(wall).has(8,8,8))
        for(q in 0..3) assertEquals(768,Geometry.solid(Geometry.rotate(listOf(wall),q)).count())
        assertFails { Solid.part(Part(tilt=1)) }
    }

    @Test fun `seed library examples generate a deterministic complete L building`() {
        val lib=de.teutonstudio.copycatarchitect.server.Library();lib.seed()
        val b=lib.buildings.getValue("demo@1");val p=Generator.generate(b,lib.forms,lib.modules)
        assertEquals(1115,p.placements.size)
        assertTrue(p.placements.map { it.component }.containsAll(listOf("floor","ceiling","wall","corner","trim","gable","roof")))
        val out=java.nio.file.Path.of("build/examples");java.nio.file.Files.createDirectories(out)
        val gson=com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val fixtures=mapOf("library" to lib,"l-building" to b,"inner-corner" to lib.forms.getValue("inner@1"),"window-module" to lib.modules.getValue("window@1"),"interlude-module" to lib.modules.getValue("pier@1"),"flat-building" to b.copy(wings=listOf(Rect()),roof="flat"))
        fixtures.forEach { (name,value) -> java.nio.file.Files.writeString(out.resolve("$name.json"),gson.toJson(value)) }
    }

    private fun groupedForm()=Form(id="multi_material",name="Stone + oak cap",parts=listOf(Part("copycats:copycat_block"),Part("copycats:copycat_layer",top=true,group="cap")),groups=listOf(MaterialGroup(),MaterialGroup("cap","Cap","frame",1)))
    @Test fun `foreground groups partition overlapping voxels without changing collision`() {
        val f=groupedForm();val r=Geometry.resolve(f.parts,f.groups)
        assertEquals(4096,r.solid.count());assertEquals(512,r.owned.getValue("cap").count());assertEquals(3584,r.owned.getValue("body").count())
        assertEquals("cap",r.owner(8,15,8));assertEquals("body",r.owner(8,0,8))
        assertEquals(4096,r.owned.values.sumOf { it.count() })
        assertEquals(2,Geometry.validate(f).parts.size)
        val back=Geometry.validate(f.copy(groups=f.groups.map { it.copy(priority=1-it.priority) }))
        assertEquals(1,back.parts.size);assertEquals("body",back.parts.single().group)
        assertEquals(256,Extrusion.cost(back.parts,back.groups))
        assertEquals(288,Extrusion.cost(f.parts,f.groups))
    }
    @Test fun `coincident duplicate groups pay and expose only the foreground`() {
        val f=groupedForm().copy(parts=listOf(Part("copycats:copycat_block"),Part("copycats:copycat_block",group="cap")))
        val saved=Geometry.validate(f)
        assertEquals(listOf("cap"),saved.parts.map { it.group });assertEquals(256,Extrusion.cost(saved.parts,saved.groups))
        assertEquals(setOf("minecraft:oak_planks"),Placement(parts=saved.parts,groups=saved.groups,surfaces=mapOf("body" to "minecraft:stone","cap" to "minecraft:oak_planks")).surfaceItems().keys)
    }
    @Test fun `material requirements and idempotence follow resolved ownership`() {
        val f=groupedForm();val p=Placement(parts=f.parts,groups=f.groups,surfaces=mapOf("body" to "minecraft:stone","cap" to "minecraft:oak_planks"))
        assertEquals(mapOf("minecraft:stone" to 1,"minecraft:oak_planks" to 1),p.surfaceItems())
        assertEquals(mapOf("minecraft:stone" to 1),p.copy(surfaces=emptyMap()).surfaceItems())
        assertNotEquals(p.visualKey(),p.copy(groups=p.groups.map { it.copy(priority=1-it.priority) }).visualKey())
        val renamed=p.copy(parts=p.parts.map { if(it.group=="cap") it.copy(group="top") else it },groups=p.groups.map { if(it.id=="cap") it.copy(id="top") else it },surfaces=mapOf("body" to "minecraft:stone","top" to "minecraft:oak_planks"))
        assertEquals(p.visualKey(),renamed.visualKey())
    }
    @Test fun `material group priority ties and invalid references are rejected`() {
        val f=groupedForm()
        assertFails { Geometry.validate(f.copy(groups=f.groups.map { it.copy(priority=0) })) }
        assertFails { Geometry.validate(f.copy(parts=f.parts+Part(group="missing"))) }
        assertFails { Geometry.validate(f.copy(groups=List(9) { MaterialGroup("g$it","Group","wall",it) })) }
    }
    @Test fun `module material roles survive the shared building generator`() {
        val f=groupedForm();val b=building(roof="flat").copy(floorForm="multi@1",ceilingForm="multi@1")
        val p=Generator.generate(b,forms+("multi@1" to f),modules)
        val plates=p.placements.filter { it.component in listOf("floor","ceiling") }
        assertTrue(plates.all { it.surfaces["cap"]==b.palette.getValue("frame") })
        assertEquals(9*7*3,plates.sumOf { it.surfaceItems()["minecraft:oak_planks"] ?: 0 })
        assertEquals(9*7*4,p.materials.getValue("minecraft:oak_planks"))
        val out=java.nio.file.Path.of("build/examples");java.nio.file.Files.createDirectories(out)
        java.nio.file.Files.writeString(out.resolve("multi-material-form.json"),de.teutonstudio.copycatarchitect.server.Json.encode(f))
    }

    @Test fun `two slab materials retain independent upper and lower faces`() {
        val groups=listOf(MaterialGroup(),MaterialGroup("top","Upper slab","frame",1))
        val p=Placement(parts=listOf(Part("copycats:copycat_slab"),Part("copycats:copycat_slab",top=true,group="top")),groups=groups,surfaces=mapOf("body" to "minecraft:stone","top" to "minecraft:oak_planks"))
        assertEquals(256,Extrusion.cost(p.parts,p.groups));assertEquals(4096,Geometry.solid(p.parts).count())
        assertEquals("body",Geometry.resolve(p.parts,p.groups).owner(8,0,8));assertEquals("top",Geometry.resolve(p.parts,p.groups).owner(8,15,8))
        assertEquals(2,p.surfaceItems().size)
    }

}
