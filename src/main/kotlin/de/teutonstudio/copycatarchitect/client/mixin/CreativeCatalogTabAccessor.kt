package de.teutonstudio.copycatarchitect.client.mixin

import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(CreativeModeTab::class)
interface CreativeCatalogTabAccessor {
    @Accessor("displayItems") fun architectSetDisplayItems(items:Collection<ItemStack>)
}
