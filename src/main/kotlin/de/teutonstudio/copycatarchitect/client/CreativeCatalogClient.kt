package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.CreativeCatalogSection
import de.teutonstudio.copycatarchitect.integration.CreativeCatalog
import de.teutonstudio.copycatarchitect.client.mixin.CreativeCatalogTabAccessor
import de.teutonstudio.copycatarchitect.client.mixin.CreativeCatalogScreenAccessor
import de.teutonstudio.copycatarchitect.server.CompositeFormItem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
import kotlin.math.roundToInt

/** Empty inventory rows carry section banners without introducing collectible separator items. */
object CreativeCatalogClient {
    private const val COLUMNS=9
    private const val VISIBLE_ROWS=5
    private val sectionRows=linkedMapOf<CreativeCatalogSection,Int>()
    var refreshIngredients:()->Unit = {}
    fun setup() {
        CreativeCatalog.savedForms={ ClientState.snapshot.library.let { library -> library.forms.entries.filter { it.value.name.isNotBlank() }
            .sortedWith(compareBy({ it.value.name.lowercase() },{ it.value.id },{ it.value.version }))
            .mapNotNull { (key,form) -> runCatching { val settings=library.printSettings[key]
                CompositeFormItem.stack(form,settings?.surfaces.orEmpty(),settings?.turn ?: 0)
            }.getOrNull() } } }
        NeoForge.EVENT_BUS.addListener(::renderSections)
    }
    /** Called after the server synchronizes the player's library, including saves and deletes. */
    fun refresh() {
        val mc=Minecraft.getInstance();val tab=CreativeCatalog.TAB.get()
        val previous=tab.displayItems.toList();val screen=mc.screen as? CreativeModeInventoryScreen
        val visible=screen?.menu?.items==previous
        CreativeCatalog.parameters?.let(tab::buildContents)
        if(visible && screen!=null) {
            screen.menu.items.clear();screen.menu.items.addAll(tab.displayItems);screen.menu.scrollTo(0f)
        }
        refreshIngredients()
    }
    @JvmStatic fun arrange(tab:CreativeModeTab) {
        if(tab!==CreativeCatalog.TAB.get()) return
        val arranged=mutableListOf<ItemStack>();sectionRows.clear()
        val accepted=mutableListOf<ItemStack>()
        for((section,entries) in CreativeCatalog.sections()) {
            sectionRows[section]=arranged.size/COLUMNS
            repeat(COLUMNS) { arranged+=ItemStack.EMPTY }
            for(stack in entries) if(!stack.isEmpty && accepted.none { ItemStack.isSameItemSameComponents(it,stack) }) {
                arranged+=stack;accepted+=stack
            }
            while(arranged.size%COLUMNS!=0) arranged+=ItemStack.EMPTY
        }
        (tab as CreativeCatalogTabAccessor).architectSetDisplayItems(arranged)
    }
    private fun renderSections(event:ScreenEvent.Render.Post) {
        val screen=event.screen as? CreativeModeInventoryScreen ?: return
        val tab=CreativeCatalog.TAB.get()
        if(screen.menu.items!=tab.displayItems.toList()) return
        val scroll=(screen as CreativeCatalogScreenAccessor).architectScrollOffset()
        val rows=(Mth.positiveCeilDiv(screen.menu.items.size,COLUMNS)-VISIBLE_ROWS).coerceAtLeast(0)
        val current=(scroll*rows).roundToInt().coerceAtLeast(0)
        val left=screen.guiLeft+8;val top=screen.guiTop+17
        for((section,row) in sectionRows) {
            val visible=row-current;if(visible !in 0 until VISIBLE_ROWS) continue
            val y=top+visible*18
            val background=if(section==CreativeCatalogSection.PROCEDURAL) 0xff5a3a20.toInt() else 0xff1f4b59.toInt()
            val border=if(section==CreativeCatalogSection.PROCEDURAL) 0xffffc66d.toInt() else 0xff79d7ee.toInt()
            event.guiGraphics.fill(left,y,left+162,y+17,background)
            event.guiGraphics.fill(left,y,left+3,y+17,border)
            event.guiGraphics.drawString(screen.minecraft!!.font,Component.translatable("creative_section.copycat_architect.${section.key}"),left+7,y+5,0xffffffff.toInt(),true)
        }
    }
}
