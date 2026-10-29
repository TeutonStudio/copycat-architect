package de.teutonstudio.copycatarchitect.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Called only by the opt-in client smoke run, with the editor already open. */
internal object EditorValueInputSmoke {
    private fun entry(screen:Screen)=screen.children().filterIsInstance<EditBox>().single()

    fun verify(mc:Minecraft) {
        val parent=checkNotNull(mc.screen)
        try {
            var number=4
            var edits=0
            EditorValueInput.integer(Component.literal("Smoke: numeric draft"),number,-64..64) { number=it;edits++ }
            val cancelled=checkNotNull(mc.screen)
            for(invalid in listOf("-","65","99999999999")) {
                entry(cancelled).value=invalid
                check(cancelled.keyPressed(257,0,0))
                check(mc.screen===cancelled && number==4 && edits==0)
            }
            entry(cancelled).value="-23"
            cancelled.resize(mc,mc.window.guiScaledWidth,mc.window.guiScaledHeight)
            check(entry(cancelled).value=="-23")
            check(number==4 && edits==0)
            check(cancelled.keyPressed(256,0,0))
            check(mc.screen===parent && number==4 && edits==0)

            EditorValueInput.integer(Component.literal("Smoke: numeric accept"),number,-64..64) { number=it;edits++ }
            val accepted=checkNotNull(mc.screen)
            entry(accepted).value="-64"
            check(accepted.keyPressed(257,0,0))
            check(mc.screen===parent && number==-64 && edits==1)

            var group="body"
            EditorValueInput.text(Component.literal("Smoke: new group"),"") { group=it }
            val named=checkNotNull(mc.screen)
            "  Neue Gruppe  ".forEach { check(named.charTyped(it,0)) }
            check(group=="body")
            named.resize(mc,mc.window.guiScaledWidth,mc.window.guiScaledHeight)
            check(entry(named).value=="  Neue Gruppe  ")
            check(named.keyPressed(257,0,0))
            check(mc.screen===parent && group=="Neue Gruppe")

            var attempts=0
            EditorValueInput.text(Component.literal("Smoke: rejected edit"),"body") {
                attempts++
                require(it!="rejected") { "Smoke: rejected by document validation" }
                group=it
            }
            val retry=checkNotNull(mc.screen)
            entry(retry).value="rejected"
            check(retry.keyPressed(257,0,0))
            check(mc.screen===retry && attempts==1 && group=="Neue Gruppe")
            entry(retry).value="frame"
            check(retry.keyPressed(257,0,0))
            check(mc.screen===parent && attempts==2 && group=="frame")
        } finally {
            if(mc.screen!==parent) mc.setScreen(parent)
        }
    }
}
