package de.teutonstudio.copycatarchitect.integration

import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.CartridgeRegistry
import de.teutonstudio.copycatarchitect.server.Economy
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.registries.DeferredRegister

object CreativeCatalog {
    val ID:ResourceLocation=ResourceLocation.fromNamespaceAndPath(Architect.ID,"copycats")
    private val tabs=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,Architect.ID)
    val TAB=tabs.register("copycats") { -> CreativeModeTab.builder().title(Component.translatable("itemGroup.copycat_architect.copycats"))
        .icon { ItemStack(Architect.GUN.get()) }.displayItems { _,_ -> }.build() }
    var savedForms:()->List<ItemStack> = { emptyList() }
    var parameters:CreativeModeTab.ItemDisplayParameters?=null;private set

    fun register(bus:IEventBus) {
        tabs.register(bus)
        bus.addListener(EventPriority.LOWEST,::contents)
    }
    fun isNativeCopycat(stack:ItemStack):Boolean {
        val key=BuiltInRegistries.ITEM.getKey(stack.item)
        return CreativeCatalogPolicy.isCopycat(key.namespace,key.path)
    }
    fun sections():Map<CreativeCatalogSection,List<ItemStack>> {
        val native=BuiltInRegistries.ITEM.filter { it!==Items.AIR }.map { ItemStack(it) }.filter(::isNativeCopycat)
        val ordered=BaseShapes.all.map { it.source }.distinct().withIndex().associate { it.value to it.index }
        val geometry=native.filter { stack -> val key=BuiltInRegistries.ITEM.getKey(stack.item);CreativeCatalogPolicy.section(key.namespace,key.path)==CreativeCatalogSection.BASIC }
            .sortedWith(compareBy<ItemStack> { ordered[BuiltInRegistries.ITEM.getKey(it.item).toString()] ?: Int.MAX_VALUE }.thenBy { BuiltInRegistries.ITEM.getKey(it.item).toString() })
        val frequent=native.filter { stack -> val key=BuiltInRegistries.ITEM.getKey(stack.item);CreativeCatalogPolicy.section(key.namespace,key.path)==CreativeCatalogSection.FREQUENT }
            .sortedBy { BuiltInRegistries.ITEM.getKey(it.item).toString() }
        return linkedMapOf(
            CreativeCatalogSection.PROCEDURAL to listOf(ItemStack(Architect.GUN.get()),ItemStack(CartridgeRegistry.EMPTY.get()),Economy.cartridge(256),ItemStack(CartridgeRegistry.CREATIVE.get()),ItemStack(Architect.TABLE_ITEM.get())),
            CreativeCatalogSection.BASIC to geometry,CreativeCatalogSection.FREQUENT to frequent,CreativeCatalogSection.SAVED to savedForms())
    }
    private fun contents(event:BuildCreativeModeTabContentsEvent) {
        if(event.tabKey.location()==ID) {
            parameters=event.parameters
            val accepted=mutableListOf<ItemStack>()
            for(stack in sections().values.flatten()) if(!stack.isEmpty && accepted.none { ItemStack.isSameItemSameComponents(it,stack) }) {
                event.accept(stack);accepted+=stack
            }
            return
        }
        // Hide upstream catalog duplicates, including entries contributed to another mod's tab.
        (event.parentEntries.toList()+event.searchEntries.toList()).filter(::isNativeCopycat).forEach {
            event.remove(it,CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS)
        }
    }
}
