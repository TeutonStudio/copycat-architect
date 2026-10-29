package de.teutonstudio.copycatarchitect.server

import com.mojang.authlib.GameProfile
import dan200.computercraft.api.lua.MethodResult
import dan200.computercraft.api.turtle.ITurtleAccess
import dan200.computercraft.api.turtle.TurtleCommand
import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.integration.ArchitectPeripheral
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.function.Consumer

/** Runs the actual peripheral command bodies against a server world with deterministic queue dispatch. */
@GameTestHolder(Architect.ID)
@PrefixGameTestTemplate(false)
object TurtlePrinterGameTests {
    private class Turtle(h:GameTestHelper,val owner:GameProfile=GameProfile(UUID.randomUUID(),"PrinterTurtle"),prepare:Boolean=true) {
        val inventory=SimpleContainer(16)
        var position:BlockPos=h.absolutePos(BlockPos(1,1,1))
        var direction=Direction.EAST
        var commands=0
        val access:ITurtleAccess=Proxy.newProxyInstance(ITurtleAccess::class.java.classLoader,arrayOf(ITurtleAccess::class.java)) { proxy,method,args ->
            when(method.name) {
                "getLevel"->h.level
                "getPosition"->position
                "getDirection"->direction
                "getOwningPlayer"->owner
                "getInventory"->inventory
                "isRemoved"->false
                "getSelectedSlot","getFuelLevel"->0
                "executeCommand"->{
                    commands++
                    val result=(args!![0] as TurtleCommand).execute(proxy as ITurtleAccess)
                    if(result.isSuccess) MethodResult.of(true,*(result.results ?: emptyArray())) else MethodResult.of(false,result.errorMessage)
                }
                "equals"->proxy===args?.get(0)
                "hashCode"->System.identityHashCode(proxy)
                "toString"->"Printer acceptance turtle"
                else->error("Unexpected turtle API during printer command: ${method.name}")
            }
        } as ITurtleAccess
        val peripheral=ArchitectPeripheral(access)
        val target:BlockPos get()=position.relative(direction)
        init { if(prepare) {
            h.level.setBlockAndUpdate(target,Blocks.AIR.defaultBlockState())
            h.level.setBlockAndUpdate(target.below(),Blocks.STONE.defaultBlockState())
        } }
    }
    private fun form()=Geometry.validate(Form(id="turtle_printer_raw",name="Turtle printer template",parts=listOf(Part("copycats:copycat_block"))))
    private fun device(h:GameTestHelper):ItemStack=ItemStack(Architect.GUN.get()).also { stack ->
        PrinterStack.id(stack);PrinterStack.draft(stack,form(),emptyMap(),1)
        val slots=SimpleContainer(7)
        repeat(7) { i -> slots.setItem(i,Economy.cartridge((i+1)*32).also { it.set(DataComponents.CUSTOM_NAME,Component.literal("Turtle cartridge ${i+1}")) }) }
        PrinterStack.contents(stack,slots,h.level.registryAccess())
    }
    @Suppress("UNCHECKED_CAST") private fun success(h:GameTestHelper,result:MethodResult):Map<String,Any?> {
        val values=checkNotNull(result.result)
        h.assertTrue(values.firstOrNull()==true,"Printer command failed: ${values.lastOrNull()}")
        return values[1] as Map<String,Any?>
    }
    private fun failed(h:GameTestHelper,result:MethodResult,message:String) {
        h.assertTrue(result.result?.firstOrNull()==false,message)
    }
    private fun inventory(turtle:Turtle)=(0 until 16).map { turtle.inventory.getItem(it).copy() }
    private fun same(first:List<ItemStack>,second:List<ItemStack>)=first.size==second.size && first.indices.all { ItemStack.matches(first[it],second[it]) }
    private fun cartridges(h:GameTestHelper,stack:ItemStack)=PrinterStack.contents(stack,h.level.registryAccess()).map(ItemStack::copy)

    @JvmStatic @GameTest(template="empty") fun turtlePrinterBeginCancelPreservesSevenSlotsAndChecksOwnerSession(h:GameTestHelper) {
        val turtle=Turtle(h);val api=turtle.peripheral
        failed(h,api.printerBegin("front"),"The peripheral upgrade must not fabricate a placeable printer")
        val original=device(h);turtle.inventory.setItem(0,original.copy())
        val begun=success(h,api.printerBegin("front"));val session=begun.getValue("session") as String
        val be=h.level.getBlockEntity(turtle.target) as PrinterEntity
        h.assertTrue(turtle.inventory.getItem(0).isEmpty && be.owner==turtle.owner.id.toString() && be.session==session,"Begin must move the extra device to its owned adjacent frame")
        h.assertTrue(PrinterService.active(FakePlayerFactory.get(h.level,turtle.owner))==null,"A turtle printer must not open or select a player GUI session")
        h.assertTrue(same(cartridges(h,original),(0 until 7).map { be.cartridges.getItem(it) }),"Begin must retain every named cartridge in its original position")
        failed(h,api.printerCancel("front","expired"),"An expired session must not pick up the device")
        val stranger=Turtle(h,GameProfile(UUID.randomUUID(),"OtherPrinterTurtle"),prepare=false)
        failed(h,stranger.peripheral.printerCancel("front",session),"Another owner's turtle must not use a known session token")
        val returned=success(h,api.printerCancel("front",session));val slot=(returned.getValue("printerSlot") as Number).toInt()-1
        h.assertTrue(h.level.getBlockState(turtle.target).isAir && ItemStack.matches(original,turtle.inventory.getItem(slot)),"Cancel must return exactly the original reusable seven-slot device")
        failed(h,api.printerCancel("front",session),"A cancelled session cannot return a second device")
        h.assertTrue(h.level.getEntitiesOfClass(ItemEntity::class.java,AABB(turtle.target).inflate(1.0)).isEmpty(),"Cancel must not create an additional world drop")
        h.assertTrue(turtle.commands>=5,"Every peripheral operation must pass through the turtle command dispatcher")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun turtlePrinterPrintUsesCartridgesReturnsDeviceAndStoresNamedHistory(h:GameTestHelper) {
        val turtle=Turtle(h);val api=turtle.peripheral;turtle.inventory.setItem(0,device(h))
        val session=success(h,api.printerBegin("front")).getValue("session") as String
        val preview=success(h,api.printerPreview("front",session,Json.encode(form()),"{}",2))
        h.assertTrue((preview.getValue("units") as Number).toInt()==256,"The physical preview must report the actual full-block zinc cost")
        turtle.inventory.setItem(1,Economy.cartridge(256))
        val loaded=api.printerLoadCartridge("front",session,7,2)
        h.assertTrue(loaded.result?.firstOrNull()==true && Economy.units(turtle.inventory.getItem(1))==224,"Loading must exchange one real cartridge and return the previous cartridge")
        val unloaded=api.printerLoadCartridge("front",session,7,0)
        h.assertTrue(unloaded.result?.firstOrNull()==true && (h.level.getBlockEntity(turtle.target) as PrinterEntity).cartridges.getItem(6).isEmpty,"Unloading must return the cartridge to the actual turtle inventory")
        val result=success(h,api.printerPrint("front",session));val slot=(result.getValue("printerSlot") as Number).toInt()-1
        val printed=h.level.getBlockEntity(turtle.target) as? CompositeEntity
        h.assertTrue(printed!=null && printed.receipt.units==256 && printed.receipt.surfaces.isEmpty(),"Print must replace the frame with paid uncoated geometry")
        val returned=turtle.inventory.getItem(slot);val stored=cartridges(h,returned)
        h.assertTrue(returned.`is`(Architect.GUN.get()) && stored.size==7 && stored[6].isEmpty,"The physical printer must return with seven persisted cartridge positions")
        val total=(0 until 16).sumOf { i -> Economy.units(turtle.inventory.getItem(i)).toLong() }+stored.sumOf { Economy.units(it).toLong() }
        h.assertTrue(total==896L,"The complete printer/inventory transfer must subtract exactly 256 units without duplicating or losing cartridges")
        h.assertTrue((0 until 16).count { turtle.inventory.getItem(it).`is`(Architect.GUN.get()) }==1,"A successful print returns exactly one physical device")
        val historyId=result.getValue("historyId") as String
        val saved=success(h,api.printerSaveHistory(historyId,"Named turtle print"));val key=saved.getValue("key") as String
        val lib=ArchitectData.get(h.level).library(turtle.owner.id)
        h.assertTrue(lib.history.single().id==historyId && lib.forms[key]?.name=="Named turtle print","Only successful prints enter history and explicit naming creates the saved form")
        h.assertTrue(lib.printSettings[key]==PrintSettings(emptyMap(),2),"The turtle's named history save must preserve the physical print's orientation and coatings")
        failed(h,api.printerPrint("front",session),"A completed session cannot print or return the device again")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun turtlePrinterProtectionAndFullInventoryLeaveWorldAndSevenSlotsIntact(h:GameTestHelper) {
        val turtle=Turtle(h);val api=turtle.peripheral;turtle.inventory.setItem(0,device(h));val original=inventory(turtle)
        var protect=true
        var throwing=false
        val listener=Consumer<BlockEvent.EntityPlaceEvent> { event ->
            if(protect && event.level===h.level && event.pos==turtle.target && event.entity?.uuid==turtle.owner.id) {
                if(throwing) throw IllegalStateException("Intentional printer protection failure")
                event.isCanceled=true
            }
        }
        NeoForge.EVENT_BUS.addListener(listener)
        try {
            failed(h,api.printerBegin("front"),"Protected placement must not start a printer")
            h.assertTrue(h.level.getBlockState(turtle.target).isAir && same(original,inventory(turtle)),"A denied begin must preserve the target and original device contents")
            protect=false
            val session=success(h,api.printerBegin("front")).getValue("session") as String
            val be=h.level.getBlockEntity(turtle.target) as PrinterEntity
            val before=be.saveWithoutMetadata(h.level.registryAccess());val beforeInventory=inventory(turtle)
            protect=true
            failed(h,api.printerPrint("front",session),"A protected print must be rejected")
            val after=h.level.getBlockEntity(turtle.target) as? PrinterEntity
            h.assertTrue(after!=null && !after.released && after.saveWithoutMetadata(h.level.registryAccess())==before && same(beforeInventory,inventory(turtle)),"Protection rollback must restore the frame, session, seven named cartridges and turtle inventory")
            throwing=true
            failed(h,api.printerPrint("front",session),"A throwing placement hook must fail without leaving the printed block")
            val restored=h.level.getBlockEntity(turtle.target) as? PrinterEntity
            h.assertTrue(restored!=null && !restored.released && restored.saveWithoutMetadata(h.level.registryAccess())==before && same(beforeInventory,inventory(turtle)),"A throwing hook must restore the complete printer and both inventories exactly")
            throwing=false
            protect=false
            repeat(16) { turtle.inventory.setItem(it,ItemStack(Items.STONE,64)) }
            val full=inventory(turtle)
            failed(h,api.printerPrint("front",session),"Printing requires a reserved slot for the returned machine")
            failed(h,api.printerCancel("front",session),"Cancelling with a full inventory must retain the machine in the world")
            h.assertTrue(h.level.getBlockEntity(turtle.target)===restored && same(full,inventory(turtle)),"A missing return slot must not spend or lose any items")
            turtle.inventory.setItem(15,ItemStack.EMPTY)
            success(h,api.printerCancel("front",session))
            h.assertTrue(turtle.inventory.getItem(15).`is`(Architect.GUN.get()),"After freeing one slot the complete device remains recoverable")
            h.assertTrue(h.level.getEntitiesOfClass(ItemEntity::class.java,AABB(turtle.target).inflate(1.0)).isEmpty(),"Placement protection and rollback must never duplicate the device as a drop")
        } finally { NeoForge.EVENT_BUS.unregister(listener) }
        h.succeed()
    }
}
