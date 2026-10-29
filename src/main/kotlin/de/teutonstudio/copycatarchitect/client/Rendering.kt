package de.teutonstudio.copycatarchitect.client

import com.mojang.blaze3d.vertex.*
import com.mojang.math.Axis
import de.teutonstudio.copycatarchitect.Architect
import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.*
import net.minecraft.client.renderer.blockentity.*
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.world.phys.BlockHitResult

@EventBusSubscriber(modid=Architect.ID,bus=EventBusSubscriber.Bus.MOD,value=[Dist.CLIENT])
object ClientSetup {
    @SubscribeEvent fun setup(e:FMLClientSetupEvent) { e.enqueueWork {
        println("ARCHITECT CLIENT SETUP")
        Network.clientReceiver={ json ->
            val snapshot=Json.decode<Snapshot>(json);ClientState.snapshot=snapshot;Extrusion.outputs=snapshot.extrusionOutputs
            val mc=Minecraft.getInstance()
            CreativeCatalogClient.refresh()
            if(mc.screen is PrinterStorageScreen) (mc.screen as PrinterStorageScreen).refresh()
            if(snapshot.editor=="printer") mc.setScreen(ShapeScreen(printerSession=snapshot.printer))
            if(snapshot.editor=="printerStorage" && mc.screen !is PrinterStorageScreen) mc.setScreen(PrinterStorageScreen(mc.screen))
            if(snapshot.editor=="printerDone") { WorldPeek.active=false;mc.setScreen(null) }
            if(snapshot.editor=="shape") mc.setScreen(ShapeScreen())
            if(snapshot.editor=="building") mc.setScreen(BuildingScreen())
        }
        CreativeCatalogClient.setup()
        net.createmod.ponder.foundation.PonderIndex.addPlugin(ArchitectPonderPlugin())
        NeoForge.EVENT_BUS.addListener(ClientSmoke::tick)
        NeoForge.EVENT_BUS.addListener(ClientSmoke::frame)
        NeoForge.EVENT_BUS.addListener(PrinterDisplayCapture::afterScreen)
        NeoForge.EVENT_BUS.addListener(GunControls::tick)
    } }
    @SubscribeEvent fun upgradeModels(e:dan200.computercraft.api.client.turtle.RegisterTurtleModellersEvent) { e.register(Architect.UPGRADE.get(),dan200.computercraft.api.client.turtle.TurtleUpgradeModeller.flatItem()) }
    @SubscribeEvent fun keys(e:net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent) { e.register(GunControls.rotate) }
    @SubscribeEvent fun fluidExtensions(e:net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent) { CartridgeClient.register(e);e.registerItem(CompositeFormItemRenderer.extensions,Architect.COMPOSITE_ITEM.get()) }
    @SubscribeEvent fun renderers(e:EntityRenderersEvent.RegisterRenderers) {
        e.registerBlockEntityRenderer(Architect.BE.get()) { CompositeRenderer(it) }
        e.registerBlockEntityRenderer(Architect.PRINTER_BE.get()) { PrinterRenderer(it) }
    }
    fun preview(e:RenderLevelStageEvent) {
        if(e.stage!=RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val mc=Minecraft.getInstance();val p=mc.player ?: return;val level=mc.level ?: return
        if(!p.mainHandItem.`is`(Architect.GUN.get())) return
        val hit=mc.hitResult as? BlockHitResult ?: return
        val tag=p.mainHandItem.get(DataComponents.CUSTOM_DATA)?.copyTag()
        val form=ClientState.snapshot.library.forms[tag?.getString("form").orEmpty().ifEmpty { "inner@1" }] ?: return
        val pos=hit.blockPos.relative(hit.direction);val camera=e.camera.position
        val pose=e.poseStack;pose.pushPose();pose.translate(pos.x-camera.x,pos.y-camera.y,pos.z-camera.z)
        Meshes.draw(Geometry.rotate(form.parts,tag?.getInt("turn") ?: 0),tag?.getString("material").orEmpty().ifEmpty { "minecraft:stone" },pose,mc.renderBuffers().bufferSource(),15728880,0.45f,groups=form.groups,surfaces=Json.materials(tag?.getString("surfaces").orEmpty().ifEmpty { "{}" }))
        mc.renderBuffers().bufferSource().endBatch();pose.popPose()
    }
}
object ClientState {
    var snapshot=Snapshot()
    fun send(c:Command) {
        if(Minecraft.getInstance().connection==null) return
        val json=Json.encode(c)
        if(json.length>30000) { snapshot=snapshot.copy(message=ui("request_too_large"));return }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(Request(json))
    }
}
class CompositeRenderer(ctx:BlockEntityRendererProvider.Context):BlockEntityRenderer<CompositeEntity> {
    override fun render(be:CompositeEntity,partial:Float,pose:PoseStack,buffers:MultiBufferSource,light:Int,overlay:Int) {
        val neighbors=buildMap<Direction,List<Part>> {
            for(d in Direction.entries) {
                val pos=be.blockPos.relative(d);val other=be.level?.getBlockEntity(pos) as? CompositeEntity
                if(other!=null) put(d,other.parts)
                else if(be.level?.getBlockState(pos)==runCatching { Economy.state(be.material) }.getOrNull()) put(d,listOf(Part("copycats:copycat_block")))
            }
        }
        Meshes.draw(be.parts,be.material,pose,buffers,light,neighbors=neighbors,groups=be.groups,surfaces=be.surfaces)
    }
}
data class Face(val direction:Direction,val vertices:List<FloatArray>,val group:String)
object Meshes {
    private data class MeshKey(val parts:List<Part>,val neighbors:Map<Direction,List<Part>>,val groups:List<MaterialGroup>,val transparent:Set<String>)
    private val cache=object:LinkedHashMap<MeshKey,List<Face>>(64,0.75f,true) { override fun removeEldestEntry(e:MutableMap.MutableEntry<MeshKey,List<Face>>?)=size>1024 }
    fun mesh(parts:List<Part>,neighbors:Map<Direction,List<Part>> = emptyMap(),groups:List<MaterialGroup> = DEFAULT_GROUPS,transparent:Set<String> = emptySet()):List<Face> {
        val resolved=Geometry.resolve(parts,groups);val solid=resolved.solid;return cache.getOrPut(MeshKey(parts,neighbors,groups,transparent)) {
            val adjacent=neighbors.mapValues { Geometry.solid(it.value) }
            val result=mutableListOf<Face>()
            for(d in Direction.entries) for(w in 0..15) {
                val grid=Array(16) { arrayOfNulls<String>(16) }
                fun xyz(u:Int,v:Int)=when(d.axis) { Direction.Axis.X->intArrayOf(w,v,u);Direction.Axis.Y->intArrayOf(u,w,v);else->intArrayOf(u,v,w) }
                for(v in 0..15) for(u in 0..15) { val a=xyz(u,v);val x=a[0]+d.stepX;val y=a[1]+d.stepY;val z=a[2]+d.stepZ
                    val outside=x !in 0..15 || y !in 0..15 || z !in 0..15
                    val covered=if(outside) adjacent[d]?.has(Math.floorMod(x,16),Math.floorMod(y,16),Math.floorMod(z,16)) ?: false else solid.has(x,y,z)
                    val owner=resolved.owner(a[0],a[1],a[2]);val neighbor=resolved.owner(x,y,z)
                    // Only the opaque side of a glass/material interface is emitted.
                    // The transparent group never contributes a coincident back face.
                    val interfaceVisible=!outside && owner!=neighbor && neighbor in transparent && owner !in transparent
                    grid[v][u]=if(!covered || interfaceVisible) owner else null }
                for(v in 0..15) for(u in 0..15) if(grid[v][u]!=null) {
                    val owner=grid[v][u]!!
                    var U=u+1;while(U<16 && grid[v][U]==owner) U++
                    var V=v+1;while(V<16 && (u until U).all { grid[V][it]==owner }) V++
                    for(a in v until V) for(b in u until U) grid[a][b]=null
                    val plane=(w+if(d.axisDirection==Direction.AxisDirection.POSITIVE) 1 else 0)/16f
                    fun vertex(a:Int,b:Int):FloatArray=when(d.axis) { Direction.Axis.X->floatArrayOf(plane,b/16f,a/16f);Direction.Axis.Y->floatArrayOf(a/16f,plane,b/16f);else->floatArrayOf(a/16f,b/16f,plane) }
                    val points=listOf(vertex(u,v),vertex(U,v),vertex(U,V),vertex(u,V))
                    val reverse=when(d) { Direction.NORTH,Direction.EAST,Direction.UP->true;else->false }
                    result+=Face(d,if(reverse) points.reversed() else points,owner)
                }
            };result
        }
    }
    fun draw(parts:List<Part>,material:String,pose:PoseStack,buffers:MultiBufferSource,light:Int,alpha:Float=1f,neighbors:Map<Direction,List<Part>> = emptyMap(),groups:List<MaterialGroup> = DEFAULT_GROUPS,surfaces:Map<String,String> = emptyMap()) {
        val mc=Minecraft.getInstance()
        val models=(surfaces.values+material).distinct().associateWith { id ->
            val state=runCatching { CopycatMaterials.state(id.ifEmpty { "create:zinc_block" }) }.getOrElse { net.minecraft.world.level.block.Blocks.STONE.defaultBlockState() };state to mc.blockRenderer.getBlockModel(state)
        }
        val consumer=buffers.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS))
        val matrix=pose.last()
        val transparent=groups.filter { models.getValue(surfaces[it.id] ?: material).first.block is net.minecraft.world.level.block.TransparentBlock }.map { it.id }.toSet()
        for(face in mesh(parts,neighbors,groups,transparent)) {
            val (state,model)=models.getValue(surfaces[face.group] ?: material)
            val quads=model.getQuads(state,face.direction,RandomSource.create(42))
            val sprite=quads.firstOrNull()?.sprite ?: model.particleIcon
            val shade=when(face.direction.axis) { Direction.Axis.Y->1f;Direction.Axis.X->0.8f;else->0.65f }
            for(v in face.vertices) {
                val (u,t)=when(face.direction.axis) { Direction.Axis.X->v[2] to (1-v[1]);Direction.Axis.Y->v[0] to v[2];else->v[0] to (1-v[1]) }
                consumer.addVertex(matrix,v[0],v[1],v[2]).setColor(shade,shade,shade,alpha).setUv(sprite.getU(u),sprite.getV(t))
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(matrix,face.direction.stepX.toFloat(),face.direction.stepY.toFloat(),face.direction.stepZ.toFloat())
            }
        }
    }
    fun preview(g:GuiGraphics,x:Int,y:Int,scale:Float,yaw:Float,parts:List<Part>,material:String="minecraft:stone",pitch:Float=25f,groups:List<MaterialGroup> = DEFAULT_GROUPS,surfaces:Map<String,String> = emptyMap()) {
        g.flush();val p=g.pose();p.pushPose();p.translate(x.toDouble(),y.toDouble(),180.0);p.scale(scale,-scale,scale)
        p.mulPose(Axis.XP.rotationDegrees(pitch));p.mulPose(Axis.YP.rotationDegrees(yaw));p.translate(-0.5,-0.5,-0.5)
        draw(parts,material,p,g.bufferSource(),15728880,groups=groups,surfaces=surfaces)
        val lines=g.bufferSource().getBuffer(RenderType.lines());val matrix=p.last()
        for(axis in 0..2) for(a in 0..1) for(b in 0..1) {
            val start=when(axis) { 0->floatArrayOf(0f,a.toFloat(),b.toFloat());1->floatArrayOf(a.toFloat(),0f,b.toFloat());else->floatArrayOf(a.toFloat(),b.toFloat(),0f) }
            val end=start.copyOf();end[axis]=1f
            for(v in listOf(start,end)) lines.addVertex(matrix,v[0],v[1],v[2]).setColor(0.35f,0.65f,0.75f,0.6f).setNormal(matrix,if(axis==0) 1f else 0f,if(axis==1) 1f else 0f,if(axis==2) 1f else 0f)
        }
        g.flush();p.popPose()
    }
}
