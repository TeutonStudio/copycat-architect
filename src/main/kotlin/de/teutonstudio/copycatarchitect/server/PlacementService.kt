package de.teutonstudio.copycatarchitect.server

import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.*
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.common.CommonHooks
import net.neoforged.neoforge.common.util.BlockSnapshot

object Economy {
    val palette=listOf("minecraft:stone","minecraft:stone_bricks","minecraft:cobblestone","minecraft:oak_planks","minecraft:oak_log","minecraft:glass","minecraft:deepslate_tiles","minecraft:bricks","minecraft:white_concrete","minecraft:spruce_planks")
    fun item(id:String):Item {
        val key=ResourceLocation.tryParse(id) ?: error("Invalid material ID")
        require(BuiltInRegistries.ITEM.containsKey(key)) { "Unknown material $id" }
        return BuiltInRegistries.ITEM.get(key)
    }
    fun state(id:String):BlockState {
        return CopycatMaterials.state(id)
    }
    fun nativeState(id:String):BlockState {
        require(id in palette || id in BuildingGraphCompiler.CARPETS) { "Unsupported building block: $id" }
        return (item(id) as? BlockItem)?.block?.defaultBlockState() ?: error("Building item must be a block")
    }
    fun isCartridge(s:ItemStack)=s.item is CartridgeItem
    fun isCreativeCartridge(s:ItemStack)=(s.item as? CartridgeItem)?.kind==CartridgeKind.CREATIVE
    fun units(s:ItemStack):Int = when((s.item as? CartridgeItem)?.kind) {
        CartridgeKind.CREATIVE->Int.MAX_VALUE
        CartridgeKind.EMPTY->0
        CartridgeKind.FULL->s.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getInt("units") ?: 256
        else->0
    }
    fun cartridge(n:Int):ItemStack {
        require(n in 0..1_000_000) { "Invalid cartridge units" }
        return ItemStack(if(n==0) CartridgeRegistry.EMPTY.get() else Architect.CARTRIDGE.get()).also {
            it.set(DataComponents.CUSTOM_DATA,CustomData.of(CompoundTag().apply { putInt("units",n) }))
        }
    }
    /** Keep names and unrelated item data when converting between the empty and filled item IDs. */
    fun withUnits(stack:ItemStack,n:Int):ItemStack {
        require(isCartridge(stack) && !isCreativeCartridge(stack) && n in 0..1_000_000) { "Invalid cartridge" }
        val result=ItemStack(if(n==0) CartridgeRegistry.EMPTY.get() else Architect.CARTRIDGE.get(),stack.count)
        result.applyComponents(stack.componentsPatch)
        val data=stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
        data.putInt("units",n);result.set(DataComponents.CUSTOM_DATA,CustomData.of(data))
        return result
    }
    data class Debit(val inventory:Container,val result:List<ItemStack>,val paidRaw:Int=0) {
        fun apply() { result.forEachIndexed { i,s -> inventory.setItem(i,s) };inventory.setChanged() }
    }
    fun prepare(inv:Container,raw:Int,material:String,materialCount:Int):Debit = prepare(inv,raw,mapOf(material to materialCount))
    fun prepare(inv:Container,raw:Int,surfaces:Map<String,Int>):Debit {
        require(raw>=0 && surfaces.size<=8 && surfaces.values.all { it in 1..8 }) { "Invalid material requirements" }
        val stacks=(0 until inv.containerSize).map { inv.getItem(it).copy() }.toMutableList()
        var needRaw=raw
        for((material,count) in surfaces) {
            var needMat=count;val mat=item(material)
            for(s in stacks) if(needMat>0 && s.`is`(mat)) { val take=minOf(needMat,s.count);s.shrink(take);needMat-=take }
            require(needMat==0) { "Missing surface material: $material" }
        }
        if(stacks.any(::isCreativeCartridge)) return Debit(inv,stacks,0)
        for(i in stacks.indices) if(needRaw>0 && isCartridge(stacks[i])) {
            val s=stacks[i]
            val available=units(s); require(available in 0..1_000_000) { "Invalid cartridge" }
            val take=minOf(needRaw,available);stacks[i]=withUnits(s,available-take);needRaw-=take
        }
        // Zinc ingots can be loaded into an empty cartridge in the same inventory.
        val zinc=item("create:zinc_ingot")
        for(s in stacks) if(needRaw>0 && s.`is`(zinc)) {
            val take=minOf(s.count,(needRaw+255)/256);val provided=take*256
            val rest=(provided-needRaw).coerceAtLeast(0)
            if(rest>0) {
                val empty=stacks.firstOrNull { it.isEmpty && it!==s } ?: if(s.count==take) s else null
                require(empty!=null) { "No inventory slot for remaining extrusion units" }
                s.shrink(take)
                if(empty===s) { s.count=1; /* handled by replacement below */ }
                val index=stacks.indexOfFirst { it===empty }
                val mutable=stacks.toMutableList();mutable[index]=cartridge(rest)
                needRaw=(needRaw-provided).coerceAtLeast(0)
                require(needRaw==0);return Debit(inv,mutable,raw)
            } else { s.shrink(take);needRaw-=provided }
        }
        require(needRaw<=0) { "Missing extrusion material: $needRaw units" }
        return Debit(inv,stacks,raw)
    }
}
object PlacementService {
    fun matches(level:ServerLevel,pos:BlockPos,p:Placement):Boolean {
        if(p.block.isNotEmpty()) return level.getBlockState(pos)==Economy.nativeState(p.block)
        val be=level.getBlockEntity(pos) as? CompositeEntity ?: return false
        return Geometry.resolve(be.parts,be.groups).visualKey(be.material,be.surfaces)==p.visualKey()
    }
    fun place(level:ServerLevel,pos:BlockPos,p:Placement,inventory:Container,actor:Player,operation:String,creative:Boolean=false,rawCost:Int=if(p.block.isEmpty()) Extrusion.cost(p.parts,p.groups) else 0,replaceable:Block?=null):Boolean {
        require(level.hasChunkAt(pos) && level.isInWorldBounds(pos)) { "Target chunk is not loaded" }
        if(matches(level,pos,p)) return false // Idempotent before charging.
        require(level.getBlockState(pos).isAir || replaceable!=null && level.getBlockState(pos).`is`(replaceable)) { "Target blocked at $pos" }
        require(level.mayInteract(actor,pos) && actor.mayUseItemAt(pos,net.minecraft.core.Direction.UP,ItemStack(Architect.GUN.get()))) { "Placement denied" }
        require(level.getEntities(actor,AABB(pos)).none { it.isAlive && !it.isSpectator }) { "Target occupied by an entity" }
        if(p.block.isEmpty()) { Geometry.normalized(p.parts,p.groups);require(p.surfaces.size<=8 && p.surfaces.keys.all { id -> p.groups.any { it.id==id } }) { "Unknown material group assignment" };p.surfaceItems().keys.forEach { Economy.state(it) } } else Economy.nativeState(p.block)
        val debit=if(creative) null else Economy.prepare(inventory,rawCost,p.surfaceItems())
        val snapshot=BlockSnapshot.create(level.dimension(),level,pos)
        val state=if(p.block.isEmpty()) Architect.COMPOSITE.get().defaultBlockState() else Economy.nativeState(p.block)
        require(state.canSurvive(level,pos)) { "Building block needs valid support at $pos" }
        try {
            // Protection hooks may reject placement or throw; either outcome restores the full device.
            require(level.setBlock(pos,state,3)) { "World rejected placement" }
            check(!net.neoforged.neoforge.event.EventHooks.onBlockPlace(actor,snapshot,net.minecraft.core.Direction.UP)) { "Placement denied by protection" }
            if(p.block.isEmpty()) {
                val be=level.getBlockEntity(pos) as? CompositeEntity ?: error("Composite unavailable")
                be.install(p,Receipt(debit?.paidRaw ?: 0,p.material,false,operation,if(creative) emptyMap() else p.surfaceItems()),actor.uuid.toString())
            }
            debit?.apply()
            return true
        } catch(e:Exception) {
            snapshot.restore()
            throw e
        }
    }
    fun world(p:P)=BlockPos(p.x,p.y,p.z)
    fun local(p:BlockPos)=P(p.x,p.y,p.z)
}
