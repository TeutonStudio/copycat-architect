package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.*
import net.createmod.catnip.math.Pointing
import net.createmod.ponder.api.PonderPalette
import net.createmod.ponder.api.scene.SceneBuilder
import net.createmod.ponder.api.scene.SceneBuildingUtil
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

object ArchitectPonderScenes {
    private fun text(scene:String,key:String)=I18n.get("copycat_architect.ponder.$scene.$key")
    private fun start(scene:SceneBuilder,id:String) {
        scene.title(id,text(id,"header"));scene.configureBasePlate(0,0,5);scene.showBasePlate();scene.idle(10)
    }
    private fun note(scene:SceneBuilder,util:SceneBuildingUtil,id:String,key:String,pos:BlockPos,duration:Int=75) {
        scene.overlay().showText(duration).attachKeyFrame().text(text(id,key)).pointAt(util.vector().centerOf(pos)).placeNearTarget()
        scene.idle(duration+10)
    }
    private fun state(id:String):BlockState=BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState()
    private fun composite(scene:SceneBuilder,pos:BlockPos,part:Part=Part("copycats:copycat_block"),material:String="minecraft:stone_bricks") {
        scene.world().setBlock(pos,Architect.COMPOSITE.get().defaultBlockState(),false)
        scene.world().modifyBlockEntity(pos,CompositeEntity::class.java) { be ->
            be.parts=listOf(part);be.material=material;be.groups=DEFAULT_GROUPS;be.surfaces=emptyMap()
        }
    }
    fun printer(scene:SceneBuilder,util:SceneBuildingUtil) {
        start(scene,"printer")
        val position=util.grid().at(2,1,2)
        scene.overlay().showControls(util.vector().topOf(position),Pointing.DOWN,45).withItem(ItemStack(Architect.GUN.get())).rightClick()
        scene.world().setBlock(position,Architect.PRINTER.get().defaultBlockState(),false)
        scene.world().showSection(util.select().position(position),Direction.DOWN)
        note(scene,util,"printer","place",position)
        scene.overlay().showControls(util.vector().topOf(position),Pointing.DOWN,60).rightClick()
        note(scene,util,"printer","graph",position,90)
        scene.overlay().showControls(util.vector().blockSurface(position,Direction.WEST),Pointing.RIGHT,60).withItem(Economy.cartridge(256))
        val cartridge=scene.world().createItemEntity(Vec3(0.8,1.4,2.5),Vec3.ZERO,Economy.cartridge(256))
        note(scene,util,"printer","materials",position,90)
        composite(scene,position,Part("copycats:copycat_stairs"))
        scene.world().modifyEntity(cartridge) { entity -> if(entity is ItemEntity) entity.item=Economy.cartridge((256-Extrusion.cost(listOf(Part("copycats:copycat_stairs")))).coerceAtLeast(0)) }
        scene.world().createItemEntity(Vec3(3.6,1.3,2.5),Vec3.ZERO,ItemStack(Architect.GUN.get()))
        scene.effects().indicateSuccess(position)
        note(scene,util,"printer","print",position,90)
        scene.overlay().showControls(util.vector().topOf(position),Pointing.DOWN,60).withItem(ItemStack(Architect.GUN.get())).whileSneaking().rightClick()
        note(scene,util,"printer","library",position,95)
        scene.markAsFinished()
    }
    fun cartridges(scene:SceneBuilder,util:SceneBuildingUtil) {
        start(scene,"cartridges")
        val basin=util.grid().at(1,1,2);val burner=basin.below();val spout=util.grid().at(3,2,2);val depot=spout.below()
        val heat=state("create:blaze_burner").block.stateDefinition.possibleStates.firstOrNull { s -> s.values.any { (property,value) -> property.name=="blaze" && value.toString().equals("kindled",true) } } ?: state("create:blaze_burner")
        scene.world().setBlock(burner,heat,false);scene.world().setBlock(basin,state("create:basin"),false)
        scene.world().setBlock(spout,state("create:spout"),false);scene.world().setBlock(depot,state("create:depot"),false)
        scene.world().showSection(util.select().fromTo(0,0,1,4,2,3),Direction.DOWN)
        scene.overlay().showControls(util.vector().topOf(basin),Pointing.DOWN,60).withItem(ItemStack(Economy.item("create:zinc_ingot")))
        note(scene,util,"cartridges","melt",basin,90)
        val empty=ItemStack(CartridgeRegistry.EMPTY.get())
        val filling=scene.world().createItemEntity(util.vector().topOf(depot).add(0.0,0.15,0.0),Vec3.ZERO,empty)
        scene.overlay().showControls(util.vector().topOf(depot),Pointing.DOWN,60).withItem(empty)
        scene.overlay().showLine(PonderPalette.BLUE,util.vector().topOf(basin),util.vector().centerOf(spout),90)
        note(scene,util,"cartridges","fill",spout,90)
        scene.world().modifyEntity(filling) { entity -> if(entity is ItemEntity) entity.item=Economy.cartridge(256) }
        scene.effects().indicateSuccess(depot)
        note(scene,util,"cartridges","capacity",depot,80)
        scene.overlay().showControls(util.vector().topOf(depot),Pointing.DOWN,60).withItem(ItemStack(CartridgeRegistry.CREATIVE.get()))
        note(scene,util,"cartridges","creative",depot,85)
        scene.overlay().showControls(util.vector().topOf(depot),Pointing.DOWN,60).withItem(ItemStack(Architect.GUN.get())).whileSneaking().rightClick()
        note(scene,util,"cartridges","storage",depot,85)
        scene.markAsFinished()
    }
    fun table(scene:SceneBuilder,util:SceneBuildingUtil) {
        start(scene,"table");scene.scaleSceneView(0.85f)
        val table=util.grid().at(0,1,0)
        scene.world().setBlock(table,Architect.TABLE.get().defaultBlockState(),false)
        scene.world().showSection(util.select().position(table),Direction.DOWN)
        scene.overlay().showControls(util.vector().topOf(table),Pointing.DOWN,60).rightClick()
        note(scene,util,"table","graph",table,90)
        for(x in 1..3) for(z in 1..3) composite(scene,BlockPos(x,1,z),material="minecraft:oak_planks")
        scene.world().showSection(util.select().fromTo(1,1,1,3,1,3),Direction.DOWN)
        note(scene,util,"table","segments",BlockPos(2,1,2),85)
        for(x in 1..3) for(z in 1..3) if(x!=2 || z!=2) for(y in 2..3) {
            if(x==2 && z==3 && y==2) continue
            composite(scene,BlockPos(x,y,z))
        }
        scene.world().showSection(util.select().fromTo(1,2,1,3,3,3),Direction.DOWN)
        for(x in 1..3) for(z in 1..3) composite(scene,BlockPos(x,4,z),material="minecraft:oak_planks")
        scene.world().showSection(util.select().fromTo(1,4,1,3,4,3),Direction.DOWN)
        note(scene,util,"table","storeys",BlockPos(2,3,2),90)
        for(x in 1..3) for(z in 1..3) composite(scene,BlockPos(x,5+if(x==2) 1 else 0,z),Part(if(x==2) "copycats:copycat_slab" else "copycats:copycat_stairs",if(x==1) 3 else 1),"minecraft:deepslate_tiles")
        scene.world().showSection(util.select().fromTo(1,5,1,3,6,3),Direction.DOWN)
        note(scene,util,"table","roof",BlockPos(2,5,2),90)
        scene.effects().indicateSuccess(table)
        note(scene,util,"table","turtle",table,95)
        scene.markAsFinished()
    }
}
