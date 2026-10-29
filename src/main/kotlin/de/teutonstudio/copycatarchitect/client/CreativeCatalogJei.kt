package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.integration.CreativeCatalog
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.VanillaTypes
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter
import mezz.jei.api.ingredients.subtypes.UidContext
import mezz.jei.api.registration.ISubtypeRegistration
import mezz.jei.api.runtime.IJeiRuntime
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/** Recipe-browser visibility changes only; every upstream recipe and item ID stays registered. */
@JeiPlugin
class CreativeCatalogJei:IModPlugin {
    private var runtime:IJeiRuntime?=null
    override fun getPluginUid():ResourceLocation=ResourceLocation.fromNamespaceAndPath(Architect.ID,"copycats_catalog")
    override fun registerItemSubtypes(registration:ISubtypeRegistration) {
        val interpreter=object:ISubtypeInterpreter<ItemStack> {
            override fun getSubtypeData(ingredient:ItemStack,context:UidContext):Any?=ingredient.get(DataComponents.CUSTOM_DATA)?.copyTag()
            @Suppress("DEPRECATION") override fun getLegacyStringSubtypeInfo(ingredient:ItemStack,context:UidContext)=getSubtypeData(ingredient,context)?.toString().orEmpty()
        }
        registration.registerSubtypeInterpreter(Architect.COMPOSITE_ITEM.get(),interpreter)
        registration.registerSubtypeInterpreter(Architect.CARTRIDGE.get(),interpreter)
    }
    override fun onRuntimeAvailable(jeiRuntime:IJeiRuntime) {
        runtime=jeiRuntime;CreativeCatalogClient.refreshIngredients=::refresh
        refresh()
    }
    override fun onRuntimeUnavailable() { runtime=null;CreativeCatalogClient.refreshIngredients={} }
    private fun refresh() {
        val ingredients=runtime?.ingredientManager ?: return
        val old=ingredients.getAllIngredients(VanillaTypes.ITEM_STACK).filter { CreativeCatalog.isNativeCopycat(it) || it.`is`(Architect.COMPOSITE_ITEM.get()) }
        ingredients.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK,old)
        ingredients.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK,CreativeCatalog.savedForms())
    }
}
