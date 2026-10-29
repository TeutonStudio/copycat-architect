package de.teutonstudio.copycatarchitect.server

import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import net.neoforged.neoforge.common.util.FakePlayerFactory
import com.mojang.authlib.GameProfile
import java.util.UUID

@GameTestHolder(Architect.ID)
@PrefixGameTestTemplate(false)
object IntegrationTests {
    @JvmStatic @GameTest(template="empty") fun rawCopycatsAndMaterialValidation(h:GameTestHelper) {
        val groups=listOf(MaterialGroup(),MaterialGroup("cap","Cap","frame",1))
        val parts=listOf(Part("copycats:copycat_slab"),Part("copycats:copycat_slab",top=true,group="cap"))
        val raw=Placement(parts=parts,material="",groups=groups)
        h.assertTrue(raw.surfaceItems().isEmpty(),"Uncoated Copycats must not require a surface item")
        h.assertTrue(raw.copy(surfaces=mapOf("cap" to "minecraft:gold_block")).surfaceItems()==mapOf("minecraft:gold_block" to 1),"Only coated groups consume a material sample")
        h.assertTrue(raw.visualKey()!=raw.copy(parts=listOf(parts[0])).visualKey(),"Raw geometry must remain part of placement identity")
        h.assertTrue(CopycatMaterials.state("minecraft:gold_block").block==Blocks.GOLD_BLOCK,"Inventory full blocks are valid coatings outside the preset palette")
        h.assertTrue(CopycatMaterials.state("minecraft:glass").block==Blocks.GLASS,"Glass remains a valid Copycat coating")
        h.assertTrue(runCatching { CopycatMaterials.state("minecraft:chest") }.isFailure,"Block entity materials must be rejected")
        h.assertTrue(runCatching { CopycatMaterials.state("minecraft:oak_slab") }.isFailure,"Non-full block materials must be rejected")
        h.assertTrue(runCatching { CopycatMaterials.state("copycat_architect:composite") }.isFailure,"A Copycat cannot use another functional Copycat as its coating")
        h.succeed()
    }
    @JvmStatic @GameTest(template="empty") fun copycatCoatingsUseHitGroupsAndRefundSharedSamplesOnce(h:GameTestHelper) {
        val level=h.level;val pos=h.absolutePos(BlockPos(1,1,1))
        val actor=FakePlayerFactory.get(level,GameProfile(UUID.randomUUID(),"ArchitectCoatings"))
        val groups=listOf(MaterialGroup(),MaterialGroup("cap","Cap","frame",1))
        val parts=listOf(Part("copycats:copycat_slab"),Part("copycats:copycat_slab",top=true,group="cap"))
        level.setBlockAndUpdate(pos,Architect.COMPOSITE.get().defaultBlockState())
        val be=level.getBlockEntity(pos) as CompositeEntity
        be.install(Placement(parts=parts,material="",groups=groups),Receipt(units=256,operation="coatings"),actor.uuid.toString())
        val state=be.blockState;val block=state.block as CompositeBlock
        fun hit(y:Double)=net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3(pos.x+0.5,pos.y+y,pos.z+1.0),net.minecraft.core.Direction.SOUTH,pos,false)
        val lower=hit(0.25);val upper=hit(0.75)
        val stone=ItemStack(net.minecraft.world.item.Items.STONE,2)
        actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,stone)
        state.useItemOn(stone,level,actor,net.minecraft.world.InteractionHand.MAIN_HAND,lower)
        h.assertTrue(stone.count==1 && be.coating("body")=="minecraft:stone" && be.coating("cap").isEmpty(),"Applying a block must coat only the hit group and consume one sample")
        state.useItemOn(stone,level,actor,net.minecraft.world.InteractionHand.MAIN_HAND,upper)
        h.assertTrue(stone.count==1 && be.receipt.surfaces==mapOf("minecraft:stone" to 1),"Two groups sharing one material sample must not debit twice")
        val wrench=ItemStack(Economy.item("create:wrench"));actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,wrench)
        fun context(hit:net.minecraft.world.phys.BlockHitResult)=net.minecraft.world.item.context.UseOnContext(actor,net.minecraft.world.InteractionHand.MAIN_HAND,hit)
        fun stoneCount()=(0 until actor.inventory.containerSize).sumOf { i -> actor.inventory.getItem(i).let { if(it.`is`(net.minecraft.world.item.Items.STONE)) it.count else 0 } }
        block.onWrenched(state,context(lower))
        h.assertTrue(be.coating("body").isEmpty() && be.coating("cap")=="minecraft:stone" && stoneCount()==0,"Wrenching one group must retain the shared material until its last use")
        block.onSneakWrenched(state,context(upper))
        h.assertTrue(level.getBlockEntity(pos)===be && be.coating("cap").isEmpty() && stoneCount()==1 && be.receipt.surfaces.isEmpty(),"Sneak wrench removes only the hit coating and refunds its sample once")
        block.onWrenched(state,context(upper))
        h.assertTrue(stoneCount()==1 && be.receipt.units==256 && be.receipt.operation=="coatings","Repeated wrenching must not duplicate materials or modify extrusion receipts")
        val glass=ItemStack(net.minecraft.world.item.Items.GLASS,2);actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,glass)
        state.useItemOn(glass,level,actor,net.minecraft.world.InteractionHand.MAIN_HAND,upper)
        val loaded=CompositeEntity(pos,state);loaded.loadWithComponents(be.saveWithoutMetadata(level.registryAccess()),level.registryAccess())
        h.assertTrue(glass.count==1 && loaded.material.isEmpty() && loaded.coating("body").isEmpty() && loaded.coating("cap")=="minecraft:glass" && loaded.receipt==be.receipt,"Raw and coated groups plus their paid receipts must survive NBT reload")
        h.assertTrue(loaded.removeCoating("cap").count==1 && loaded.removeCoating("cap").isEmpty && loaded.receipt.units==256,"Reloaded coatings must refund only their paid material once")
        level.removeBlock(pos,false);h.succeed()
    }
    @JvmStatic @GameTest(template="empty") fun survivalReceiptRoundtrip(h:GameTestHelper) {
        val l=h.level;val pos=h.absolutePos(BlockPos(1,1,1));l.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState())
        val actor=FakePlayerFactory.get(l,GameProfile(UUID.randomUUID(),"ArchitectTest"));actor.setPos(pos.x+3.0,pos.y.toDouble(),pos.z+3.0)
        val inv=SimpleContainer(4);inv.setItem(0,Economy.cartridge(256));inv.setItem(1,ItemStack(net.minecraft.world.item.Items.STONE,2))
        val placement=Placement(P(),listOf(Part(),Part(turn=1)),"minecraft:stone")
        PlacementService.place(l,pos,placement,inv,actor,"test")
        h.assertTrue(Economy.units(inv.getItem(0))==224,"Must debit exactly 32 units")
        h.assertTrue(inv.getItem(1).count==1,"Must consume one surface")
        h.assertTrue(!PlacementService.place(l,pos,placement,inv,actor,"test"),"Repeat must be idempotent")
        h.assertTrue(Economy.units(inv.getItem(0))==224,"Repeat must not charge")
        val be=l.getBlockEntity(pos) as CompositeEntity
        val tag=be.saveWithoutMetadata(l.registryAccess());val loaded=CompositeEntity(pos,be.blockState);loaded.loadWithComponents(tag,l.registryAccess())
        h.assertTrue(Geometry.solid(loaded.parts).key()==Geometry.solid(placement.parts).key(),"Geometry survives NBT reload")
        h.assertTrue(loaded.receipt.units==32,"Receipt survives reload")
        l.destroyBlock(pos,true,actor)
        h.succeed()
    }
    @JvmStatic @GameTest(template="empty") fun printerPlacesOnCopycatFaces(h:GameTestHelper) {
        val level=h.level
        val actor=FakePlayerFactory.get(level,GameProfile(UUID.randomUUID(),"ArchitectPrinterFaces"))
        actor.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL)
        var printer=ItemStack(Architect.GUN.get())
        val form=Geometry.validate(Form(id="printer_faces",parts=listOf(Part("copycats:copycat_block"))))
        PrinterStack.draft(printer,form,emptyMap(),0)
        actor.inventory.setItem(1,Economy.cartridge(4096));actor.inventory.setItem(2,ItemStack(net.minecraft.world.item.Items.STONE,32))
        for(part in listOf(Part("copycats:copycat_block"),Part("copycats:copycat_slab"))) {
            val base=h.absolutePos(BlockPos(1,1,1));actor.setPos(base.x+4.0,base.y.toDouble(),base.z+4.0)
            (net.minecraft.core.Direction.entries.map { base.relative(it) }+base).forEach { level.setBlockAndUpdate(it,Blocks.AIR.defaultBlockState()) }
            val support=Placement(parts=listOf(part))
            PlacementService.place(level,base,support,actor.inventory,actor,"printer-support",creative=true)
            val center=(level.getBlockEntity(base) as CompositeEntity).shape().bounds().center.add(net.minecraft.world.phys.Vec3.atLowerCornerOf(base))
            for(face in net.minecraft.core.Direction.entries) {
                actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,printer)
                val target=base.relative(face);val start=center.add(net.minecraft.world.phys.Vec3.atLowerCornerOf(face.normal).scale(1.1))
                val hit=level.clip(net.minecraft.world.level.ClipContext(start,center,net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,actor))
                h.assertTrue(hit.blockPos==base && hit.direction==face,"Raycast must hit the Copycat face $face ($part)")
                val result=actor.gameMode.useItemOn(actor,level,printer,net.minecraft.world.InteractionHand.MAIN_HAND,hit)
                h.assertTrue(result.consumesAction(),"Printer interaction must handle Copycat face $face ($part)")
                val be=level.getBlockEntity(target) as? PrinterEntity
                h.assertTrue(be!=null && be.owner==actor.uuid.toString(),"Printer must be placed next to Copycat face $face ($part)")
                h.assertTrue(PlacementService.matches(level,base,support),"Printer placement must preserve the clicked Copycat")
                printer=PrinterService.cancelDirect(level,be!!,actor)
                h.assertTrue(level.getBlockState(target).isAir && printer.`is`(Architect.GUN.get()) && printer.count==1,"Cancel must recover the reusable printer without leaving a printed Copycat")
            }
            val hit=net.minecraft.world.phys.BlockHitResult(center,net.minecraft.core.Direction.UP,base,false)
            h.assertTrue(level.getBlockState(base).useItemOn(ItemStack.EMPTY,level,actor,net.minecraft.world.InteractionHand.MAIN_HAND,hit)==net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION,"Empty-hand group inspection remains available")
            h.assertTrue(level.getBlockState(base).useWithoutItem(level,actor,hit).consumesAction(),"Empty-hand group inspection still handles the click")
            level.destroyBlock(base,true,actor)
            level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java,net.minecraft.world.phys.AABB(base).inflate(2.0)).forEach { it.discard() }
        }
        h.assertTrue(Economy.units(actor.inventory.getItem(1))==4096 && actor.inventory.getItem(2).count==32,"Placing and cancelling twelve printers must consume no extrusion material or surfaces")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun graphCutPlacementAndReload(h:GameTestHelper) {
        val l=h.level;val pos=h.absolutePos(BlockPos(1,1,1));l.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState())
        val actor=FakePlayerFactory.get(l,GameProfile(UUID.randomUUID(),"ArchitectGraph"));actor.setPos(pos.x+3.0,pos.y.toDouble(),pos.z+3.0)
        val graph=NodeGraph(nodes=listOf(GraphNode("body",part=Part("copycats:copycat_block")),
            GraphNode("cutter",part=Part("copycats:copycat_vertical_slice",size=3)),
            GraphNode("cut",NodeKind.DIFFERENCE,listOf("body","cutter")),GraphNode("result",NodeKind.OUTPUT,listOf("cut"))))
        val form=Geometry.validate(Form(graph=graph))
        val placement=Placement(parts=Geometry.rotate(form.parts,1))
        val inv=SimpleContainer(3);inv.setItem(0,Economy.cartridge(512));inv.setItem(1,ItemStack(net.minecraft.world.item.Items.STONE,2))
        PlacementService.place(l,pos,placement,inv,actor,"graph")
        h.assertTrue(Economy.units(inv.getItem(0))==256 && inv.getItem(1).count==1,"Only surviving source and surface are charged")
        val be=l.getBlockEntity(pos) as CompositeEntity
        val loaded=CompositeEntity(pos,be.blockState);loaded.loadWithComponents(be.saveWithoutMetadata(l.registryAccess()),l.registryAccess())
        val solid=Geometry.solid(placement.parts)
        h.assertTrue(Geometry.solid(loaded.parts).key()==solid.key(),"Clipped shape persists without a live graph")
        for(x in 0..15) for(y in 0..15) for(z in 0..15) h.assertTrue(loaded.shape().toAabbs().any { it.contains((x+0.5)/16,(y+0.5)/16,(z+0.5)/16) }==solid.has(x,y,z),"Clipped collision must match the rendered grid")
        h.assertTrue(!PlacementService.place(l,pos,placement,inv,actor,"repeat"),"Graph placement is idempotent")
        h.assertTrue(loaded.receipt.units==256,"Cutting cannot manufacture refundable sources")
        l.destroyBlock(pos,true,actor);h.succeed()
    }
    @JvmStatic @GameTest(template="empty") fun nodeMaterialBranchesSurvivalRoundtrip(h:GameTestHelper) {
        val l=h.level;val pos=h.absolutePos(BlockPos(1,1,1));l.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState())
        val actor=FakePlayerFactory.get(l,GameProfile(UUID.randomUUID(),"ArchitectBranches"));actor.setPos(pos.x+3.0,pos.y.toDouble(),pos.z+3.0)
        val groups=listOf(MaterialGroup(),MaterialGroup("cap","Wood","frame",1))
        val graph=NodeGraph(nodes=listOf(GraphNode("source",part=Part("copycats:copycat_block")),GraphNode("mask",part=Part("copycats:copycat_slab")),
            GraphNode("bottom",NodeKind.INTERSECTION,listOf("source","mask"),materialMode=MaterialMode.GROUPED,materialGroup="cap"),
            GraphNode("top",NodeKind.DIFFERENCE,listOf("source","mask")),GraphNode("join",NodeKind.UNION,listOf("top","bottom")),
            GraphNode("result",NodeKind.OUTPUT,listOf("join"),previewGroup="cap")))
        val form=Geometry.validate(Form(graph=graph,groups=groups))
        val placement=Placement(parts=form.parts,groups=groups,surfaces=mapOf("body" to "minecraft:stone","cap" to "minecraft:oak_planks"))
        val inv=SimpleContainer(4);inv.setItem(0,Economy.cartridge(512));inv.setItem(1,ItemStack(net.minecraft.world.item.Items.STONE,2));inv.setItem(2,ItemStack(net.minecraft.world.item.Items.OAK_PLANKS,2))
        PlacementService.place(l,pos,placement,inv,actor,"branches")
        h.assertTrue(Economy.units(inv.getItem(0))==256,"Shared source is paid once across material branches")
        h.assertTrue(inv.getItem(1).count==1 && inv.getItem(2).count==1,"Both visible surfaces are paid")
        val be=l.getBlockEntity(pos) as CompositeEntity;val loaded=CompositeEntity(pos,be.blockState)
        loaded.loadWithComponents(be.saveWithoutMetadata(l.registryAccess()),l.registryAccess())
        val resolved=Geometry.resolve(loaded.parts,loaded.groups)
        h.assertTrue(resolved.owner(0,0,0)=="cap" && resolved.owner(0,15,0)=="body","Branch materials survive NBT")
        h.assertTrue(resolved.solid.count()==4096,"Preview selection must never remove placed geometry")
        h.assertTrue(Extrusion.cost(loaded.parts,loaded.groups)==256 && loaded.receipt.units==256,"Shared source accounting persists")
        h.assertTrue(!PlacementService.place(l,pos,placement,inv,actor,"repeat"),"Identical branches remain idempotent")
        l.destroyBlock(pos,true,actor)
        val drops=l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java,net.minecraft.world.phys.AABB(pos).inflate(1.0))
        h.assertTrue(drops.filter { it.item.`is`(Architect.CARTRIDGE.get()) }.sumOf { Economy.units(it.item) }==256,"Only paid source units are refunded")
        h.assertTrue(drops.any { it.item.`is`(net.minecraft.world.item.Items.STONE) } && drops.any { it.item.`is`(net.minecraft.world.item.Items.OAK_PLANKS) },"Both paid surfaces are refunded")
        h.succeed()
    }
    @JvmStatic @GameTest(template="empty") fun dependenciesAndPersistence(h:GameTestHelper) {
        for(a in Adapters.all.values) h.assertTrue(BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(a.source)),"Missing source ${a.source}")
        h.assertTrue(Architect.UPGRADE.get()!=null,"Upgrade type registered")
        val l=h.level;val owner=UUID.randomUUID();val d=ArchitectData.get(l);val lib=d.library(owner)
        val plan=Generator.generate(lib.buildings.getValue("demo@1"),lib.forms,lib.modules)
        val job=Job(owner=owner.toString(),plan=plan,index=7)
        d.db.jobs+=job
        val tag=d.save(net.minecraft.nbt.CompoundTag(),l.registryAccess())
        val output=java.io.ByteArrayOutputStream()
        net.minecraft.nbt.NbtIo.writeCompressed(tag,output)
        val fromDisk=net.minecraft.nbt.NbtIo.readCompressed(java.io.ByteArrayInputStream(output.toByteArray()),net.minecraft.nbt.NbtAccounter.unlimitedHeap())
        val restored=Json.decode<Database>(String(fromDisk.getByteArray("architect"),Charsets.UTF_8))
        h.assertTrue(restored.jobs.last().index==7,"Job progress persists")
        h.assertTrue(restored.libraries.getValue(owner.toString()).modules.size==2,"Modules persist")
        h.succeed()
    }
    @JvmStatic @GameTest(template="empty",timeoutTicks=200000) fun completeSurvivalTurtleWithReboot(h:GameTestHelper) {
        if(java.lang.Boolean.getBoolean("architect.graphWorldSmoke")) { h.succeed();return }
        val l=h.level
        val owner=GameProfile(UUID.randomUUID(),"ArchitectBuilder")
        val actor=FakePlayerFactory.get(l,owner)
        val dock=h.absolutePos(BlockPos(2,2,2));val origin=PlacementService.local(dock)+P(5,-1,5)
        val d=ArchitectData.get(l);val lib=d.library(owner.id)
        val graph=NodeGraph(nodes=listOf(
            GraphNode("body",part=Part("copycats:copycat_block")),
            GraphNode("cutter",part=Part("copycats:copycat_slab",top=true)),
            GraphNode("cap",part=Part("copycats:copycat_vertical_slice",size=3,group="cap")),
            GraphNode("cut",NodeKind.DIFFERENCE,listOf("body","cutter"),materialMode=MaterialMode.GROUPED,materialGroup="body"),
            GraphNode("clip",NodeKind.INTERSECTION,listOf("cutter","cap"),materialMode=MaterialMode.GROUPED,materialGroup="cap"),
            GraphNode("join",NodeKind.UNION,listOf("cut","clip")),
            GraphNode("result",NodeKind.OUTPUT,listOf("join"))))
        lib.forms["multi@1"]=Geometry.validate(Form(id="multi",graph=graph,groups=listOf(MaterialGroup(),MaterialGroup("cap","Oak cap","frame",1))))
        val b=lib.buildings.getValue("demo@1").copy(floorForm="multi@1",ceilingForm="multi@1",palette=Building().palette.mapValues { if(it.key=="frame") "minecraft:oak_planks" else "minecraft:stone" })
        val local=Generator.generate(b,lib.forms,lib.modules)
        val plan=local.copy(placements=local.placements.map { it.copy(pos=origin+it.pos) },clear=local.clear.map { origin+it })
        val j=Job(owner=owner.id.toString(),dimension=l.dimension().location().toString(),name="Turtle L integration",origin=origin,dock=PlacementService.local(dock),plan=plan)
        val (_,max)=Jobs.bounds(j)
        for(x in dock.x..max.x) for(z in dock.z..max.z) for(y in origin.y..max.y) l.setBlockAndUpdate(BlockPos(x,y,z),Blocks.AIR.defaultBlockState())
        l.setBlockAndUpdate(dock.below(),Blocks.CHEST.defaultBlockState())
        val chest=l.getBlockEntity(dock.below()) as net.minecraft.world.level.block.entity.ChestBlockEntity
        for(i in 0 until chest.containerSize) chest.setItem(i,ItemStack(if(i<18) net.minecraft.world.item.Items.STONE else if(i<26) net.minecraft.world.item.Items.OAK_PLANKS else net.minecraft.world.item.Items.COAL,64))
        val turtleBlock=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("computercraft:turtle_normal"))
        l.setBlockAndUpdate(dock,turtleBlock.defaultBlockState())
        turtleBlock.setPlacedBy(l,dock,turtleBlock.defaultBlockState(),actor,ItemStack(turtleBlock))
        val turtle=l.getBlockEntity(dock) as dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity
        val access=turtle.access
        val holder=l.registryAccess().registryOrThrow(dan200.computercraft.api.turtle.ITurtleUpgrade.REGISTRY).getHolderOrThrow(dan200.computercraft.api.turtle.ITurtleUpgrade.createKey(ResourceLocation.parse("copycat_architect:extrusion_gun")))
        access.setUpgrade(dan200.computercraft.api.turtle.TurtleSide.LEFT,dan200.computercraft.api.upgrades.UpgradeData.ofDefault(holder))
        turtle.setItem(0,Economy.cartridge(1000000));turtle.setItem(1,ItemStack(net.minecraft.world.item.Items.COAL,64));turtle.setItem(2,ItemStack(net.minecraft.world.item.Items.STONE,2))
        val computer=turtle.createServerComputer()
        val startup="shell.run(\"architect\", \"${j.id}\")".toByteArray(Charsets.UTF_8)
        computer.createRootMount()!!.openFile("startup.lua",setOf(java.nio.file.StandardOpenOption.WRITE,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)).use { it.write(java.nio.ByteBuffer.wrap(startup)) }
        d.db.jobs+=j;d.setDirty();Jobs.beginCheck(l,j);computer.turnOn()
        var rebooted=false;var pausedAt=0L;var resumed=false
        h.onEachTick {
            if(j.index>12 && !rebooted) { computer.reboot();rebooted=true }
            if(j.index>256 && pausedAt==0L) { j.state="paused";j.message="Integration pause";pausedAt=h.tick }
            if(pausedAt>0 && h.tick>pausedAt+50 && !resumed) { Jobs.beginCheck(l,j);computer.reboot();resumed=true }
            if(h.tick % 2000L==0L) {
                val terminal=computer.terminalState.create()
                val text=(0 until terminal.height).joinToString(" | ") { terminal.getLine(it).toString().trim() }
                println("ARCHITECT TURTLE ${j.index}/${plan.placements.size}: $text")
            }
            if(j.state=="blocked") h.fail(j.message)
        }
        h.succeedWhen {
            h.assertTrue(j.state=="complete","Building in progress ${j.index}/${plan.placements.size}: ${j.message}")
            h.assertTrue(rebooted && resumed,"Turtle must reboot, pause and resume")
            h.assertTrue(access.position==dock && j.message.startsWith("Complete;"),"Turtle must return to depot")
            for(p in plan.placements) h.assertTrue(PlacementService.matches(l,PlacementService.world(p.pos),p),"World differs from normalized plan at ${p.pos}")
            for(p in plan.clear) h.assertTrue(l.getBlockState(PlacementService.world(p)).isAir,"Opening filled")
        }
    }

    @JvmStatic @GameTest(template="empty") fun inventoryFailureAndBlockedTargets(h:GameTestHelper) {
        val l=h.level;val pos=h.absolutePos(BlockPos(1,1,1));l.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState())
        val actor=FakePlayerFactory.get(l,GameProfile(UUID.randomUUID(),"ArchitectFailures"));actor.setPos(pos.x+3.0,pos.y.toDouble(),pos.z+3.0)
        val inv=SimpleContainer(3);inv.setItem(0,Economy.cartridge(256))
        val placement=Placement(P(),listOf(Part()),"minecraft:stone")
        h.assertTrue(runCatching { PlacementService.place(l,pos,placement,inv,actor,"missing") }.isFailure,"Missing material must fail")
        h.assertTrue(Economy.units(inv.getItem(0))==256 && l.getBlockState(pos).isAir,"Failure must leave inventory and world intact")
        inv.setItem(1,ItemStack(net.minecraft.world.item.Items.STONE,2));l.setBlockAndUpdate(pos,Blocks.DIRT.defaultBlockState())
        h.assertTrue(runCatching { PlacementService.place(l,pos,placement,inv,actor,"blocked") }.isFailure,"Foreign block must block placement")
        h.assertTrue(Economy.units(inv.getItem(0))==256 && inv.getItem(1).count==2,"Blocked placement must not charge")
        val full=SimpleContainer(2);full.setItem(0,ItemStack(Economy.item("create:zinc_ingot"),2));full.setItem(1,ItemStack(net.minecraft.world.item.Items.STONE,2))
        h.assertTrue(runCatching { Economy.prepare(full,16,"minecraft:stone",1) }.isFailure,"Full inventory must preserve fractional remainder")
        h.assertTrue(full.getItem(0).count==2 && full.getItem(1).count==2,"Prepare must be atomic")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty",timeoutTicks=400) fun adaptersMatchPublishedSourceStates(h:GameTestHelper) {
        for(a in Adapters.all.values) for(size in 1..a.max) for(top in listOf(false,true)) for(turn in 0..3) for(tilt in (if(a.tiltable) 0..3 else 0..0)) for(spin in 0..3) {
            val p=Part(a.source,turn,top,size,tilt=tilt,spin=spin)
            val block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse(a.source))
            val source=de.teutonstudio.copycatarchitect.integration.SourceStates.apply(block.defaultBlockState(),p.sourceProperties())
            val boxes=source.getShape(h.level,h.absolutePos(BlockPos.ZERO)).toAabbs()
            val local=Solid.part(p)
            for(x in 0..15) for(y in 0..15) for(z in 0..15) {
                val hit=boxes.any { it.contains((x+0.5)/16,(y+0.5)/16,(z+0.5)/16) }
                if(hit!=local.has(x,y,z)) h.fail("Source mismatch: $p at $x/$y/$z")
            }
            if(a.source=="copycats:copycat_flat_pane") for(cut in 1..2) {
                val fraction=Solid.part(p.copy(discCut=cut))
                h.assertTrue(local.contains(fraction),"Centered disc fraction must stay inside its native pane: $p, cut=$cut")
                h.assertTrue(fraction.count()==local.count()/(if(cut==1) 2 else 4),"Centered disc fraction must retain its exact native thickness")
            }
        }
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty",timeoutTicks=400) fun centeredDiscCompositionWorldAndRefund(h:GameTestHelper) {
        val l=h.level;val pos=h.absolutePos(BlockPos(1,1,1))
        val actor=FakePlayerFactory.get(l,GameProfile(UUID.randomUUID(),"ArchitectDisc"));actor.setPos(pos.x+3.0,pos.y.toDouble(),pos.z+3.0)
        fun canonicalItemStacks(stacks:List<ItemStack>):Map<String,Int> = stacks.filterNot { it.isEmpty }
            .groupBy { it.copyWithCount(1).save(l.registryAccess()).toString() }.mapValues { (_,items) -> items.sumOf { it.count } }.toSortedMap()
        fun inventoryItems(inventory:SimpleContainer)=canonicalItemStacks((0 until inventory.containerSize).map { inventory.getItem(it) })
        fun collisionKey():String {
            val bits=java.util.BitSet(4096);val state=l.getBlockState(pos)
            val boxes=state.getCollisionShape(l,pos).toAabbs()
            val contextual=state.getCollisionShape(l,pos,net.minecraft.world.phys.shapes.CollisionContext.empty()).toAabbs()
            for(x in 0..15) for(y in 0..15) for(z in 0..15) {
                val filled=boxes.any { it.contains((x+0.5)/16,(y+0.5)/16,(z+0.5)/16) }
                h.assertTrue(filled==contextual.any { it.contains((x+0.5)/16,(y+0.5)/16,(z+0.5)/16) },"Context-free and contextual world collision must both use the placed block entity")
                if(filled) bits.set(x+16*z+256*y)
            }
            return bits.toLongArray().joinToString(",")
        }
        fun compiled(parts:List<Part>):Placement {
            val nodes=parts.mapIndexed { i,p -> GraphNode("disc$i",part=p) }
            val graph=NodeGraph(nodes=if(nodes.size==1) nodes+GraphNode("result",NodeKind.OUTPUT,listOf(nodes.single().id)) else
                nodes+GraphNode("join",NodeKind.UNION,nodes.map { it.id })+GraphNode("result",NodeKind.OUTPUT,listOf("join")))
            return Placement(parts=GraphCompiler.compile(graph),material="minecraft:stone")
        }
        val expectedDrops=canonicalItemStacks(listOf(Economy.cartridge(32),ItemStack(net.minecraft.world.item.Items.STONE)))
        val orientations=listOf(Part("copycats:copycat_flat_pane"),Part("copycats:copycat_flat_pane",top=true),
            Part("copycats:copycat_flat_pane",tilt=1),Part("copycats:copycat_flat_pane",tilt=3),
            Part("copycats:copycat_flat_pane",tilt=1,turn=1),Part("copycats:copycat_flat_pane",tilt=1,turn=3))
        for(orientation in orientations) {
            val whole=compiled(listOf(orientation))
            val quarter=orientation.copy(discCut=2)
            val quarters=compiled((0..3).map { checkNotNull(PartRotations.rotate(quarter,RotationAxis.NORMAL,it)) })
            h.assertTrue(whole.visualKey()==quarters.visualKey(),"Four normal-axis quarters must compile to the whole centered disc: $orientation")
            h.assertTrue(Extrusion.cost(whole.parts)==32 && Extrusion.cost(quarters.parts)==32,"Whole disc and four quarters must both cost 32 units")
            var wholeState:net.minecraft.world.level.block.state.BlockState?=null
            var wholeCollision="";var wholeMaterials="";var wholeRefund:Map<String,Int>?=null
            for((index,placement) in listOf(whole,quarters).withIndex()) {
                l.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState())
                val inventory=SimpleContainer(3);inventory.setItem(0,Economy.cartridge(256));inventory.setItem(1,ItemStack(net.minecraft.world.item.Items.STONE,2))
                h.assertTrue(PlacementService.place(l,pos,placement,inventory,actor,"disc"),"Disc graph must place successfully")
                val state=l.getBlockState(pos);val entity=l.getBlockEntity(pos) as CompositeEntity
                h.assertTrue(state.`is`(Architect.COMPOSITE.get()),"Every disc representation must use the same placed block type")
                h.assertTrue(Economy.units(inventory.getItem(0))==224 && inventory.getItem(1).count==1,"Every disc representation must consume exactly 32 units and one surface")
                h.assertTrue(entity.receipt.units==32 && entity.receipt.surfaces==mapOf("minecraft:stone" to 1),"Disc receipt must record the same paid inputs")
                val collision=collisionKey();val materials=Geometry.resolve(entity.parts,entity.groups).visualKey(entity.material,entity.surfaces)
                h.assertTrue(collision==Geometry.solid(whole.parts).key(),"Placed collision must match every whole-disc voxel: $orientation")
                h.assertTrue(materials==whole.visualKey(),"Placed materials must match the whole centered disc")
                val loaded=CompositeEntity(pos,state);loaded.loadWithComponents(entity.saveWithoutMetadata(l.registryAccess()),l.registryAccess())
                h.assertTrue(Geometry.resolve(loaded.parts,loaded.groups).visualKey(loaded.material,loaded.surfaces)==whole.visualKey() && loaded.receipt==entity.receipt,"Disc orientation, material and receipt must survive NBT reload")
                val beforeRepeat=inventoryItems(inventory)
                val equivalent=if(index==0) quarters else whole
                h.assertTrue(PlacementService.matches(l,pos,equivalent),"The other graph representation must match the existing disc")
                h.assertTrue(!PlacementService.place(l,pos,equivalent,inventory,actor,"disc-equivalent"),"Equivalent disc graph must be idempotent")
                h.assertTrue(inventoryItems(inventory)==beforeRepeat,"Switching disc representations must never charge again")
                h.assertTrue(l.destroyBlock(pos,true,actor),"Disc must be breakable")
                val drops=l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java,net.minecraft.world.phys.AABB(pos).inflate(1.0))
                val refund=canonicalItemStacks(drops.map { it.item });drops.forEach { it.discard() }
                h.assertTrue(refund==expectedDrops,"Actual disc break drops must be exactly one 32-unit cartridge and its surface, including item components")
                if(index==0) { wholeState=state;wholeCollision=collision;wholeMaterials=materials;wholeRefund=refund }
                else h.assertTrue(state==wholeState && collision==wholeCollision && materials==wholeMaterials && refund==wholeRefund,"Whole disc and four rotated quarters must have identical world block, collision, material and actual break drops")
            }
        }
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun groupedMaterialsConsumptionPersistenceAndRefund(h:GameTestHelper) {
        val l=h.level;val pos=h.absolutePos(BlockPos(1,1,1));l.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState())
        val actor=FakePlayerFactory.get(l,GameProfile(UUID.randomUUID(),"ArchitectGroups"));actor.setPos(pos.x+3.0,pos.y.toDouble(),pos.z+3.0)
        val groups=listOf(MaterialGroup(),MaterialGroup("cap","Cap","frame",1))
        val p=Placement(parts=listOf(Part("copycats:copycat_block"),Part("copycats:copycat_layer",top=true,group="cap")),groups=groups,surfaces=mapOf("body" to "minecraft:stone","cap" to "minecraft:oak_planks"))
        val inventory=SimpleContainer(4);inventory.setItem(0,Economy.cartridge(512));inventory.setItem(1,ItemStack(net.minecraft.world.item.Items.STONE,2))
        h.assertTrue(runCatching { PlacementService.place(l,pos,p,inventory,actor,"groups") }.isFailure,"All group materials must be available before charging")
        h.assertTrue(Economy.units(inventory.getItem(0))==512 && inventory.getItem(1).count==2,"Missing group must not debit other materials")
        inventory.setItem(2,ItemStack(net.minecraft.world.item.Items.OAK_PLANKS,2))
        PlacementService.place(l,pos,p,inventory,actor,"groups")
        h.assertTrue(Economy.units(inventory.getItem(0))==224 && inventory.getItem(1).count==1 && inventory.getItem(2).count==1,"Exactly 288 units and two surfaces must be consumed")
        h.assertTrue(!PlacementService.place(l,pos,p,inventory,actor,"groups"),"Grouped repetition must be idempotent")
        val be=l.getBlockEntity(pos) as CompositeEntity
        val copy=CompositeEntity(pos,be.blockState);copy.loadWithComponents(be.saveWithoutMetadata(l.registryAccess()),l.registryAccess())
        h.assertTrue(copy.groups==groups && copy.surfaces==p.surfaces && copy.receipt.surfaces==p.surfaceItems(),"Group priorities, materials and receipt must survive world serialization")
        h.assertTrue(!PlacementService.matches(l,pos,p.copy(groups=groups.map { it.copy(priority=1-it.priority) })),"Different foreground must not be mistaken for a completed target")
        l.destroyBlock(pos,true,actor)
        val items=l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java,net.minecraft.world.phys.AABB(pos).inflate(1.0))
        h.assertTrue(items.filter { it.item.`is`(Architect.CARTRIDGE.get()) }.sumOf { Economy.units(it.item) }==288,"Refund must return paid raw units")
        h.assertTrue(items.any { it.item.`is`(net.minecraft.world.item.Items.STONE) } && items.any { it.item.`is`(net.minecraft.world.item.Items.OAK_PLANKS) },"Both paid materials must be refunded")
        h.succeed()
    }

    @JvmStatic @GameTest(template="empty") fun carpetFloorUsesNativeItemsAndChecksSupport(h:GameTestHelper) {
        val l=h.level;val pos=h.absolutePos(BlockPos(1,2,1));l.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());l.setBlockAndUpdate(pos.below(),Blocks.AIR.defaultBlockState())
        val actor=FakePlayerFactory.get(l,GameProfile(UUID.randomUUID(),"ArchitectCarpet"));actor.setPos(pos.x+3.0,pos.y.toDouble(),pos.z+3.0)
        val inv=SimpleContainer(4);inv.setItem(0,Economy.cartridge(512));inv.setItem(1,ItemStack(net.minecraft.world.item.Items.STONE,2));inv.setItem(2,ItemStack(net.minecraft.world.item.Items.WHITE_CARPET,2))
        val carpet=Placement(block="minecraft:white_carpet",component="floor")
        h.assertTrue(runCatching { PlacementService.place(l,pos,carpet,inv,actor,"unsupported") }.isFailure,"Carpet requires a supporting block")
        h.assertTrue(inv.getItem(2).count==2 && l.getBlockState(pos).isAir,"Unsupported carpet must not consume or appear")
        PlacementService.place(l,pos.below(),Placement(parts=listOf(Part("copycats:copycat_block"))),inv,actor,"support")
        PlacementService.place(l,pos,carpet,inv,actor,"finish")
        h.assertTrue(l.getBlockState(pos).`is`(Blocks.WHITE_CARPET) && inv.getItem(2).count==1,"Floor finish is a real carpet with real consumption")
        h.assertTrue(Economy.units(inv.getItem(0))==256,"Only the structural Copycat requires zinc")
        h.assertTrue(!PlacementService.place(l,pos,carpet,inv,actor,"repeat"),"Carpet repeat is idempotent")
        h.succeed()
    }
    @JvmStatic @GameTest(template="empty",timeoutTicks=180000) fun graphBuildingTurtleWithBasementAndCarpet(h:GameTestHelper) {
        val l=h.level;val owner=GameProfile(UUID.randomUUID(),"ArchitectGraphBuild");val actor=FakePlayerFactory.get(l,owner)
        val dock=h.absolutePos(BlockPos(2,2,2)).offset(96,0,96);val origin=PlacementService.local(dock)+P(5,-1,5)
        val d=ArchitectData.get(l);val lib=d.library(owner.id);var graph=BuildingGraph.starter()
        graph=graph.copy(nodes=graph.nodes+BuildingNode("unit",BuildingKind.SEGMENT,"A",module="",length=1)+BuildingNode("BC",BuildingKind.PATTERN,"BC",inputs=listOf("unit","unit")))
        graph=graph.replace(graph.nodes.first { it.id=="wall" }.copy(kind=BuildingKind.PATTERN,inputs=listOf("unit","BC")))
        graph=graph.replace(graph.nodes.first { it.id=="floor" }.copy(thickness=2,occupied=true,finish="minecraft:white_carpet"))
        graph=graph.replace(graph.nodes.first { it.id=="storey" }.copy(inputs=listOf("room","room"),offsets=listOf(P(),P(4,0,0))))
        graph=graph.replace(graph.nodes.first { it.id=="building" }.copy(levels=mapOf(-1 to "storey",0 to "storey",1 to "storey")))
        val b=Building(schema=2,id="graph_turtle",graph=graph,palette=Building().palette.mapValues { "minecraft:stone" });lib.buildings["graph_turtle@1"]=b
        val local=Generator.generate(b,lib.forms,lib.modules)
        h.assertTrue(local.materials.getValue("minecraft:white_carpet")==54,"Every room reserves a real carpet boundary")
        val saved=d.save(net.minecraft.nbt.CompoundTag(),l.registryAccess());val restored=Json.decode<Database>(String(saved.getByteArray("architect"),Charsets.UTF_8));val restoredLib=restored.libraries.getValue(owner.id.toString())
        h.assertTrue(Generator.generate(restoredLib.buildings.getValue("graph_turtle@1"),restoredLib.forms,restoredLib.modules)==local,"Building graph and level keys survive world serialization")
        val plan=local.copy(placements=local.placements.map { it.copy(pos=origin+it.pos) },clear=local.clear.map { it+origin })
        val j=Job(owner=owner.id.toString(),dimension=l.dimension().location().toString(),name="Graph basement acceptance",origin=origin,dock=PlacementService.local(dock),plan=plan)
        val (_,max)=Jobs.bounds(j)
        val chunks=buildList { for(x in (dock.x shr 4)..(max.x shr 4)) for(z in (dock.z shr 4)..(max.z shr 4)) add(x to z) };chunks.forEach { (x,z) -> l.setChunkForced(x,z,true);l.chunkSource.addRegionTicket(net.minecraft.server.level.TicketType.START,net.minecraft.world.level.ChunkPos(x,z),2,net.minecraft.util.Unit.INSTANCE) }
        for(x in dock.x..max.x) for(z in dock.z..max.z) for(y in origin.y..max.y) l.setBlockAndUpdate(BlockPos(x,y,z),Blocks.AIR.defaultBlockState())
        l.setBlockAndUpdate(dock.below(),Blocks.CHEST.defaultBlockState());val chest=l.getBlockEntity(dock.below()) as net.minecraft.world.level.block.entity.ChestBlockEntity
        for(i in 0 until chest.containerSize) chest.setItem(i,ItemStack(when { i<24->net.minecraft.world.item.Items.STONE;i==24->net.minecraft.world.item.Items.WHITE_CARPET;else->net.minecraft.world.item.Items.COAL },64))
        val block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("computercraft:turtle_normal"));l.setBlockAndUpdate(dock,block.defaultBlockState());block.setPlacedBy(l,dock,block.defaultBlockState(),actor,ItemStack(block))
        val turtle=l.getBlockEntity(dock) as dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity;val access=turtle.access
        val holder=l.registryAccess().registryOrThrow(dan200.computercraft.api.turtle.ITurtleUpgrade.REGISTRY).getHolderOrThrow(dan200.computercraft.api.turtle.ITurtleUpgrade.createKey(ResourceLocation.parse("copycat_architect:extrusion_gun")))
        access.setUpgrade(dan200.computercraft.api.turtle.TurtleSide.LEFT,dan200.computercraft.api.upgrades.UpgradeData.ofDefault(holder))
        turtle.setItem(0,Economy.cartridge(1000000));turtle.setItem(1,ItemStack(net.minecraft.world.item.Items.COAL,64));turtle.setItem(2,ItemStack(net.minecraft.world.item.Items.STONE,2))
        val computer=turtle.createServerComputer();val startup="shell.run(\"architect\", \"${j.id}\")".toByteArray(Charsets.UTF_8)
        computer.createRootMount()!!.openFile("startup.lua",setOf(java.nio.file.StandardOpenOption.WRITE,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)).use { it.write(java.nio.ByteBuffer.wrap(startup)) }
        d.db.jobs+=j;d.setDirty();Jobs.beginCheck(l,j);computer.turnOn();var reboot=false
        h.onEachTick {
            if(j.index>24 && !reboot) { lib.forms.clear();computer.reboot();reboot=true }
            if(j.state=="blocked") h.fail(j.message)
            if(h.tick==100L || h.tick%4000L==0L) {
                val terminal=computer.terminalState.create();val text=(0 until terminal.height).joinToString(" | ") { terminal.getLine(it).toString().trim() }
                println("ARCHITECT GRAPH TURTLE ${j.index}/${plan.placements.size}: ${j.message}; on=${computer.isOn}; removed=${turtle.isRemoved}; ticking=${l.isPositionEntityTicking(access.position)}; $text")
            }
        }
        h.succeedWhen {
            h.assertTrue(j.state=="complete","Graph building in progress ${j.index}/${plan.placements.size}: ${j.message}")
            h.assertTrue(reboot && access.position==dock,"Graph turtle resumes and returns to depot")
            plan.placements.forEach { h.assertTrue(PlacementService.matches(l,PlacementService.world(it.pos),it),"Graph world differs at ${it.pos}") }
            plan.clear.forEach { h.assertTrue(l.getBlockState(PlacementService.world(it)).isAir,"Graph room is obstructed") }
            chunks.forEach { (x,z) -> l.setChunkForced(x,z,false);l.chunkSource.removeRegionTicket(net.minecraft.server.level.TicketType.START,net.minecraft.world.level.ChunkPos(x,z),2,net.minecraft.util.Unit.INSTANCE) }
            println("ARCHITECT GRAPH TURTLE COMPLETE: ${plan.placements.size} placements, basement/ground/upper, two rooms per storey, carpet floors, frozen templates and reboot")
        }
    }

}
