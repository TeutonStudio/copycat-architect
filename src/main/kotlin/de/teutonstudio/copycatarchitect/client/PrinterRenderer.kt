package de.teutonstudio.copycatarchitect.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.PrinterEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.hypot

/** Mirrors the active local editor; other printers display their synchronized graph projection. */
class PrinterRenderer(context:BlockEntityRendererProvider.Context):BlockEntityRenderer<PrinterEntity> {
    override fun render(be:PrinterEntity,partial:Float,pose:PoseStack,buffers:MultiBufferSource,light:Int,overlay:Int) {
        val form=be.preview
        if(form!=null && form.parts.isNotEmpty()) {
            pose.pushPose();pose.translate(0.18,0.18,0.18);pose.scale(0.64f,0.64f,0.64f)
            Meshes.draw(Geometry.rotate(form.parts,be.turn),"",pose,buffers,15728880,0.62f,groups=form.groups,surfaces=be.surfaces)
            pose.popPose()
        }
        if(PrinterDisplayCapture.draw(be.session,pose,buffers)) return
        pose.pushPose();pose.translate(0.131,0.986,0.131);pose.mulPose(Axis.XP.rotationDegrees(90f));pose.scale(0.00205f,0.00205f,0.00205f)
        rect(pose,buffers,0f,0f,360f,360f,0xff101b24.toInt())
        for(i in 12..348 step 12) {
            rect(pose,buffers,i.toFloat(),28f,i+0.45f,338f,0xff1c303c.toInt(),-0.01f)
            rect(pose,buffers,8f,i.toFloat(),352f,i+0.45f,0xff1c303c.toInt(),-0.01f)
        }
        rect(pose,buffers,0f,0f,360f,24f,0xff354854.toInt(),-0.02f)
        val title=form?.name ?: Component.translatable("block.copycat_architect.block_printer").string
        text(pose,buffers,title,9f,8f,0xffffcc83.toInt(),340)
        val graph=form?.let(GraphPresentation::shape)
        if(graph!=null) graph(pose,buffers,graph) else {
            text(pose,buffers,Component.translatable("item.copycat_architect.extrusion_gun").string,45f,172f,0xff86bcc7.toInt(),275)
        }
        rect(pose,buffers,0f,340f,360f,360f,0xff23323e.toInt(),-0.02f)
        if(form!=null) text(pose,buffers,"${form.groups.size}  /  ${Math.floorMod(be.turn,4)*90}°",9f,346f,0xffb4c8d2.toInt(),340)
        pose.popPose()
    }
    private data class Panel(val x:Float,val y:Float,val width:Float,val height:Float)
    private fun graph(pose:PoseStack,buffers:MultiBufferSource,graph:NodeGraph) {
        if(graph.nodes.isEmpty()) return
        val minX=graph.nodes.minOf { it.x };val minY=graph.nodes.minOf { it.y }
        val spanX=graph.nodes.maxOf { it.x+184 }-minX;val spanY=graph.nodes.maxOf { it.y+76 }-minY
        val scale=minOf(334f/spanX.coerceAtLeast(1),296f/spanY.coerceAtLeast(1),1f)
        val left=13f+(334f-spanX*scale)/2;val top=30f+(296f-spanY*scale)/2
        val panels=graph.nodes.associate { n -> n.id to Panel(left+(n.x-minX)*scale,top+(n.y-minY)*scale,184f*scale,76f*scale) }
        for(n in graph.nodes) for((index,id) in n.inputs.withIndex()) {
            val source=panels[id] ?: continue;val target=panels.getValue(n.id)
            val sy=source.y+source.height/2;val ty=target.y+target.height*(index+1)/(n.inputs.size+1)
            val mid=(source.x+source.width+target.x)/2
            line(pose,buffers,source.x+source.width,sy,mid,sy,0xff72c4bc.toInt())
            line(pose,buffers,mid,sy,mid,ty,0xff72c4bc.toInt())
            line(pose,buffers,mid,ty,target.x,ty,0xff72c4bc.toInt())
        }
        for(n in graph.nodes) {
            val p=panels.getValue(n.id)
            val color=when(n.kind) { NodeKind.INPUT->0xff365b68.toInt();NodeKind.OUTPUT->0xff74613b.toInt();NodeKind.ROTATION->0xff956d40.toInt();else->0xff514969.toInt() }
            rect(pose,buffers,p.x-1,p.y-1,p.x+p.width+1,p.y+p.height+1,0xff68818f.toInt(),-0.04f)
            rect(pose,buffers,p.x,p.y,p.x+p.width,p.y+p.height,0xff23323e.toInt(),-0.05f)
            rect(pose,buffers,p.x,p.y,p.x+p.width,p.y+minOf(17f,p.height/2),color,-0.06f)
            if(p.width>=35f) text(pose,buffers,ui("node_"+n.kind.name.lowercase()),p.x+3,p.y+3,0xffe3eaf0.toInt(),(p.width-5).toInt())
            if(n.kind==NodeKind.INPUT && p.height>30f && p.width>=35f)
                text(pose,buffers,ui(BaseShapes.find(n.part.source,n.part.discCut).name),p.x+3,p.y+21,0xffa4c4ce.toInt(),(p.width-5).toInt())
            n.inputs.indices.forEach { i -> val y=p.y+p.height*(i+1)/(n.inputs.size+1);rect(pose,buffers,p.x-2,y-2,p.x+2,y+2,0xff72d3c0.toInt(),-0.07f) }
            if(n.kind!=NodeKind.OUTPUT) rect(pose,buffers,p.x+p.width-2,p.y+p.height/2-2,p.x+p.width+2,p.y+p.height/2+2,0xff72d3c0.toInt(),-0.07f)
        }
    }
    private fun text(pose:PoseStack,buffers:MultiBufferSource,value:String,x:Float,y:Float,color:Int,width:Int) {
        val font=Minecraft.getInstance().font
        pose.pushPose();pose.translate(0.0,0.0,-0.1)
        font.drawInBatch(font.plainSubstrByWidth(value,width.coerceAtLeast(0)),x,y,color,false,pose.last().pose(),buffers,Font.DisplayMode.NORMAL,0,15728880)
        pose.popPose()
    }
    private fun rect(pose:PoseStack,buffers:MultiBufferSource,x:Float,y:Float,right:Float,bottom:Float,color:Int,z:Float=-0.005f) {
        val consumer=buffers.getBuffer(RenderType.debugQuads());val matrix=pose.last()
        for((a,b) in listOf(x to y,x to bottom,right to bottom,right to y)) consumer.addVertex(matrix,a,b,z).setColor(color)
    }
    private fun line(pose:PoseStack,buffers:MultiBufferSource,x:Float,y:Float,endX:Float,endY:Float,color:Int) {
        val length=hypot(endX-x,endY-y)
        if(length<0.01f) return
        if(abs(endX-x)<0.01f) rect(pose,buffers,x-0.65f,minOf(y,endY),x+0.65f,maxOf(y,endY),color,-0.03f)
        else rect(pose,buffers,minOf(x,endX),y-0.65f,maxOf(x,endX),y+0.65f,color,-0.03f)
    }
}
