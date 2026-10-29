package de.teutonstudio.copycatarchitect.server

import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem

/** Create's spout and item drain use this standard NeoForge capability directly. One mB is one raw unit. */
class CartridgeFluidHandler(private var stack:ItemStack):IFluidHandlerItem {
    override fun getContainer()=stack
    override fun getTanks()=1
    override fun getTankCapacity(tank:Int)=maxOf(256,Economy.units(stack).coerceIn(0,1_000_000))
    override fun isFluidValid(tank:Int,fluid:FluidStack)=tank==0 && fluid.`is`(CartridgeRegistry.ZINC.get())
    override fun getFluidInTank(tank:Int):FluidStack {
        val units=Economy.units(stack).coerceIn(0,1_000_000)
        return if(tank!=0 || units==0 || Economy.isCreativeCartridge(stack)) FluidStack.EMPTY else FluidStack(CartridgeRegistry.ZINC.get(),units)
    }
    override fun fill(resource:FluidStack,action:IFluidHandler.FluidAction):Int {
        if(stack.count!=1 || Economy.isCreativeCartridge(stack) || !isFluidValid(0,resource)) return 0
        val units=Economy.units(stack);if(units !in 0..1_000_000) return 0
        val accepted=minOf(resource.amount,(256-units).coerceAtLeast(0))
        if(accepted>0 && action.execute()) stack=Economy.withUnits(stack,units+accepted)
        return accepted
    }
    override fun drain(resource:FluidStack,action:IFluidHandler.FluidAction):FluidStack =
        if(isFluidValid(0,resource)) drain(resource.amount,action) else FluidStack.EMPTY
    override fun drain(maxDrain:Int,action:IFluidHandler.FluidAction):FluidStack {
        if(stack.count!=1 || Economy.isCreativeCartridge(stack)) return FluidStack.EMPTY
        val units=Economy.units(stack);if(units !in 0..1_000_000) return FluidStack.EMPTY
        val amount=minOf(maxDrain,units)
        if(amount<=0) return FluidStack.EMPTY
        if(action.execute()) stack=Economy.withUnits(stack,units-amount)
        return FluidStack(CartridgeRegistry.ZINC.get(),amount)
    }
}
