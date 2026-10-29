package de.teutonstudio.copycatarchitect.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

/** Screen buttons use the same palette and selection accent as the node controls. */
class EditorButton(x:Int,y:Int,w:Int,label:Component,action:()->Unit):
    Button(x,y,w,18,label,{ action() },DEFAULT_NARRATION) {
    var selected=false
    var dropdown=false
    override fun renderWidget(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
        val font=Minecraft.getInstance().font
        val highlighted=active && isHoveredOrFocused
        g.fill(x,y,x+width,y+height,when { !active->0xff23313b.toInt();highlighted->0xff48616d.toInt();selected->0xff40545c.toInt();else->0xff304653.toInt() })
        g.renderOutline(x,y,width,height,if(highlighted || selected) 0xffd7b77b.toInt() else 0xff4b6571.toInt())
        if(selected) g.fill(x+1,y+height-2,x+width-1,y+height-1,0xffffc66d.toInt())
        val color=if(active) 0xffe3eaf0.toInt() else 0xff7f939d.toInt()
        val text=font.plainSubstrByWidth(message.string,(width-if(dropdown) 18 else 8).coerceAtLeast(1))
        g.drawString(font,text,if(dropdown) x+5 else x+(width-font.width(text))/2,y+5,color,false)
        if(dropdown) g.drawString(font,"▾",x+width-11,y+5,0xffffc66d.toInt(),false)
    }
}

/** A modal screen-space list: the viewport clips neither labels nor pointer targets. */
class EditorDropdown(val anchor:Button,val options:List<Pair<String,()->Unit>>,selected:Int) {
    var index=selected.coerceIn(0,options.lastIndex.coerceAtLeast(0))
    var first=selected.coerceAtLeast(0)
    private fun box(sw:Int,sh:Int):IntArray {
        val font=Minecraft.getInstance().font
        val w=maxOf(anchor.width,options.maxOfOrNull { font.width(it.first)+22 } ?: 80).coerceAtMost((sw-16).coerceAtLeast(1))
        val rows=minOf(options.size,((sh-20)/20).coerceAtLeast(1),12)
        first=first.coerceIn(0,(options.size-rows).coerceAtLeast(0))
        val below=anchor.y+anchor.height+2
        val top=if(below+rows*20<=sh-8) below else (anchor.y-rows*20-2).coerceAtLeast(8)
        return intArrayOf(anchor.x.coerceIn(8,maxOf(8,sw-w-8)),top,w,rows)
    }
    fun render(g:GuiGraphics,mx:Int,my:Int,sw:Int,sh:Int) {
        val (x,y,w,rows)=box(sw,sh);val font=Minecraft.getInstance().font
        first=first.coerceIn(0,(options.size-rows).coerceAtLeast(0))
        g.flush();g.pose().pushPose();g.pose().translate(0f,0f,600f)
        g.fill(x-2,y-2,x+w+2,y+rows*20+2,0xffd7b77b.toInt())
        for(i in 0 until rows) {
            val chosen=i+first;val hovered=mx in x until x+w && my in y+i*20 until y+(i+1)*20
            g.fill(x,y+i*20,x+w,y+(i+1)*20,if(hovered) 0xff48616d.toInt() else if(chosen==index) 0xff40545c.toInt() else 0xff233640.toInt())
            if(chosen==index) g.fill(x,y+i*20,x+2,y+(i+1)*20,0xffffc66d.toInt())
            g.drawString(font,font.plainSubstrByWidth(options[chosen].first,w-16),x+6,y+i*20+6,0xffe3eaf0.toInt(),false)
        }
        if(rows<options.size) g.drawString(font,"↕",x+w-9,y+6,0xffffc66d.toInt(),false)
        g.flush();g.pose().popPose()
    }
    fun click(mx:Double,my:Double,button:Int,sw:Int,sh:Int):(() -> Unit)? {
        val (x,y,w,rows)=box(sw,sh)
        return if(button==0 && mx>=x && mx<x+w && my>=y && my<y+rows*20) options.getOrNull(first+((my-y)/20).toInt())?.second else null
    }
    fun scroll(dy:Double,sw:Int,sh:Int) { val rows=box(sw,sh)[3];first=(first-if(dy>0) 1 else -1).coerceIn(0,(options.size-rows).coerceAtLeast(0)) }
    fun key(key:Int,sw:Int,sh:Int):(() -> Unit)? {
        val rows=box(sw,sh)[3]
        when(key) { 265->index=(index-1).coerceAtLeast(0);264->index=(index+1).coerceAtMost(options.lastIndex);268->index=0;269->index=options.lastIndex;257,335->return options.getOrNull(index)?.second }
        if(index<first) first=index
        if(index>=first+rows) first=index-rows+1
        return null
    }
}

/** Holding the pointer over the eye reveals the machine without changing the graph or its history. */
object WorldPeek { var active=false }
class PrinterMemoryButton(x:Int,y:Int,action:()->Unit):Button(x,y,32,18,tr("printer_storage"),{ action() },DEFAULT_NARRATION) {
    init { setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("printer_storage"))) }
    override fun renderWidget(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
        val hover=isHoveredOrFocused
        g.fill(x,y,x+width,y+height,if(hover) 0xff48616d.toInt() else 0xff304653.toInt())
        g.renderOutline(x,y,width,height,if(hover) 0xffd7b77b.toInt() else 0xff4b6571.toInt())
        val color=if(hover) 0xffffd18b.toInt() else 0xffd3e1dd.toInt()
        g.renderOutline(x+9,y+4,14,11,color)
        for(row in 0..2) { g.fill(x+12,y+6+row*3,x+14,y+7+row*3,color);g.fill(x+16,y+6+row*3,x+20,y+7+row*3,color) }
    }
}
class PrinterEyeButton(x:Int,y:Int):net.minecraft.client.gui.components.AbstractWidget(x,y,28,18,tr("printer_eye")) {
    init { setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("printer_eye"))) }
    override fun renderWidget(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
        val hover=isMouseOver(mx.toDouble(),my.toDouble())
        g.fill(x,y,x+width,y+height,if(hover) 0xff6a6750.toInt() else 0xff354854.toInt())
        g.renderOutline(x,y,width,height,0xffc5b17d.toInt())
        val color=if(hover) 0xffffd18b.toInt() else 0xffd3e1dd.toInt()
        g.fill(x+6,y+7,x+8,y+11,color);g.fill(x+8,y+5,x+20,y+7,color);g.fill(x+8,y+11,x+20,y+13,color);g.fill(x+20,y+7,x+22,y+11,color);g.fill(x+12,y+7,x+16,y+11,color)
    }
    override fun updateWidgetNarration(out:net.minecraft.client.gui.narration.NarrationElementOutput) { out.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,message) }
}
