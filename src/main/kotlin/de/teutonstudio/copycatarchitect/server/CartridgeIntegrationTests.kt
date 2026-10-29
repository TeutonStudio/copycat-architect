package de.teutonstudio.copycatarchitect.server

import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.Part
import de.teutonstudio.copycatarchitect.domain.Placement
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Consumer

@GameTestHolder(Architect.ID)
@PrefixGameTestTemplate(false)
object CartridgeIntegrationTests {
    @JvmStatic @GameTest(template="empty") fun creativeCartridgeDebitAndReceipt(h:GameTestHelper) {
        val legacy=ItemStack(Architect.CARTRIDGE.get())
        h.assertTrue(Economy.units(legacy)==256,"Existing cartridges without custom data must retain one zinc ingot")
        legacy.set(DataComponents.CUSTOM_NAME,Component.literal("Existing cartridge"))
        val inventory=SimpleContainer(3);inventory.setItem(0,Economy.withUnits(legacy,16));inventory.setItem(1,ItemStack(Items.STONE,2))
        Economy.prepare(inventory,16,"minecraft:stone",1).apply()
        h.assertTrue(inventory.getItem(0).`is`(CartridgeRegistry.EMPTY.get()) && Economy.units(inventory.getItem(0))==0,"Exhausted cartridges become the empty item")
        h.assertTrue(inventory.getItem(0).get(DataComponents.CUSTOM_NAME)==legacy.get(DataComponents.CUSTOM_NAME),"Empty conversion preserves item components")
        inventory.setItem(0,Economy.cartridge(256));inventory.setItem(2,ItemStack(CartridgeRegistry.CREATIVE.get()))
        val before=inventory.getItem(2).save(h.level.registryAccess()).toString()
        val debit=Economy.prepare(inventory,1_000_000,"minecraft:stone",1)
        h.assertTrue(debit.paidRaw==0 && Economy.units(inventory.getItem(0))==256,"Creative material is free and debit preparation is atomic")
        debit.apply()
        h.assertTrue(Economy.units(inventory.getItem(0))==256 && inventory.getItem(1).isEmpty,"Creative cartridges preserve ordinary raw units while still charging surfaces")
        h.assertTrue(inventory.getItem(2).save(h.level.registryAccess()).toString()==before,"Creative cartridge components and item count remain unchanged")
        h.assertTrue(runCatching { Economy.prepare(inventory,256,"minecraft:stone",1) }.isFailure,"Creative raw material must not supply missing surface items")
        val actor=FakePlayerFactory.get(h.level,GameProfile(UUID.randomUUID(),"CreativeCartridge"))
        val pos=h.absolutePos(BlockPos(1,1,1));actor.setPos(pos.x+4.0,pos.y.toDouble(),pos.z+4.0)
        h.level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());inventory.setItem(1,ItemStack(Items.STONE))
        PlacementService.place(h.level,pos,Placement(parts=listOf(Part("copycats:copycat_block"))),inventory,actor,"creative-cartridge")
        val entity=h.level.getBlockEntity(pos) as CompositeEntity
        h.assertTrue(entity.receipt.units==0 && entity.receipt.surfaces==mapOf("minecraft:stone" to 1),"Creative raw cartridges must not produce refundable zinc receipts")
        h.level.destroyBlock(pos,true,actor)
        val drops=h.level.getEntitiesOfClass(ItemEntity::class.java,AABB(pos).inflate(1.0))
        h.assertTrue(drops.none { Economy.isCartridge(it.item) } && drops.any { it.item.`is`(Items.STONE) },"Breaking a creatively funded form only refunds its paid surface")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun createSpoutFillsAndDrainPreservesExactUnits(h:GameTestHelper) {
        val integration=Class.forName("com.simibubi.create.content.fluids.transfer.GenericItemFilling")
        val canFill=integration.getMethod("canItemBeFilled",Level::class.java,ItemStack::class.java)
        val amount=integration.getMethod("getRequiredAmountForItem",Level::class.java,ItemStack::class.java,FluidStack::class.java)
        val fill=integration.getMethod("fillItem",Level::class.java,Int::class.javaPrimitiveType,ItemStack::class.java,FluidStack::class.java)
        var stack=Economy.cartridge(0)
        stack.set(DataComponents.CUSTOM_NAME,Component.literal("Refillable"))
        h.assertTrue(canFill.invoke(null,h.level,stack)==true,"Create must recognize the empty cartridge as a fillable fluid container")
        val metal=FluidStack(CartridgeRegistry.ZINC.get(),100)
        h.assertTrue(amount.invoke(null,h.level,stack,metal)==100,"Create must simulate exact partial filling without changing the cartridge")
        h.assertTrue(Economy.units(stack)==0,"Filling simulation must not change units")
        stack=fill.invoke(null,h.level,100,stack,metal) as ItemStack
        h.assertTrue(Economy.units(stack)==100 && metal.isEmpty,"Create filling must transfer every supplied mB exactly once")
        val refill=FluidStack(CartridgeRegistry.ZINC.get(),256)
        h.assertTrue(amount.invoke(null,h.level,stack,refill)==156,"Create must only request the remaining cartridge capacity")
        stack=fill.invoke(null,h.level,156,stack,refill) as ItemStack
        h.assertTrue(Economy.units(stack)==256 && refill.amount==100 && canFill.invoke(null,h.level,stack)==false,"A full cartridge holds exactly one ingot without discarding the remaining fluid")
        val handler=checkNotNull(stack.getCapability(Capabilities.FluidHandler.ITEM))
        h.assertTrue(handler.fill(FluidStack(net.minecraft.world.level.material.Fluids.WATER,100),FluidAction.EXECUTE)==0,"Cartridges reject unrelated liquids")
        h.assertTrue(handler.drain(64,FluidAction.SIMULATE).amount==64 && Economy.units(handler.getContainer())==256,"Drain simulation is atomic")
        h.assertTrue(handler.drain(64,FluidAction.EXECUTE).amount==64 && Economy.units(handler.getContainer())==192,"Item drains preserve exact partial remainders")
        handler.drain(1000,FluidAction.EXECUTE)
        h.assertTrue(handler.getContainer().`is`(CartridgeRegistry.EMPTY.get()) && Economy.units(handler.getContainer())==0,"Draining produces a reusable empty cartridge")
        h.assertTrue(handler.getContainer().get(DataComponents.CUSTOM_NAME)?.string=="Refillable","Filling and draining preserve unrelated item components")
        h.assertTrue(ItemStack(CartridgeRegistry.CREATIVE.get()).getCapability(Capabilities.FluidHandler.ITEM)==null,"Creative raw supplies must not turn into ordinary refillable liquid")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun fourPlacedCartridgesPersistAndRefund(h:GameTestHelper) {
        val level=h.level;val pos=h.absolutePos(BlockPos(1,1,1));level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState())
        val actor=FakePlayerFactory.get(level,GameProfile(UUID.randomUUID(),"CartridgeStack"));actor.setPos(pos.x+4.0,pos.y.toDouble(),pos.z+4.0);actor.gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
        val inputs=listOf(Economy.cartridge(0),Economy.cartridge(32),Economy.cartridge(256),ItemStack(CartridgeRegistry.CREATIVE.get()))
        for((index,original) in inputs.withIndex()) {
            actor.setItemInHand(InteractionHand.MAIN_HAND,original.copy())
            val clicked=if(index==0) pos.below() else pos
            val hit=BlockHitResult(Vec3.atCenterOf(clicked),Direction.UP,clicked,false)
            val result=actor.gameMode.useItemOn(actor,level,actor.mainHandItem,InteractionHand.MAIN_HAND,hit)
            h.assertTrue(result.consumesAction() && actor.mainHandItem.isEmpty,"Every cartridge variant must place through the real block interaction")
            h.assertTrue(level.getBlockState(pos).getValue(CartridgeStackBlock.COUNT)==index+1,"Cartridges stack from one to four at the same position")
        }
        val entity=level.getBlockEntity(pos) as CartridgeStackEntity
        val reloaded=CartridgeStackEntity(pos,entity.blockState);reloaded.loadWithComponents(entity.saveWithoutMetadata(level.registryAccess()),level.registryAccess())
        h.assertTrue(reloaded.cartridges.map(Economy::units)==listOf(0,32,256,Int.MAX_VALUE),"Every cartridge's kind and exact units survive NBT")
        val handler=checkNotNull(level.getCapability(Capabilities.FluidHandler.BLOCK,pos,null))
        h.assertTrue(handler.fill(FluidStack(CartridgeRegistry.ZINC.get(),100),FluidAction.EXECUTE)==100,"Create pipes can refill a placed cartridge")
        h.assertTrue(Economy.units(entity.cartridges[0])==100 && Economy.units(entity.cartridges[1])==32,"Placed filling preserves the other stored cartridges")
        h.assertTrue(level.destroyBlock(pos,true,actor),"Cartridge holder must be breakable")
        val drops=level.getEntitiesOfClass(ItemEntity::class.java,AABB(pos).inflate(1.0)).flatMap { List(it.item.count) { _ -> it.item.copyWithCount(1) } }
        h.assertTrue(drops.size==4 && drops.map(Economy::units).sorted()==listOf(32,100,256,Int.MAX_VALUE),"Breaking returns exactly the four original cartridges with their current partial contents")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun cancelledActualCartridgePlacementPreservesHandAndExistingStack(h:GameTestHelper) {
        val level=h.level;val pos=h.absolutePos(BlockPos(1,1,1));val neighbor=pos.east()
        level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(neighbor.below(),Blocks.STONE.defaultBlockState())
        fun named(units:Int)=Economy.cartridge(units).also { it.set(DataComponents.CUSTOM_NAME,Component.literal("Protected partial cartridge $units")) }
        for(mode in listOf(GameType.SURVIVAL,GameType.CREATIVE)) {
            for(target in listOf(pos,neighbor)) {
                (level.getBlockEntity(target) as? CartridgeStackEntity)?.cartridges?.clear()
                level.setBlockAndUpdate(target,Blocks.AIR.defaultBlockState())
            }
            val actor=FakePlayerFactory.get(level,GameProfile(UUID.randomUUID(),"DeniedCartridge"))
            actor.gameMode.changeGameModeForPlayer(mode);actor.setPos(pos.x+4.0,pos.y.toDouble(),pos.z+4.0)
            var deny=true;var cancellations=0
            val protection=Consumer<BlockEvent.EntityPlaceEvent> { event ->
                if(deny && event.level===level && event.entity?.uuid==actor.uuid && event.placedBlock.`is`(CartridgeRegistry.BLOCK.get())) {
                    cancellations++;event.isCanceled=true
                }
            }
            NeoForge.EVENT_BUS.addListener(protection)
            try {
                fun use(clicked:BlockPos,face:Direction)=actor.gameMode.useItemOn(actor,level,actor.mainHandItem,InteractionHand.MAIN_HAND,BlockHitResult(Vec3.atCenterOf(clicked),face,clicked,false))
                fun noDrops()=h.assertTrue(level.getEntitiesOfClass(ItemEntity::class.java,AABB(pos).inflate(1.5)).isEmpty(),"A cancelled $mode placement must not duplicate any cartridge as a drop")
                val original=named(73);actor.setItemInHand(InteractionHand.MAIN_HAND,original.copy())
                use(pos.below(),Direction.UP)
                h.assertTrue(cancellations==1 && level.getBlockState(pos).isAir && ItemStack.matches(actor.mainHandItem,original),"A cancelled initial $mode placement must restore the held cartridge, including its partial units and components")
                noDrops()
                deny=false
                for(index in 0..3) {
                    actor.setItemInHand(InteractionHand.MAIN_HAND,named((index+1)*16))
                    use(if(index==0) pos.below() else pos,Direction.UP)
                    h.assertTrue(level.getBlockState(pos).getValue(CartridgeStackBlock.COUNT)==index+1,"Unprotected setup must place each cartridge into the existing stack")
                    if(index==0) {
                        // Cancelling an in-place count change must restore both the property and the old block-entity items.
                        val before=(level.getBlockEntity(pos) as CartridgeStackEntity).saveWithoutMetadata(level.registryAccess())
                        actor.setItemInHand(InteractionHand.MAIN_HAND,original.copy());deny=true
                        use(pos,Direction.UP)
                        h.assertTrue(cancellations==2 && level.getBlockState(pos).getValue(CartridgeStackBlock.COUNT)==1 &&
                            (level.getBlockEntity(pos) as CartridgeStackEntity).saveWithoutMetadata(level.registryAccess())==before && ItemStack.matches(actor.mainHandItem,original),
                            "A rejected addition must restore the existing cartridge and held item without changing their components")
                        noDrops();deny=false
                    }
                }
                val before=(level.getBlockEntity(pos) as CartridgeStackEntity).saveWithoutMetadata(level.registryAccess())
                actor.setItemInHand(InteractionHand.MAIN_HAND,original.copy());deny=true
                // A full stack routes another placement to its adjacent supported block.
                use(pos,Direction.EAST)
                h.assertTrue(cancellations==3 && level.getBlockState(neighbor).isAir && level.getBlockState(pos).getValue(CartridgeStackBlock.COUNT)==4 &&
                    (level.getBlockEntity(pos) as CartridgeStackEntity).saveWithoutMetadata(level.registryAccess())==before && ItemStack.matches(actor.mainHandItem,original),
                    "A denied placement beside a full stack must preserve all four stored cartridges, their NBT and the held cartridge")
                noDrops()
                actor.gameMode.changeGameModeForPlayer(GameType.ADVENTURE);actor.isShiftKeyDown=true;actor.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY)
                val result=level.getBlockState(pos).useWithoutItem(level,actor,BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false))
                h.assertTrue(result==net.minecraft.world.InteractionResult.FAIL &&
                    (level.getBlockEntity(pos) as CartridgeStackEntity).saveWithoutMetadata(level.registryAccess())==before,
                    "A player without item-use permission must not withdraw a cartridge by shift-clicking")
                noDrops()
            } finally { NeoForge.EVENT_BUS.unregister(protection) }
        }
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun recyclingRecipesRespectCopycatZincCosts(h:GameTestHelper) {
        val records=CartridgeIntegrationTests::class.java.getResourceAsStream("/data/copycat_architect/zinc_recycling.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        h.assertTrue(records.size()>=47,"Recycling must cover functional Copycats as well as geometry sources")
        for((item,entry) in records.entrySet()) {
            val data=entry.asJsonObject;val units=data.get("units").asInt;val count=data.get("items").asInt
            val recipe=h.level.recipeManager.byKey(ResourceLocation.fromNamespaceAndPath(Architect.ID,"zinc_recycling/"+item.replace(':','_'))).orElseThrow().value()
            val fluids=recipe.javaClass.getMethod("getFluidResults").invoke(recipe) as List<*>
            val metal=fluids.single() as FluidStack
            h.assertTrue(metal.`is`(CartridgeRegistry.ZINC.get()) && metal.amount==units && recipe.ingredients.size==count,"Loaded Create recycling must preserve the exact recorded item/fluid quantities for $item")
            for(cut in h.level.recipeManager.getAllRecipesFor(RecipeType.STONECUTTING)) {
                val result=cut.value().getResultItem(h.level.registryAccess())
                if(result.`is`(Economy.item(item)) && cut.value().ingredients.single().test(ItemStack(Economy.item("create:zinc_ingot"))))
                    h.assertTrue(units.toLong()*result.count<=256L*count,"Recycling cannot return more zinc than the actual stonecutting recipe paid for $item")
            }
        }
        h.assertTrue(records.getAsJsonObject("copycats:copycat_cogwheel").get("units").asInt==64 && records.getAsJsonObject("copycats:copycat_fluid_pipe").get("units").asInt==64,"Functional Copycat substrates must not multiply their extra zinc payment")
        h.assertTrue(records.getAsJsonObject("copycats:copycat_ladder").get("items").asInt==3,"Fractional recipe values must use exact batches instead of rounding zinc upward")
        h.succeed()
    }
}
