package de.teutonstudio.copycatarchitect.server

import com.mojang.serialization.MapCodec
import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import net.minecraft.core.*
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.*
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.*
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.*
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.entity.*
import net.minecraft.world.level.block.state.*
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.*
import net.minecraft.network.chat.Component
import java.util.concurrent.ConcurrentHashMap
import com.simibubi.create.content.equipment.wrench.IWrenchable
import com.simibubi.create.content.equipment.wrench.WrenchItem

data class Receipt(val units:Int=0,val material:String="",val paidMaterial:Boolean=false,val operation:String="",val surfaces:Map<String,Int> = emptyMap())
class CompositeEntity(pos:BlockPos,state:BlockState):BlockEntity(Architect.BE.get(),pos,state) {
    var parts:List<Part> = listOf(Part("copycats:copycat_block"))
    var material:String=""
    var groups:List<MaterialGroup> = DEFAULT_GROUPS
    var surfaces:Map<String,String> = emptyMap()
    var receipt=Receipt()
    var owner=""
    fun install(p:Placement,r:Receipt,owner:String) { parts=Geometry.normalized(p.parts,p.groups);material=p.material;groups=p.groups;surfaces=p.surfaces;receipt=r;this.owner=owner;setChanged();level?.sendBlockUpdated(blockPos,blockState,blockState,3) }
    override fun saveAdditional(tag:CompoundTag,registries:HolderLookup.Provider) { super.saveAdditional(tag,registries);tag.putString("parts",Json.encode(parts));tag.putString("material",material);tag.putString("groups",Json.encode(groups));tag.putString("surfaces",Json.encode(surfaces));tag.putString("receipt",Json.encode(receipt));tag.putString("owner",owner) }
    override fun loadAdditional(tag:CompoundTag,registries:HolderLookup.Provider) {
        super.loadAdditional(tag,registries)
        groups=if(tag.contains("groups")) Json.decode<Array<MaterialGroup>>(tag.getString("groups")).toList() else DEFAULT_GROUPS
        surfaces=if(tag.contains("surfaces")) Json.materials(tag.getString("surfaces")) else emptyMap()
        if(tag.contains("parts")) parts=Geometry.normalized(Json.decode<Array<Part>>(tag.getString("parts")).toList(),groups)
        material=if(tag.contains("material")) tag.getString("material") else "minecraft:stone";owner=tag.getString("owner")
        if(tag.contains("receipt")) receipt=Json.decode(tag.getString("receipt"))
    }
    fun hitGroup(hit:BlockHitResult):MaterialGroup? {
        val local=hit.location.subtract(net.minecraft.world.phys.Vec3.atLowerCornerOf(blockPos)).subtract(net.minecraft.world.phys.Vec3.atLowerCornerOf(hit.direction.normal).scale(0.00001))
        val id=Geometry.resolve(parts,groups).owner(kotlin.math.floor(local.x*16).toInt().coerceIn(0,15),kotlin.math.floor(local.y*16).toInt().coerceIn(0,15),kotlin.math.floor(local.z*16).toInt().coerceIn(0,15))
        return groups.firstOrNull { it.id==id }
    }
    fun coating(group:String):String=surfaces[group] ?: material
    private fun visibleCoatings():Map<String,String> = Geometry.resolve(parts,groups).owned.keys.associateWith(::coating)
    private fun paidCoatings():Map<String,Int> = if(receipt.surfaces.isEmpty() && receipt.paidMaterial && receipt.material.isNotEmpty()) mapOf(receipt.material to 1) else receipt.surfaces
    fun coatingCost(group:String,id:String):Int {
        require(group in visibleCoatings() && id.isNotEmpty()) { "Unknown coating group" }
        require(coating(group).isEmpty() || coating(group)==id) { "Remove the existing coating with a wrench first" }
        return if(id in visibleCoatings().values) 0 else 1
    }
    /** Return whether anything changed; the caller consumes exactly coatingCost before this edit. */
    fun applyCoating(group:String,id:String,paid:Boolean):Boolean {
        CopycatMaterials.state(id)
        val count=coatingCost(group,id)
        if(coating(group)==id) return false
        val receipts=paidCoatings().toMutableMap()
        if(paid && count>0) receipts[id]=(receipts[id] ?: 0)+count
        surfaces=surfaces+(group to id)
        receipt=receipt.copy(paidMaterial=false,surfaces=receipts)
        changedCoatings();return true
    }
    /** A shared material sample is refunded only after its last visible group is stripped. */
    fun removeCoating(group:String):ItemStack {
        require(group in visibleCoatings()) { "Unknown coating group" }
        val old=coating(group)
        if(old.isEmpty()) return ItemStack.EMPTY
        val paid=paidCoatings().toMutableMap()
        surfaces=surfaces+(group to "")
        val count=if(old !in visibleCoatings().values) paid.remove(old) ?: 0 else 0
        receipt=receipt.copy(paidMaterial=false,surfaces=paid)
        changedCoatings()
        return if(count>0) ItemStack(Economy.item(old),count) else ItemStack.EMPTY
    }
    private fun changedCoatings() { setChanged();level?.sendBlockUpdated(blockPos,blockState,blockState,3) }
    override fun getUpdateTag(registries:HolderLookup.Provider)=saveWithoutMetadata(registries)
    override fun getUpdatePacket()=ClientboundBlockEntityDataPacket.create(this)
    fun shape():VoxelShape { if(shapes.size>1024) shapes.clear();return shapes.computeIfAbsent(parts) {
        Geometry.solid(parts).boxes().fold(Shapes.empty()) { s,b -> Shapes.or(s,Block.box(b.x.toDouble(),b.y.toDouble(),b.z.toDouble(),b.maxX.toDouble(),b.maxY.toDouble(),b.maxZ.toDouble())) }.optimize()
    } }
    companion object { private val shapes=ConcurrentHashMap<List<Part>,VoxelShape>() }
}
// Every placement stores its geometry in the block entity, so BlockState must not cache a shared full cube.
class CompositeBlock(properties:Properties):BaseEntityBlock(properties.dynamicShape()),IWrenchable {
    override fun codec():MapCodec<out BaseEntityBlock> = simpleCodec(::CompositeBlock)
    override fun newBlockEntity(pos:BlockPos,state:BlockState)=CompositeEntity(pos,state)
    override fun getRenderShape(state:BlockState)=RenderShape.ENTITYBLOCK_ANIMATED
    override fun getShape(state:BlockState,level:BlockGetter,pos:BlockPos,context:CollisionContext):VoxelShape = (level.getBlockEntity(pos) as? CompositeEntity)?.shape() ?: Shapes.block()
    override fun getCollisionShape(state:BlockState,level:BlockGetter,pos:BlockPos,context:CollisionContext)=getShape(state,level,pos,context)
    override fun useItemOn(stack:ItemStack,state:BlockState,level:Level,pos:BlockPos,player:Player,hand:InteractionHand,hit:BlockHitResult):ItemInteractionResult {
        // Let the gun reach Item.useOn instead of consuming the click as a group inspection.
        if(stack.`is`(Architect.GUN.get()) || stack.item is CompositeFormItem) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
        if(stack.item is WrenchItem) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
        val material=stack.item as? BlockItem ?: return super.useItemOn(stack,state,level,pos,player,hand,hit)
        val id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(material).toString()
        if(runCatching { CopycatMaterials.state(id) }.isFailure) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
        val be=level.getBlockEntity(pos) as? CompositeEntity ?: return ItemInteractionResult.FAIL
        val group=be.hitGroup(hit) ?: return ItemInteractionResult.FAIL
        if(be.coating(group.id).isNotEmpty()) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
        if(be.owner.isNotEmpty() && be.owner!=player.uuid.toString() && !player.isCreative) return ItemInteractionResult.FAIL
        if(!level.mayInteract(player,pos) || !player.mayUseItemAt(pos,hit.direction,stack)) return ItemInteractionResult.FAIL
        if(!level.isClientSide) {
            val count=be.coatingCost(group.id,id)
            if(stack.count<count) return ItemInteractionResult.FAIL
            if(be.applyCoating(group.id,id,!player.isCreative) && !player.isCreative) stack.shrink(count)
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide)
    }
    override fun onWrenched(state:BlockState,context:UseOnContext)=stripCoating(context)
    override fun onSneakWrenched(state:BlockState,context:UseOnContext)=stripCoating(context)
    private fun stripCoating(context:UseOnContext):InteractionResult {
        val player=context.player ?: return InteractionResult.PASS
        val level=context.level;val pos=context.clickedPos
        if(!level.mayInteract(player,pos) || !player.mayUseItemAt(pos,context.clickedFace,context.itemInHand)) return InteractionResult.FAIL
        val be=level.getBlockEntity(pos) as? CompositeEntity ?: return InteractionResult.PASS
        if(be.owner.isNotEmpty() && be.owner!=player.uuid.toString() && !player.isCreative) return InteractionResult.FAIL
        val hit=BlockHitResult(context.clickLocation,context.clickedFace,pos,false)
        val group=be.hitGroup(hit) ?: return InteractionResult.PASS
        if(!level.isClientSide) {
            val returned=be.removeCoating(group.id)
            if(!returned.isEmpty && !player.inventory.add(returned)) player.drop(returned,false)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
    override fun useWithoutItem(state:BlockState,level:Level,pos:BlockPos,player:Player,hit:BlockHitResult):InteractionResult {
        if(!level.isClientSide) (level.getBlockEntity(pos) as? CompositeEntity)?.let { be ->
            be.hitGroup(hit)?.let { group -> player.displayClientMessage(Component.translatable("gui.copycat_architect.hit_group",group.name,Economy.item(be.coating(group.id).ifEmpty { "create:zinc_block" }).description),true) }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
    override fun onRemove(state:BlockState,level:Level,pos:BlockPos,next:BlockState,moving:Boolean) {
        if(!state.`is`(next.block) && !level.isClientSide) (level.getBlockEntity(pos) as? CompositeEntity)?.let { be ->
            if(be.receipt.units>0 || be.receipt.paidMaterial || be.receipt.surfaces.isNotEmpty()) {
                if(be.receipt.units>0) popResource(level,pos,Economy.cartridge(be.receipt.units))
                be.receipt.surfaces.forEach { (id,count) -> popResource(level,pos,ItemStack(Economy.item(id),count)) }
                if(be.receipt.paidMaterial && be.receipt.surfaces.isEmpty()) popResource(level,pos,ItemStack(Economy.item(be.receipt.material)))
                be.receipt=Receipt()
            }
        }
        super.onRemove(state,level,pos,next,moving)
    }
}
class TableBlock(properties:Properties):Block(properties) {
    override fun useWithoutItem(state:BlockState,level:Level,pos:BlockPos,player:Player,hit:BlockHitResult):InteractionResult {
        if(player is net.minecraft.server.level.ServerPlayer) Network.open(player,"building")
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
}
class GunItem(properties:Properties):Item(properties) {
    override fun use(level:Level,player:Player,hand:InteractionHand):InteractionResultHolder<ItemStack> {
        if(player is net.minecraft.server.level.ServerPlayer) Network.open(player,"shape")
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide)
    }
    override fun useOn(ctx:UseOnContext):InteractionResult {
        val player=ctx.player ?: return InteractionResult.FAIL
        if(player.isShiftKeyDown) { if(player is net.minecraft.server.level.ServerPlayer) Network.open(player,"shape");return InteractionResult.SUCCESS }
        if(ctx.level.isClientSide) return InteractionResult.SUCCESS
        try {
            val tag=ctx.itemInHand.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            val lib=ArchitectData.get(ctx.level as ServerLevel).library(player.uuid)
            val f=lib.forms[tag.getString("form").ifEmpty { "inner@1" }] ?: error("Choose a saved form")
            val q=tag.getInt("turn");val material=tag.getString("material").ifEmpty { "minecraft:stone" }
            val p=Placement(P(),Geometry.rotate(f.parts,q),material,groups=f.groups,surfaces=tag.getString("surfaces").ifEmpty { "{}" }.let { Json.materials(it) })
            val pos=if(ctx.level.getBlockState(ctx.clickedPos).canBeReplaced()) ctx.clickedPos else ctx.clickedPos.relative(ctx.clickedFace)
            PlacementService.place(ctx.level as ServerLevel,pos,p,player.inventory,player,"hand",player.isCreative)
        } catch(e:Exception) { player.displayClientMessage(Component.literal(e.message ?: "Placement failed"),true);return InteractionResult.FAIL }
        return InteractionResult.SUCCESS
    }
}
