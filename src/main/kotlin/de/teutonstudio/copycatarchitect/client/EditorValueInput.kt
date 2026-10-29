package de.teutonstudio.copycatarchitect.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** A draft never mutates the edited document until a valid value is accepted. */
class EditorValueDraft<T>(initial:String,private val parse:(String)->T?) {
    var value=initial
    private var finished=false
    val valid get()=!finished && parse(value)!=null
    fun submit(apply:(T)->Unit):Boolean {
        if(finished) return false
        val parsed=parse(value) ?: return false
        apply(parsed)
        finished=true
        return true
    }
    fun cancel() { finished=true }
}

/** Direct entry for numbers and names; closed sets continue to use a choice control. */
object EditorValueInput {
    fun integer(title:Component,current:Int,range:IntRange,apply:(Int)->Unit) {
        require(!range.isEmpty())
        open(title,current.toString(),12,Component.literal("${range.first} … ${range.last}"),
            { it.toIntOrNull()?.takeIf { number -> number in range } },apply)
    }
    fun text(title:Component,current:String,maxLength:Int=32,validate:(String)->Boolean={ it.isNotBlank() },apply:(String)->Unit) {
        open(title,current,maxLength,null,{ it.trim().takeIf(validate) },apply)
    }
    private fun <T> open(title:Component,current:String,maxLength:Int,hint:Component?,parse:(String)->T?,apply:(T)->Unit) {
        val minecraft=Minecraft.getInstance()
        minecraft.setScreen(ValueScreen(minecraft.screen,title,current,maxLength,hint,parse,apply))
    }
    private class ValueScreen<T>(private val parent:Screen?,title:Component,current:String,private val maxLength:Int,
        private val hint:Component?,parse:(String)->T?,private val apply:(T)->Unit):Screen(title) {
        private val draft=EditorValueDraft(current,parse)
        private var error=""
        override fun init() {
            val w=minOf(320,(width-32).coerceAtLeast(80));val left=(width-w)/2;val top=height/2-26
            val input=addRenderableWidget(EditBox(font,left,top,w,20,title).apply {
                setMaxLength(maxLength);value=draft.value
            })
            addRenderableWidget(EditorButton(left,top+52,(w-8)/2,tr("cancel")) { onClose() })
            val save=addRenderableWidget(EditorButton(left+(w+8)/2,top+52,(w-8)/2,tr("save")) { accept() })
            save.active=draft.valid
            input.setResponder { draft.value=it;error="";save.active=draft.valid }
            setInitialFocus(input)
            input.setHighlightPos(0)
        }
        private fun accept() {
            try {
                if(draft.submit(apply)) minecraft?.setScreen(parent)
                else error=ui("value_invalid")
            } catch(e:Exception) { error=e.message ?: title.string }
        }
        override fun onClose() { draft.cancel();minecraft?.setScreen(parent) }
        override fun keyPressed(key:Int,scan:Int,mods:Int):Boolean {
            if(key==257 || key==335) { accept();return true }
            return super.keyPressed(key,scan,mods)
        }
        override fun render(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
            g.fill(0,0,width,height,0xff15212a.toInt())
            val w=minOf(320,(width-32).coerceAtLeast(80));val left=(width-w)/2;val top=height/2-26
            g.drawString(font,title,left,top-20,0xffffc66d.toInt(),false)
            hint?.let { g.drawString(font,it,left,top+29,0xffa7bec7.toInt(),false) }
            if(error.isNotEmpty()) g.drawString(font,font.plainSubstrByWidth(error,w),left,top+87,0xffffb295.toInt(),false)
            super.render(g,mx,my,delta)
        }
        override fun renderBackground(g:GuiGraphics,mx:Int,my:Int,delta:Float) {}
        override fun isPauseScreen()=false
    }
}
