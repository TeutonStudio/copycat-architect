package de.teutonstudio.copycatarchitect.client.mixin

import de.teutonstudio.copycatarchitect.client.CreativeCatalogClient
import net.minecraft.world.item.CreativeModeTab
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(CreativeModeTab::class)
abstract class CreativeCatalogSectionsMixin {
    @Inject(method=["buildContents"],at=[At("RETURN")])
    private fun architectArrangeSections(parameters:CreativeModeTab.ItemDisplayParameters,ci:CallbackInfo) {
        CreativeCatalogClient.arrange(this as CreativeModeTab)
    }
}
