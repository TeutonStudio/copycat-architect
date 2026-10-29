package de.teutonstudio.copycatarchitect.server

import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.block.Block

enum class CartridgeKind { FULL, EMPTY, CREATIVE }

/** The original cartridge ID and its legacy custom-data units remain valid. */
class CartridgeItem(properties:Properties,val kind:CartridgeKind=CartridgeKind.FULL):BlockItem(CartridgeRegistry.BLOCK.get(),properties) {
    override fun registerBlocks(map:MutableMap<Block,Item>,item:Item) {
        if(kind==CartridgeKind.FULL) super.registerBlocks(map,item)
    }
    override fun isFoil(stack:ItemStack)=kind==CartridgeKind.CREATIVE
    override fun appendHoverText(stack:ItemStack,context:TooltipContext,tooltip:MutableList<Component>,flag:TooltipFlag) {
        tooltip+=if(kind==CartridgeKind.CREATIVE) Component.translatable("item.copycat_architect.cartridge.infinite")
            else Component.translatable("item.copycat_architect.cartridge.units",Economy.units(stack))
        tooltip+=Component.translatable("item.copycat_architect.cartridge.place")
    }
}
