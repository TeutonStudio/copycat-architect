package de.teutonstudio.copycatarchitect.server

import com.mojang.serialization.MapCodec
import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import net.minecraft.core.*
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.*
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.*
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.*
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.entity.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.*
import java.util.UUID

data class PrintRecord(val id:String=UUID.randomUUID().toString(),val form:Form=Form(),val surfaces:Map<String,String> = emptyMap(),val turn:Int=0,val time:Long=0)
data class CartridgeView(val item:String="",val units:Int=0,val creative:Boolean=false)
data class PrinterView(val position:P=P(),val session:String="",val form:Form=Form(),val surfaces:Map<String,String> = emptyMap(),val turn:Int=0,val slots:List<CartridgeView> = emptyList(),val placed:Boolean=false)

/** The complete device, including all seven physical cartridges, travels with its item. */
object PrinterStack {
    fun tag(stack:ItemStack)=stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
    fun update(stack:ItemStack,change:(CompoundTag)->Unit) { val tag=tag(stack);change(tag);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag)) }
    fun id(stack:ItemStack):String { val tag=tag(stack);val id=tag.getString("printer_id");if(id.isNotEmpty()) return id;return UUID.randomUUID().toString().also { fresh -> update(stack) { it.putString("printer_id",fresh) } } }
    fun definition(json:String):Form {
        val draft=Json.decode<Form>(json)
        draft.graph?.let { GraphCompiler.structure(it,draft.groups) }
        return runCatching { Geometry.validate(draft) }.getOrElse { if(draft.graph!=null) draft.copy(parts=emptyList()) else throw it }
    }
    fun form(stack:ItemStack):Form = runCatching { tag(stack).getString("draft").takeIf(String::isNotEmpty)?.let(::definition) }.getOrNull()
        ?: Geometry.validate(Form(id=UUID.randomUUID().toString(),name="Unnamed print",graph=NodeGraph.fromParts(listOf(Part("copycats:copycat_block")))))
    fun surfaces(stack:ItemStack)=runCatching { Json.materials(tag(stack).getString("surfaces").ifEmpty { "{}" }) }.getOrDefault(emptyMap())
    fun contents(stack:ItemStack,registries:HolderLookup.Provider):NonNullList<ItemStack> = NonNullList.withSize(7,ItemStack.EMPTY).also { ContainerHelper.loadAllItems(tag(stack).getCompound("cartridges"),it,registries) }
    fun contents(stack:ItemStack,slots:Container,registries:HolderLookup.Provider) = update(stack) { tag -> tag.put("cartridges",CompoundTag().also { out -> ContainerHelper.saveAllItems(out,NonNullList.withSize(7,ItemStack.EMPTY).also { list -> repeat(7) { list[it]=slots.getItem(it).copy() } },registries) }) }
    fun draft(stack:ItemStack,form:Form,surfaces:Map<String,String>,turn:Int)=update(stack) { it.putString("draft",Json.encode(form));it.putString("surfaces",Json.encode(surfaces));it.putInt("turn",turn) }
}
class PrinterEntity(pos:BlockPos,state:BlockState):BlockEntity(Architect.PRINTER_BE.get(),pos,state) {
    var original:ItemStack=ItemStack.EMPTY
    var owner="";var session="";var released=false;var returnItem=true
    var preview:Form?=null;var surfaces:Map<String,String> = emptyMap();var turn=0
    val cartridges=object:SimpleContainer(7) { override fun canPlaceItem(slot:Int,stack:ItemStack)=Economy.isCartridge(stack) }
    init { cartridges.addListener { setChanged() } }
    fun changed() { setChanged();level?.sendBlockUpdated(blockPos,blockState,blockState,3) }
    fun device():ItemStack=original.copy().also { stack -> if(!stack.isEmpty) { level?.let { PrinterStack.contents(stack,cartridges,it.registryAccess()) };preview?.let { PrinterStack.draft(stack,it,surfaces,turn) } } }
    override fun saveAdditional(tag:CompoundTag,registries:HolderLookup.Provider) {
        super.saveAdditional(tag,registries)
        if(!original.isEmpty) tag.put("original",original.save(registries))
        ContainerHelper.saveAllItems(tag,NonNullList.withSize(7,ItemStack.EMPTY).also { list -> repeat(7) { list[it]=cartridges.getItem(it) } },registries)
        tag.putString("owner",owner);tag.putString("session",session);tag.putBoolean("released",released);tag.putBoolean("returnItem",returnItem)
        preview?.let { tag.putString("preview",Json.encode(it)) };tag.putString("surfaces",Json.encode(surfaces));tag.putInt("turn",turn)
    }
    override fun loadAdditional(tag:CompoundTag,registries:HolderLookup.Provider) {
        super.loadAdditional(tag,registries);original=ItemStack.parseOptional(registries,tag.getCompound("original"))
        val slots=NonNullList.withSize(7,ItemStack.EMPTY);ContainerHelper.loadAllItems(tag,slots,registries);repeat(7) { cartridges.setItem(it,slots[it]) }
        owner=tag.getString("owner");session=tag.getString("session");released=tag.getBoolean("released");returnItem=!tag.contains("returnItem") || tag.getBoolean("returnItem")
        preview=tag.getString("preview").takeIf(String::isNotEmpty)?.let { runCatching { PrinterStack.definition(it) }.getOrNull() }
        surfaces=runCatching { Json.materials(tag.getString("surfaces").ifEmpty { "{}" }) }.getOrDefault(emptyMap());turn=Math.floorMod(tag.getInt("turn"),4)
    }
    override fun getUpdateTag(registries:HolderLookup.Provider)=saveWithoutMetadata(registries)
    override fun getUpdatePacket()=ClientboundBlockEntityDataPacket.create(this)
}
class PrinterBlock(properties:Properties):BaseEntityBlock(properties) {
    override fun codec():MapCodec<out BaseEntityBlock> = simpleCodec(::PrinterBlock)
    override fun newBlockEntity(pos:BlockPos,state:BlockState)=PrinterEntity(pos,state)
    override fun getRenderShape(state:BlockState)=RenderShape.MODEL
    override fun getShape(state:BlockState,level:BlockGetter,pos:BlockPos,context:CollisionContext):VoxelShape=FRAME
    override fun useWithoutItem(state:BlockState,level:Level,pos:BlockPos,player:Player,hit:BlockHitResult):InteractionResult {
        if(player is ServerPlayer) runCatching { PrinterService.open(player,level.getBlockEntity(pos) as PrinterEntity,player.isShiftKeyDown) }.onFailure { player.displayClientMessage(Component.literal(it.message.orEmpty()),true) }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
    override fun onRemove(state:BlockState,level:Level,pos:BlockPos,next:BlockState,moving:Boolean) {
        if(!next.`is`(this) && !level.isClientSide && !level.restoringBlockSnapshots) (level.getBlockEntity(pos) as? PrinterEntity)?.let { be ->
            if(!be.released && be.returnItem && !be.original.isEmpty) { be.released=true;popResource(level,pos,be.device());be.original=ItemStack.EMPTY }
        }
        super.onRemove(state,level,pos,next,moving)
    }
    companion object {
        val FRAME:VoxelShape=Shapes.or(Block.box(0.0,14.0,0.0,16.0,16.0,16.0),*listOf(0.0 to 0.0,14.0 to 0.0,0.0 to 14.0,14.0 to 14.0).map { (x,z)->Block.box(x,0.0,z,x+2,14.0,z+2) }.toTypedArray())
    }
}
/** Retains the legacy extrusion_gun registry ID so existing inventories and turtle upgrades survive. */
class PrinterItem(properties:Item.Properties):BlockItem(Architect.PRINTER.get(),properties) {
    override fun onItemUseFirst(stack:ItemStack,ctx:UseOnContext):InteractionResult {
        val actor=ctx.player ?: return InteractionResult.PASS
        // Run through NeoForge's normal placement transaction before Creative's outer count restore.
        return if(actor.isCreative && !actor.isShiftKeyDown) stack.useOn(ctx) else InteractionResult.PASS
    }
    override fun use(level:Level,player:Player,hand:InteractionHand):InteractionResultHolder<ItemStack> {
        if(player is ServerPlayer) Network.open(player,"printerStorage")
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide)
    }
    override fun useOn(ctx:UseOnContext):InteractionResult {
        val actor=ctx.player ?: return InteractionResult.FAIL
        if(actor.isShiftKeyDown) { if(actor is ServerPlayer) Network.open(actor,"printerStorage");return InteractionResult.sidedSuccess(ctx.level.isClientSide) }
        if(!ctx.level.isClientSide) PrinterStack.id(ctx.itemInHand)
        val saved=ctx.itemInHand.copyWithCount(1);val target=net.minecraft.world.item.context.BlockPlaceContext(ctx).clickedPos
        val result=super.useOn(ctx)
        if(result.consumesAction() && actor is ServerPlayer) {
            val be=ctx.level.getBlockEntity(target) as? PrinterEntity ?: return result
            be.original=saved;be.owner=actor.uuid.toString();be.session=UUID.randomUUID().toString();be.returnItem=true
            if(actor.isCreative) ctx.itemInHand.shrink(1)
            be.preview=PrinterStack.form(saved);be.surfaces=PrinterStack.surfaces(saved);be.turn=Math.floorMod(PrinterStack.tag(saved).getInt("turn"),4)
            val slots=PrinterStack.contents(saved,ctx.level.registryAccess());repeat(7) { be.cartridges.setItem(it,slots[it]) };be.changed()
            PrinterService.open(actor,be,false)
        }
        return result
    }
    override fun appendHoverText(stack:ItemStack,context:TooltipContext,tooltip:MutableList<Component>,flag:TooltipFlag) {
        tooltip+=Component.translatable("item.copycat_architect.printer.place")
        tooltip+=Component.translatable("item.copycat_architect.printer.memory")
    }
}
class CompositeFormItem(properties:Item.Properties):Item(properties) {
    override fun useOn(ctx:UseOnContext):InteractionResult {
        val player=ctx.player ?: return InteractionResult.FAIL
        if(ctx.level.isClientSide) return InteractionResult.SUCCESS
        return try {
            val form=Geometry.validate(Json.decode<Form>(PrinterStack.tag(ctx.itemInHand).getString("form_definition")))
            val pos=if(ctx.level.getBlockState(ctx.clickedPos).canBeReplaced()) ctx.clickedPos else ctx.clickedPos.relative(ctx.clickedFace)
            val surfaces=PrinterStack.surfaces(ctx.itemInHand);val turn=PrinterStack.tag(ctx.itemInHand).getInt("turn")
            val placed=PlacementService.place(ctx.level as ServerLevel,pos,Placement(parts=Geometry.rotate(form.parts,turn),material="",groups=form.groups,surfaces=surfaces),player.inventory,player,"saved",player.isCreative)
            if(placed && !player.isCreative) ctx.itemInHand.shrink(1)
            InteractionResult.SUCCESS
        } catch(e:Exception) { player.displayClientMessage(Component.literal(e.message.orEmpty()),true);InteractionResult.FAIL }
    }
    companion object {
        fun stack(form:Form,materials:Map<String,String> = emptyMap(),turn:Int=0):ItemStack=ItemStack(Architect.COMPOSITE_ITEM.get()).also {
            PrinterStack.update(it) { tag -> tag.putString("form_definition",Json.encode(form));tag.putString("surfaces",Json.encode(materials));tag.putInt("turn",turn) }
            it.set(DataComponents.CUSTOM_NAME,Component.literal(form.name))
        }
    }
}
object PrinterService {
    private const val ACTIVE="copycat_architect_printer"
    fun active(player:ServerPlayer):PrinterEntity? {
        val tag=player.persistentData.getCompound(ACTIVE)
        if(tag.getString("dimension")!=player.level().dimension().location().toString()) return null
        val pos=BlockPos.of(tag.getLong("position"));if(!player.serverLevel().hasChunkAt(pos) || pos.distToCenterSqr(player.position())>100) return null
        return (player.serverLevel().getBlockEntity(pos) as? PrinterEntity)?.takeIf { !it.released && it.owner==player.uuid.toString() && it.session==tag.getString("session") }
    }
    fun open(player:ServerPlayer,be:PrinterEntity,storage:Boolean) {
        require(be.owner==player.uuid.toString() && !be.released) { "This printer belongs to another player" }
        player.persistentData.put(ACTIVE,CompoundTag().apply { putLong("position",be.blockPos.asLong());putString("dimension",player.level().dimension().location().toString());putString("session",be.session) })
        Network.open(player,if(storage) "printerStorage" else "printer")
    }
    fun held(player:Player):ItemStack=when { player.mainHandItem.`is`(Architect.GUN.get())->player.mainHandItem;player.offhandItem.`is`(Architect.GUN.get())->player.offhandItem;else->ItemStack.EMPTY }
    private fun inventory(player:ServerPlayer,be:PrinterEntity?=active(player)):Pair<ItemStack,Container> {
        if(be!=null) return be.original to be.cartridges
        val stack=held(player);require(!stack.isEmpty) { "Hold or place your block printer" };PrinterStack.id(stack)
        val container=SimpleContainer(7);val slots=PrinterStack.contents(stack,player.registryAccess());repeat(7) { container.setItem(it,slots[it]) }
        container.addListener { PrinterStack.contents(stack,container,player.registryAccess()) };return stack to container
    }
    fun view(player:ServerPlayer):PrinterView? {
        val be=active(player);if(be==null && held(player).isEmpty) return null
        val (stack,inv)=inventory(player,be)
        return PrinterView(be?.blockPos?.let(PlacementService::local) ?: P(),be?.session.orEmpty(),be?.preview ?: PrinterStack.form(stack),be?.surfaces ?: PrinterStack.surfaces(stack),be?.turn ?: Math.floorMod(PrinterStack.tag(stack).getInt("turn"),4),
            (0 until 7).map { val s=inv.getItem(it);if(s.isEmpty) CartridgeView() else CartridgeView(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.item).toString(),Economy.units(s),Economy.isCreativeCartridge(s)) },be!=null)
    }
    fun update(player:ServerPlayer,c:Command) {
        val be=active(player) ?: error("Place your printer first")
        require(c.key==be.session && c.turn in 0..3) { "Printer session expired" }
        accessible(player.serverLevel(),be,player)
        val draft=Json.decode<Form>(c.payload)
        draft.graph?.let { GraphCompiler.structure(it,draft.groups) }
        val form=runCatching { Geometry.validate(draft) }.getOrElse { if(draft.graph!=null) draft.copy(parts=emptyList()) else throw it }
        require(c.surfaces.keys.all { key -> form.groups.any { it.id==key } })
        c.surfaces.values.filter(String::isNotEmpty).forEach { Economy.state(it) }
        be.preview=form;be.surfaces=c.surfaces;be.turn=c.turn;be.changed()
    }
    private fun give(player:ServerPlayer,be:PrinterEntity,stack:ItemStack) {
        if(be.returnItem) { if(!player.inventory.add(stack)) player.drop(stack,false) }
        else {
            val existing=(0 until player.inventory.containerSize).map { player.inventory.getItem(it) }.firstOrNull {
                it.`is`(Architect.GUN.get()) && PrinterStack.id(it)==PrinterStack.id(stack)
            }
            if(existing!=null) existing.set(DataComponents.CUSTOM_DATA,stack.get(DataComponents.CUSTOM_DATA)!!)
            else if(!player.inventory.add(stack)) player.drop(stack,false)
        }
        player.persistentData.remove(ACTIVE)
    }
    private fun accessible(level:ServerLevel,be:PrinterEntity,actor:Player) {
        require(be.level===level && level.getBlockEntity(be.blockPos)===be && !be.released) { "Printer unavailable" }
        require(be.owner==actor.uuid.toString() && be.blockPos.distToCenterSqr(actor.position())<=100 && level.mayInteract(actor,be.blockPos) &&
            actor.mayUseItemAt(be.blockPos,Direction.UP,be.original)) { "Printer access denied" }
    }
    fun cancelDirect(level:ServerLevel,be:PrinterEntity,actor:Player):ItemStack {
        accessible(level,be,actor);val stack=be.device();be.released=true
        if(!level.removeBlock(be.blockPos,false)) { be.released=false;error("World rejected printer pickup") }
        be.original=ItemStack.EMPTY;return stack
    }
    fun cancel(player:ServerPlayer,session:String) {
        val be=active(player) ?: return;require(be.session==session)
        val stack=cancelDirect(player.serverLevel(),be,player);give(player,be,stack)
    }
    /** One authoritative transaction shared by the player screen and the turtle peripheral. */
    fun printDirect(level:ServerLevel,be:PrinterEntity,inventory:Container,actor:Player,form:Form,surfaces:Map<String,String>,turn:Int):ItemStack {
        accessible(level,be,actor);require(turn in 0..3)
        val checked=Geometry.validate(form);require(surfaces.keys.all { id -> checked.groups.any { it.id==id } })
        surfaces.values.filter(String::isNotEmpty).forEach { Economy.state(it) }
        be.preview=checked;be.surfaces=surfaces;be.turn=turn
        val backup=be.saveWithoutMetadata(level.registryAccess());be.released=true
        try {
            PlacementService.place(level,be.blockPos,Placement(parts=Geometry.rotate(checked.parts,turn),material="",groups=checked.groups,surfaces=surfaces),CompoundContainer(be.cartridges,inventory),actor,"printer:${be.session}",actor.isCreative,replaceable=Architect.PRINTER.get())
            return be.device().also { be.original=ItemStack.EMPTY }
        } catch(e:Exception) {
            (level.getBlockEntity(be.blockPos) as? PrinterEntity)?.let { restored -> restored.loadWithComponents(backup,level.registryAccess());restored.released=false;restored.changed() }
            be.released=false;throw e
        }
    }
    fun record(library:Library,form:Form,surfaces:Map<String,String>,turn:Int) {
        library.history.add(0,PrintRecord(form=form,surfaces=surfaces,turn=turn,time=System.currentTimeMillis()))
        while(library.history.size>24 || library.history.isNotEmpty() && (Json.encode(library.history).length>80000 || Json.encode(library).length>180000)) library.history.removeLast()
    }
    fun print(player:ServerPlayer,c:Command,library:Library):Boolean {
        update(player,c);val be=active(player) ?: error("Printer unavailable");val form=be.preview ?: error("No print definition")
        val stack=printDirect(player.serverLevel(),be,player.inventory,player,form,be.surfaces,be.turn)
        give(player,be,stack);record(library,form,be.surfaces,be.turn);return true
    }
    fun cartridge(player:ServerPlayer,slot:Int,inventorySlot:Int) {
        require(slot in 0..6);val be=active(player)
        if(be!=null) accessible(player.serverLevel(),be,player)
        val (_,container)=inventory(player,be)
        if(inventorySlot in 0 until player.inventory.containerSize) {
            val source=player.inventory.getItem(inventorySlot);require(Economy.isCartridge(source)) { "Choose a cartridge from your inventory" }
            val old=container.getItem(slot).copy();container.setItem(slot,source.copyWithCount(1));source.shrink(1)
            if(!old.isEmpty && !player.inventory.add(old)) player.drop(old,false)
        } else { val old=container.removeItemNoUpdate(slot);if(!old.isEmpty && !player.inventory.add(old)) player.drop(old,false);container.setChanged() }
        be?.changed();player.inventory.setChanged()
    }
    fun load(player:ServerPlayer,form:Form,surfaces:Map<String,String>,turn:Int) {
        val be=active(player)
        if(be!=null) accessible(player.serverLevel(),be,player)
        val checked=Geometry.validate(form)
        if(be!=null) { be.preview=checked;be.surfaces=surfaces;be.turn=turn;be.changed() }
        else PrinterStack.draft(inventory(player).first,checked,surfaces,turn)
    }
}
