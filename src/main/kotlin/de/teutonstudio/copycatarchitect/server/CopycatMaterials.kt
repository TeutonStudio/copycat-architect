package de.teutonstudio.copycatarchitect.server

import de.teutonstudio.copycatarchitect.Architect
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.TransparentBlock
import net.minecraft.world.level.block.state.BlockState

/** A coating borrows an ordinary block's appearance, never its block entity or behaviour. */
object CopycatMaterials {
    fun state(id:String):BlockState {
        val key=ResourceLocation.tryParse(id) ?: error("Invalid material ID")
        require(BuiltInRegistries.ITEM.containsKey(key)) { "Unknown material $id" }
        val block=(BuiltInRegistries.ITEM.get(key) as? BlockItem)?.block ?: error("Material must be a block")
        val state=block.defaultBlockState()
        require(key.namespace!=Architect.ID && !state.hasBlockEntity() &&
            (block is TransparentBlock || state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO))) {
            "Copycat coatings require a full block without a block entity"
        }
        return state
    }
}
