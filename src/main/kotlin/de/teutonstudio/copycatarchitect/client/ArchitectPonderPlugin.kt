package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.server.CartridgeRegistry
import net.createmod.ponder.api.registration.PonderPlugin
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation

/** Registered through PonderIndex during client setup; Create supplies the hold-W integration. */
class ArchitectPonderPlugin:PonderPlugin {
    override fun getModId()=Architect.ID
    override fun registerScenes(helper:PonderSceneRegistrationHelper<ResourceLocation>) {
        helper.forComponents(BuiltInRegistries.ITEM.getKey(Architect.GUN.get()))
            .addStoryBoard("architect_workshop",ArchitectPonderScenes::printer)
        helper.forComponents(listOf(BuiltInRegistries.ITEM.getKey(CartridgeRegistry.EMPTY.get()),BuiltInRegistries.ITEM.getKey(Architect.CARTRIDGE.get()),BuiltInRegistries.ITEM.getKey(CartridgeRegistry.CREATIVE.get())))
            .addStoryBoard("architect_workshop",ArchitectPonderScenes::cartridges)
        helper.forComponents(BuiltInRegistries.ITEM.getKey(Architect.TABLE_ITEM.get()))
            .addStoryBoard("architect_workshop",ArchitectPonderScenes::table)
    }
}
