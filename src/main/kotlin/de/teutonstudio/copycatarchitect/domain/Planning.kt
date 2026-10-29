package de.teutonstudio.copycatarchitect.domain

data class Cell(val pos:P=P(), val form:String="full@1", val block:String="", val role:String="wall", val turn:Int=0,
                val empty:Boolean=false, val flexible:Boolean=false)
data class Module(val schema:Int=1,val id:String="",val version:Int=1,val name:String="Module",val width:Int=3,
                  val height:Int=4,val depth:Int=1,val cells:List<Cell> = emptyList(),val minWidth:Int=width,val maxWidth:Int=width) {
    val key get() = "$id@$version"
    fun validate():Module {
        require(schema==1 && width in 1..32 && height in 1..16 && depth in 1..4 && name.length in 1..64)
        require(minWidth in 1..width && maxWidth in width..32)
        require(cells.size<=2048 && cells.map { it.pos }.distinct().size==cells.size) { "Duplicate module cells" }
        for(c in cells) require(c.pos.x in 0 until width && c.pos.y in 0 until height && c.pos.z in 0 until depth && c.turn in 0..3)
        if(minWidth!=maxWidth) require(cells.all { it.flexible || it.pos.x==0 }) { "Variable modules need explicit flexible cells" }
        return this
    }
}
enum class Remainder { EXACT, START, END, CENTER, ADJUST }
data class Pattern(val sequence:List<String> = listOf("window@1"),val interlude:String="pier@1",val mode:Int=1,
                   val start:String="",val end:String="",val remainder:Remainder=Remainder.END,
                   val filler:String="pier@1",val partial:Boolean=false)
data class Span(val key:String,val start:Int,val width:Int)
data class Distribution(val spans:List<Span>,val remainder:Int)
object Patterns {
    fun distribute(length:Int,p:Pattern,modules:Map<String,Module>):Distribution {
        require(length>=0 && p.mode in 0..2 && p.sequence.isNotEmpty() && p.sequence.size<=16)
        fun m(k:String)=modules[k] ?: error("Missing module: $k")
        val out=mutableListOf<Span>(); var cursor=0
        fun add(k:String,w:Int=m(k).width) { out+=Span(k,cursor,w);cursor+=w }
        if(p.start.isNotEmpty()) add(p.start)
        val endWidth=if(p.end.isEmpty()) 0 else m(p.end).width
        val W=length-cursor-endWidth
        require(W>=0) { "Fixed ends are wider than wall" }
        val begin=cursor
        if(p.mode==1) {
            val s=m(p.sequence[0]).width; val i=if(p.interlude.isEmpty()) 0 else if(p.remainder==Remainder.ADJUST) m(p.interlude).minWidth else m(p.interlude).width
            val n=(W+i)/(s+i)
            if(n>0) repeat(n) { j -> if(j>0 && i>0) add(p.interlude,i); add(p.sequence[0]) }
        } else {
            val seq=if(p.mode==0) listOf(p.sequence[0]) else p.sequence
            val cycle=seq.sumOf { m(it).width }
            repeat(W/cycle) { seq.forEach { add(it) } }
            if(p.partial) for(k in seq) { if(cursor-begin+m(k).width>W) break; add(k) }
        }
        var rest=W-(cursor-begin)
        val original=rest
        if(rest>0) when(p.remainder) {
            Remainder.EXACT -> error("Wall has $rest blocks of unused length")
            Remainder.ADJUST -> {
                val gaps=out.indices.filter { out[it].key==p.interlude && p.mode==1 }
                require(gaps.isNotEmpty()) { "No inner interlude can absorb remaining length" }
                val module=m(p.interlude)
                require(module.maxWidth>module.width) { "Interlude is not explicitly variable" }
                var shift=0
                for(j in out.indices) {
                    val old=out[j];var extra=0
                    if(j in gaps) { extra=minOf(rest,module.maxWidth-old.width);rest-=extra }
                    out[j]=old.copy(start=old.start+shift,width=old.width+extra);shift+=extra
                }
                require(rest==0) { "Variable interludes cannot absorb remaining $rest blocks" }; cursor+=shift
            }
            else -> {
                require(p.filler.isNotEmpty() && m(p.filler).width==1) { "Remainder needs a one-block filler module" }
                val left=when(p.remainder) { Remainder.START->rest;Remainder.CENTER->rest/2;else->0 }
                val index=out.indexOfFirst { it.start>=begin }.let { if(it<0) out.size else it }
                for(j in index until out.size) out[j]=out[j].copy(start=out[j].start+left)
                repeat(left) { out+=Span(p.filler,begin+it,1) }
                repeat(rest-left) { out+=Span(p.filler,cursor+left+it,1) };cursor+=rest;rest=0
            }
        }
        if(p.end.isNotEmpty()) add(p.end)
        return Distribution(out.sortedBy { it.start },original)
    }
}
data class Rect(val x:Int=0,val z:Int=0,val width:Int=11,val length:Int=9) {
    fun has(x:Int,z:Int) = x in this.x until this.x+width && z in this.z until this.z+length
}
data class Building(val schema:Int=1,val id:String="",val version:Int=1,val name:String="L House",val wings:List<Rect> = listOf(Rect(),Rect(0,9,5,6)),
    val floors:Int=2,val roomHeight:Int=4,val patterns:List<Pattern> = List(4) { Pattern() },
    val floorForm:String="full@1",val ceilingForm:String="full@1",val floorThickness:Int=1,val ceilingThickness:Int=1,
    val holes:List<P> = emptyList(),val roof:String="gable",val ridge:Int=0,val overhang:Int=1,val roofThickness:Int=1,
    val trimForm:String="inner@1",val palette:Map<String,String> = mapOf("wall" to "minecraft:stone_bricks","frame" to "minecraft:oak_planks","glass" to "minecraft:glass","roof" to "minecraft:deepslate_tiles","gable" to "minecraft:stone_bricks","trim" to "minecraft:oak_planks"),val graph:BuildingGraph?=null)
data class Placement(val pos:P=P(),val parts:List<Part> = emptyList(),val material:String="minecraft:stone",val block:String="",val component:String="wall",val groups:List<MaterialGroup> = DEFAULT_GROUPS,val surfaces:Map<String,String> = emptyMap()) {
    fun surfaceItems():Map<String,Int> = if(block.isNotEmpty()) mapOf(block to 1) else Geometry.resolve(parts,groups).materialSolids(material,surfaces).keys.filter { it.isNotEmpty() }.associateWith { 1 }
    fun visualKey():String = if(block.isNotEmpty()) "block:$block" else Geometry.resolve(parts,groups).visualKey(material,surfaces)
}
data class Plan(val placements:List<Placement>,val clear:List<P>,val warnings:List<String>,val raw:Int,val materials:Map<String,Int>)
class Resolver {
    private val cells=linkedMapOf<P,Placement>();private val air=linkedSetOf<P>()
    fun clear(p:P) { require(!cells.containsKey(p)) { "Opening overlaps solid at $p" };air+=p }
    fun add(p:Placement,merge:Boolean=false) {
        require(p.pos !in air) { "Required opening obstructed at ${p.pos}" }
        val old=cells[p.pos]
        if(old==null) { cells[p.pos]=p;return }
        if(old.visualKey()==p.visualKey()) return
        if(merge && old.block.isEmpty() && p.block.isEmpty() && old.material==p.material && old.groups==p.groups && old.surfaces==p.surfaces) {
            cells[p.pos]=old.copy(parts=Geometry.normalized(old.parts+p.parts,old.groups));return
        }
        error("Incompatible ${old.component}/${p.component} at ${p.pos}")
    }
    fun plan(warnings:List<String>):Plan {
        val list=cells.values.sortedWith(compareBy({it.pos.y},{it.pos.z},{it.pos.x}))
        require(air.size<=200000) { "Plan exceeds 200000 air requirements" }
        require(list.size<=32768) { "Plan exceeds 32768 positions" }
        val materials=linkedMapOf<String,Int>()
        for(p in list) p.surfaceItems().forEach { (id,count) -> materials[id]=(materials[id] ?: 0)+count }
        return Plan(list,air.toList(),warnings,list.filter { it.block.isEmpty() }.sumOf { Extrusion.cost(it.parts,it.groups) },materials)
    }
}
object Generator {
    fun generate(b:Building,forms:Map<String,Form>,modules:Map<String,Module>):Plan {
        if(b.graph!=null) return BuildingGraphCompiler.compile(b,forms,modules).plan
        require(b.name.length in 1..64 && b.holes.size<=4096 && b.palette.size<=16)
        require(b.schema==1 && b.floors in 1..8 && b.roomHeight in 2..12 && b.wings.size in 1..2)
        require(b.ridge in 0..1 && b.overhang in 0..2 && b.roofThickness in 1..2 && b.roof in listOf("flat","gable"))
        require(b.floorThickness in 1..2 && b.ceilingThickness==b.floorThickness) { "Shared plates require equal thickness" }
        require(b.floorForm==b.ceilingForm) { "Shared floor/ceiling must use the same form" }
        require(b.patterns.size==4)
        b.wings.forEach { require(it.width in 3..32 && it.length in 3..32 && it.x in 0..32 && it.z in 0..32) }
        modules.values.forEach { it.validate() }
        if(b.wings.size==2) {
            val a=b.wings[0];val c=b.wings[1]
            require(a.x==0 && a.z==0 && c.x==0 && c.z==a.length && c.width<a.width) { "L wings must form an aligned north/south L" }
            require(b.ridge==0) { "L gable requires ridges along Z" }
        }
        fun footprint(x:Int,z:Int)=b.wings.any { it.has(x,z) }
        val footprint=buildSet { b.wings.forEach { r -> for(x in r.x until r.x+r.width) for(z in r.z until r.z+r.length) add(P(x,0,z)) } }
        val resolver=Resolver();val warnings=mutableListOf<String>()
        fun shape(key:String)=forms[key]?.let { Geometry.validate(it).parts } ?: error("Missing form: $key")
        fun put(p:P,key:String,role:String,type:String,q:Int=0,merge:Boolean=false) {
            val f=Geometry.validate(forms[key] ?: error("Missing form: $key"))
            val surfaces=f.groups.associate { g -> val materialRole=if(g.role=="surface") role else g.role;g.id to (b.palette[materialRole] ?: error("Missing material role: $materialRole")) }
            resolver.add(Placement(p,Geometry.rotate(f.parts,q),b.palette[role] ?: error("Missing material role: $role"),component=type,groups=f.groups,surfaces=surfaces),merge)
        }
        val story=b.roomHeight+b.floorThickness
        // One shared structural plate at each story boundary.
        for(f in 0..b.floors) for(t in 0 until b.floorThickness) for(p in footprint) {
            val pos=P(p.x,f*story+t,p.z)
            if(pos !in b.holes) put(pos,b.floorForm,"wall",if(f==0) "floor" else "ceiling") else resolver.clear(pos)
        }
        val dirs=listOf(P(0,0,-1),P(1,0,0),P(0,0,1),P(-1,0,0))
        val exterior=footprint.filter { p -> dirs.any { !footprint(p.x+it.x,p.z+it.z) } }.toSet()
        // Corners (including concave joints) are reserved globally before pattern distribution.
        val corners=footprint.filter { p -> dirs.count { d -> !footprint(p.x+d.x,p.z+d.z) }>=2 ||
            (dirs.indices.any { i -> val d=dirs[i];val e=dirs[(i+1)%4]; footprint(p.x+d.x,p.z+d.z) && footprint(p.x+e.x,p.z+e.z) && !footprint(p.x+d.x+e.x,p.z+d.z+e.z) }) }.toSet()
        val boundary=exterior+corners
        val claimed=mutableSetOf<P>()
        for(f in 0 until b.floors) {
            val base=f*story+b.floorThickness
            for(c in corners) for(v in 0 until b.roomHeight) put(P(c.x,base+v,c.z),"full@1","wall","corner")
            for(q in 0..3) {
                val normal=dirs[q];val along=P(-normal.z,0,normal.x)
                val candidates=boundary.filter { it !in corners && !footprint(it.x+normal.x,it.z+normal.z) }.toSet()
                for(first in candidates.sortedWith(compareBy({it.z},{it.x}))) {
                    if(first in claimed) continue
                    var start=first
                    while(start+P(-along.x,0,-along.z) in candidates) start+=P(-along.x,0,-along.z)
                    val run=mutableListOf<P>();var pos=start
                    while(pos in candidates && pos !in claimed) { run+=pos;claimed+=pos;pos+=along }
                    if(run.isEmpty()) continue
                    val distribution=Patterns.distribute(run.size,b.patterns[q],modules)
                    if(distribution.remainder>0) warnings+="Wall $q: ${distribution.remainder} remainder cells filled"
                    for(span in distribution.spans) {
                        val m=modules[span.key] ?: error("Missing module")
                        require(m.height==b.roomHeight && m.depth==1) { "Wall module must match room height and depth 1" }
                        for(cell in m.cells) {
                            val xs=if(span.width!=m.width && cell.flexible) (0 until span.width).toList() else listOf(cell.pos.x)
                            for(u in xs) {
                                val local=run[span.start+u]; val target=P(local.x,base+cell.pos.y,local.z)
                                if(cell.empty || cell.block=="minecraft:air") resolver.clear(target)
                                else if(cell.block.isEmpty()) put(target,cell.form,cell.role,"wall",q+cell.turn)
                                else resolver.add(Placement(target,material="",block=cell.block,component="wall"))
                            }
                        }
                    }
                }
            }
            claimed.clear()
        }
        val eave=b.floors*story+b.floorThickness // Top ceiling remains below roof.
        if(b.roof=="flat") {
            for(p in footprint) for(t in 0 until b.roofThickness) put(P(p.x,eave+t,p.z),b.ceilingForm,"roof","roof")
        } else {
            // Union the roof height field before creating the skin: no overlapping independent roofs.
            val heights=mutableMapOf<P,Int>()
            for(r in b.wings) {
                for(x in r.x-b.overhang until r.x+r.width+b.overhang) for(z in r.z-b.overhang until r.z+r.length+b.overhang) {
                    // Internal wing overhang is clipped at the shared footprint boundary.
                    if(b.wings.size==2 && z>=r.z+r.length && footprint(x,z)) continue
                    if(b.wings.size==2 && z<r.z && footprint(x,z)) continue
                    val across=if(b.ridge==0) x-r.x else z-r.z
                    val width=if(b.ridge==0) r.width else r.length
                    val h=minOf(across,width-1-across)
                    val p=P(x,0,z);heights[p]=maxOf(heights[p] ?: Int.MIN_VALUE,h)
                }
            }
            for((p,h) in heights) {
                val r=b.wings.filter { if(b.ridge==0) p.z in it.z-b.overhang until it.z+it.length+b.overhang else p.x in it.x-b.overhang until it.x+it.width+b.overhang }.maxByOrNull { if(b.ridge==0) minOf(p.x-it.x,it.x+it.width-1-p.x) else minOf(p.z-it.z,it.z+it.length-1-p.z) }!!
                val a=if(b.ridge==0) p.x-r.x else p.z-r.z;val w=if(b.ridge==0) r.width else r.length
                val ridge=a==w/2 && w%2==1
                val rotation=if(b.ridge==0) (if(a<w/2) 3 else 1) else (if(a<w/2) 0 else 2)
                for(t in 0 until b.roofThickness) resolver.add(Placement(P(p.x,eave+b.overhang+h+t,p.z),listOf(Part(if(ridge) "copycats:copycat_slab" else "copycats:copycat_stairs",rotation)),b.palette.getValue("roof"),component="roof"))
                // Seal the exposed vertical joint of the union height field between L wings.
                if(p !in boundary && footprint(p.x,p.z)) for(d in dirs) {
                    val neighbor=heights[p+d] ?: continue
                    if(footprint(p.x+d.x,p.z+d.z) && neighbor<h-1) for(y in eave+b.overhang+neighbor+1 until eave+b.overhang+h) {
                        resolver.add(Placement(P(p.x,y,p.z),shape("full@1"),b.palette.getValue("roof"),component="roof"),true)
                    }
                }
                // Close exterior gables and the step at unequal wing widths with solid wall material.
                if(p in boundary) for(y in eave until eave+b.overhang+h) put(P(p.x,y,p.z),"full@1","gable","gable")
            }
        }
        // Trim is mounted on the outside of boundary cells and cannot close openings.
        if(b.trimForm.isNotEmpty()) for(p in boundary) for(q in 0..3) {
            val d=dirs[q];if(footprint(p.x+d.x,p.z+d.z)) continue
            for(f in 1..b.floors) put(P(p.x+d.x,f*story-1,p.z+d.z),b.trimForm,"trim","trim",q,true)
        }
        return resolver.plan(warnings.distinct())
    }
}
