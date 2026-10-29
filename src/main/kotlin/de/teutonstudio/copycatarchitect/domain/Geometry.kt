package de.teutonstudio.copycatarchitect.domain

import java.util.BitSet
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class P(val x: Int = 0, val y: Int = 0, val z: Int = 0) {
    operator fun plus(p: P) = P(x+p.x,y+p.y,z+p.z)
    fun turn(q: Int): P = when (Math.floorMod(q,4)) { 1 -> P(-z,y,x); 2 -> P(-x,y,-z); 3 -> P(z,y,-x); else -> this }
}
data class Part(val source: String = "copycats:copycat_slice", val turn: Int = 0, val top: Boolean = false,
                val size: Int = 1, val offset: P = P(), val tilt: Int = 0, val group: String = "body", val cutMask: String = "", val recipeSource:String="",
                val spin:Int=0, val discCut:Int=0) {
    /** Source-local spin precedes mounting; turn retains its saved world-Y meaning. */
    internal fun oriented(point:P,vector:Boolean=false):P {
        val edge=if(vector) 0 else 15
        var p=point.copy(y=if(top) edge-point.y else point.y)
        repeat(spin) { p=P(edge-p.z,p.y,p.x) }
        repeat(tilt) { p=P(p.x,edge-p.z,p.y) }
        repeat(turn) { p=P(edge-p.z,p.y,p.x) }
        return p
    }
    fun sourceProperties():Map<String,String> {
        val horizontalTurn=Math.floorMod(turn+spin,4)
        val horizontal=listOf("south","west","north","east")[horizontalTurn]
        val normal=oriented(P(0,1,0),true)
        val facing=directionName(normal)
        val axis=if(normal.x!=0) "x" else if(normal.y!=0) "y" else "z"
        val opposite=mapOf("up" to "down","down" to "up","east" to "west","west" to "east","south" to "north","north" to "south")
        return when(source) {
            "copycats:copycat_block" -> emptyMap()
            "copycats:copycat_slab" -> mapOf("axis" to axis,"type" to (if(normal.x+normal.y+normal.z>0) "bottom" else "top"))
            "copycats:copycat_beam", "copycats:copycat_flat_pane" -> mapOf("axis" to axis)
            "copycats:copycat_byte" -> {
                val p=oriented(P(4,4,4))
                val selected=(if(p.y>=8) "top_" else "bottom_")+(if(p.z>=8) "south" else "north")+(if(p.x>=8) "east" else "west")
                listOf("top_northeast","top_northwest","top_southeast","top_southwest","bottom_northeast","bottom_northwest","bottom_southeast","bottom_southwest").associateWith { (it==selected).toString() }
            }
            "create:copycat_step" -> mapOf("facing" to horizontal,"half" to (if(top) "top" else "bottom"))
            "copycats:copycat_vertical_step" -> mapOf("facing" to horizontal)
            "copycats:copycat_half_panel" -> {
                val tangent=oriented(P(0,0,1),true)
                // Native OFFSET uses horizontal directions even for vertical offsets.
                val offsetDirection=if(tangent.y==0) tangent else if(normal.x!=0) P(tangent.y,0,0) else P(0,0,tangent.y)
                mapOf("facing" to opposite.getValue(facing),"offset" to directionName(offsetDirection))
            }
            "copycats:copycat_byte_panel" -> {
                val face=opposite.getValue(facing)
                val p=oriented(P(4,1,4))
                // Copycats+ labels quadrants in the local coordinate frame of the attached face.
                val h=when(face) { "north" -> 15-p.x;"east" -> 15-p.z;"west" -> p.z;else -> p.x }
                val v=when(face) { "down" -> p.z;"up" -> 15-p.z;else -> p.y }
                val selected=(if(v>=8) "top_" else "bottom_")+(if(h>=8) "left" else "right")
                mapOf("facing" to face)+listOf("bottom_left","bottom_right","top_left","top_right").associateWith { (it==selected).toString() }
            }
            "copycats:copycat_half_layer" -> {
                val positive=horizontalTurn==1 || horizontalTurn==2
                mapOf("axis" to (if(horizontalTurn%2==0) "z" else "x"),"half" to (if(top) "top" else "bottom"),"positive_layers" to (if(positive) size else 0).toString(),"negative_layers" to (if(positive) 0 else size).toString())
            }
            "copycats:copycat_vertical_half_layer" -> mapOf("facing" to horizontal,"positive_layers" to "0","negative_layers" to size.toString())
            "copycats:copycat_stacked_half_layer" -> mapOf("facing" to horizontal,"positive_layers" to (if(top) size else 0).toString(),"negative_layers" to (if(top) 0 else size).toString())
            "create:copycat_panel" -> mapOf("facing" to facing)
            "copycats:copycat_layer" -> mapOf("facing" to facing,"layers" to size.toString())
            "copycats:copycat_stairs" -> mapOf("facing" to horizontal,"half" to (if(top) "top" else "bottom"),"shape" to "straight")
            "copycats:copycat_vertical_slice" -> mapOf("facing" to horizontal,"layers" to size.toString())
            else -> mapOf("facing" to horizontal,"half" to (if(top) "top" else "bottom"),"layers" to size.toString())
        }
    }
}
internal fun directionName(direction:P):String = when(direction) {
    P(0,1,0)->"up";P(0,-1,0)->"down";P(1,0,0)->"east";P(-1,0,0)->"west";P(0,0,1)->"south";P(0,0,-1)->"north"
    else->error("Direction must be a unit axis")
}
data class MaterialGroup(val id:String="body",val name:String="Body",val role:String="surface",val priority:Int=0)
val DEFAULT_GROUPS:List<MaterialGroup> = listOf(MaterialGroup())
data class Form(val schema: Int = 3, val id: String = "", val version: Int = 1, val name: String = "Form",
                val parts: List<Part> = listOf(Part()), val groups:List<MaterialGroup> = DEFAULT_GROUPS, val graph:NodeGraph? = null)
data class Box(val x: Int, val y: Int, val z: Int, val maxX: Int, val maxY: Int, val maxZ: Int)
data class Adapter(val source: String, val name: String, val max: Int, val output: Int, val layered: Boolean = false, val tiltable:Boolean = false,
                   val boxes: (Int) -> List<Box>)
object Adapters {
    private fun box(x:Int,y:Int,z:Int,X:Int,Y:Int,Z:Int) = listOf(Box(x,y,z,X,Y,Z))
    val all = listOf(
        Adapter("copycats:copycat_block", "full",1,1) { box(0,0,0,16,16,16) },
        Adapter("copycats:copycat_slab", "slab",1,2,tiltable=true) { box(0,0,0,16,8,16) },
        Adapter("copycats:copycat_stairs", "stairs",1,1) { listOf(Box(0,0,0,16,8,16),Box(0,8,8,16,16,16)) },
        Adapter("create:copycat_panel", "panel",1,4,tiltable=true) { box(0,0,0,16,3,16) },
        Adapter("copycats:copycat_slice", "slice",8,16,true) { box(0,0,16-it*2,16,it*2,16) },
        Adapter("copycats:copycat_vertical_slice", "vertical_slice",8,16,true) { box(16-it*2,0,16-it*2,16,16,16) },
        Adapter("copycats:copycat_corner_slice", "corner_slice",8,16,true) { box(16-it*2,0,16-it*2,16,it*2,16) },
        Adapter("copycats:copycat_layer", "layer",8,8,true,true) { box(0,0,0,16,it*2,16) },
        Adapter("copycats:copycat_beam", "beam",1,4,tiltable=true) { box(4,0,4,12,16,12) },
        Adapter("copycats:copycat_byte", "byte",1,8,tiltable=true) { box(0,0,0,8,8,8) },
        Adapter("create:copycat_step", "step",1,4) { box(0,0,8,16,8,16) },
        Adapter("copycats:copycat_vertical_step", "vertical_step",1,4) { box(8,0,8,16,16,16) },
        Adapter("copycats:copycat_half_panel", "half_panel",1,8,tiltable=true) { box(0,0,8,16,3,16) },
        Adapter("copycats:copycat_flat_pane", "flat_pane",1,8,tiltable=true) { box(0,7,0,16,9,16) },
        Adapter("copycats:copycat_byte_panel", "byte_panel",1,16,tiltable=true) { box(0,0,0,8,3,8) },
        Adapter("copycats:copycat_half_layer", "half_layer",8,16,true) { box(0,0,0,16,it*2,8) },
        Adapter("copycats:copycat_vertical_half_layer", "vertical_half_layer",8,16,true) { box(0,0,16-it*2,8,16,16) },
        Adapter("copycats:copycat_stacked_half_layer", "stacked_half_layer",8,16,true) { box(0,0,16-it*2,16,8,16) }
    ).associateBy { it.source }
    fun get(id:String) = all[id] ?: error("Unsupported base shape: $id")
}
class Solid private constructor(private val bits: BitSet) {
    constructor():this(BitSet(4096))
    fun has(x:Int,y:Int,z:Int) = x in 0..15 && y in 0..15 && z in 0..15 && bits[x+16*z+256*y]
    fun count() = bits.cardinality()
    fun union(other:Solid) = Solid((bits.clone() as BitSet).apply { or(other.bits) })
    fun intersect(other:Solid) = Solid((bits.clone() as BitSet).apply { and(other.bits) })
    fun mask():String = Base64.getEncoder().encodeToString(bits.toByteArray())
    fun rotate(q:Int):Solid = rotate(RotationAxis.Y,q)
    fun rotate(axis:RotationAxis,q:Int):Solid {
        require(axis!=RotationAxis.NORMAL) { "A solid rotation needs a world axis" }
        val result=Solid()
        voxels { x,y,z -> val p=rotatePoint(P(x,y,z),axis,q);result.bits.set(p.x+16*p.z+256*p.y) }
        return result
    }
    fun minus(other:Solid) = Solid((bits.clone() as BitSet).apply { andNot(other.bits) })
    fun contains(other:Solid) = (other.bits.clone() as BitSet).apply { andNot(bits) }.isEmpty
    fun key() = bits.toLongArray().joinToString(",")
    fun boxes():List<Box> { // Greedy disjoint cuboids; exact render/collision source.
        val todo = bits.clone() as BitSet
        val out = mutableListOf<Box>()
        fun has(x:Int,y:Int,z:Int) = x<16 && y<16 && z<16 && todo[x+16*z+256*y]
        while (!todo.isEmpty) {
            val i=todo.nextSetBit(0); val x=i%16; val z=(i/16)%16; val y=i/256
            var X=x+1; while(X<16 && has(X,y,z)) X++
            var Z=z+1; while(Z<16 && (x until X).all { has(it,y,Z) }) Z++
            var Y=y+1; while(Y<16 && (x until X).all { a -> (z until Z).all { b -> has(a,Y,b) } }) Y++
            for(a in x until X) for(b in y until Y) for(c in z until Z) todo.clear(a+16*c+256*b)
            out+=Box(x,y,z,X,Y,Z)
        }
        return out
    }
    fun voxels(action:(Int,Int,Int)->Unit) { var i=bits.nextSetBit(0); while(i>=0) { action(i%16,i/256,(i/16)%16); i=bits.nextSetBit(i+1) } }
    companion object {
        fun fromMask(mask:String):Solid {
            require(mask.length in 1..684) { "Invalid cut mask" }
            val bytes=Base64.getDecoder().decode(mask)
            require(bytes.size<=512) { "Cut mask exceeds block" }
            return Solid(BitSet.valueOf(bytes))
        }
        fun part(p:Part):Solid {
            val a=Adapters.get(p.source)
            require(p.size in 1..a.max && p.turn in 0..3 && p.tilt in 0..3 && p.spin in 0..3) { "Invalid shape size or rotation" }
            require(p.discCut in 0..2 && (p.discCut==0 || p.source==BaseShapes.DISC_SOURCE)) { "Disc fractions require a centered Copycat pane" }
            require(listOf(p.offset.x,p.offset.y,p.offset.z).all { it in -15..15 }) { "Offset outside block" }
            require(p.tilt==0 || a.tiltable) { "This base shape does not support vertical rotation" }
            val result=Solid()
            val boxes=when(p.discCut) {
                1->listOf(Box(0,7,8,16,9,16))
                2->listOf(Box(0,7,0,8,9,8))
                else->a.boxes(p.size)
            }
            for(b in boxes) for(x0 in b.x until b.maxX) for(y0 in b.y until b.maxY) for(z0 in b.z until b.maxZ) {
                val v=p.oriented(P(x0,y0,z0))+p.offset
                require(v.x in 0..15 && v.y in 0..15 && v.z in 0..15) { "Part exceeds block boundary" }
                result.bits.set(v.x+16*v.z+256*v.y)
            }
            if(p.cutMask.isEmpty()) return result
            val clipped=fromMask(p.cutMask)
            require(clipped.count()>0 && result.contains(clipped)) { "Cut mask must be inside its source shape" }
            return clipped
        }
    }
}
object Geometry {
    private val cache=ConcurrentHashMap<List<Part>,Solid>()
    private data class ResolvedKey(val parts:List<Part>,val groups:List<MaterialGroup>)
    private val resolvedCache=ConcurrentHashMap<ResolvedKey,ResolvedGeometry>()
    fun validateGroups(groups:List<MaterialGroup>,parts:List<Part>) {
        require(groups.size in 1..8 && groups.map { it.id }.distinct().size==groups.size) { "A form needs 1–8 uniquely named material groups" }
        require(groups.map { it.priority }.distinct().size==groups.size) { "Material groups need distinct foreground priorities" }
        groups.forEach { require(it.id.matches(Regex("[a-zA-Z0-9_-]{1,36}")) && it.name.length in 1..32 && it.role.matches(Regex("[a-zA-Z0-9_-]{1,32}")) && it.priority in -32..32) { "Invalid material group" } }
        require(parts.all { p -> groups.any { it.id==p.group } }) { "Part refers to an unknown material group" }
    }
    fun normalized(parts:List<Part>,groups:List<MaterialGroup> = DEFAULT_GROUPS):List<Part> {
        require(parts.size in 1..32) { "A form needs 1–32 parts" };validateGroups(groups,parts)
        require(parts.all { it.recipeSource.isEmpty() || it.recipeSource.matches(Regex("[a-zA-Z0-9_-]{1,36}")) }) { "Invalid source receipt reference" }
        val keep=mutableSetOf<Int>();var foreground=Solid()
        // Foreground owns every overlapping voxel. Hidden parts are removed; a
        // material-changing overlay is meaningful even inside a full base block.
        for(g in groups.sortedByDescending { it.priority }) {
            val candidates=parts.indices.filter { parts[it].group==g.id }
            val clips=candidates.associateWith { Solid.part(parts[it]).minus(foreground) }
            val selected=mutableListOf<Int>()
            for(i in candidates) {
                val s=clips.getValue(i);if(s.count()==0) continue
                if(selected.fold(Solid()) { r,k -> r.union(clips.getValue(k)) }.contains(s)) continue
                selected.removeAll { s.contains(clips.getValue(it)) };selected+=i
            }
            var i=selected.lastIndex
            while(i>=0 && selected.size>1) {
                val rest=selected.filterIndexed { j,_ -> j!=i }.fold(Solid()) { r,k -> r.union(clips.getValue(k)) }
                if(rest.contains(clips.getValue(selected[i]))) selected.removeAt(i)
                i--
            }
            keep+=selected
            foreground=candidates.fold(foreground) { r,k -> r.union(Solid.part(parts[k])) }
        }
        return parts.filterIndexed { i,_ -> i in keep }
    }
    fun resolve(parts:List<Part>,groups:List<MaterialGroup> = DEFAULT_GROUPS):ResolvedGeometry {
        validateGroups(groups,parts)
        if(resolvedCache.size>2048) resolvedCache.clear()
        return resolvedCache.computeIfAbsent(ResolvedKey(parts.toList(),groups.toList())) {
            var occupied=Solid();val owned=linkedMapOf<String,Solid>()
            for(g in groups.sortedByDescending { it.priority }) {
                val whole=parts.filter { it.group==g.id }.fold(Solid()) { r,p -> r.union(Solid.part(p)) }
                val clipped=whole.minus(occupied)
                if(clipped.count()>0) owned[g.id]=clipped
                occupied=occupied.union(whole)
            }
            ResolvedGeometry(occupied,owned)
        }
    }
    fun solid(parts:List<Part>):Solid {
        if(cache.size>2048) cache.clear()
        return cache.computeIfAbsent(parts.toList()) { it.fold(Solid()) { s,p -> s.union(Solid.part(p)) } }
    }
    fun validate(f:Form):Form {
        require(f.schema in 1..3 && f.name.length in 1..64 && f.version>0) { "Invalid form schema/name/version" }
        if(f.graph!=null) return f.copy(schema=3,graph=f.graph.copy(schema=2),parts=GraphCompiler.compile(f.graph,f.groups))
        require(f.parts.all { it.cutMask.isEmpty() && it.recipeSource.isEmpty() }) { "Clipped parts require their editable node graph" }
        return f.copy(schema=3,parts=normalized(f.parts,f.groups))
    }
    /** Isolate the resolved ownership, rather than revealing hidden source overlap. View only. */
    fun previewParts(parts:List<Part>,groups:List<MaterialGroup>,group:String):List<Part> {
        if(group.isEmpty()) return parts
        require(groups.any { it.id==group }) { "Unknown preview material group" }
        val owned=resolve(parts,groups).owned[group] ?: return emptyList()
        return parts.filter { it.group==group }.mapNotNull { p ->
            val source=Solid.part(p);val visible=source.intersect(owned)
            if(visible.count()==0) null else if(visible.contains(source)) p else p.copy(cutMask=visible.mask())
        }
    }
    fun rotate(parts:List<Part>,q:Int) = parts.map { p -> p.copy(turn=Math.floorMod(p.turn+q,4),offset=p.offset.turn(q),cutMask=if(p.cutMask.isEmpty()) "" else Solid.fromMask(p.cutMask).rotate(q).mask()) }
}
class ResolvedGeometry(val solid:Solid,val owned:Map<String,Solid>) {
    private val owners=Array<String?>(4096) { null }.apply { owned.forEach { (id,s) -> s.voxels { x,y,z -> this[x+16*z+256*y]=id } } }
    fun owner(x:Int,y:Int,z:Int):String? = if(x in 0..15 && y in 0..15 && z in 0..15) owners[x+16*z+256*y] else null
    fun materialSolids(base:String,surfaces:Map<String,String>):Map<String,Solid> {
        val result=linkedMapOf<String,Solid>()
        owned.forEach { (id,s) -> val material=surfaces[id] ?: base;result[material]=(result[material] ?: Solid()).union(s) }
        return result
    }
    fun visualKey(base:String,surfaces:Map<String,String>)=materialSolids(base,surfaces).toSortedMap().entries.joinToString(";") { it.key+":"+it.value.key() }
}
// Integer extrusion units: 1 zinc ingot = 256; never round fractional ingots away.
// Costs mirror the pinned source cutting output, not visible volume.
object Extrusion {
    var outputs:Map<String,Int> = Adapters.all.mapValues { it.value.output }
    fun cost(parts:List<Part>,groups:List<MaterialGroup> = DEFAULT_GROUPS,recipes:Map<String,Int> = outputs):Int {
        val normalized=Geometry.normalized(parts,groups)
        // A shared source split into material regions is still one paid source recipe.
        return normalized.withIndex().distinctBy { (index,p) ->
            if(p.recipeSource.isEmpty()) index else p.recipeSource to p.copy(group="",cutMask="",recipeSource="")
        }.sumOf { (_,p) ->
            val a=Adapters.get(p.source)
            val output=recipes[p.source] ?: error("Missing extrusion recipe")
            require(output>0) { "Invalid extrusion recipe output" }
            val divisor=output.toLong()*(1 shl p.discCut)
            require(p.discCut==0 || 256L%divisor==0L) { "Disc fraction requires an integral extrusion cost" }
            (256L/divisor).toInt() * if(a.layered) p.size else 1
        }
    }
}
