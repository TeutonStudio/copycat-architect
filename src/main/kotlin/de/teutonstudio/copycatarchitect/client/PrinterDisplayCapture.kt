package de.teutonstudio.copycatarchitect.client

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import de.teutonstudio.copycatarchitect.Architect
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.neoforged.neoforge.client.event.ScreenEvent
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** One bounded GPU-only copy of the local printer editor; no pixel readback or file allocation. */
object PrinterDisplayCapture {
    private val location=ResourceLocation.fromNamespaceAndPath(Architect.ID,"dynamic/printer_display")
    private var target:TextureTarget?=null
    private var registered=false
    private var editor=WeakReference<ShapeScreen>(null)
    private var session=""
    var serial=0L;private set
    val width get()=target?.width ?: 0
    val height get()=target?.height ?: 0

    fun afterScreen(event:ScreenEvent.Render.Post) {
        val mc=Minecraft.getInstance();val screen=event.screen as? ShapeScreen ?: return
        val view=ClientState.snapshot.printer ?: return
        if(mc.screen!==screen || !view.placed || view.session.isEmpty() || WorldPeek.active) return
        val key=if(editor.get()===screen) session else screen.printerCommand("printerPreview").key
        if(key!=view.session) return
        event.guiGraphics.flush();RenderSystem.assertOnRenderThread()
        val source=mc.mainRenderTarget
        val scale=minOf(1.0,512.0/source.width.coerceAtLeast(1),512.0/source.height.coerceAtLeast(1))
        val w=(source.width*scale).roundToInt().coerceAtLeast(1);val h=(source.height*scale).roundToInt().coerceAtLeast(1)
        val read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val boundTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST);val scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        val viewport=IntArray(4);GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport)
        val clearColor=FloatArray(4);GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE,clearColor)
        try {
            if(scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST)
            val capture=target?.takeIf { it.width==w && it.height==h } ?: run {
                target?.destroyBuffers()
                TextureTarget(w,h,false,Minecraft.ON_OSX).also { target=it;it.setFilterMode(GL11.GL_LINEAR) }
            }
            if(!registered) {
                mc.textureManager.register(location,object:AbstractTexture() {
                    override fun getId()=target?.colorTextureId ?: -1
                    override fun setFilter(blur:Boolean,mipmap:Boolean) { super.setFilter(true,false) }
                    override fun load(manager:ResourceManager) {}
                    override fun close() { target?.destroyBuffers();target=null;registered=false;session="" }
                })
                registered=true
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,source.frameBufferId)
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,capture.frameBufferId)
            GL30.glBlitFramebuffer(0,0,source.width,source.height,0,0,w,h,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_LINEAR)
            editor=WeakReference(screen);session=key;serial++
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read)
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw)
            RenderSystem.bindTexture(boundTexture)
            if(depth) RenderSystem.enableDepthTest() else RenderSystem.disableDepthTest()
            if(scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST)
            RenderSystem.clearColor(clearColor[0],clearColor[1],clearColor[2],clearColor[3])
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3])
        }
    }
    fun hasCapture(key:String)=target!=null && serial>0 && key.isNotEmpty() && session==key
    fun isEditorActive(key:String)=hasCapture(key) && Minecraft.getInstance().screen===editor.get() &&
        ClientState.snapshot.printer?.let { it.placed && it.session==key }==true

    fun draw(key:String,pose:PoseStack,buffers:MultiBufferSource):Boolean =
        if(isEditorActive(key)) drawRetained(key,pose,buffers) else false

    /** Retained access is used by the isolated visual acceptance scene after closing its editor. */
    fun drawRetained(key:String,pose:PoseStack,buffers:MultiBufferSource):Boolean {
        if(!hasCapture(key)) return false
        val matrix=pose.last();val edge=0.738f;val left=0.131f;val top=0.131f;val y=0.987f
        val background=buffers.getBuffer(RenderType.debugQuads())
        for((x,z) in listOf(left to top,left to top+edge,left+edge to top+edge,left+edge to top))
            background.addVertex(matrix,x,y-0.0001f,z).setColor(0xff101b24.toInt())
        val aspect=width.toFloat()/height.coerceAtLeast(1)
        val w=if(aspect>=1) edge else edge*aspect;val h=if(aspect>=1) edge/aspect else edge
        val x=left+(edge-w)/2;val z=top+(edge-h)/2
        val consumer=buffers.getBuffer(RenderType.entityTranslucent(location))
        // Framebuffer texture V=1 is the actual GUI's top row.
        for(v in listOf(floatArrayOf(x,z,0f,1f),floatArrayOf(x,z+h,0f,0f),floatArrayOf(x+w,z+h,1f,0f),floatArrayOf(x+w,z,1f,1f)))
            consumer.addVertex(matrix,v[0],y,v[1]).setColor(1f,1f,1f,1f).setUv(v[2],v[3])
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(matrix,0f,1f,0f)
        return true
    }
}
