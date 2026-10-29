package de.teutonstudio.copycatarchitect.server

import de.teutonstudio.copycatarchitect.Architect
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
import net.neoforged.neoforge.fluids.BaseFlowingFluid
import net.neoforged.neoforge.fluids.FluidType
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries

/** Kept separate so the legacy cartridge's registry ID and recipe never change. */
object CartridgeRegistry {
    private val blocks=DeferredRegister.createBlocks(Architect.ID)
    private val items=DeferredRegister.createItems(Architect.ID)
    private val entities=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,Architect.ID)
    private val fluidTypes=DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES,Architect.ID)
    private val fluids=DeferredRegister.create(Registries.FLUID,Architect.ID)
    val BLOCK=blocks.register("cartridge_stack") { -> CartridgeStackBlock(BlockBehaviour.Properties.of().strength(0.5f).sound(SoundType.METAL).noOcclusion()) }
    val ENTITY=entities.register("cartridge_stack") { -> BlockEntityType.Builder.of(::CartridgeStackEntity,BLOCK.get()).build(null) }
    val EMPTY=items.register("empty_cartridge") { -> CartridgeItem(Item.Properties().stacksTo(1),CartridgeKind.EMPTY) }
    val CREATIVE=items.register("creative_cartridge") { -> CartridgeItem(Item.Properties().stacksTo(1),CartridgeKind.CREATIVE) }
    val ZINC_TYPE=fluidTypes.register("molten_zinc") { -> FluidType(FluidType.Properties.create().density(7000).viscosity(3000).temperature(700).lightLevel(8)) }
    val ZINC=fluids.register("molten_zinc") { -> BaseFlowingFluid.Source(fluidProperties()) }
    val FLOWING_ZINC=fluids.register("flowing_molten_zinc") { -> BaseFlowingFluid.Flowing(fluidProperties()) }
    val ZINC_BLOCK=blocks.register("molten_zinc") { -> LiquidBlock(ZINC.get(),BlockBehaviour.Properties.of().liquid().noCollission().strength(100f).noLootTable().lightLevel { 8 }) }
    val ZINC_BUCKET=items.register("molten_zinc_bucket") { -> BucketItem(ZINC.get(),Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)) }
    private fun fluidProperties():BaseFlowingFluid.Properties=BaseFlowingFluid.Properties({ ZINC_TYPE.get() },{ ZINC.get() },{ FLOWING_ZINC.get() })
        .bucket { ZINC_BUCKET.get() }.block { ZINC_BLOCK.get() }.slopeFindDistance(2).levelDecreasePerBlock(2).tickRate(30)

    fun register(bus:IEventBus) {
        blocks.register(bus);items.register(bus);entities.register(bus);fluidTypes.register(bus);fluids.register(bus)
        bus.addListener(::capabilities)
    }
    private fun capabilities(event:RegisterCapabilitiesEvent) {
        event.registerItem(Capabilities.FluidHandler.ITEM,{ stack,_ -> CartridgeFluidHandler(stack) },Architect.CARTRIDGE.get(),EMPTY.get())
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK,ENTITY.get()) { entity,_ -> entity.fluidHandler }
    }
}
