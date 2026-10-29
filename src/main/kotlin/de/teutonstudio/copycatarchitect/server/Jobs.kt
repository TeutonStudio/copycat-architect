package de.teutonstudio.copycatarchitect.server

import de.teutonstudio.copycatarchitect.domain.*
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.BlockPos
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

object Jobs {
    private data class Check(val level:ServerLevel,val job:Job,val positions:Iterator<P>,val targets:Map<P,Placement>,val indices:Map<P,Int>)
    private val checks=mutableMapOf<String,Check>()
    fun bounds(j:Job):Pair<P,P> {
        val a=j.plan.placements
        require(a.isNotEmpty()) { "Empty building plan" }
        return P(a.minOf { it.pos.x }-1,j.origin.y,a.minOf { it.pos.z }-1) to P(a.maxOf { it.pos.x }+1,a.maxOf { it.pos.y }+2,a.maxOf { it.pos.z }+1)
    }
    fun validateDock(j:Job) {
        val (a,b)=bounds(j)
        require(j.dock.x<a.x && j.dock.z<a.z && j.dock.x>=a.x-8 && j.dock.z>=a.z-8 && j.dock.y==j.origin.y+1) { "Dock must be 2–8 blocks northwest of the building at floor + 1" }
        require((b.x-j.dock.x+1).toLong()*(b.z-j.dock.z+1)*(b.y-j.dock.y+1)<=200000) { "Work volume exceeds 200000 cells" }
    }
    fun beginCheck(level:ServerLevel,j:Job) {
        require(j.dimension==level.dimension().location().toString()) { "Wrong dimension" }
        validateDock(j);val (_,b)=bounds(j)
        val positions=sequence {
            for(p in j.plan.placements) yield(p.pos)
            for(p in j.plan.clear) yield(p)
            for(y in j.dock.y..b.y) for(z in j.dock.z..b.z) for(x in j.dock.x..b.x) yield(P(x,y,z))
        }
        checks[j.id]=Check(level,j,positions.iterator(),j.plan.placements.associateBy { it.pos },j.plan.placements.withIndex().associate { it.value.pos to it.index });j.state="checking";j.message="Checking loaded work space";ArchitectData.get(level).setDirty()
    }
    fun tick(event:ServerTickEvent.Post) {
        val iterator=checks.values.iterator()
        while(iterator.hasNext()) {
            val c=iterator.next();val j=c.job
            if(j.state!="checking") { iterator.remove();continue }
            try {
                repeat(512) {
                    if(c.positions.hasNext()) {
                        val p=c.positions.next();val pos=PlacementService.world(p)
                        require(c.level.hasChunkAt(pos) && c.level.isInWorldBounds(pos)) { "Work space chunk unloaded: $p" }
                        val target=c.targets[p]
                        if(target!=null && c.level.getBlockState(pos).isAir) j.index=minOf(j.index,c.indices.getValue(p))
                        val dockTurtle=c.level.getBlockEntity(pos) as? dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity
                        val ownTurtle=dockTurtle!=null && dockTurtle.access.owningPlayer?.id.toString()==j.owner && ((j.computer<0 && p==j.dock) || dockTurtle.computerID==j.computer)
                        require(ownTurtle || c.level.getBlockState(pos).isAir || (target!=null && PlacementService.matches(c.level,pos,target))) { "Work space blocked: $p" }
                    }
                }
                if(!c.positions.hasNext()) { j.state="running";j.message="Waiting for assigned turtle at dock";iterator.remove();ArchitectData.get(c.level).setDirty() }
            } catch(e:Exception) { j.state="blocked";j.message=e.message ?: "Work space blocked";iterator.remove();ArchitectData.get(c.level).setDirty() }
        }
    }
}
