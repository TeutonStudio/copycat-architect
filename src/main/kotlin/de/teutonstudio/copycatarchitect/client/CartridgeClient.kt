package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.server.CartridgeRegistry
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent

object CartridgeClient {
    fun register(event:RegisterClientExtensionsEvent) {
        event.registerFluidType(object:IClientFluidTypeExtensions {
            override fun getStillTexture()=ResourceLocation.withDefaultNamespace("block/water_still")
            override fun getFlowingTexture()=ResourceLocation.withDefaultNamespace("block/water_flow")
            override fun getTintColor()=0xffb9c5c9.toInt()
        },CartridgeRegistry.ZINC_TYPE.get())
    }
}
