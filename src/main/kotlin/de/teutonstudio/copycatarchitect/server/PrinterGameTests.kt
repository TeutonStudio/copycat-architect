package de.teutonstudio.copycatarchitect.server

import com.mojang.authlib.GameProfile
import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Consumer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.BlockEvent

@GameTestHolder(Architect.ID)
@PrefixGameTestTemplate(false)
object PrinterGameTests {
    private fun form(twoGroups:Boolean=false)=Geometry.validate(if(twoGroups) Form(id="printer_groups",name="Printer material groups",
        parts=listOf(Part("copycats:copycat_slab"),Part("copycats:copycat_slab",top=true,group="cap")),
        groups=listOf(MaterialGroup(),MaterialGroup("cap","Cap","frame",1))) else Form(id="printer_raw",name="Raw printer form",parts=listOf(Part("copycats:copycat_block"))))
    private fun stack(h:GameTestHelper,form:Form,slots:SimpleContainer,surfaces:Map<String,String> = emptyMap(),turn:Int=0)=ItemStack(Architect.GUN.get()).also {
        PrinterStack.id(it);PrinterStack.draft(it,form,surfaces,turn);PrinterStack.contents(it,slots,h.level.registryAccess())
        it.set(DataComponents.CUSTOM_NAME,Component.literal("Seven-slot test printer"))
    }
    private fun place(h:GameTestHelper,device:ItemStack,name:String,mode:GameType=GameType.SURVIVAL):Pair<ServerPlayer,PrinterEntity> {
        val level=h.level;val pos=h.absolutePos(BlockPos(1,1,1))
        val actor=FakePlayerFactory.get(level,GameProfile(UUID.randomUUID(),name))
        actor.gameMode.changeGameModeForPlayer(mode);actor.setPos(pos.x+4.0,pos.y.toDouble(),pos.z+4.0)
        level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState())
        actor.setItemInHand(InteractionHand.MAIN_HAND,device)
        val hit=BlockHitResult(Vec3(pos.x+0.5,pos.y.toDouble(),pos.z+0.5),Direction.UP,pos.below(),false)
        val result=actor.gameMode.useItemOn(actor,level,device,InteractionHand.MAIN_HAND,hit)
        h.assertTrue(result.consumesAction(),"The actual printer item must handle placement")
        val entity=level.getBlockEntity(pos) as? PrinterEntity
        h.assertTrue(entity!=null && device.isEmpty,"Physical placement must move the complete printer item into its block entity, including in Creative mode")
        h.assertTrue(entity!!.owner==actor.uuid.toString() && entity.session.isNotEmpty(),"Placement must establish an owned printer session")
        h.assertTrue(PrinterService.active(actor)===entity,"The placed printer must become the authenticated active device")
        return actor to entity
    }
    private fun slotsEqual(first:List<ItemStack>,second:List<ItemStack>)=first.size==7 && second.size==7 && first.indices.all { ItemStack.matches(first[it],second[it]) }
    private fun contents(be:PrinterEntity)=(0 until 7).map { be.cartridges.getItem(it).copy() }
    private fun update(be:PrinterEntity,form:Form,surfaces:Map<String,String> = emptyMap(),turn:Int=0)=Command("printerPreview",key=be.session,payload=Json.encode(form),surfaces=surfaces,turn=turn)

    @JvmStatic @GameTest(template="empty") fun printerPlacePreviewCancelAndSevenSlotPersistence(h:GameTestHelper) {
        val form=form(true);val surfaces=mapOf("cap" to "minecraft:glass")
        val slots=SimpleContainer(7)
        repeat(7) { i -> slots.setItem(i,Economy.cartridge(i*32).also { it.set(DataComponents.CUSTOM_NAME,Component.literal("Cartridge ${i+1}")) }) }
        val device=stack(h,form,slots,surfaces,1);val original=device.copy()
        val (actor,be)=place(h,device,"PrinterCancel")
        PrinterService.update(actor,update(be,form,surfaces,1))
        h.assertTrue(be.preview==form && be.surfaces==surfaces && be.turn==1,"The authenticated preview must update the world block entity")
        h.assertTrue(runCatching { PrinterService.update(actor,update(be,form).copy(key="expired")) }.isFailure,"An expired session must not change a printer")
        val stranger=FakePlayerFactory.get(h.level,GameProfile(UUID.randomUUID(),"PrinterStranger"));stranger.setPos(actor.x,actor.y,actor.z)
        h.assertTrue(runCatching { PrinterService.cancelDirect(h.level,be,stranger) }.isFailure,"Another player must not pick up an owned printer")
        val loaded=PrinterEntity(be.blockPos,be.blockState);loaded.loadWithComponents(be.saveWithoutMetadata(h.level.registryAccess()),h.level.registryAccess())
        h.assertTrue(loaded.owner==be.owner && loaded.session==be.session && loaded.preview==be.preview && slotsEqual(contents(be),contents(loaded)),"Printer NBT must persist the preview, ownership, session and all seven item stacks")
        val recovered=PrinterService.cancelDirect(h.level,be,actor)
        h.assertTrue(h.level.getBlockState(be.blockPos).isAir && ItemStack.matches(original,recovered),"Cancel must recover the exact reusable printer without spending cartridges or surfaces")
        h.assertTrue(slotsEqual(PrinterStack.contents(original,h.level.registryAccess()),PrinterStack.contents(recovered,h.level.registryAccess())),"Cancel must retain all seven cartridge components")
        h.assertTrue(runCatching { PrinterService.cancelDirect(h.level,be,actor) }.isFailure,"A released printer cannot be returned twice")
        h.assertTrue(h.level.getEntitiesOfClass(ItemEntity::class.java,AABB(be.blockPos).inflate(1.0)).isEmpty(),"Direct cancellation must not also drop a duplicate device")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun printerMissingMaterialsRetainDeviceAndSuccessfulPrintDeduplicatesSurfaces(h:GameTestHelper) {
        val form=form(true);val slots=SimpleContainer(7);slots.setItem(0,Economy.cartridge(16))
        slots.setItem(6,Economy.cartridge(0).also { it.set(DataComponents.CUSTOM_NAME,Component.literal("Reserve cartridge")) })
        val (actor,be)=place(h,stack(h,form,slots),"PrinterCost")
        val inventory=SimpleContainer(2);val before=contents(be)
        h.assertTrue(runCatching { PrinterService.printDirect(h.level,be,inventory,actor,form,emptyMap(),0) }.isFailure,"Raw printing still requires enough extrusion material")
        h.assertTrue(h.level.getBlockEntity(be.blockPos)===be && !be.released && slotsEqual(before,contents(be)),"An unfunded print must retain the placed printer and every cartridge")
        be.cartridges.setItem(0,Economy.cartridge(256));val funded=contents(be)
        val surfaces=mapOf("body" to "minecraft:stone","cap" to "minecraft:stone")
        h.assertTrue(runCatching { PrinterService.printDirect(h.level,be,inventory,actor,form,surfaces,1) }.isFailure,"Requested coatings must be paid even when raw material is available")
        h.assertTrue(h.level.getBlockEntity(be.blockPos)===be && !be.released && slotsEqual(funded,contents(be)) && inventory.isEmpty,"A missing coating must not spend raw material or remove the printer")
        inventory.setItem(0,ItemStack(Items.STONE))
        val recovered=PrinterService.printDirect(h.level,be,inventory,actor,form,surfaces,1)
        val printed=h.level.getBlockEntity(be.blockPos) as? CompositeEntity
        h.assertTrue(printed!=null && inventory.isEmpty,"A successful print must replace the frame with its form and consume one shared surface sample")
        h.assertTrue(printed!!.receipt.units==256 && printed.receipt.surfaces==mapOf("minecraft:stone" to 1) && printed.material.isEmpty(),"The printed receipt must contain only the paid extrusion and deduplicated coating")
        h.assertTrue(Geometry.solid(printed.parts).key()==Geometry.solid(Geometry.rotate(form.parts,1)).key(),"The world print must match the preview rotation")
        val recoveredSlots=PrinterStack.contents(recovered,h.level.registryAccess())
        h.assertTrue(recoveredSlots.size==7 && Economy.units(recoveredSlots[0])==0 && recoveredSlots[0].`is`(CartridgeRegistry.EMPTY.get()) && ItemStack.matches(recoveredSlots[6],funded[6]),"The returned printer must contain depleted and untouched cartridges in their original slots")
        h.assertTrue(PrinterStack.form(recovered)==form && PrinterStack.surfaces(recovered)==surfaces && PrinterStack.tag(recovered).getInt("turn")==1,"The returned device must remember its printed graph, surfaces and rotation")
        h.assertTrue(runCatching { PrinterService.printDirect(h.level,be,inventory,actor,form,surfaces,1) }.isFailure,"A completed printer must not print or refund twice")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun rawPrintConsumesOnlyExtrusionWithoutSurfaceItems(h:GameTestHelper) {
        val form=form();val slots=SimpleContainer(7);slots.setItem(3,Economy.cartridge(256))
        val (actor,be)=place(h,stack(h,form,slots),"PrinterRaw")
        val recovered=PrinterService.printDirect(h.level,be,SimpleContainer(1),actor,form,emptyMap(),0)
        val printed=h.level.getBlockEntity(be.blockPos) as CompositeEntity
        h.assertTrue(printed.material.isEmpty() && printed.surfaces.isEmpty() && printed.receipt.units==256 && printed.receipt.surfaces.isEmpty(),"An uncoated print must store raw zinc appearance and charge only extrusion")
        h.assertTrue(Economy.units(PrinterStack.contents(recovered,h.level.registryAccess())[3])==0,"The exact paid raw units must be removed from the device cartridge")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun creativeCartridgePrintReturnsDeviceRecordsHistoryAndNeverRefundsFreeZinc(h:GameTestHelper) {
        val form=form();val slots=SimpleContainer(7);slots.setItem(0,Economy.cartridge(128));slots.setItem(2,ItemStack(CartridgeRegistry.CREATIVE.get()))
        val creative=slots.getItem(2).copy();val (actor,be)=place(h,stack(h,form,slots),"PrinterCreative")
        val library=Library();val command=update(be,form).copy(action="printerPrint")
        h.assertTrue(PrinterService.print(actor,command,library),"The authenticated print wrapper must complete a creative-cartridge print")
        val printed=h.level.getBlockEntity(be.blockPos) as CompositeEntity
        h.assertTrue(printed.receipt.units==0 && printed.receipt.surfaces.isEmpty(),"Creative raw material must never become refundable zinc")
        h.assertTrue(library.history.size==1 && library.history[0].form==form && library.history[0].surfaces.isEmpty(),"A successful player print must append its actual definition to history")
        val devices=(0 until actor.inventory.containerSize).map { actor.inventory.getItem(it) }.filter { it.`is`(Architect.GUN.get()) }
        h.assertTrue(devices.size==1 && devices.single().count==1 && PrinterService.active(actor)==null,"The player wrapper must return exactly one device and close the placed session")
        val returned=devices.single();val restored=ItemStack.parseOptional(h.level.registryAccess(),returned.save(h.level.registryAccess()) as CompoundTag)
        val returnedSlots=PrinterStack.contents(restored,h.level.registryAccess())
        h.assertTrue(Economy.units(returnedSlots[0])==128 && ItemStack.matches(creative,returnedSlots[2]),"Infinite raw supply must preserve ordinary cartridges and its own components through item NBT")
        h.assertTrue(slotsEqual(PrinterStack.contents(returned,h.level.registryAccess()),returnedSlots),"All seven cartridge positions must survive the returned item's serialization")
        h.level.destroyBlock(be.blockPos,true,actor)
        h.assertTrue(h.level.getEntitiesOfClass(ItemEntity::class.java,AABB(be.blockPos).inflate(1.0)).none { Economy.isCartridge(it.item) },"Breaking a freely funded raw print must not generate zinc cartridges")
        h.succeed()
    }
    @JvmStatic @GameTest(template="empty") fun creativePlayerPhysicalPlacementAndCancelReturnOneExactSevenSlotDevice(h:GameTestHelper) {
        val slots=SimpleContainer(7)
        repeat(7) { i -> slots.setItem(i,(if(i==6) ItemStack(CartridgeRegistry.CREATIVE.get()) else Economy.cartridge(i*32)).also {
            it.set(DataComponents.CUSTOM_NAME,Component.literal("Creative stored cartridge ${i+1}"))
        }) }
        val source=stack(h,form(true),slots,mapOf("cap" to "minecraft:glass"),3);val original=source.copy()
        val (actor,be)=place(h,source,"CreativePhysicalPrinter",GameType.CREATIVE)
        h.assertTrue(actor.mainHandItem.isEmpty && be.returnItem,"Creative use through the real game-mode interaction must move the sole physical device, not retain a parallel inventory copy")
        h.assertTrue(slotsEqual(PrinterStack.contents(original,h.level.registryAccess()),contents(be)),"Creative placement must preserve all seven named cartridge items")
        actor.setItemInHand(InteractionHand.MAIN_HAND,ItemStack(Items.STONE))
        PrinterService.cancel(actor,be.session)
        val devices=(0 until actor.inventory.containerSize).map { actor.inventory.getItem(it) }.filter { it.`is`(Architect.GUN.get()) }
        h.assertTrue(h.level.getBlockState(be.blockPos).isAir && devices.size==1 && ItemStack.matches(devices.single(),original),"Cancelling after switching hands must return exactly the complete original device to the inventory")
        h.assertTrue(h.level.getEntitiesOfClass(ItemEntity::class.java,AABB(be.blockPos).inflate(1.0)).isEmpty(),"Creative cancellation must not also spawn a duplicate device")
        h.succeed()
    }
    @JvmStatic @GameTest(template="empty") fun placedPrinterMutationsRespectBuildPermissionAndHeldStorageStillWorks(h:GameTestHelper) {
        val slots=SimpleContainer(7);repeat(7) { slots.setItem(it,Economy.cartridge((it+1)*16)) }
        val (actor,be)=place(h,stack(h,form(),slots),"PrinterBuildPermission")
        actor.inventory.setItem(5,Economy.cartridge(256))
        val before=be.saveWithoutMetadata(h.level.registryAccess())
        val inventory=(0 until actor.inventory.containerSize).map { actor.inventory.getItem(it).copy() }
        actor.gameMode.changeGameModeForPlayer(GameType.ADVENTURE)
        h.assertTrue(!actor.mayUseItemAt(be.blockPos,Direction.UP,be.original),"The test must revoke ordinary item-use permission at the placed machine")
        h.assertTrue(runCatching { PrinterService.update(actor,update(be,form(),turn=2)) }.isFailure,"A denied placed preview cannot modify the block entity")
        h.assertTrue(runCatching { PrinterService.load(actor,form(),emptyMap(),2) }.isFailure,"A denied placed library load cannot change the draft")
        h.assertTrue(runCatching { PrinterService.cartridge(actor,0,5) }.isFailure,"A denied placed cartridge transfer cannot move items")
        h.assertTrue(be.saveWithoutMetadata(h.level.registryAccess())==before && inventory.indices.all { ItemStack.matches(inventory[it],actor.inventory.getItem(it)) },"All denied mutations must preserve the exact frame and both inventories")
        actor.gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
        val held=PrinterService.cancelDirect(h.level,be,actor);actor.setItemInHand(InteractionHand.MAIN_HAND,held)
        actor.gameMode.changeGameModeForPlayer(GameType.ADVENTURE)
        PrinterService.load(actor,form(),emptyMap(),2);PrinterService.cartridge(actor,0,5)
        h.assertTrue(PrinterStack.tag(held).getInt("turn")==2 && Economy.units(PrinterStack.contents(held,h.level.registryAccess())[0])==256,"A held printer's own draft and seven-slot storage must remain usable without world placement permission")
        h.succeed()
    }
    @JvmStatic @GameTest(template="empty") fun cancelledActualPrinterPlacementPreservesHandAndSevenSlotsInBothGameModes(h:GameTestHelper) {
        val level=h.level;val pos=h.absolutePos(BlockPos(1,1,1));level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState())
        for(mode in listOf(GameType.SURVIVAL,GameType.CREATIVE)) {
            level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState())
            val actor=FakePlayerFactory.get(level,GameProfile(UUID.randomUUID(),"PrinterPlaceDenied"))
            actor.gameMode.changeGameModeForPlayer(mode);actor.setPos(pos.x+4.0,pos.y.toDouble(),pos.z+4.0)
            val slots=SimpleContainer(7);repeat(7) { i -> slots.setItem(i,Economy.cartridge((i+1)*16).also { it.set(DataComponents.CUSTOM_NAME,Component.literal("Protected cartridge ${i+1}")) }) }
            val held=stack(h,form(),slots);val original=held.copy();actor.setItemInHand(InteractionHand.MAIN_HAND,held)
            val protection=Consumer<BlockEvent.EntityPlaceEvent> { event -> if(event.level===level && event.pos==pos && event.entity?.uuid==actor.uuid) event.isCanceled=true }
            NeoForge.EVENT_BUS.addListener(protection)
            try {
                val hit=BlockHitResult(Vec3(pos.x+0.5,pos.y.toDouble(),pos.z+0.5),Direction.UP,pos.below(),false)
                actor.gameMode.useItemOn(actor,level,held,InteractionHand.MAIN_HAND,hit)
                h.assertTrue(level.getBlockState(pos).isAir && ItemStack.matches(actor.mainHandItem,original),"A cancelled real $mode placement must preserve the original held printer and every component")
                h.assertTrue(slotsEqual(PrinterStack.contents(actor.mainHandItem,level.registryAccess()),PrinterStack.contents(original,level.registryAccess())),"Placement protection must preserve all seven cartridges in $mode")
                h.assertTrue(level.getEntitiesOfClass(ItemEntity::class.java,AABB(pos).inflate(1.0)).isEmpty(),"Cancelled $mode placement must not also drop a printer or cartridge")
            } finally { NeoForge.EVENT_BUS.unregister(protection) }
        }
        h.succeed()
    }
    @JvmStatic @GameTest(template="empty") fun namedHistoryPreservesCoatingsAndRotationInSavedItemAndLibraryRoundtrip(h:GameTestHelper) {
        val definition=form(true);val surfaces=mapOf("body" to "minecraft:stone","cap" to "minecraft:glass")
        val slots=SimpleContainer(7);slots.setItem(0,Economy.cartridge(256))
        val (actor,be)=place(h,stack(h,definition,slots,surfaces,3),"PrinterSavedSettings")
        val payment=SimpleContainer(2);payment.setItem(0,ItemStack(Items.STONE));payment.setItem(1,ItemStack(Items.GLASS))
        PrinterService.printDirect(h.level,be,payment,actor,definition,surfaces,3)
        val library=Library();PrinterService.record(library,definition,surfaces,3)
        val key=library.savePrintHistory(library.history.single().id,"Named coated print")
        val restored=Json.decode<Library>(Json.encode(library));val settings=restored.printSettings.getValue(key)
        h.assertTrue(settings.surfaces==surfaces && settings.turn==3 && restored.forms.getValue(key).name=="Named coated print","Naming history and saving the library must retain the actual printed palette and orientation")
        val saved=CompositeFormItem.stack(restored.forms.getValue(key),settings.surfaces,settings.turn)
        h.assertTrue(PrinterStack.surfaces(saved)==surfaces && PrinterStack.tag(saved).getInt("turn")==3,"The saved creative item must carry the same coatings and rotation as the completed print")
        h.succeed()
    }
    @JvmStatic @GameTest(template="empty") fun openingStorageSynchronizesLatestDraftOutsidePreviewThrottle(h:GameTestHelper) {
        val slots=SimpleContainer(7);slots.setItem(0,Economy.cartridge(192))
        val (actor,be)=place(h,stack(h,form(),slots),"PrinterStorageDraft")
        val before=contents(be)
        Network.handle(actor,Json.encode(update(be,form())))
        val latest=form(true).copy(name="Latest unsent edit")
        val materials=mapOf("body" to "","cap" to "minecraft:gold_block")
        Network.handle(actor,Json.encode(update(be,latest,materials,3).copy(action="printerStorage")))
        val snapshot=PrinterService.view(actor)
        h.assertTrue(snapshot?.form==latest && snapshot.surfaces==materials && snapshot.turn==3,"Opening storage immediately after a preview must synchronize the latest complete draft, coatings and rotation")
        h.assertTrue(slotsEqual(before,contents(be)),"Opening memory must preserve all seven physical cartridge stacks")
        PrinterService.cancelDirect(h.level,be,actor);h.succeed()
    }
}
