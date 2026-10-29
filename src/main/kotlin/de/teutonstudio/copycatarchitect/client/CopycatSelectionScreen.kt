package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.BaseShape
import de.teutonstudio.copycatarchitect.domain.BaseShapes
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/** An inventory-shaped catalog; choosing an icon never moves a player's items. */
class CopycatSelectionScreen private constructor(
    private val parent:Screen?,
    private val current:BaseShape,
    private val apply:(BaseShape)->Unit
):Screen(tr("select_copycat")) {
    private val entries=BaseShapes.all
    private val stacks=entries.map { ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(it.source))) }
    private val slots=mutableListOf<SlotButton>()
    private var index=entries.indexOfFirst { it.id==current.id }.coerceAtLeast(0)
    private var keyboardSelection=false
    private var finished=false
    private val rowCount=((entries.size+COLUMNS-1)/COLUMNS).coerceAtLeast(1)
    private val inventoryHeight=17+rowCount*SLOT_SIZE
    private val detailOffset=(rowCount-1)*SLOT_SIZE
    private val panelHeight=142+detailOffset
    private val left get()=(width-PANEL_WIDTH)/2
    private val top get()=(height-panelHeight)/2

    override fun init() {
        slots.clear()
        entries.forEachIndexed { i,adapter ->
            slots+=addRenderableWidget(SlotButton(left+7+i%COLUMNS*SLOT_SIZE,top+17+i/COLUMNS*SLOT_SIZE,i,adapter,stacks[i]))
        }
        addRenderableWidget(Button.builder(tr("cancel")) { onClose() }
            .bounds(left+8,top+114+detailOffset,PANEL_WIDTH-16,20).build())
    }

    override fun setInitialFocus() { slots.getOrNull(index)?.let { setInitialFocus(it) } }

    override fun repositionElements() {
        slots.indexOfFirst { it.isFocused }.takeIf { it>=0 }?.let { index=it }
        super.repositionElements()
    }

    /** Stable screen-space targets for the native client smoke. */
    internal fun slotPoint(source:String,discCut:Int=0):Pair<Double,Double>?=slotPoint(BaseShapes.find(source,discCut))
    internal fun slotPoint(shape:BaseShape):Pair<Double,Double>? {
        val i=entries.indexOfFirst { it.id==shape.id }
        return if(i<0) null else Pair(left+16.0+i%COLUMNS*SLOT_SIZE,top+26.0+i/COLUMNS*SLOT_SIZE)
    }

    private fun choose(i:Int) {
        if(finished) return
        val shape=entries.getOrNull(i) ?: return
        finished=true
        // The editor's graph widget is disposed while another screen is open.
        // Restore its widgets before applying a document edit.
        minecraft?.setScreen(parent)
        apply(shape)
    }

    override fun onClose() {
        if(finished) return
        finished=true
        minecraft?.setScreen(parent)
    }

    override fun keyPressed(key:Int,scan:Int,mods:Int):Boolean {
        val focusedIndex=slots.indexOfFirst { it.isFocused }
        if(focusedIndex>=0) index=focusedIndex
        val next=when(key) {
            263->(index-1).coerceAtLeast(0)
            262->(index+1).coerceAtMost(entries.lastIndex)
            265->verticalIndex(-1)
            264->verticalIndex(1)
            268->0
            269->entries.lastIndex
            else->null
        }
        if(next!=null) {
            index=next;keyboardSelection=true;setInitialFocus(slots[index]);return true
        }
        if((key==257 || key==335) && focusedIndex>=0) { choose(index);return true }
        return super.keyPressed(key,scan,mods)
    }

    private fun verticalIndex(direction:Int):Int {
        val row=(index/COLUMNS+direction).coerceIn(0,rowCount-1)
        return (row*COLUMNS+index%COLUMNS).coerceAtMost(entries.lastIndex)
    }

    override fun mouseMoved(mx:Double,my:Double) {
        keyboardSelection=false
        super.mouseMoved(mx,my)
    }

    override fun render(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
        g.fill(0,0,width,height,0xc0101820.toInt())
        // Use the vanilla chest's title and repeat its slot row, extend its plain side rails,
        // then close with the original bottom bevel. Resource packs remain valid.
        g.blit(FRAME,left,top,0,0,PANEL_WIDTH,17)
        repeat(rowCount) { row ->
            g.blit(FRAME,left,top+17+row*SLOT_SIZE,0,17,PANEL_WIDTH,SLOT_SIZE)
        }
        g.blit(FRAME,left,top+inventoryHeight,PANEL_WIDTH,panelHeight-inventoryHeight-7,0f,8f,PANEL_WIDTH,1,256,256)
        g.blit(FRAME,left,top+panelHeight-7,0,215,PANEL_WIDTH,7)
        g.drawString(font,title,left+8,top+6,0xff404040.toInt(),false)
        entries.indexOfFirst { it.id==current.id }.takeIf { it>=0 }?.let { i ->
            g.drawString(font,tr("copycat_current"),left+8,top+45+detailOffset,0xff404040.toInt(),false)
            g.renderItem(stacks[i],left+8,top+59+detailOffset)
            g.drawString(font,font.plainSubstrByWidth(ui(entries[i].name),PANEL_WIDTH-40),left+30,top+60+detailOffset,0xff303030.toInt(),false)
            g.drawString(font,font.plainSubstrByWidth(stacks[i].hoverName.string,PANEL_WIDTH-40),left+30,top+70+detailOffset,0xff606060.toInt(),false)
        }
        font.split(tr("copycat_choose_hint"),PANEL_WIDTH-16).take(2).forEachIndexed { i,line ->
            g.drawString(font,line,left+8,top+88+detailOffset+i*10,0xff606060.toInt(),false)
        }
        super.render(g,mx,my,delta)
        val hovered=slots.indexOfFirst { it.isMouseOver(mx.toDouble(),my.toDouble()) }
        val tooltipIndex=if(hovered>=0) hovered else if(keyboardSelection) slots.indexOfFirst { it.isFocused } else -1
        if(tooltipIndex>=0) {
            val adapter=entries[tooltipIndex]
            val lines=mutableListOf<Component>(tr(adapter.name),stacks[tooltipIndex].hoverName,
                Component.literal(adapter.source).withStyle(ChatFormatting.DARK_GRAY))
            if(adapter.id==current.id) lines+=tr("copycat_selected").copy().withStyle(ChatFormatting.GOLD)
            val point=slotPoint(adapter)!!
            g.renderComponentTooltip(font,lines,if(hovered>=0) mx else point.first.toInt(),if(hovered>=0) my else point.second.toInt())
        }
    }

    override fun renderBackground(g:GuiGraphics,mx:Int,my:Int,delta:Float) {}
    override fun isPauseScreen()=false

    private inner class SlotButton(x:Int,y:Int,private val slotIndex:Int,private val adapter:BaseShape,private val stack:ItemStack):
        Button(x,y,18,18,tr(adapter.name).copy().append(" · ").append(stack.hoverName).append(" · ").append(adapter.source),
            { choose(slotIndex) },DEFAULT_NARRATION) {
        private val selected=adapter.id==current.id
        override fun renderWidget(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
            if(isHoveredOrFocused) g.fill(x+1,y+1,x+17,y+17,0x50ffffff)
            if(selected) g.renderOutline(x,y,18,18,0xffb68a32.toInt())
            if(isFocused) g.renderOutline(x+1,y+1,16,16,0xfff7f1d7.toInt())
            g.renderItem(stack,x+1,y+1)
            if(adapter.discCut>0) {
                val fraction=if(adapter.discCut==1) "½" else "¼"
                val badgeWidth=font.width(fraction)+2
                g.fill(x+18-badgeWidth,y+10,x+18,y+18,0xd0253541.toInt())
                g.drawString(font,fraction,x+19-badgeWidth,y+10,0xffffd883.toInt(),false)
            }
        }
    }

    companion object {
        private const val PANEL_WIDTH=176
        private const val COLUMNS=9
        private const val SLOT_SIZE=18
        private val FRAME=ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png")
        fun open(current:BaseShape,apply:(BaseShape)->Unit) {
            val minecraft=Minecraft.getInstance()
            minecraft.setScreen(CopycatSelectionScreen(minecraft.screen,current,apply))
        }
    }
}
