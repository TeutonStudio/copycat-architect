package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.*
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Renderable
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Physical cartridge management and persistent print storage, including a 427 × 240 layout. */
class PrinterStorageScreen(private val parent:Screen?=null):EditorScreen(tr("printer_storage")) {
    private data class Entry(val key:String,val form:Form,val surfaces:Map<String,String>,val turn:Int,val time:Long=0)
    private data class Rect(val x:Int,val y:Int,val w:Int,val h:Int) {
        fun contains(mx:Double,my:Double)=mx>=x && my>=y && mx<x+w && my<y+h
    }
    private var saved=false
    private var selected=""
    private var first=0
    private var inventorySlot=-1
    private var hint=""
    private var observed:Snapshot?=null
    private var previewForm:Form?=null
    private var preview:Result<Form>?=null
    private var hardwareForm:Form?=null
    private var hardwarePreview:Result<Form>?=null
    private val compact get()=width<640
    private val leftWidth get()=if(compact) 188 else minOf(288,(width*0.36).roundToInt())
    private val hardware get()=Rect(8,30,leftWidth,height-50)
    private val archive get()=Rect(hardware.x+hardware.w+8,30,width-hardware.w-32,height-50)
    private val previewWidth get()=if(compact) 88 else minOf(160,archive.w/3)
    private val list get()=Rect(archive.x+10,archive.y+36,(archive.w-previewWidth-26).coerceAtLeast(72),(archive.h-96).coerceAtLeast(28))
    private val rowHeight get()=if(compact) 24 else 30
    private val rows get()=(list.h/rowHeight).coerceAtLeast(1)
    private val view get()=ClientState.snapshot.printer
    private fun entries():List<Entry> = if(saved) ClientState.snapshot.library.let { library -> library.forms.entries.map {
        val settings=library.printSettings[it.key] ?: PrintSettings();Entry(it.key,it.value,settings.surfaces,settings.turn)
    } }
        else ClientState.snapshot.library.history.map { Entry(it.id,it.form,it.surfaces,it.turn,it.time) }
    private fun selection():Entry?=entries().firstOrNull { it.key==selected }
    private fun chosen():Entry?=selection() ?: view?.let { Entry("",it.form,it.surfaces,it.turn) }
    private fun cartridgeRect(slot:Int):Rect {
        val gap=if(compact) 24 else 34
        return Rect(hardware.x+(hardware.w-7*gap+4)/2+slot*gap,hardware.y+27,if(compact) 22 else 30,if(compact) 22 else 30)
    }
    private fun inventoryRect(slot:Int):Rect {
        val pitch=if(compact) 18 else 24
        val column=if(slot<9) slot else (slot-9)%9
        val row=if(slot<9) 3 else (slot-9)/9
        val startY=hardware.y+if(compact) 106 else maxOf(128,hardware.h-4*pitch-20)
        return Rect(hardware.x+(hardware.w-9*pitch)/2+column*pitch,startY+row*pitch+if(row==3) 4 else 0,pitch,pitch)
    }
    override fun init() { refresh() }
    /** Called by the snapshot receiver; also checked in tick for server-driven inventory changes. */
    fun refresh() {
        if(minecraft==null) return
        (parent as? ShapeScreen)?.loadPrinterDraft(ClientState.snapshot.printer)
        observed=ClientState.snapshot
        val values=entries()
        if(values.none { it.key==selected }) selected=values.firstOrNull()?.key.orEmpty()
        first=first.coerceIn(0,(values.size-rows).coerceAtLeast(0))
        clearWidgets();resetDropdown()
        button(width-76,3,68,"close") { onClose() }
        if(view?.placed==true) button(width-157,3,77,"ps_graph") { ClientState.send(Command("printerGraph")) }
        val a=archive
        val half=(a.w-24)/2
        (button(a.x+8,a.y+10,half,"ps_history") { if(saved) { saved=false;selected="";first=0;refresh() } } as EditorButton).selected=!saved
        (button(a.x+12+half,a.y+10,half,"ps_saved") { if(!saved) { saved=true;selected="";first=0;refresh() } } as EditorButton).selected=saved
        val controlY=a.y+a.h-47
        button(a.x+10,controlY,a.w-20,"ps_load") {
            selection()?.let { ClientState.send(Command("printerLoad",key=it.key));hint=ui("ps_loaded") }
        }.active=selection()!=null && view!=null
        button(a.x+10,controlY+23,a.w-20,"ps_save_named") {
            selection()?.let { entry -> EditorValueInput.text(tr("ps_save_named"),entry.form.name,maxLength=64) { name ->
                ClientState.send(Command("historySave",key=entry.key,payload=name))
            } }
        }.active=!saved && selection()!=null
    }
    override fun tick() {
        super.tick()
        if(observed!==ClientState.snapshot) refresh()
        val stack=minecraft?.player?.inventory?.let { if(inventorySlot in 0..35) it.getItem(inventorySlot) else ItemStack.EMPTY }
        if(stack==null || !Economy.isCartridge(stack)) inventorySlot=-1
    }
    override fun onClose() { minecraft?.setScreen(parent) }

    private fun panel(g:GuiGraphics,r:Rect,title:String) {
        g.fill(r.x,r.y,r.x+r.w,r.y+r.h,0xff242a2b.toInt())
        g.fill(r.x,r.y,r.x+r.w,r.y+2,0xffb8b9ad.toInt())
        g.fill(r.x,r.y,r.x+2,r.y+r.h,0xff8d968f.toInt())
        g.fill(r.x+r.w-2,r.y,r.x+r.w,r.y+r.h,0xff101718.toInt())
        g.fill(r.x,r.y+r.h-2,r.x+r.w,r.y+r.h,0xff101718.toInt())
        if(title.isNotEmpty()) label(g,title,r.x+10,r.y+10,0xffebd49b.toInt())
        for(x in listOf(r.x+4,r.x+r.w-7)) for(y in listOf(r.y+4,r.y+r.h-7)) {
            g.fill(x,y,x+3,y+3,0xffa48c5d.toInt());g.fill(x+1,y+1,x+2,y+2,0xff4a4536.toInt())
        }
    }
    private fun slot(g:GuiGraphics,r:Rect,stack:ItemStack,active:Boolean,enabled:Boolean,mx:Int,my:Int) {
        g.fill(r.x,r.y,r.x+r.w,r.y+r.h,if(active) 0xffd8b86c.toInt() else 0xff101718.toInt())
        g.fill(r.x+1,r.y+1,r.x+r.w-1,r.y+r.h-1,if(enabled) 0xff4c5550.toInt() else 0xff303936.toInt())
        g.fill(r.x+2,r.y+2,r.x+r.w-1,r.y+3,0xff252d2b.toInt())
        g.fill(r.x+2,r.y+r.h-2,r.x+r.w-1,r.y+r.h-1,0xffa1a59a.toInt())
        if(r.contains(mx.toDouble(),my.toDouble())) g.fill(r.x+1,r.y+1,r.x+r.w-1,r.y+r.h-1,0x387cc8ba)
        if(!stack.isEmpty) {
            val x=r.x+(r.w-16)/2;val y=r.y+(r.h-16)/2
            g.renderItem(stack,x,y);g.renderItemDecorations(font,stack,x,y)
        }
    }
    private fun stack(item:String)=ResourceLocation.tryParse(item)?.let { id -> if(BuiltInRegistries.ITEM.containsKey(id)) ItemStack(BuiltInRegistries.ITEM.get(id)) else ItemStack.EMPTY } ?: ItemStack.EMPTY
    private fun shortUnits(units:Int)=when { units>=1_000_000->"${units/1_000_000}M";units>=10_000->"${units/1000}k";else->units.toString() }
    private fun drawHardware(g:GuiGraphics,mx:Int,my:Int) {
        panel(g,hardware,ui("ps_cartridges"))
        for(i in 0..6) {
            val r=cartridgeRect(i);val contents=view?.slots?.getOrNull(i)
            slot(g,r,stack(contents?.item.orEmpty()),false,true,mx,my)
            val amount=when { contents==null || contents.item.isEmpty()->"·";contents.creative->"∞";else->shortUnits(contents.units) }
            label(g,amount,r.x+(r.w-font.width(amount))/2,r.y+r.h+3,if(contents?.creative==true) 0xffc9a9eb.toInt() else 0xffd8d4b9.toInt())
        }
        val instructions=ui(if(inventorySlot>=0) (if(compact) "ps_choose_target_compact" else "ps_choose_target") else (if(compact) "ps_choose_inventory_compact" else "ps_choose_inventory"))
        val textY=hardware.y+if(compact) 66 else 84
        g.drawWordWrap(font,Component.literal(instructions),hardware.x+10,textY,hardware.w-20,0xffa9b9ae.toInt())
        val invY=inventoryRect(9).y-12
        if(!compact) view?.let { printer ->
            val x=hardware.x+14;val y=hardware.y+124;val w=hardware.w-28;val h=invY-y-13
            if(h>=100) {
                g.fill(x,y,x+w,y+h,0xff182e30.toInt());g.renderOutline(x,y,w,h,0xff526e69.toInt())
                label(g,ui("ps_active_print"),x+8,y+9,0xffb7bb9e.toInt())
                label(g,font.plainSubstrByWidth(printer.form.name,w-16),x+8,y+22,0xffe4d9ad.toInt())
                if(hardwareForm!=printer.form) { hardwareForm=printer.form;hardwarePreview=runCatching { Geometry.validate(printer.form) } }
                hardwarePreview?.getOrNull()?.let { form ->
                    g.enableScissor(x+1,y+35,x+w-1,y+h-24)
                    Meshes.preview(g,x+w/2,y+35+(h-59)/2,minOf(w,h-59)*0.3f,yaw,Geometry.rotate(form.parts,printer.turn),material="",groups=form.groups,surfaces=printer.surfaces)
                    g.disableScissor()
                }
                val amount=if(printer.slots.any { it.creative }) "∞" else printer.slots.sumOf { it.units.toLong() }.toString()
                label(g,font.plainSubstrByWidth(ui("ps_available",amount),w-16),x+8,y+h-15,0xffcdb47c.toInt())
            }
        }
        label(g,ui("ps_inventory"),hardware.x+12,invY,0xffe4d6af.toInt())
        val inventory=minecraft?.player?.inventory
        for(i in 0..35) {
            val value=inventory?.getItem(i) ?: ItemStack.EMPTY
            slot(g,inventoryRect(i),value,i==inventorySlot,Economy.isCartridge(value),mx,my)
        }
    }
    private fun drawArchive(g:GuiGraphics,mx:Int,my:Int) {
        panel(g,archive,"")
        val area=list;val values=entries()
        g.fill(area.x-1,area.y-1,area.x+area.w+1,area.y+area.h+1,0xff121d20.toInt())
        g.enableScissor(area.x,area.y,area.x+area.w,area.y+area.h)
        if(values.isEmpty()) g.drawWordWrap(font,tr(if(saved) "ps_saved_empty" else "ps_history_empty"),area.x+5,area.y+6,area.w-10,0xff8faaa8.toInt())
        for(row in 0 until rows) {
            val entry=values.getOrNull(first+row) ?: break;val top=area.y+row*rowHeight
            val hovered=mx in area.x until area.x+area.w && my in top until top+rowHeight
            g.fill(area.x,top,area.x+area.w,top+rowHeight-1,if(entry.key==selected) 0xff3c5552.toInt() else if(hovered) 0xff30413e.toInt() else 0xff243330.toInt())
            if(entry.key==selected) g.fill(area.x,top,area.x+2,top+rowHeight-1,0xffd7b36d.toInt())
            label(g,font.plainSubstrByWidth(entry.form.name,area.w-12),area.x+6,top+4,0xffe9e3cb.toInt())
            val detail=if(entry.time>0) DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.time)) else ui("ps_version",entry.form.version)
            label(g,font.plainSubstrByWidth(detail,area.w-12),area.x+6,top+14,0xff92aaa0.toInt())
        }
        g.disableScissor()
        if(values.size>rows) {
            val thumb=(area.h.toDouble()*rows/values.size).roundToInt().coerceAtLeast(8)
            val top=area.y+((area.h-thumb).toDouble()*first/(values.size-rows)).roundToInt()
            g.fill(area.x+area.w-3,top,area.x+area.w-1,top+thumb,0xffb79b63.toInt())
        }
        val choice=chosen() ?: return
        val px=area.x+area.w+10;val pw=archive.x+archive.w-10-px
        val ph=area.h.coerceAtMost(if(compact) 78 else 150)
        g.fill(px,area.y,px+pw,area.y+ph,0xff152b30.toInt())
        g.renderOutline(px,area.y,pw,ph,0xff587a78.toInt())
        if(previewForm!=choice.form) { previewForm=choice.form;preview=runCatching { Geometry.validate(choice.form) } }
        preview?.getOrNull()?.let { checked ->
            g.enableScissor(px+1,area.y+1,px+pw-1,area.y+ph-1)
            Meshes.preview(g,px+pw/2,area.y+ph/2,minOf(pw,ph)*0.3f,yaw,Geometry.rotate(checked.parts,choice.turn),material="",groups=checked.groups,surfaces=choice.surfaces)
            g.disableScissor()
            if(!compact) {
                label(g,font.plainSubstrByWidth(ui("ps_raw_units",Extrusion.cost(checked.parts,checked.groups)),pw),px,area.y+ph+7,0xffd7b875.toInt())
                label(g,ui("ps_groups",checked.groups.size),px,area.y+ph+19,0xff9aafa6.toInt())
            }
        }
    }
    override fun render(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
        g.fill(0,0,width,height,0xff172322.toInt());g.fill(0,0,width,24,0xff354a49.toInt())
        label(g,font.plainSubstrByWidth(title.string,(width-175).coerceAtLeast(60)),10,8,0xfff0d49a.toInt())
        drawHardware(g,mx,my);drawArchive(g,mx,my)
        children().filterIsInstance<Renderable>().forEach { it.render(g,mx,my,delta) }
        val message=hint.ifEmpty { ClientState.snapshot.message }
        label(g,font.plainSubstrByWidth(message,width-16),8,height-12,0xffdac58c.toInt())
        var tooltip:List<Component>?=null
        for(i in 0..6) if(cartridgeRect(i).contains(mx.toDouble(),my.toDouble())) {
            val entry=view?.slots?.getOrNull(i)
            tooltip=listOf(tr("ps_slot",i+1),if(entry==null || entry.item.isEmpty()) tr("ps_slot_empty") else stack(entry.item).hoverName,
                if(entry?.creative==true) Component.translatable("item.copycat_architect.cartridge.infinite") else tr("ps_raw_units",entry?.units ?: 0),tr("ps_take_hint"))
        }
        val inventory=minecraft?.player?.inventory
        for(i in 0..35) if(inventoryRect(i).contains(mx.toDouble(),my.toDouble())) inventory?.getItem(i)?.takeIf { !it.isEmpty }?.let { value ->
            tooltip=listOf(value.hoverName,if(Economy.isCartridge(value)) tr("ps_select_cartridge") else tr("ps_only_cartridges"))
        }
        tooltip?.let { g.renderComponentTooltip(font,it,mx,my) }
    }
    override fun mouseClicked(mx:Double,my:Double,button:Int):Boolean {
        if(button==0 || button==1) {
            for(i in 0..6) if(cartridgeRect(i).contains(mx,my)) {
                if(view==null) return true
                val remove=button==1 || inventorySlot<0
                if(remove && view?.slots?.getOrNull(i)?.item.isNullOrEmpty()) { hint=ui("ps_choose_inventory");return true }
                ClientState.send(Command("printerCartridge",slot=i,inventorySlot=if(remove) -1 else inventorySlot))
                inventorySlot=-1;hint="";return true
            }
            for(i in 0..35) if(inventoryRect(i).contains(mx,my)) {
                val value=minecraft?.player?.inventory?.getItem(i) ?: ItemStack.EMPTY
                inventorySlot=if(Economy.isCartridge(value)) i else -1
                hint=ui(if(inventorySlot>=0) "ps_choose_target" else "ps_only_cartridges");return true
            }
            if(button==0 && list.contains(mx,my)) {
                entries().getOrNull(first+((my-list.y)/rowHeight).toInt())?.let { selected=it.key;refresh() }
                return true
            }
        }
        return super.mouseClicked(mx,my,button)
    }
    override fun mouseScrolled(mx:Double,my:Double,dx:Double,dy:Double):Boolean {
        if(archive.contains(mx,my)) { first=(first-if(dy>0) 1 else -1).coerceIn(0,(entries().size-rows).coerceAtLeast(0));return true }
        return super.mouseScrolled(mx,my,dx,dy)
    }
}
