package de.teutonstudio.copycatarchitect.server

import dan200.computercraft.api.turtle.ITurtleAccess
import de.teutonstudio.copycatarchitect.Architect
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.neoforged.neoforge.common.util.FakePlayerFactory

/** Filtered physical transfer from the chest/barrel directly below the dock. */
object Resupply {
    fun load(t:ITurtleAccess,level:ServerLevel,j:Job):Map<String,Int> {
        require(PlacementService.local(t.position)==j.dock) { "Resupply requires the configured dock" }
        val source=level.getBlockEntity(t.position.below())
        require(source is ChestBlockEntity || source is BarrelBlockEntity) { "Put a chest or barrel directly below the dock" }
        val actor=FakePlayerFactory.get(level,t.owningPlayer ?: error("Missing turtle owner"))
        require(level.mayInteract(actor,t.position.below())) { "Material station access denied" }
        val chest=source as Container;val inventory=t.inventory;val moved=linkedMapOf<String,Int>()
        val requested=j.plan.materials.keys.toMutableList()
        requested+="create:zinc_ingot"
        val fuel=listOf("minecraft:coal","minecraft:charcoal","minecraft:coal_block")
        fuel.firstOrNull { id -> (0 until chest.containerSize).any { chest.getItem(it).`is`(Economy.item(id)) } }?.let { requested+=it }
        for(id in requested.distinct()) {
            val item=Economy.item(id)
            var need=(64-(0 until inventory.containerSize).sumOf { if(inventory.getItem(it).`is`(item)) inventory.getItem(it).count else 0 }).coerceAtLeast(0)
            for(slot in 0 until chest.containerSize) {
                if(need==0) break
                val stack=chest.getItem(slot)
                if(!stack.`is`(item)) continue
                val amount=transfer(chest,slot,inventory,minOf(need,stack.count))
                moved[id]=(moved[id] ?: 0)+amount;need-=amount
            }
        }
        val hasRaw=(0 until inventory.containerSize).any { Economy.units(inventory.getItem(it))>0 }
        if(!hasRaw) for(slot in 0 until chest.containerSize) {
            val s=chest.getItem(slot)
            if(Economy.isCartridge(s) && Economy.units(s)>0) {
                val n=transfer(chest,slot,inventory,1)
                if(n>0) { moved["copycat_architect:cartridge"]=n;break }
            }
        }
        return moved.filterValues { it>0 }
    }
    private fun transfer(source:Container,slot:Int,target:Container,requested:Int):Int {
        val stack=source.getItem(slot)
        if(stack.isEmpty || !source.canTakeItem(target,slot,stack)) return 0
        var left=requested
        for(destination in 0 until target.containerSize) {
            if(left==0) break
            val current=target.getItem(destination)
            if(!target.canPlaceItem(destination,stack)) continue
            if(!current.isEmpty && !ItemStack.isSameItemSameComponents(current,stack)) continue
            val room=minOf(target.maxStackSize,stack.maxStackSize)-(if(current.isEmpty) 0 else current.count)
            val n=minOf(left,room,stack.count).coerceAtLeast(0)
            if(n==0) continue
            if(current.isEmpty) target.setItem(destination,stack.copyWithCount(n)) else current.grow(n)
            stack.shrink(n);left-=n
        }
        val count=requested-left
        if(count>0) { source.setChanged();target.setChanged() }
        return count
    }
}
