package de.teutonstudio.copycatarchitect.client

import com.mojang.blaze3d.vertex.PoseStack
import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.Json
import de.teutonstudio.copycatarchitect.server.PrinterStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions

/** Saved forms retain their real geometry in creative, recipe-viewer, hand and dropped-item renders. */
class CompositeFormItemRenderer:BlockEntityWithoutLevelRenderer(Minecraft.getInstance().blockEntityRenderDispatcher,Minecraft.getInstance().entityModels) {
    private data class Key(val definition:String,val materials:String,val turn:Int)
    private data class Model(val parts:List<Part>,val groups:List<MaterialGroup>,val surfaces:Map<String,String>)
    private val cache=object:LinkedHashMap<Key,Model>(32,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Key,Model>?)=size>128
    }
    override fun renderByItem(stack:ItemStack,context:ItemDisplayContext,pose:PoseStack,buffers:MultiBufferSource,light:Int,overlay:Int) {
        val data=PrinterStack.tag(stack)
        val key=Key(data.getString("form_definition"),data.getString("surfaces"),Math.floorMod(data.getInt("turn"),4))
        val model=cache.getOrPut(key) {
            runCatching {
                val form=Geometry.validate(Json.decode<Form>(key.definition))
                val surfaces=Json.materials(key.materials.ifEmpty { "{}" }).filterKeys { id -> form.groups.any { it.id==id } }
                Model(Geometry.rotate(form.parts,key.turn),form.groups,surfaces)
            }.getOrElse { Model(listOf(Part("copycats:copycat_block")),DEFAULT_GROUPS,emptyMap()) }
        }
        pose.pushPose()
        Meshes.draw(model.parts,"",pose,buffers,light,groups=model.groups,surfaces=model.surfaces)
        pose.popPose()
    }
    companion object {
        val extensions:IClientItemExtensions=object:IClientItemExtensions {
            private val renderer by lazy { CompositeFormItemRenderer() }
            override fun getCustomRenderer():BlockEntityWithoutLevelRenderer=renderer
        }
    }
}
