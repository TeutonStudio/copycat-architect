package de.teutonstudio.copycatarchitect.client

import com.lowdragmc.kilagraph.graph.util.KGGraphView
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI
import com.lowdragmc.lowdraglib2.gui.ui.Style
import com.lowdragmc.lowdraglib2.gui.ui.UI
import com.lowdragmc.lowdraglib2.gui.ui.UIElement
import com.lowdragmc.lowdraglib2.gui.ui.data.Tooltips
import com.lowdragmc.lowdraglib2.gui.ui.event.CommandEvents
import com.lowdragmc.lowdraglib2.gui.ui.elements.Menu
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button as KilaButton
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture
import com.lowdragmc.lowdraglib2.gui.texture.ColorBorderTexture
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandleHelpers
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.GraphCommands
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.IGraphCommand
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.LayoutCommands
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.WireCommands
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.NodeElement
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.node.PortElement
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.Capabilities
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.graph.CustomGraphModelImpl
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.PortModel
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.definition.IPortDefinitionContext
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.wire.WireModel
import dev.vfyjxf.taffy.style.TaffyPosition
import dev.vfyjxf.taffy.style.FlexDirection
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.Tuple
import de.teutonstudio.copycatarchitect.domain.BaseShapes
import de.teutonstudio.copycatarchitect.domain.Part
import de.teutonstudio.copycatarchitect.domain.RotationAxis
import de.teutonstudio.copycatarchitect.server.Economy
import org.joml.Vector2f
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.roundToInt

/** A view projection only. The editor's domain objects remain the sole saved document. */
data class KilaNode(val id:String,val title:String,val x:Int,val y:Int,val inputs:List<String>,
                    val output:Boolean=true,val deletable:Boolean=true,
                    val inputTypes:List<String> = emptyList(),val outputType:String="shape",val source:String="",val discCut:Int=0,val part:Part?=null,
                    val rotatable:Boolean=false,val rotationAxis:RotationAxis=RotationAxis.NORMAL)
data class KilaLink(val source:String,val target:String,val slot:Int)
data class KilaGraphData(val nodes:List<KilaNode>,val links:List<KilaLink>)
data class KilaInsertion(val label:String,val category:String="",val icon:String="",val insert:(Int,Int)->Unit)

/** Semantic port types also supply the native quick-info label. */
private object KilaPortTypes {
    private val handles=mutableMapOf<String,TypeHandle>()
    fun of(key:String):TypeHandle=handles.getOrPut(key) {
        val label=key.split('|').joinToString(" / ") { type -> when(type) {
            "shape","COPYCAT" -> ui("port_copycat")
            "rotation" -> ui("port_rotation")
            "WALL" -> ui("bg_segment")
            else -> ui("bg_"+type.lowercase())
        } }
        TypeHandleHelpers.customType(Any::class.java,"copycat_architect:$key",label).also {
            TypeHandleHelpers.setCustomColor(it,if(key=="rotation") 0xffd6a469.toInt() else 0xff72c4bc.toInt())
        }
    }
}

/** Navigation belongs to the view and never enters document undo, validation or exports. */
class KilaGraphState {
    var selected=""
    var offsetX=0f
    var offsetY=0f
    var zoom=1f
    var fitted=false
}

/**
 * Embeds KilaGraph's real canvas in the existing Minecraft screen. Only supported domain
 * operations are offered; unsupported LDLib variables, subgraphs and resource saving are absent.
 * Every completed command is projected back to the screen's existing validation/undo path.
 */
class KilaGraphEditor(x:Int,y:Int,w:Int,h:Int,screen:Screen,private val state:KilaGraphState,
                      private val data:()->KilaGraphData,private val selected:(String)->Unit,
                      private val commit:(KilaGraphData)->Unit,private val failure:(String)->Unit,
                      private val readOnly:Boolean=false,private val insertions:List<KilaInsertion> = emptyList(),
                      private val selectSource:(String)->Unit={},
                      private val rotateSource:(String,RotationAxis,Int)->Unit={_,_,_->})
    :AbstractWidget(x,y,w,h,Component.literal("KilaGraph")) {
    private var loaded=data()
    private var pending:KilaGraphData?=null
    private var pendingInsertion:(()->Unit)?=null
    private var pendingSource:String?=null
    private var pendingRotation:Pair<String,Int>?=null
    private var forceReload=false
    private val models=linkedMapOf<String,NodeModel>()
    private val ids=hashMapOf<NodeModel,String>()
    private val sourceButtons=hashMapOf<String,UIElement>()
    private val rotationButtons=hashMapOf<Pair<String,Int>,UIElement>()
    private var lastTick=Long.MIN_VALUE
    private var disposed=false
    private var restoreNavigation=false
    private val canvas=object:KGGraphView() {
        override fun initHeaders() {}
        override fun initPanels() {}
        override fun fitGraphChildren(padding:Float) { fitNative(padding) }
        override fun claimsCommand(command:String)=command==CommandEvents.SELECT_ALL
        override fun createMenu(mouseX:Float,mouseY:Float):TreeBuilder.Menu {
            val menu=TreeBuilder.Menu.start()
            if(!readOnly && insertions.isNotEmpty()) {
                val position=getContentViewContainer().worldToLocalLayoutOffset(Vector2f(mouseX,mouseY))
                fun leaves(target:TreeBuilder.Menu,items:List<KilaInsertion>) {
                    items.forEach { item -> target.leaf(if(item.icon.isEmpty()) IGuiTexture.EMPTY else ItemStackTexture(Economy.item(item.icon)),Component.literal(item.label)) {
                        pendingInsertion={ item.insert(position.x.roundToInt().coerceIn(-8000,8000),position.y.roundToInt().coerceIn(-8000,8000)) }
                    } }
                }
                menu.branch(tr("add_node")) { branch ->
                    insertions.groupBy { it.category }.forEach { (category,items) ->
                        if(category.isEmpty()) leaves(branch,items)
                        else branch.branch(Component.literal(category)) { leaves(it,items) }
                    }
                }
            }
            menu.leaf(tr("fit_graph")) { fitGraphChildren() }
            if(!readOnly && getSelected().any { it is WireModel || it is NodeModel && it.isDeletable }) {
                menu.leaf(Component.translatable("selectWorld.delete")) { deleteSelectedElements() }
            }
            return menu
        }
    }
    private val root=UIElement()
    private val ui:ModularUI

    init {
        canvas.layout { it.positionType(TaffyPosition.ABSOLUTE).left(x.toFloat()).top(y.toFloat()).width(w.toFloat()).height(h.toFloat()) }
        // Our bounded documents remain editable at overview zoom; keep their titles and sockets visible.
        canvas.graphView.graphViewStyle { it.lodEnabled(false) }
        canvas.header.setDisplay(false)
        canvas.blackboard.setDisplay(false)
        canvas.inspector.setDisplay(false)
        canvas.preview.setDisplay(false)
        root.layout { it.widthPercent(100f).heightPercent(100f) }
        root.addChild(canvas)
        ui=ModularUI(UI.of(root,StylesheetManager.MODERN),Minecraft.getInstance().player)
        canvas.addCommandListener { _,_,_ -> pending=snapshot() }
        canvas.setReadOnly(readOnly)
        load(loaded)
        ui.setScreenAndInit(screen)
    }

    private class ViewNode(var projection:KilaNode=KilaNode("","",0,0,emptyList())):Node() {
        override fun getDisplayName()=Component.literal(projection.title)
        override fun getNodeWidth()=168f
        override fun onDefinePorts(context:IPortDefinitionContext) {
            projection.inputs.forEachIndexed { index,label ->
                context.addInputPort("in$index",KilaPortTypes.of(projection.inputTypes.getOrNull(index) ?: "shape"))
                    .withDisplayName(Component.literal(label)).withTooltips(Tooltips.of(Component.literal(label)))
                    .withoutConfigurator().withoutSerialization().build()
            }
            if(projection.output) context.addOutputPort("out",KilaPortTypes.of(projection.outputType))
                .withDisplayName(Component.literal(" ")).build()
        }
    }

    private inner class ViewGraph:Graph() {
        override fun createGraphModel():CustomGraphModelImpl = object:CustomGraphModelImpl(this) {
            override fun canAssignTo(destination:PortModel,source:PortModel):Boolean {
                if(!super.canAssignTo(destination,source)) return false
                val from=((source.nodeModel as? ICustomNodeModel)?.node as? ViewNode)?.projection ?: return false
                val to=((destination.nodeModel as? ICustomNodeModel)?.node as? ViewNode)?.projection ?: return false
                val slot=destination.portId.removePrefix("in").toIntOrNull() ?: return false
                return from.outputType in (to.inputTypes.getOrNull(slot) ?: "shape").split('|')
            }
        }
        override fun getSupportNodes():List<Class<out Node>> = emptyList()
        override fun getSupportTypes():List<TypeHandle> = (loaded.nodes.flatMap { it.inputTypes+it.outputType }+"shape").distinct().map(KilaPortTypes::of)
        override fun getLibrarySupportTypes():List<TypeHandle> = emptyList()
        override fun canExecuteCommand(command:IGraphCommand):Boolean = !readOnly && when(command) {
            is GraphCommands.MoveElementsCommand,is LayoutCommands.AutoLayoutCommand -> true
            is WireCommands.CreateWireCommand -> true
            is GraphCommands.DeleteElementsCommand -> command.elementsToDelete.all {
                it is WireModel || it is NodeModel && ids[it]?.let { id -> loaded.nodes.find { n -> n.id==id }?.deletable }==true
            }
            else -> false
        }
    }

    private var worldPeek=false
    fun setWorldPeek(value:Boolean) {
        if(worldPeek==value) return
        worldPeek=value
        val color=if(value) 0x24101b24 else 0xff101b24.toInt()
        Style.importantPipeline(canvas.style) { it.backgroundTexture(ColorRectTexture(color)) }
        Style.importantPipeline(canvas.graphView.style) { it.backgroundTexture(ColorRectTexture(color)) }
        canvas.graphView.graphViewStyle { it.gridLineColor(if(value) 0x183d5964 else 0x30404f57);it.gridAccentColor(if(value) 0x284a6872 else 0x40566870) }
    }

    private fun load(value:KilaGraphData) {
        val preserve=state.fitted
        models.clear();ids.clear();sourceButtons.clear();rotationButtons.clear()
        val graph=ViewGraph()
        value.nodes.forEach { n ->
            val model=graph.graphModel.createNodeModel(ViewNode(n),Vector2f(n.x.toFloat(),n.y.toFloat()))
            model.clearCapabilities()
            model.setCapability(Capabilities.SELECTABLE,true)
            model.setCapability(Capabilities.MOVABLE,!readOnly)
            model.setCapability(Capabilities.DELETABLE,n.deletable && !readOnly)
            models[n.id]=model;ids[model]=n.id
        }
        value.links.forEach { link ->
            val from=models[link.source]?.outputsById?.get("out")
            val to=models[link.target]?.inputsById?.get("in${link.slot}")
            if(from!=null && to!=null) graph.graphModel.createWire(to,from)
        }
        canvas.loadGraph(graph)
        canvas.blackboard.setDisplay(false);canvas.inspector.setDisplay(false);canvas.preview.setDisplay(false)
        value.nodes.forEach { n ->
            val element=models[n.id]?.let { canvas.getModelElement(it) } as? NodeElement
            element?.nodeTittle?.style {
                it.tooltips(Component.literal(n.title))
            }
            if(element!=null && n.source.isNotEmpty()) {
                val button=KilaButton().setText(ui(BaseShapes.find(n.source,n.discCut).name))
                button.layout { it.height(24f).marginAll(5f) }
                button.addPreIcon(ItemStackTexture(Economy.item(n.source)))
                button.style { it.tooltips(tr("choose_base_copycat")) }
                button.setActive(!readOnly)
                button.setOnClick { event -> pendingSource=n.id;event.stopPropagation() }
                element.addChild(button)
                sourceButtons[n.id]=button
            }
            if(element!=null && n.rotatable) {
                val rotations=UIElement().layout { it.height(18f).marginHorizontal(5f).marginBottom(5f).flexDirection(FlexDirection.ROW) }
                listOf(-1,2,1).forEach { turns ->
                    val label=when(turns) { -1->"−90°";2->"180°";else->"+90°" }
                    val axis=if(n.rotationAxis==RotationAxis.NORMAL) ui("rotation_normal_generic") else n.rotationAxis.name
                    val rotation=KilaButton().setText(label)
                    rotation.layout { it.width(49f).height(18f).marginRight(3f) }
                    rotation.buttonStyle { it.baseTexture(ColorBorderTexture(-1,0x8068818f.toInt())).hoverTexture(ColorRectTexture(0x6049606b)).pressedTexture(ColorRectTexture(0x807aa0ac.toInt())) }
                    rotation.style { it.tooltips(tr("rotate_about_axis",turns*90,axis)) }
                    rotation.setActive(!readOnly)
                    rotation.setOnClick { event -> pendingRotation=n.id to turns;event.stopPropagation() }
                    rotations.addChild(rotation)
                    rotationButtons[n.id to turns]=rotation
                }
                element.addChild(rotations)
            }
        }
        loaded=value
        restoreNavigation=preserve
        models[state.selected]?.let { canvas.addSelected(it) }
    }

    private fun snapshot():KilaGraphData {
        val active=canvas.graph?.graphModel ?: return loaded
        // LDLib retains null tombstones until its next graph update after a delete command.
        val remaining=active.nodeModels.filterNotNull().toSet()
        val nodes=loaded.nodes.mapNotNull { old -> models[old.id]?.takeIf { it in remaining }?.let { model ->
            old.copy(x=model.position.x.roundToInt(),y=model.position.y.roundToInt())
        } }
        val links=active.wireModels.filterNotNull().mapNotNull { wire ->
            val source=ids[wire.fromPort?.nodeModel] ?: return@mapNotNull null
            val target=ids[wire.toPort?.nodeModel] ?: return@mapNotNull null
            val slot=wire.toPort?.portId?.removePrefix("in")?.toIntOrNull() ?: return@mapNotNull null
            KilaLink(source,target,slot)
        }
        return KilaGraphData(nodes,links)
    }

    private fun rememberNavigation() {
        if(restoreNavigation) return
        state.offsetX=canvas.graphView.offsetX;state.offsetY=canvas.graphView.offsetY
        state.zoom=canvas.graphView.scale;state.fitted=true
    }

    private fun afterInput() {
        rememberNavigation()
        canvas.getSelected().firstNotNullOfOrNull { ids[it] }?.let { id ->
            if(id!=state.selected) { state.selected=id;selected(id) }
        }
        val next=pending
        pending=null
        if(next!=null && next!=loaded) {
            try { commit(next) } catch(e:Exception) { failure(e.message.orEmpty()) }
            forceReload=true
        }
        val insertion=pendingInsertion
        pendingInsertion=null
        try { insertion?.invoke() } catch(e:Exception) { failure(e.message.orEmpty()) }
        val rotation=pendingRotation
        pendingRotation=null
        if(rotation!=null) rotateSource(rotation.first,loaded.nodes.firstOrNull { it.id==rotation.first }?.rotationAxis ?: RotationAxis.NORMAL,rotation.second)
        val source=pendingSource
        pendingSource=null
        // Open the modal only after KilaGraph has finished dispatching the click.
        if(source!=null) selectSource(source)
    }

    fun selectNode(id:String) {
        if(state.selected==id && models[id]?.let { canvas.isSelected(it) }==true) return
        state.selected=id
        canvas.clearAllSelected();models[id]?.let { canvas.addSelected(it) }
    }

    /** Use node-local model positions so embedding the canvas cannot bias the fit bounds. */
    private fun fitNative(padding:Float) {
        if(models.isEmpty()) return
        val bounds=models.values.map { model ->
            val element=canvas.getModelElement(model)
            floatArrayOf(model.position.x,model.position.y,
                model.position.x+maxOf(168f,element?.sizeWidth ?: 0f),
                model.position.y+maxOf(32f,element?.sizeHeight ?: 0f))
        }
        canvas.graphView.fit(bounds.minOf { it[0] }-padding,bounds.minOf { it[1] }-padding,
            bounds.maxOf { it[2] }+padding,bounds.maxOf { it[3] }+padding,0.1f)
    }

    fun fit() { restoreNavigation=false;fitNative(15f);rememberNavigation() }

    /** The shared Remove button follows the native selection, including cables and multiple nodes. */
    fun deleteSelection() {
        if(readOnly || disposed) return
        canvas.deleteSelectedElements();afterInput()
    }

    fun hasOpenMenu()=canvas.isMenuOpen

    private fun clampRootMenus() {
        fun clamp(element:UIElement) {
            if(element is Menu<*,*> && element.sizeWidth>0f && element.sizeHeight>0f) {
                val dx=element.positionX.coerceIn(0f,(ui.screenWidth-element.sizeWidth).coerceAtLeast(0f))-element.positionX
                val dy=element.positionY.coerceIn(0f,(ui.screenHeight-element.sizeHeight).coerceAtLeast(0f))-element.positionY
                if(dx!=0f || dy!=0f) {
                    // Root anchors use !important; nested native menus also need screen bounds
                    // after an ancestor moved. Let that layout settle before clamping children.
                    Style.importantPipeline(element.layout) { it.left(element.layoutX+dx).top(element.layoutY+dy) }
                    return
                }
            }
            element.children.toList().forEach(::clamp)
        }
        root.children.filterIsInstance<Menu<*,*>>().forEach(::clamp)
    }

    /** Native popups may cover adjacent inspectors; draw their existing trees above sibling widgets. */
    fun renderPopupOverlay(graphics:GuiGraphics,mouseX:Int,mouseY:Int,partialTick:Float) {
        if(disposed || !hasOpenMenu()) return
        val context=GUIContext.of(ui,graphics,mouseX,mouseY,partialTick)
        root.children.filterIsInstance<Menu<*,*>>().forEach { it.drawInBackground(context) }
        context.callPostRendering()
    }

    /** Actual native menu row hit point, used by the real-client acceptance scenario. */
    fun menuItemPoint(label:String):Pair<Double,Double>? {
        fun find(element:UIElement):UIElement? {
            if(element is Menu<*,*>) {
                element.nodeUIs.entries.firstOrNull { entry ->
                    ((entry.key.key as? Tuple<*,*>)?.b as? Component)?.string==label
                }?.let { return it.value }
            }
            return element.children.firstNotNullOfOrNull(::find)
        }
        val row=root.children.filterIsInstance<Menu<*,*>>().firstNotNullOfOrNull(::find) ?: return null
        if(row.sizeWidth<=0f || row.sizeHeight<=0f) return null
        val point=row.localToWorld(Vector2f(row.positionX+row.sizeWidth*0.5f,row.positionY+row.sizeHeight*0.5f))
        return point.x.toDouble() to point.y.toDouble()
    }

    /** Canonical graph coordinates at the visible viewport centre, for the shared insert toolbar. */
    fun insertionPoint():Pair<Int,Int> {
        return graphPoint(x+width*0.5,y+height*0.5)
    }
    fun graphPoint(screenX:Double,screenY:Double):Pair<Int,Int> {
        val point=canvas.getContentViewContainer().worldToLocalLayoutOffset(Vector2f(screenX.toFloat(),screenY.toFloat()))
        return point.x.roundToInt().coerceIn(-8000,8000) to point.y.roundToInt().coerceIn(-8000,8000)
    }

    /** Screen-space hit point from KilaGraph's actual laid-out title, including pan and zoom. */
    fun nodeHeaderPoint(id:String):Pair<Double,Double>? {
        val model=models[id] ?: return null
        val element=canvas.getModelElement(model) as? NodeElement ?: return null
        val title=element.nodeTittle ?: return null
        val point=title.localToWorld(Vector2f(title.positionX+title.sizeWidth*0.5f,title.positionY+title.sizeHeight*0.5f))
        return point.x.toDouble() to point.y.toDouble()
    }

    fun outputPortType(id:String):TypeHandle?=models[id]?.outputsById?.get("out")?.dataTypeHandle
    fun inputPortType(id:String,slot:Int):TypeHandle?=models[id]?.inputsById?.get("in$slot")?.dataTypeHandle
    fun inputPortPoint(id:String,slot:Int):Pair<Double,Double>?=portPoint(models[id]?.inputsById?.get("in$slot"))
    fun outputPortPoint(id:String):Pair<Double,Double>?=portPoint(models[id]?.outputsById?.get("out"))
    fun sourceButtonPoint(id:String):Pair<Double,Double>? {
        val button=sourceButtons[id] ?: return null
        val point=button.localToWorld(Vector2f(button.positionX+button.sizeWidth*0.5f,button.positionY+button.sizeHeight*0.5f))
        return point.x.toDouble() to point.y.toDouble()
    }
    fun rotationButtonPoint(id:String,quarterTurns:Int):Pair<Double,Double>? {
        val button=rotationButtons[id to quarterTurns] ?: return null
        val point=button.localToWorld(Vector2f(button.positionX+button.sizeWidth*0.5f,button.positionY+button.sizeHeight*0.5f))
        return point.x.toDouble() to point.y.toDouble()
    }
    private fun portPoint(model:PortModel?):Pair<Double,Double>? {
        if(model==null) return null
        val element=canvas.getModelElement(model) as? PortElement ?: return null
        val icon=element.connector.connectorIcon
        val point=icon.localToWorld(Vector2f(icon.positionX+icon.sizeWidth*0.5f,icon.positionY+icon.sizeHeight*0.5f))
        return point.x.toDouble() to point.y.toDouble()
    }

    /** Called when the enclosing screen rebuilds or closes. */
    fun dispose() {
        if(disposed) return
        rememberNavigation();disposed=true;ui.onRemoved()
    }

    override fun renderWidget(graphics:GuiGraphics,mouseX:Int,mouseY:Int,partialTick:Float) {
        if(disposed) return
        val current=data()
        if(forceReload || current!=loaded) {
            rememberNavigation();load(current);forceReload=false
        }
        val tick=Minecraft.getInstance().level?.gameTime ?: (System.nanoTime()/50_000_000L)
        if(tick!=lastTick) { ui.tick();lastTick=tick }
        if(restoreNavigation) {
            canvas.graphView.offsetX=state.offsetX;canvas.graphView.offsetY=state.offsetY
            canvas.graphView.scale=state.zoom
            // Offset setters do not refresh LDLib's transform when the zoom is unchanged.
            val scale=canvas.graphView.scale
            canvas.graphView.contentRoot.transform { it.translate(-state.offsetX*scale,-state.offsetY*scale).scale(scale) }
            restoreNavigation=false
        }
        ui.widget.render(graphics,mouseX,mouseY,partialTick)
        clampRootMenus()
        graphics.enableScissor(x,y,x+width,y+height)
        drawOverviewLabels(graphics)
        graphics.disableScissor()
        rememberNavigation()
    }

    /** Keep an overview readable without changing fitted geometry or the native hit regions. */
    private fun drawOverviewLabels(graphics:GuiGraphics) {
        if(canvas.graphView.scale>=0.65f || hasOpenMenu()) return
        val font=Minecraft.getInstance().font
        val captions=loaded.nodes.mapNotNull { node ->
            val element=models[node.id]?.let { canvas.getModelElement(it) } as? NodeElement ?: return@mapNotNull null
            val title=element.nodeTittle ?: return@mapNotNull null
            val point=title.localToWorld(Vector2f(title.positionX,title.positionY))
            val right=point.x+title.sizeWidth*canvas.graphView.scale
            val bottom=point.y+title.sizeHeight*canvas.graphView.scale
            if(right<x || point.x>x+width || bottom<y || point.y>y+height) return@mapNotNull null
            val px=point.x.roundToInt().coerceAtLeast(x+2)
            val py=(point.y.roundToInt()-12).coerceIn(y+2,maxOf(y+2,y+height-13))
            Triple(node,px,py)
        }
        for((node,px,py) in captions) {
            val neighbour=captions.filter { it.second>px && abs(it.third-py)<13 }.minOfOrNull { it.second }
            val labelWidth=minOf(112,x+width-px-5,neighbour?.let { it-px-6 } ?: Int.MAX_VALUE)
            if(labelWidth<font.width("1…")) continue
            val text=if(font.width(node.title)<=labelWidth) node.title else font.plainSubstrByWidth(node.title,(labelWidth-font.width("…")).coerceAtLeast(1))+"…"
            val textWidth=font.width(text)
            graphics.fill(px-2,py-1,px+textWidth+3,py+11,0xef1c303c.toInt())
            graphics.fill(px-2,py-1,px-1,py+11,if(node.id==state.selected) 0xffffbd63.toInt() else 0xff577989.toInt())
            graphics.drawString(font,text,px,py,if(node.id==state.selected) 0xffffce86.toInt() else 0xffe3edf2.toInt(),false)
        }
    }

    override fun mouseClicked(mouseX:Double,mouseY:Double,button:Int):Boolean {
        val menuOpen=hasOpenMenu()
        if(!isMouseOver(mouseX,mouseY) && !menuOpen) return false
        ui.refreshHoveredElementAtScreen(mouseX.toFloat(),mouseY.toFloat())
        val result=ui.widget.mouseClicked(mouseX,mouseY,button)
        afterInput();return menuOpen || result || isMouseOver(mouseX,mouseY)
    }
    override fun mouseReleased(mouseX:Double,mouseY:Double,button:Int):Boolean {
        ui.refreshHoveredElementAtScreen(mouseX.toFloat(),mouseY.toFloat())
        val result=ui.widget.mouseReleased(mouseX,mouseY,button);afterInput();return result
    }
    override fun mouseDragged(mouseX:Double,mouseY:Double,button:Int,dragX:Double,dragY:Double):Boolean {
        val result=ui.widget.mouseDragged(mouseX,mouseY,button,dragX,dragY);afterInput();return result
    }
    override fun mouseMoved(mouseX:Double,mouseY:Double) {
        ui.refreshHoveredElementAtScreen(mouseX.toFloat(),mouseY.toFloat())
        ui.widget.mouseMoved(mouseX,mouseY)
    }
    override fun mouseScrolled(mouseX:Double,mouseY:Double,scrollX:Double,scrollY:Double):Boolean {
        val menuOpen=hasOpenMenu()
        if(!isMouseOver(mouseX,mouseY) && !menuOpen) return false
        ui.refreshHoveredElementAtScreen(mouseX.toFloat(),mouseY.toFloat())
        val result=ui.widget.mouseScrolled(mouseX,mouseY,scrollX,scrollY);afterInput();return menuOpen || result
    }
    override fun keyPressed(keyCode:Int,scanCode:Int,modifiers:Int):Boolean {
        // The shared screen owns document undo, redo and JSON clipboard shortcuts.
        if(modifiers and GLFW.GLFW_MOD_CONTROL!=0 && keyCode in listOf(GLFW.GLFW_KEY_Z,GLFW.GLFW_KEY_Y,GLFW.GLFW_KEY_C,GLFW.GLFW_KEY_V)) return false
        val result=ui.widget.keyPressed(keyCode,scanCode,modifiers);afterInput();return result
    }
    override fun keyReleased(keyCode:Int,scanCode:Int,modifiers:Int)=ui.widget.keyReleased(keyCode,scanCode,modifiers)
    override fun charTyped(codePoint:Char,modifiers:Int)=ui.widget.charTyped(codePoint,modifiers)
    override fun setFocused(focused:Boolean) { super.setFocused(focused);ui.widget.setFocused(focused) }
    override fun updateWidgetNarration(output:NarrationElementOutput) { defaultButtonNarrationText(output) }
}
