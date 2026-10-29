package de.teutonstudio.copycatarchitect.domain

/** A selectable base keeps the real source item ID, including centered pane fractions. */
data class BaseShape(val source:String,val discCut:Int=0) {
    val id:String get() = source+when(discCut) { 1->"#half_disc";2->"#quarter_disc";else->"" }
    val name:String get() = when(discCut) { 1->"half_disc";2->"quarter_disc";else->Adapters.get(source).name }
    fun part(group:String="body")=Part(source=source,group=group,discCut=discCut)
}

object BaseShapes {
    const val DISC_SOURCE="copycats:copycat_flat_pane"
    val all:List<BaseShape> = Adapters.all.keys.flatMap { source ->
        if(source==DISC_SOURCE) (0..2).map { BaseShape(source,it) } else listOf(BaseShape(source))
    }
    fun get(id:String):BaseShape = all.firstOrNull { it.id==id } ?: error("Unsupported base shape: $id")
    fun find(source:String,discCut:Int=0):BaseShape = all.firstOrNull { it.source==source && it.discCut==discCut }
        ?: error("Unsupported base shape: $source/$discCut")
}

enum class RotationAxis { X,Y,Z,NORMAL }

/** Quarter-turns around block center; vectors use the same rotation without its translation. */
internal fun rotatePoint(point:P,axis:RotationAxis,quarterTurns:Int,vector:Boolean=false):P {
    require(axis!=RotationAxis.NORMAL) { "A point rotation needs a world axis" }
    val edge=if(vector) 0 else 15
    var p=point
    repeat(Math.floorMod(quarterTurns,4)) {
        p=when(axis) {
            RotationAxis.X->P(p.x,edge-p.z,p.y)
            RotationAxis.Y->P(edge-p.z,p.y,p.x)
            RotationAxis.Z->P(edge-p.y,p.x,p.z)
            RotationAxis.NORMAL->error("A point rotation needs a world axis")
        }
    }
    return p
}

/** Finds a legal source orientation instead of admitting rotations its native state cannot express. */
object PartRotations {
    private val basis=listOf(P(1,0,0),P(0,1,0),P(0,0,1))
    private data class Key(val part:Part,val axis:RotationAxis,val quarterTurns:Int)
    private data class Cached(val part:Part?)
    private val cache=java.util.concurrent.ConcurrentHashMap<Key,Cached>()
    fun normal(part:Part):P = part.oriented(P(0,1,0),true)

    fun rotate(part:Part,axis:RotationAxis,quarterTurns:Int):Part? {
        if(cache.size>2048) cache.clear()
        val key=Key(part,axis,Math.floorMod(quarterTurns,4))
        return cache.computeIfAbsent(key) { Cached(rotateUncached(it.part,it.axis,it.quarterTurns)) }.part
    }

    private fun rotateUncached(part:Part,axis:RotationAxis,quarterTurns:Int):Part? = runCatching {
        Solid.part(part)
        val q=Math.floorMod(quarterTurns,4)
        if(q==0) return@runCatching part
        val normal=normal(part)
        val worldAxis=if(axis!=RotationAxis.NORMAL) axis else when {
            normal.x!=0->RotationAxis.X
            normal.y!=0->RotationAxis.Y
            else->RotationAxis.Z
        }
        // Keep local clockwise spin consistent after mounting; legacy world-Y turn has the opposite angular sense to X/Z.
        val signedQ=if(axis==RotationAxis.NORMAL) q*(normal.x+normal.y+normal.z)*(if(worldAxis==RotationAxis.Y) 1 else -1) else q
        val offset=rotatePoint(part.offset,worldAxis,signedQ,true)
        val mask=if(part.cutMask.isEmpty()) "" else Solid.fromMask(part.cutMask).rotate(worldAxis,signedQ).mask()
        val targetBasis=basis.map { rotatePoint(part.oriented(it,true),worldAxis,signedQ,true) }
        val adapter=Adapters.get(part.source)
        val candidates=buildList {
            for(top in listOf(part.top,!part.top)) for(tilt in (if(adapter.tiltable) 0..3 else 0..0)) for(turn in 0..3) for(spin in 0..3) {
                add(part.copy(top=top,tilt=tilt,turn=turn,spin=spin,offset=offset,cutMask=mask))
            }
        }.sortedWith(compareBy<Part> {
            (if(it.top==part.top) 0 else 4)+distance(it.turn,part.turn)+distance(it.tilt,part.tilt)+distance(it.spin,part.spin)
        }.thenBy { it.top!=part.top }.thenBy { it.spin }.thenBy { it.tilt }.thenBy { it.turn })

        // Matching the frame preserves an invisible pane normal and makes later local spins predictable.
        val frame=candidates.firstOrNull { candidate -> basis.map { candidate.oriented(it,true) }==targetBasis }
        val result=frame ?: run {
            // Symmetric sources may have an equivalent native state with a different frame, e.g. upside-down stairs.
            val target=Solid.part(part.copy(offset=P(),cutMask="")).rotate(worldAxis,signedQ).key()
            candidates.firstOrNull { Solid.part(it.copy(offset=P(),cutMask="")).key()==target }
        } ?: return@runCatching null
        Solid.part(result)
        result
    }.getOrNull()

    private fun distance(a:Int,b:Int):Int = minOf(Math.floorMod(a-b,4),Math.floorMod(b-a,4))
}
