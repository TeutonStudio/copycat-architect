package de.teutonstudio.copycatarchitect.client

import com.mojang.blaze3d.platform.InputConstants
import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.server.Command
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.neoforged.neoforge.client.event.ClientTickEvent
import org.lwjgl.glfw.GLFW

object GunControls {
    val rotate=KeyMapping("key.copycat_architect.rotate",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_R,"key.categories.copycat_architect")
    fun tick(e:ClientTickEvent.Post) {
        val mc=Minecraft.getInstance()
        while(rotate.consumeClick()) {
            if(mc.screen!=null) continue
            val player=mc.player ?: continue
            if(!player.mainHandItem.`is`(Architect.GUN.get())) continue
            val turn=player.mainHandItem.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getInt("turn") ?: 0
            ClientState.send(Command("printerRotate",turn=(turn+1)%4))
        }
    }
}
