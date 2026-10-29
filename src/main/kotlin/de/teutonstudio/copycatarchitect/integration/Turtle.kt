package de.teutonstudio.copycatarchitect.integration

import dan200.computercraft.api.turtle.*
import dan200.computercraft.api.upgrades.UpgradeType
import dan200.computercraft.api.peripheral.*
import dan200.computercraft.api.lua.*
import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.*
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.common.util.BlockSnapshot
import java.util.UUID

class ArchitectUpgrade:AbstractTurtleUpgrade(TurtleUpgradeType.PERIPHERAL,Component.translatable("upgrade.copycat_architect.gun"),ItemStack(Architect.GUN.get())) {
    override fun getType():UpgradeType<out ITurtleUpgrade> = Architect.UPGRADE.get()
    override fun createPeripheral(turtle:ITurtleAccess,side:TurtleSide):IPeripheral=ArchitectPeripheral(turtle)
}
class ArchitectPeripheral(private val turtle:ITurtleAccess):IPeripheral {
    override fun getType()="copycat_architect"
    override fun equals(other:IPeripheral?)=other is ArchitectPeripheral && other.turtle===turtle
    private fun table(x:Any):Any=Json.gson.fromJson(Json.encode(x),Any::class.java)
    private fun queue(action:(ITurtleAccess,ServerLevel)->Any?):MethodResult = turtle.executeCommand { t ->
        try {
            require(!t.isRemoved) { "Turtle was removed" }
            val value=action(t,t.level as ServerLevel)
            TurtleCommandResult.success(if(value==null) emptyArray() else arrayOf(table(value)))
        } catch(e:Exception) { TurtleCommandResult.failure(e.message?.take(250) ?: "Invalid architect command") }
    }
    private fun data(level:ServerLevel)=ArchitectData.get(level)
    private fun library(t:ITurtleAccess,level:ServerLevel)=data(level).library(t.owningPlayer?.id ?: error("Turtle must have an owner"))
    private fun job(t:ITurtleAccess,level:ServerLevel,computer:IComputerAccess,id:String):Job {
        require(id.length<=64)
        val j=data(level).db.jobs.firstOrNull { it.id==id && it.owner==t.owningPlayer?.id.toString() && it.dimension==level.dimension().location().toString() } ?: error("Job unavailable to this turtle")
        require(j.computer==computer.id) { "Job is assigned to a different turtle" };return j
    }
    @LuaFunction fun pose():MethodResult=queue { t,_ -> mapOf("x" to t.position.x,"y" to t.position.y,"z" to t.position.z,"facing" to t.direction.get2DDataValue(),"fuel" to t.fuelLevel) }
    @LuaFunction fun forms():MethodResult=queue { t,l -> library(t,l).forms.map { (key,f) -> mapOf("key" to key,"name" to f.name,"version" to f.version,"units" to Extrusion.cost(f.parts,f.groups),"groups" to f.groups) } }
    private fun target(t:ITurtleAccess,direction:String)=t.position.relative(when(direction) { "front"->t.direction;"up"->Direction.UP;"down"->Direction.DOWN;else->error("Direction must be front, up or down") })
    private fun actor(t:ITurtleAccess,level:ServerLevel)=FakePlayerFactory.get(level,t.owningPlayer ?: error("Missing owner")).also {
        it.setPos(t.position.x+0.5,t.position.y.toDouble(),t.position.z+0.5)
    }
    private fun printer(t:ITurtleAccess,level:ServerLevel,direction:String,session:String):PrinterEntity {
        require(session.isNotEmpty() && session.length<=64) { "Invalid printer session" }
        val position=target(t,direction)
        require(level.isInWorldBounds(position) && level.hasChunkAt(position) && t.position.distManhattan(position)==1) { "Adjacent printer target is unloaded" }
        val be=level.getBlockEntity(position) as? PrinterEntity ?: error("No adjacent printer")
        require(!be.released && be.owner==t.owningPlayer?.id.toString() && be.session==session && be.original.`is`(Architect.GUN.get())) { "Printer owner or session mismatch" }
        val user=actor(t,level)
        require(level.mayInteract(user,position) && user.mayUseItemAt(position,Direction.UP,be.original)) { "Printer access denied" }
        return be
    }
    private fun slots(be:PrinterEntity)=(0 until 7).map { i ->
        val stack=be.cartridges.getItem(i)
        mapOf("slot" to i+1,"item" to if(stack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(stack.item).toString(),"units" to Economy.units(stack),"creative" to Economy.isCreativeCartridge(stack))
    }
    private fun printerView(be:PrinterEntity)=mapOf("session" to be.session,"position" to PlacementService.local(be.blockPos),"formJson" to Json.encode(be.preview ?: error("No printer preview")),"paletteJson" to Json.encode(be.surfaces),"rotation" to be.turn,"slots" to slots(be))
    private fun checkedForm(formJson:String,paletteJson:String,rotation:Int):Pair<Form,Map<String,String>> {
        require(formJson.length in 1..28000 && rotation in 0..3) { "Printer form is too large or rotation is invalid" }
        val form=Geometry.validate(Json.decode<Form>(formJson));val palette=Json.materials(paletteJson)
        require(palette.keys.all { id -> form.groups.any { it.id==id } }) { "Unknown print material group" }
        palette.values.filter(String::isNotEmpty).forEach { Economy.state(it) }
        return form to palette
    }
    private fun emptySlot(inventory:Container)=(0 until inventory.containerSize).firstOrNull { inventory.getItem(it).isEmpty }
        ?: error("Keep a free turtle inventory slot for the returned printer")
    /** Reserve the empty device-return slot while zinc change is allocated by the shared debit. */
    private class PaymentInventory(private val inventory:Container,private val reserved:Int):Container by inventory {
        private fun actual(slot:Int):Int { require(slot in 0 until containerSize);return if(slot<reserved) slot else slot+1 }
        override fun getContainerSize()=inventory.containerSize-1
        override fun getItem(slot:Int)=inventory.getItem(actual(slot))
        override fun setItem(slot:Int,stack:ItemStack)=inventory.setItem(actual(slot),stack)
        override fun removeItem(slot:Int,count:Int)=inventory.removeItem(actual(slot),count)
        override fun removeItemNoUpdate(slot:Int)=inventory.removeItemNoUpdate(actual(slot))
        override fun canPlaceItem(slot:Int,stack:ItemStack)=inventory.canPlaceItem(actual(slot),stack)
    }
    @LuaFunction fun printerBegin(direction:String):MethodResult=queue { t,level ->
        val position=target(t,direction);val user=actor(t,level);library(t,level)
        require(level.isInWorldBounds(position) && level.hasChunkAt(position)) { "Target chunk is not loaded" }
        require(level.getBlockState(position).isAir) { "Printer target must be empty" }
        val slot=(0 until t.inventory.containerSize).firstOrNull { t.inventory.getItem(it).`is`(Architect.GUN.get()) }
            ?: error("Put an additional block printer in the turtle inventory; the equipped upgrade is not a placeable printer")
        val device=t.inventory.getItem(slot).copyWithCount(1);PrinterStack.id(device)
        val form=PrinterStack.form(device);val surfaces=PrinterStack.surfaces(device);val rotation=Math.floorMod(PrinterStack.tag(device).getInt("turn"),4)
        val checked=checkedForm(Json.encode(form),Json.encode(surfaces),rotation)
        val cartridges=PrinterStack.contents(device,level.registryAccess())
        require(cartridges.all { it.isEmpty || Economy.isCartridge(it) && it.count==1 }) { "Invalid printer cartridge contents" }
        require(level.mayInteract(user,position) && user.mayUseItemAt(position,Direction.UP,device)) { "Printer placement denied" }
        require(level.getEntities(user,AABB(position)).none { it.isAlive && !it.isSpectator }) { "Printer target occupied by an entity" }
        val snapshot=BlockSnapshot.create(level.dimension(),level,position)
        val state=Architect.PRINTER.get().defaultBlockState();require(state.canSurvive(level,position)) { "Printer needs valid support" }
        require(level.setBlock(position,state,3)) { "World rejected printer placement" }
        try {
            require(!net.neoforged.neoforge.event.EventHooks.onBlockPlace(user,snapshot,Direction.UP)) { "Printer placement denied by protection" }
            val be=level.getBlockEntity(position) as? PrinterEntity ?: error("Printer unavailable")
            be.original=device;be.owner=user.uuid.toString();be.session=UUID.randomUUID().toString();be.returnItem=true;be.released=false
            be.preview=checked.first;be.surfaces=checked.second;be.turn=rotation
            repeat(7) { be.cartridges.setItem(it,cartridges[it]) };be.changed()
            t.inventory.getItem(slot).shrink(1);t.inventory.setChanged()
            printerView(be)
        } catch(e:Exception) {
            (level.getBlockEntity(position) as? PrinterEntity)?.released=true
            snapshot.restore();throw e
        }
    }
    @LuaFunction fun printerPreview(direction:String,session:String,formJson:String,paletteJson:String,rotation:Int):MethodResult=queue { t,level ->
        val be=printer(t,level,direction,session);val (form,palette)=checkedForm(formJson,paletteJson,rotation)
        val placement=Placement(parts=Geometry.rotate(form.parts,rotation),material="",groups=form.groups,surfaces=palette)
        be.preview=form;be.surfaces=palette;be.turn=rotation;be.changed()
        mapOf("session" to session,"units" to Extrusion.cost(form.parts,form.groups),"surfaces" to placement.surfaceItems(),"groups" to form.groups,"rotation" to rotation)
    }
    @LuaFunction fun printerPrint(direction:String,session:String):MethodResult=queue { t,level ->
        val be=printer(t,level,direction,session);val form=be.preview ?: error("No print definition");val lib=library(t,level)
        val slot=emptySlot(t.inventory);val palette=be.surfaces.toMap();val rotation=be.turn
        val device=PrinterService.printDirect(level,be,PaymentInventory(t.inventory,slot),actor(t,level),form,palette,rotation)
        t.inventory.setItem(slot,device);t.inventory.setChanged()
        PrinterService.record(lib,form,palette,rotation);data(level).setDirty()
        mapOf("printed" to true,"position" to PlacementService.local(be.blockPos),"printerSlot" to slot+1,"historyId" to lib.history.firstOrNull()?.id.orEmpty())
    }
    @LuaFunction fun printerCancel(direction:String,session:String):MethodResult=queue { t,level ->
        val be=printer(t,level,direction,session);val slot=emptySlot(t.inventory)
        val device=PrinterService.cancelDirect(level,be,actor(t,level));t.inventory.setItem(slot,device);t.inventory.setChanged()
        mapOf("cancelled" to true,"printerSlot" to slot+1)
    }
    @LuaFunction fun printerSlots(direction:String,session:String):MethodResult=queue { t,level -> slots(printer(t,level,direction,session)) }
    @LuaFunction fun printerLoadCartridge(direction:String,session:String,printerSlot:Int,turtleSlot:Int):MethodResult=queue { t,level ->
        val be=printer(t,level,direction,session);require(printerSlot in 1..7 && turtleSlot in 0..t.inventory.containerSize) { "Printer slots are 1–7; turtle slots are 1–16, or 0 to unload" }
        val inventory=(0 until t.inventory.containerSize).map { t.inventory.getItem(it).copy() }.toMutableList()
        val index=printerSlot-1;val previous=be.cartridges.getItem(index).copy()
        val incoming=if(turtleSlot==0) ItemStack.EMPTY else inventory[turtleSlot-1].let { source ->
            require(Economy.isCartridge(source)) { "Choose a cartridge from the turtle inventory" }
            source.copyWithCount(1).also { source.shrink(1) }
        }
        if(!previous.isEmpty) {
            val candidates=(listOf(turtleSlot-1)+(0 until inventory.size)).filter { it in inventory.indices }.distinct()
            val destination=candidates.firstOrNull { i -> val stack=inventory[i]
                t.inventory.canPlaceItem(i,previous) && (stack.isEmpty || ItemStack.isSameItemSameComponents(stack,previous) && stack.count+previous.count<=minOf(stack.maxStackSize,t.inventory.maxStackSize))
            } ?: error("No turtle inventory space to return the previous cartridge")
            if(inventory[destination].isEmpty) inventory[destination]=previous else inventory[destination].grow(previous.count)
        }
        inventory.forEachIndexed { i,stack -> t.inventory.setItem(i,stack) };t.inventory.setChanged()
        be.cartridges.setItem(index,incoming);be.changed();slots(be)
    }
    @LuaFunction fun printerHistory():MethodResult=queue { t,level -> library(t,level).history.map { record ->
        mapOf("id" to record.id,"name" to record.form.name,"time" to record.time,"units" to Extrusion.cost(record.form.parts,record.form.groups),"formJson" to Json.encode(record.form),"paletteJson" to Json.encode(record.surfaces),"rotation" to record.turn)
    } }
    @LuaFunction fun printerSaveHistory(id:String,name:String):MethodResult=queue { t,level ->
        val lib=library(t,level);val key=lib.savePrintHistory(id,name)
        data(level).setDirty();mapOf("key" to key,"name" to lib.forms.getValue(key).name)
    }
    @LuaFunction fun inspect(direction:String):MethodResult=queue { t,l ->
        val pos=target(t,direction);require(l.hasChunkAt(pos)) { "Target unloaded" }
        val be=l.getBlockEntity(pos) as? CompositeEntity
        mapOf("air" to l.getBlockState(pos).isAir,"block" to net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(l.getBlockState(pos).block).toString(),"parts" to (be?.parts ?: emptyList()),"material" to (be?.material ?: ""),"groups" to (be?.groups ?: emptyList()),"surfaces" to (be?.surfaces ?: emptyMap()))
    }
    @LuaFunction fun cost(key:String):MethodResult=queue { t,l ->
        require(key.length<=80);val f=library(t,l).forms[key] ?: error("Unknown form version")
        mapOf("units" to Extrusion.cost(f.parts,f.groups),"surfaceBlocks" to 1,"groups" to f.groups)
    }
    @LuaFunction fun place(direction:String,key:String,material:String,rotation:Int):MethodResult=queue { t,l ->
        require(rotation in 0..3 && key.length<=80 && material.length<=80)
        val f=library(t,l).forms[key] ?: error("Unknown form version")
        val actor=FakePlayerFactory.get(l,t.owningPlayer ?: error("Missing owner"));actor.setPos(t.position.x+0.5,t.position.y.toDouble(),t.position.z+0.5)
        val p=Placement(P(),Geometry.rotate(f.parts,rotation),material,groups=f.groups)
        mapOf("placed" to PlacementService.place(l,target(t,direction),p,t.inventory,actor,"lua"))
    }
    @LuaFunction fun requirements(key:String,paletteJson:String):MethodResult=queue { t,l ->
        require(key.length<=80);val f=library(t,l).forms[key] ?: error("Unknown form version")
        val p=Placement(parts=f.parts,groups=f.groups,surfaces=Json.materials(paletteJson))
        require(p.surfaces.keys.all { id -> f.groups.any { it.id==id } });p.surfaceItems().keys.forEach { Economy.state(it) }
        mapOf("units" to Extrusion.cost(p.parts,p.groups),"surfaces" to p.surfaceItems())
    }
    @LuaFunction fun placeGrouped(direction:String,key:String,paletteJson:String,rotation:Int):MethodResult=queue { t,l ->
        require(rotation in 0..3 && key.length<=80);val f=library(t,l).forms[key] ?: error("Unknown form version")
        val p=Placement(parts=Geometry.rotate(f.parts,rotation),groups=f.groups,surfaces=Json.materials(paletteJson))
        val actor=FakePlayerFactory.get(l,t.owningPlayer ?: error("Missing owner"));actor.setPos(t.position.x+0.5,t.position.y.toDouble(),t.position.z+0.5)
        mapOf("placed" to PlacementService.place(l,target(t,direction),p,t.inventory,actor,"lua"))
    }
    @LuaFunction fun jobs():MethodResult=queue { t,l -> data(l).db.jobs.filter { it.owner==t.owningPlayer?.id.toString() && it.dimension==l.dimension().location().toString() && it.state!="cancelled" }.map { mapOf("id" to it.id,"name" to it.name,"state" to it.state,"computer" to it.computer,"done" to it.index,"total" to it.plan.placements.size) } }
    @LuaFunction fun claim(computer:IComputerAccess,id:String):MethodResult=queue { t,l ->
        val j=data(l).db.jobs.firstOrNull { it.id==id && it.owner==t.owningPlayer?.id.toString() && it.dimension==l.dimension().location().toString() } ?: error("Job not found")
        require(j.computer==-1 || j.computer==computer.id) { "Job already assigned" }
        if(j.computer==-1) require(PlacementService.local(t.position)==j.dock) { "First claim requires the turtle at its configured dock" }
        require(j.state!="cancelled");j.computer=computer.id;data(l).setDirty()
        val (a,b)=Jobs.bounds(j)
        mapOf("id" to j.id,"dock" to j.dock,"min" to a,"max" to b,"state" to j.state,"index" to j.index)
    }
    @LuaFunction fun next(computer:IComputerAccess,id:String):MethodResult=queue { t,l ->
        val j=job(t,l,computer,id)
        val p=j.plan.placements.getOrNull(j.index)
        mapOf("state" to j.state,"message" to j.message,"index" to j.index,"total" to j.plan.placements.size,"target" to (p ?: mapOf<String,String>()),"units" to (if(p!=null && p.block.isEmpty()) frozenCost(j,p) else 0))
    }
    private fun frozenCost(j:Job,p:Placement)=Extrusion.cost(p.parts,p.groups,j.recipeOutputs)
    @LuaFunction fun build(computer:IComputerAccess,id:String,index:Int):MethodResult=queue { t,l ->
        val j=job(t,l,computer,id);require(j.state=="running") { "Job ${j.state}: ${j.message}" }
        if(index<j.index) return@queue mapOf("done" to true,"repeated" to true)
        require(index==j.index) { "Out-of-order build action" }
        val p=j.plan.placements.getOrNull(index) ?: error("Job already complete")
        val target=PlacementService.world(p.pos)
        require(t.position.below()==target) { "Work position must be immediately above target" }
        val actor=FakePlayerFactory.get(l,t.owningPlayer ?: error("Missing owner"));actor.setPos(t.position.x+0.5,t.position.y.toDouble(),t.position.z+0.5)
        try {
            PlacementService.place(l,target,p,t.inventory,actor,"${j.id}:$index",rawCost=if(p.block.isEmpty()) frozenCost(j,p) else 0)
            j.index++;j.message="Built ${j.index}/${j.plan.placements.size}"
            if(j.index==j.plan.placements.size) j.state="complete"
            data(l).setDirty();mapOf("done" to true,"index" to j.index)
        } catch(e:Exception) { j.message=e.message ?: "Blocked";data(l).setDirty();throw e }
    }
    @LuaFunction fun resupply(computer:IComputerAccess,id:String):MethodResult=queue { t,l ->
        val j=job(t,l,computer,id);mapOf("items" to Resupply.load(t,l,j))
    }
    @LuaFunction fun report(computer:IComputerAccess,id:String,message:String):MethodResult=queue { t,l ->
        require(message.length<=200);val j=job(t,l,computer,id);j.message=message;if(message.startsWith("Paused:")) j.state="blocked";data(l).setDirty();mapOf("accepted" to true)
    }
}
