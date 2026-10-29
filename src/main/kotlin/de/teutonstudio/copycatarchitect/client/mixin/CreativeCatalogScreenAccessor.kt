package de.teutonstudio.copycatarchitect.client.mixin

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(CreativeModeInventoryScreen::class)
interface CreativeCatalogScreenAccessor {
    @Accessor("scrollOffs") fun architectScrollOffset():Float
}
