package de.teutonstudio.copycatarchitect.server

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler

/** Four individually persisted cartridges, arranged like candles on one supporting block. */
class CartridgeStackEntity(pos:BlockPos,state:BlockState):BlockEntity(CartridgeRegistry.ENTITY.get(),pos,state) {
    val cartridges:NonNullList<ItemStack> = NonNullList.withSize(4,ItemStack.EMPTY)
    fun append(stack:ItemStack) {
        require(Economy.isCartridge(stack))
        val slot=cartridges.indexOfFirst { it.isEmpty };require(slot>=0) { "Cartridge stack is full" }
        cartridges[slot]=stack.copyWithCount(1);sync()
    }
    fun removeLast():ItemStack {
        val slot=cartridges.indexOfLast { !it.isEmpty };if(slot<0) return ItemStack.EMPTY
        val result=cartridges[slot];cartridges[slot]=ItemStack.EMPTY;sync()
        return result
    }
    fun sync() {
        setChanged();val world=level ?: return
        world.sendBlockUpdated(blockPos,blockState,blockState,3)
    }
    override fun saveAdditional(tag:CompoundTag,registries:HolderLookup.Provider) {
        super.saveAdditional(tag,registries);ContainerHelper.saveAllItems(tag,cartridges,registries)
    }
    override fun loadAdditional(tag:CompoundTag,registries:HolderLookup.Provider) {
        super.loadAdditional(tag,registries);cartridges.clear();ContainerHelper.loadAllItems(tag,cartridges,registries)
    }
    override fun getUpdateTag(registries:HolderLookup.Provider)=saveWithoutMetadata(registries)
    override fun getUpdatePacket()=ClientboundBlockEntityDataPacket.create(this)
    val fluidHandler=object:IFluidHandler {
        override fun getTanks()=4
        override fun getTankCapacity(tank:Int)=if(tank in cartridges.indices && !cartridges[tank].isEmpty) CartridgeFluidHandler(cartridges[tank]).getTankCapacity(0) else 0
        override fun isFluidValid(tank:Int,stack:FluidStack)=tank in cartridges.indices && !cartridges[tank].isEmpty && !Economy.isCreativeCartridge(cartridges[tank]) && stack.`is`(CartridgeRegistry.ZINC.get())
        override fun getFluidInTank(tank:Int)=if(tank in cartridges.indices && !cartridges[tank].isEmpty) CartridgeFluidHandler(cartridges[tank]).getFluidInTank(0) else FluidStack.EMPTY
        override fun fill(resource:FluidStack,action:IFluidHandler.FluidAction):Int {
            var remaining=resource.amount
            for(slot in cartridges.indices) if(remaining>0 && isFluidValid(slot,resource)) {
                val handler=CartridgeFluidHandler(cartridges[slot]);val part=resource.copy();part.amount=remaining
                remaining-=handler.fill(part,action)
                if(action.execute()) cartridges[slot]=handler.getContainer()
            }
            if(action.execute() && remaining<resource.amount) sync()
            return resource.amount-remaining
        }
        override fun drain(resource:FluidStack,action:IFluidHandler.FluidAction)=if(resource.`is`(CartridgeRegistry.ZINC.get())) drain(resource.amount,action) else FluidStack.EMPTY
        override fun drain(maxDrain:Int,action:IFluidHandler.FluidAction):FluidStack {
            var remaining=maxDrain
            for(slot in cartridges.indices) if(remaining>0 && !cartridges[slot].isEmpty) {
                val handler=CartridgeFluidHandler(cartridges[slot]);remaining-=handler.drain(remaining,action).amount
                if(action.execute()) cartridges[slot]=handler.getContainer()
            }
            if(remaining>=maxDrain) return FluidStack.EMPTY
            if(action.execute()) sync()
            return FluidStack(CartridgeRegistry.ZINC.get(),maxDrain-remaining)
        }
    }
}

class CartridgeStackBlock(properties:Properties):BaseEntityBlock(properties) {
    init { registerDefaultState(stateDefinition.any().setValue(COUNT,1)) }
    override fun codec():MapCodec<out BaseEntityBlock> = simpleCodec(::CartridgeStackBlock)
    override fun newBlockEntity(pos:BlockPos,state:BlockState)=CartridgeStackEntity(pos,state)
    override fun createBlockStateDefinition(builder:StateDefinition.Builder<Block,BlockState>) { builder.add(COUNT) }
    override fun getRenderShape(state:BlockState)=RenderShape.MODEL
    override fun getShape(state:BlockState,level:BlockGetter,pos:BlockPos,context:CollisionContext):VoxelShape=SHAPES[state.getValue(COUNT)-1]
    override fun canSurvive(state:BlockState,level:LevelReader,pos:BlockPos)=Block.canSupportCenter(level,pos.below(),Direction.UP)
    override fun updateShape(state:BlockState,direction:Direction,neighbor:BlockState,level:LevelAccessor,pos:BlockPos,neighborPos:BlockPos):BlockState =
        if(direction==Direction.DOWN && !state.canSurvive(level,pos)) Blocks.AIR.defaultBlockState() else super.updateShape(state,direction,neighbor,level,pos,neighborPos)
    override fun canBeReplaced(state:BlockState,context:BlockPlaceContext)=
        !context.isSecondaryUseActive && Economy.isCartridge(context.itemInHand) && state.getValue(COUNT)<4
    override fun getStateForPlacement(context:BlockPlaceContext):BlockState {
        val state=context.level.getBlockState(context.clickedPos)
        return if(state.`is`(this)) state.setValue(COUNT,state.getValue(COUNT)+1) else defaultBlockState()
    }
    override fun setPlacedBy(level:Level,pos:BlockPos,state:BlockState,placer:LivingEntity?,stack:ItemStack) {
        super.setPlacedBy(level,pos,state,placer,stack)
        if(!level.isClientSide) (level.getBlockEntity(pos) as? CartridgeStackEntity)?.append(stack)
    }
    override fun useItemOn(stack:ItemStack,state:BlockState,level:Level,pos:BlockPos,player:Player,hand:InteractionHand,hit:BlockHitResult)=
        if(Economy.isCartridge(stack)) ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION else ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
    override fun useWithoutItem(state:BlockState,level:Level,pos:BlockPos,player:Player,hit:BlockHitResult):InteractionResult {
        if(!level.isClientSide) (level.getBlockEntity(pos) as? CartridgeStackEntity)?.let { entity ->
            if(player.isShiftKeyDown && player.mainHandItem.isEmpty) {
                if(!level.mayInteract(player,pos) || !player.mayUseItemAt(pos,hit.direction,player.mainHandItem)) return InteractionResult.FAIL
                val stack=entity.removeLast()
                if(!stack.isEmpty && !player.addItem(stack)) player.drop(stack,false)
                val count=entity.cartridges.count { !it.isEmpty }
                if(count==0) level.removeBlock(pos,false) else level.setBlock(pos,state.setValue(COUNT,count),3)
            } else {
                val units=entity.cartridges.filterNot { it.isEmpty }.sumOf { if(Economy.isCreativeCartridge(it)) 0L else Economy.units(it).toLong() }
                player.displayClientMessage(Component.translatable(if(entity.cartridges.any(Economy::isCreativeCartridge)) "item.copycat_architect.cartridge.infinite" else "item.copycat_architect.cartridge.units",units),true)
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
    override fun getCloneItemStack(level:LevelReader,pos:BlockPos,state:BlockState):ItemStack =
        (level.getBlockEntity(pos) as? CartridgeStackEntity)?.cartridges?.firstOrNull { !it.isEmpty }?.copy() ?: Economy.cartridge(0)
    override fun onRemove(state:BlockState,level:Level,pos:BlockPos,next:BlockState,moving:Boolean) {
        if(!state.`is`(next.block) && !level.isClientSide && !level.restoringBlockSnapshots) (level.getBlockEntity(pos) as? CartridgeStackEntity)?.let { entity ->
            entity.cartridges.forEach { if(!it.isEmpty) popResource(level,pos,it.copy()) };entity.cartridges.clear()
        }
        super.onRemove(state,level,pos,next,moving)
    }
    companion object {
        val COUNT=IntegerProperty.create("cartridges",1,4)
        private val positions=listOf(3 to 3,10 to 3,3 to 10,10 to 10)
        private val SHAPES=(1..4).map { count -> positions.take(count).fold(Shapes.empty()) { shape,(x,z) -> Shapes.or(shape,Block.box(x.toDouble(),0.0,z.toDouble(),x+3.0,9.0,z+3.0)) } }
    }
}
