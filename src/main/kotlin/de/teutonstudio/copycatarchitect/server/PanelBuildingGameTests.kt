package de.teutonstudio.copycatarchitect.server

import com.mojang.authlib.GameProfile
import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.FurnaceBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(Architect.ID)
@PrefixGameTestTemplate(false)
object PanelBuildingGameTests {
    private data class Side(val name:String,val axisX:Boolean,val positive:Boolean,val at:(Int,Int)->P)

    @JvmStatic @GameTest(template="empty",timeoutTicks=400) fun survivalPanelHouseKeepsThinWallsAndAllInteriorCells(h:GameTestHelper) {
        val level=h.level;val plan=PanelBuildingExample.plan()
        val origin=h.absolutePos(BlockPos(3,1,3))
        fun world(p:P)=origin.offset(p.x,p.y,p.z)
        val actor=FakePlayerFactory.get(level,GameProfile(UUID.randomUUID(),"PanelHouseBuilder"))
        actor.gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
        actor.isShiftKeyDown=false
        actor.setPos(origin.x-12.0,origin.y.toDouble(),origin.z-12.0)
        val occupied=plan.placements.associateBy { it.pos }
        val bounds=plan.placements.map { it.pos }+plan.clear
        for(x in bounds.minOf { it.x }..bounds.maxOf { it.x })
            for(y in bounds.minOf { it.y }..bounds.maxOf { it.y })
                for(z in bounds.minOf { it.z }..bounds.maxOf { it.z }) level.setBlockAndUpdate(world(P(x,y,z)),Blocks.AIR.defaultBlockState())

        val inventory=SimpleContainer(27);inventory.setItem(0,Economy.cartridge(plan.raw))
        var slot=1
        for((material,count) in plan.materials) {
            var left=count
            while(left>0) {
                val stack=ItemStack(Economy.item(material));val amount=minOf(left,stack.maxStackSize)
                h.assertTrue(slot<inventory.containerSize,"Calculated surface requirements must fit the test supply")
                stack.count=amount;inventory.setItem(slot++,stack);left-=amount
            }
        }
        val budget=plan.placements.sumOf { Extrusion.cost(it.parts,it.groups) }
        h.assertTrue(plan.raw==budget && plan.raw>0,"Survival supply must use the generated plan's exact raw total")
        for(placement in plan.placements) {
            h.assertTrue(PlacementService.place(level,world(placement.pos),placement,inventory,actor,"panel-house"),"Every generated house position must place in Survival: ${placement.pos}")
        }
        h.assertTrue(inventory.getItem(0).`is`(CartridgeRegistry.EMPTY.get()) && Economy.units(inventory.getItem(0))==0,"The exact raw budget must leave one empty cartridge")
        h.assertTrue((1 until inventory.containerSize).all { inventory.getItem(it).isEmpty },"Every calculated surface item must be consumed exactly once")

        fun entity(p:P)=level.getBlockEntity(world(p)) as? CompositeEntity ?: error("Missing actual house block entity at $p")
        fun collision(p:P,x:Int,y:Int,z:Int,contextual:Boolean=false):Boolean {
            val pos=world(p);val state=level.getBlockState(pos)
            val boxes=(if(contextual) state.getCollisionShape(level,pos,CollisionContext.empty()) else state.getCollisionShape(level,pos)).toAabbs()
            return boxes.any { it.contains((x+0.5)/16,(y+0.5)/16,(z+0.5)/16) }
        }
        fun material(p:P,id:String)=entity(p).let { be -> Geometry.resolve(be.parts,be.groups).materialSolids(be.material,be.surfaces)[id] ?: Solid() }
        var receipts=0
        for(placement in plan.placements) {
            val pos=world(placement.pos);val be=entity(placement.pos)
            h.assertTrue(PlacementService.matches(level,pos,placement),"Placed geometry and materials must match the generated plan at ${placement.pos}")
            val solid=Geometry.solid(placement.parts)
            val state=level.getBlockState(pos)
            val boxes=state.getCollisionShape(level,pos).toAabbs()
            val contextual=state.getCollisionShape(level,pos,CollisionContext.empty()).toAabbs()
            for(x in 0..15) for(y in 0..15) for(z in 0..15) {
                val filled=solid.has(x,y,z)
                h.assertTrue(boxes.any { it.contains((x+0.5)/16,(y+0.5)/16,(z+0.5)/16) }==filled &&
                    contextual.any { it.contains((x+0.5)/16,(y+0.5)/16,(z+0.5)/16) }==filled,
                    "Both actual world collision paths must match the exact house voxels at ${placement.pos} ($x/$y/$z)")
            }
            val loaded=CompositeEntity(pos,state);loaded.loadWithComponents(be.saveWithoutMetadata(level.registryAccess()),level.registryAccess())
            h.assertTrue(Geometry.resolve(loaded.parts,loaded.groups).visualKey(loaded.material,loaded.surfaces)==placement.visualKey() &&
                Geometry.solid(loaded.parts).key()==solid.key() && loaded.receipt==be.receipt,
                "NBT must retain the thin collision geometry, material ownership and receipt at ${placement.pos}")
            h.assertTrue(be.receipt.units==Extrusion.cost(placement.parts,placement.groups) && be.receipt.surfaces==placement.surfaceItems(),"Every house receipt must equal the material actually paid")
            receipts+=be.receipt.units
        }
        h.assertTrue(receipts==plan.raw,"All placed receipts together must equal the calculated zinc payment")

        val floorTop=plan.placements.filter { it.component=="floor" }.maxOf { it.pos.y }
        val wallBase=floorTop+1
        val sides=listOf(
            Side("north",false,false) { along,y -> P(along,y,-1) },
            Side("south",false,true) { along,y -> P(along,y,PanelBuildingExample.DEPTH) },
            Side("west",true,false) { along,y -> P(-1,y,along) },
            Side("east",true,true) { along,y -> P(PanelBuildingExample.WIDTH,y,along) }
        )
        fun wallPlane(side:Side,depth:Int)=if(side.positive) (0 until depth).toSet() else (16-depth until 16).toSet()
        for(side in sides) {
            val panel=side.at(0,wallBase+1);val window=side.at(1,wallBase+1)
            fun profile(p:P)=(0..15).filter { n -> collision(p,if(side.axisX) n else 8,8,if(side.axisX) 8 else n) }.toSet()
            h.assertTrue(profile(panel)==wallPlane(side,3),"${side.name} wall must have exactly a 3/16 panel at the room-facing edge")
            h.assertTrue(profile(window)==wallPlane(side,2),"${side.name} window must have exactly a 2/16 layer at the room-facing edge")
            val glass=material(window,"minecraft:glass")
            h.assertTrue(glass.count()==12*12*2,"${side.name} glass must remain the 2/16 pane between its 2x2 edge rails")
            glass.voxels { x,y,z ->
                val tangent=if(side.axisX) z else x;val normal=if(side.axisX) x else z
                h.assertTrue(tangent in 2..13 && y in 2..13 && normal in wallPlane(side,2),"${side.name} glass must remain inside its exact thin interior")
            }
            for((height,range) in listOf(wallBase to (0..1),(wallBase+PanelBuildingExample.HEIGHT-1) to (14..15))) {
                val rail=material(side.at(0,height),PanelBuildingExample.building.palette.getValue("trim"))
                h.assertTrue(rail.count()==16*2*2,"${side.name} panel edge must contain a full-length 2x2 material rail")
                rail.voxels { x,y,z -> h.assertTrue(y in range && (if(side.axisX) x else z) in wallPlane(side,2),"${side.name} panel rail must keep its 2/16 height and 2/16 depth") }
            }
            val frame=material(window,PanelBuildingExample.building.palette.getValue("trim"))
            h.assertTrue(frame.count()==(16*16-12*12)*2,"${side.name} window frame must have four exact 2x2 rails")
            frame.voxels { x,y,z ->
                val tangent=if(side.axisX) z else x;val normal=if(side.axisX) x else z
                h.assertTrue((tangent<2 || tangent>=14 || y<2 || y>=14) && normal in wallPlane(side,2),"${side.name} window frame must stay on its edge-mounted 2/16 plane")
            }
        }
        for(x in listOf(-1,PanelBuildingExample.WIDTH)) for(z in listOf(-1,PanelBuildingExample.DEPTH)) for(y in wallBase until wallBase+PanelBuildingExample.HEIGHT) {
            val solid=Geometry.solid(entity(P(x,y,z)).parts)
            h.assertTrue(solid.count()==2*2*16,"Every outer corner must remain a 2x2 vertical rail")
            solid.voxels { vx,_,vz -> h.assertTrue(vx in (if(x<0) 14..15 else 0..1) && vz in (if(z<0) 14..15 else 0..1),"Corner rails must meet both room-facing wall planes") }
        }

        val interior=buildList { for(x in 0 until PanelBuildingExample.WIDTH) for(y in 0 until PanelBuildingExample.HEIGHT) for(z in 0 until PanelBuildingExample.DEPTH) add(P(x,wallBase+y,z)) }
        h.assertTrue(interior.size==64 && interior.all { it !in occupied && level.getBlockState(world(it)).isAir },"All 64 interior block cells must remain free after the complete house is built")
        h.assertTrue(plan.clear.all { level.getBlockState(world(it)).isAir },"Every generated room-clearance requirement must remain unobstructed")
        val before=(0 until inventory.containerSize).map { inventory.getItem(it).copy() }
        for(placement in plan.placements) h.assertTrue(!PlacementService.place(level,world(placement.pos),placement,inventory,actor,"panel-house-repeat"),"Completed house positions must be idempotent")
        h.assertTrue(before.indices.all { ItemStack.matches(before[it],inventory.getItem(it)) },"Idempotent verification must not charge any extra zinc or surfaces")

        val furniture=listOf(
            P(0,wallBase,1) to Blocks.CRAFTING_TABLE,
            P(2,wallBase,0) to Blocks.FURNACE,
            P(PanelBuildingExample.WIDTH-1,wallBase,2) to Blocks.CHEST
        )
        actor.setPos(origin.x+1.5,origin.y+wallBase.toDouble(),origin.z+1.5)
        for((local,block) in furniture) {
            val pos=world(local);val support=pos.below();val held=ItemStack(block)
            actor.setItemInHand(InteractionHand.MAIN_HAND,held)
            val result=actor.gameMode.useItemOn(actor,level,held,InteractionHand.MAIN_HAND,BlockHitResult(Vec3(support.x+0.5,support.y+1.0,support.z+0.5),Direction.UP,support,false))
            h.assertTrue(result.consumesAction() && level.getBlockState(pos).`is`(block) && actor.mainHandItem.isEmpty,"Native ${block.descriptionId} must place normally against a thin wall on the actual Copycat floor")
        }
        h.assertTrue(level.getBlockEntity(world(furniture[1].first)) is FurnaceBlockEntity && level.getBlockEntity(world(furniture[2].first)) is ChestBlockEntity,"Furnace and chest must retain their native functional block entities")
        val furnished=furniture.map { it.first }.toSet()
        h.assertTrue(interior.filterNot { it in furnished }.all { level.getBlockState(world(it)).isAir },"Native furniture must occupy exactly its three chosen cells")
        h.assertTrue(plan.placements.all { PlacementService.matches(level,world(it.pos),it) },"Native interior placement must not replace or alter any wall, rail, floor, ceiling or roof")
        println("ARCHITECT PANEL HOUSE: ${plan.placements.size} Survival placements, ${plan.raw} zinc units, exact surfaces, four 3/16 walls and 2/16 windows, 2x2 rails, 64 free interior cells, native crafting table/furnace/chest and complete NBT/collision verification")
        h.succeed()
    }
}
