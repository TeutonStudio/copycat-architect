package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.network.chat.Component
import java.util.UUID
import kotlin.math.*

class GraphView {
    var panX=12.0;var panY=12.0;var zoom=0.75;var fitted=false
    var selected="result";var wire:String?=null;var yaw=35f
    var inspectorScroll=0
}
/** Both presentations edit the same nodes. Only completed edits enter the screen's undo history. */
class NodeGraphCanvas(x:Int,y:Int,w:Int,h:Int,private val definition:()->Form,private val surfaces:()->Map<String,String>,
                      val view:GraphView,private val commit:(NodeGraph)->Unit,private val failure:(String)->Unit,
                      private val inspector:Boolean=false,
                      private val onCreateGroup:(name:String,nodeId:String,material:Boolean,slot:Int)->Unit={_,_,_,_->},
                      private val preview:(()->Result<Form>)?=null,private val readOnly:Boolean=false):AbstractWidget(x,y,w,h,tr("node_graph")) {
    private val font get()=Minecraft.getInstance().font
    private val graph get()=definition().graph!!
    private var dragging:String?=null;private var moveX=0;private var moveY=0
    private var panning=false;private var orbit=false
    private var contextStart:Pair<Double,Double>?=null
    private var mouseX=0.0;private var mouseY=0.0
    private var screenMouseX=0.0;private var screenMouseY=0.0
    private var inspectorScroll:Int
        get()=view.inspectorScroll
        set(value) { view.inspectorScroll=value }
    private var inspectorContentHeight=0
    private enum class Field { SELECT,CONNECT,INSERT,SOURCE,TURN,TOP,SIZE,GROUP,TILT,X,Y,Z,GROUPED,SEPARATED,MATERIAL,PREVIEW,ADD,REMOVE,SWAP,ROTATION_AXIS,ROTATE,INPUT_GROUP }
    private data class Control(val field:Field,val x:Int,val y:Int,val w:Int,val label:String,val slot:Int=-1,val active:Boolean=false,val enabled:Boolean=true,val icon:String="",val floating:Boolean=false) {
        fun contains(px:Double,py:Double)=px>=x && px<x+w && py>=y && py<y+18
    }
    private data class Choice(val id:String,val label:String)
    private data class Menu(val field:Field,val node:String,val anchorX:Int,val anchorY:Int,val slot:Int=-1,
                            val insertX:Int=0,val insertY:Int=0,var first:Int=0,var cursor:Int=0,var keyboard:Boolean=false)
    private var menu:Menu?=null
    fun hasOpenMenu()=menu!=null
    private var cachedForm:Form?=null;private var compiled:Result<Form>?=null
    private fun result():Result<Form> {
        preview?.let { return it() }
        val f=definition()
        if(cachedForm!=f) { cachedForm=f;compiled=runCatching { Geometry.validate(f) } }
        return compiled!!
    }
    fun nodeWidth(n:GraphNode)=if(n.kind==NodeKind.OUTPUT) 206 else 184
    fun nodeHeight(n:GraphNode)=(if(n.kind==NodeKind.INPUT || n.kind==NodeKind.ROTATION || n.kind==NodeKind.OUTPUT) 29 else materialY(n))+propertyHeight(n)+12
    fun fit() {
        if(inspector) { inspectorScroll=0;return }
        val minX=graph.nodes.minOf { it.x };val minY=graph.nodes.minOf { it.y }
        val spanX=graph.nodes.maxOf { it.x+nodeWidth(it) }-minX+40
        val spanY=graph.nodes.maxOf { it.y+nodeHeight(it) }-minY+40
        view.zoom=minOf(width.toDouble()/spanX,height.toDouble()/spanY,1.0).coerceIn(0.25,1.75)
        view.panX=(width-spanX*view.zoom)/2+(20-minX)*view.zoom
        view.panY=(height-spanY*view.zoom)/2+(20-minY)*view.zoom;view.fitted=true
    }
    fun add(kind:NodeKind,source:String=Adapters.all.keys.first(),discCut:Int=0) {
        addAt(kind,source,((width/2-view.panX)/view.zoom).roundToInt(),((height/2-view.panY)/view.zoom).roundToInt(),discCut)
    }
    fun addAt(kind:NodeKind,source:String,px:Int,py:Int,discCut:Int=0) {
        if(readOnly) return
        safe {
            require(kind!=NodeKind.OUTPUT && graph.nodes.size<64) { ui("node_limit") }
            require(kind!=NodeKind.INPUT || graph.nodes.count { it.kind==kind }<32) { ui("source_limit") }
            val node=GraphNode(UUID.randomUUID().toString(),kind,if(kind in setOf(NodeKind.INPUT,NodeKind.ROTATION)) emptyList() else listOf("",""),BaseShapes.find(source,discCut).part(definition().groups.first().id),px.coerceIn(-8000,8000),py.coerceIn(-8000,8000))
            view.selected=node.id;view.wire=null;inspectorScroll=0;commit(graph.copy(nodes=graph.nodes+node))
        }
    }
    fun deleteSelected() { if(readOnly) return;graph.nodes.firstOrNull { it.id==view.selected && it.kind!=NodeKind.OUTPUT }?.let { menu=null;view.wire=null;commit(graph.remove(it.id));view.selected=graph.nodes.firstOrNull { n -> n.kind==NodeKind.OUTPUT }?.id.orEmpty();inspectorScroll=0 } }
    fun connect(source:String,target:String,slot:Int):Boolean = if(readOnly) false else try {
        val n=graph.nodes.first { it.id==target }
        require(slot in n.inputs.indices)
        val changed=graph.replace(n.copy(inputs=n.inputs.mapIndexed { i,v -> if(i==slot) source else v }))
        GraphCompiler.structure(changed,definition().groups);commit(changed);true
    } catch(e:Exception) { failure(e.message.orEmpty());false }
    private fun safe(action:()->Unit) { try { action() } catch(e:Exception) { failure(e.message.orEmpty()) } }
    private fun edit(node:GraphNode) { if(readOnly) return;val changed=graph.replace(node);GraphCompiler.structure(changed,definition().groups);commit(changed) }
    private fun localX(mx:Double)=(mx-x-view.panX)/view.zoom
    private fun localY(my:Double)=(my-y-view.panY)/view.zoom
    private fun position(n:GraphNode)=if(n.id==dragging) Pair(moveX,moveY) else Pair(n.x,n.y)
    private fun title(n:GraphNode)=ui("node_"+n.kind.name.lowercase())
    private fun nodeLabel(n:GraphNode)="${graph.nodes.indexOfFirst { it.id==n.id }+1} · ${title(n)}"
    private fun selectedNode()=graph.nodes.firstOrNull { it.id==view.selected } ?: graph.nodes.first { it.kind==NodeKind.OUTPUT }.also { view.selected=it.id;inspectorScroll=0 }
    private fun inputSocket(n:GraphNode,i:Int)=Pair(position(n).first.toDouble(),position(n).second+36.0+i*19)
    private fun outputSocket(n:GraphNode)=Pair(position(n).first+nodeWidth(n).toDouble(),position(n).second+36.0)
    private fun materialY(n:GraphNode)=40+n.inputs.size*19
    fun previewSelection():String=graph.nodes.single { it.kind==NodeKind.OUTPUT }.previewGroup
    fun previewParts():List<Part> = result().getOrNull()?.let { Geometry.previewParts(it.parts,it.groups,previewSelection()) } ?: emptyList()
    private fun groupName(id:String)=definition().groups.firstOrNull { it.id==id }?.name ?: definition().groups.first().name
    private fun supportsTilt(n:GraphNode)=Adapters.get(n.part.source).tiltable

    private fun axisLabel(axis:RotationAxis):String {
        if(axis!=RotationAxis.NORMAL) return axis.name
        return ui("rotation_normal_generic")
    }
    private fun rotationControls(n:GraphNode,px:Int,py:Int,w:Int):List<Control> {
        val third=(w-6)/3
        val enabled=n.kind!=NodeKind.ROTATE || n.inputs.getOrNull(1).isNullOrEmpty()
        return listOf(Control(Field.ROTATION_AXIS,px,py,w,ui("rotation_axis",axisLabel(n.rotationAxis)),enabled=enabled),
            Control(Field.TURN,px,py+42,w,ui("rotation_angle",n.quarterTurns*90),enabled=enabled))+
            listOf(-1,2,1).mapIndexed { index,turns ->
                val label=when(turns) { -1->"−90°";2->"180°";else->"+90°" }
                Control(Field.ROTATE,px+index*(third+3),py+21,if(index==2) w-2*(third+3) else third,label,slot=turns,enabled=enabled)
            }
    }
    fun rotateSource(nodeId:String,axis:RotationAxis,quarterTurns:Int) {
        if(readOnly) return
        val node=graph.nodes.firstOrNull { it.id==nodeId && it.kind in setOf(NodeKind.ROTATE,NodeKind.ROTATION) } ?: return
        if(node.kind==NodeKind.ROTATE && !node.inputs.getOrNull(1).isNullOrEmpty()) return
        safe { edit(node.copy(rotationAxis=axis,quarterTurns=Math.floorMod(node.quarterTurns+quarterTurns,4))) }
    }
    /** Actual source rotation hit regions, including inspector scroll and graph zoom. */
    fun rotationButtonPoint(axis:RotationAxis,quarterTurns:Int):Pair<Double,Double>? {
        val node=selectedNode()
        if(axis!=node.rotationAxis) return null
        val control=(if(inspector) inspectorControls(node) else graphControls(node)).firstOrNull { it.field==Field.ROTATE && it.slot==quarterTurns } ?: return null
        val screen=screenControl(control)
        return screen.x+screen.w/2.0 to screen.y+(if(inspector) 9.0 else 9.0*view.zoom)
    }
    fun rotationAxisPoint():Pair<Double,Double>? {
        val node=selectedNode()
        val control=(if(inspector) inspectorControls(node) else graphControls(node)).firstOrNull { it.field==Field.ROTATION_AXIS } ?: return null
        val screen=screenControl(control)
        return screen.x+screen.w/2.0 to screen.y+(if(inspector) 9.0 else 9.0*view.zoom)
    }

    /** Coordinates vary with the presentation; fields and edits deliberately do not. */
    private fun propertyControls(n:GraphNode,px:Int,py:Int,w:Int):List<Control> {
        val third=(w-6)/3;val half=(w-4)/2
        return (when(n.kind) {
            NodeKind.INPUT -> listOf(
                Control(Field.SOURCE,px,py,w,ui(BaseShapes.find(n.part.source,n.part.discCut).name),icon=n.part.source),
                Control(Field.TOP,px,py+21,half,ui(if(n.part.top) "top" else "bottom")),
                Control(Field.SIZE,px+half+4,py+21,w-half-4,ui("size",n.part.size),enabled=Adapters.get(n.part.source).max>1),
                Control(Field.X,px,py+42,third,"X ${n.part.offset.x}"),
                Control(Field.Y,px+third+3,py+42,third,"Y ${n.part.offset.y}"),
                Control(Field.Z,px+2*(third+3),py+42,w-2*(third+3),"Z ${n.part.offset.z}"))
            NodeKind.ROTATE,NodeKind.ROTATION -> rotationControls(n,px,py,w)
            NodeKind.OUTPUT -> listOf(Control(Field.PREVIEW,px,py,w,definition().groups.firstOrNull { it.id==n.previewGroup }?.name ?: ui("all_groups")))
            NodeKind.UNION,NodeKind.SYMMETRIC_DIFFERENCE -> {
                val assignments=if(n.materialMode==MaterialMode.GROUPED) listOf(Control(Field.MATERIAL,px,py+21,w,groupName(n.materialGroup)))
                    else n.inputs.indices.map { slot -> Control(Field.INPUT_GROUP,px,py+21+slot*21,w,ui("input_group",slot+1,n.inputGroups.getOrNull(slot)?.takeIf { it.isNotEmpty() }?.let(::groupName) ?: ui("inherit_groups")),slot=slot) }
                val controlsY=py+21+assignments.size*21
                listOf(Control(Field.GROUPED,px,py,half,ui("grouped"),active=n.materialMode==MaterialMode.GROUPED),
                    Control(Field.SEPARATED,px+half+4,py,w-half-4,ui("separated"),active=n.materialMode==MaterialMode.SEPARATED))+assignments+
                    listOf(Control(Field.ADD,px,controlsY,half,"+ ${ui("input")}",enabled=n.inputs.size<16),
                        Control(Field.REMOVE,px+half+4,controlsY,w-half-4,"− ${ui("input")}",enabled=n.inputs.size>2))
            }
            NodeKind.DIFFERENCE -> listOf(Control(Field.SWAP,px,py,w,ui("swap_inputs")))
            NodeKind.INTERSECTION -> listOf(
                Control(Field.ADD,px,py,half,"+ ${ui("input")}",enabled=n.inputs.size<16),
                Control(Field.REMOVE,px+half+4,py,w-half-4,"− ${ui("input")}",enabled=n.inputs.size>2))
        }).map { it.copy(enabled=it.enabled && !readOnly) }
    }
    fun materialModePoint(grouped:Boolean):Pair<Double,Double>? {
        val c=inspectorControls(selectedNode()).firstOrNull { it.field==(if(grouped) Field.GROUPED else Field.SEPARATED) } ?: return null
        return c.x+c.w/2.0 to c.y+9.0
    }
    fun hasUniformGroupWarning()=uniformGroupWarning(selectedNode())
    fun materialGroupPoint(slot:Int=-1):Pair<Double,Double>? {
        val n=selectedNode()
        val c=inspectorControls(n).firstOrNull { if(slot<0) it.field==Field.MATERIAL else it.field==Field.INPUT_GROUP && it.slot==slot } ?: return null
        return c.x+c.w/2.0 to c.y+9.0
    }
    private fun uniformGroupWarning(n:GraphNode)=n.kind in setOf(NodeKind.UNION,NodeKind.SYMMETRIC_DIFFERENCE) && n.materialMode==MaterialMode.SEPARATED &&
        runCatching { GraphCompiler.uniformSeparatedGroups(graph,n,definition().groups) }.getOrDefault(false)
    private fun graphControls(n:GraphNode):List<Control> {
        val (nx,ny)=position(n)
        return propertyControls(n,nx+7,ny+if(n.kind in setOf(NodeKind.INPUT,NodeKind.ROTATION,NodeKind.OUTPUT)) 29 else materialY(n),nodeWidth(n)-14)
    }
    private fun inspectorPropertyY(n:GraphNode)=y+47+(if(n.inputs.isNotEmpty()) 18+n.inputs.size*21+10 else 0)+15-inspectorScroll
    private fun propertyHeight(n:GraphNode)=when(n.kind) {
        NodeKind.INPUT,NodeKind.ROTATE,NodeKind.ROTATION->63;NodeKind.OUTPUT->171
        NodeKind.UNION,NodeKind.SYMMETRIC_DIFFERENCE->42+21*(if(n.materialMode==MaterialMode.GROUPED) 1 else n.inputs.size)+(if(uniformGroupWarning(n)) 48 else 0)
        else->21
    }
    private fun inspectorColumnWidth(n:GraphNode)=if(n.kind!=NodeKind.OUTPUT && width>=540) minOf(360,(width-40)/2) else width-24
    private fun inspectorPreviewBox(n:GraphNode):IntArray? {
        if(n.kind==NodeKind.OUTPUT) return null
        val col=inspectorColumnWidth(n)
        return if(width>=540) intArrayOf(x+24+col,y+65,width-36-col) else intArrayOf(x+12,inspectorPropertyY(n)+propertyHeight(n)+34,width-24)
    }
    private fun inspectorControls(n:GraphNode):List<Control> = listOf(Control(Field.SELECT,x+12,y+10,width-24,"${ui("selected_node")}: ${nodeLabel(n)}"))+
        n.inputs.mapIndexed { i,id -> Control(Field.CONNECT,x+12,y+65+i*21-inspectorScroll,inspectorColumnWidth(n),"${if(n.kind==NodeKind.ROTATE) ui(if(i==0) "port_copycat" else "port_rotation") else if(n.kind==NodeKind.DIFFERENCE) if(i==0) "A" else "B" else ui("input")+" "+(i+1)}: ${graph.nodes.firstOrNull { it.id==id }?.let { nodeLabel(it) } ?: ui("disconnected")}",slot=i,enabled=!readOnly) }+
        propertyControls(n,x+12,inspectorPropertyY(n),inspectorColumnWidth(n))
    private fun dropdown(field:Field)=field in setOf(Field.SELECT,Field.CONNECT,Field.TURN,Field.TOP,Field.GROUP,Field.TILT,Field.MATERIAL,Field.PREVIEW,Field.ROTATION_AXIS,Field.INPUT_GROUP)
    private fun screenControl(c:Control):Control=if(inspector) c else c.copy(x=(x+view.panX+c.x*view.zoom).roundToInt(),y=(y+view.panY+c.y*view.zoom).roundToInt(),w=(c.w*view.zoom).roundToInt())
    private fun choices(m:Menu):List<Choice> {
        if(m.field==Field.SELECT) return graph.nodes.map { Choice(it.id,nodeLabel(it)) }
        if(m.field==Field.INSERT) return BaseShapes.all.map { Choice(it.id,ui(it.name)) }+listOf(NodeKind.UNION,NodeKind.INTERSECTION,NodeKind.DIFFERENCE,NodeKind.SYMMETRIC_DIFFERENCE,NodeKind.ROTATE,NodeKind.ROTATION).map { Choice(it.name,ui("node_"+it.name.lowercase())) }
        val n=graph.nodes.firstOrNull { it.id==m.node } ?: return emptyList()
        return when(m.field) {
            Field.CONNECT -> listOf(Choice("",ui("disconnected")))+graph.nodes.filter { source -> source.kind!=NodeKind.OUTPUT && source.id!=n.id && runCatching {
                GraphCompiler.structure(graph.replace(n.copy(inputs=n.inputs.mapIndexed { i,id -> if(i==m.slot) source.id else id })),definition().groups)
            }.isSuccess }.map { Choice(it.id,nodeLabel(it)) }
            Field.TURN,Field.TILT -> (0..3).map { Choice(it.toString(),"${it*90}°") }
            Field.ROTATION_AXIS -> RotationAxis.entries.map { Choice(it.name,axisLabel(it)) }
            Field.TOP -> listOf(Choice("false",ui("bottom")),Choice("true",ui("top")))
            Field.GROUP,Field.MATERIAL,Field.PREVIEW,Field.INPUT_GROUP -> (if(m.field==Field.INPUT_GROUP) listOf(Choice("",ui("inherit_groups"))) else if(m.field==Field.PREVIEW) listOf(Choice("",ui("all_groups"))) else emptyList())+definition().groups.map { Choice(it.id,it.name) }+
                (if(m.field!=Field.PREVIEW && definition().groups.size<8) listOf(Choice("@new",ui("new_group"))) else emptyList())
            else -> emptyList()
        }
    }
    private fun selected(m:Menu):String {
        if(m.field==Field.SELECT) return view.selected
        val n=graph.nodes.firstOrNull { it.id==m.node } ?: return ""
        return when(m.field) {
            Field.CONNECT->n.inputs.getOrNull(m.slot).orEmpty();Field.SOURCE->n.part.source;Field.TURN->n.quarterTurns.toString();Field.TOP->n.part.top.toString();Field.SIZE->n.part.size.toString()
            Field.ROTATION_AXIS->n.rotationAxis.name;Field.INPUT_GROUP->n.inputGroups.getOrNull(m.slot).orEmpty();Field.GROUP->n.part.group;Field.TILT->n.part.tilt.toString();Field.MATERIAL->n.materialGroup.ifEmpty { definition().groups.first().id };Field.PREVIEW->n.previewGroup;else->""
        }
    }
    private fun menuBox(m:Menu):IntArray {
        val options=choices(m);val rows=minOf(options.size,12,((height-4)/18).coerceAtLeast(1))
        val w=minOf((width-4).coerceAtLeast(1),maxOf(160,(options.maxOfOrNull { font.width(it.label) } ?: 0)+18).coerceAtMost(280))
        val xx=m.anchorX.coerceIn(x+2,maxOf(x+2,x+width-w-2))
        val yy=(if(m.anchorY+rows*18<=y+height-2) m.anchorY else m.anchorY-rows*18-18).coerceIn(y+2,maxOf(y+2,y+height-rows*18-2))
        return intArrayOf(xx,yy,w,rows)
    }
    private fun openMenu(n:GraphNode,c:Control) {
        view.wire=null
        val sc=screenControl(c);val bottom=sc.y+(if(inspector) 18 else (18*view.zoom).roundToInt())
        val next=Menu(c.field,n.id,sc.x,bottom+1,c.slot)
        val options=choices(next);val rows=menuBox(next)[3]
        next.cursor=options.indexOfFirst { it.id==selected(next) }.coerceAtLeast(0)
        next.first=(next.cursor-rows+1).coerceIn(0,(options.size-rows).coerceAtLeast(0));menu=next
    }
    private fun choose(m:Menu,id:String) {
        if(m.field==Field.SELECT) { view.selected=id;view.wire=null;inspectorScroll=0;return }
        if(m.field==Field.INSERT) {
            val base=BaseShapes.all.firstOrNull { it.id==id }
            if(base!=null) addAt(NodeKind.INPUT,base.source,m.insertX,m.insertY,base.discCut) else addAt(NodeKind.valueOf(id),Adapters.all.keys.first(),m.insertX,m.insertY)
            return
        }
        val n=graph.nodes.firstOrNull { it.id==m.node } ?: return
        if(m.field==Field.CONNECT) { connect(id,n.id,m.slot);return }
        if(id=="@new" && m.field in setOf(Field.GROUP,Field.MATERIAL,Field.INPUT_GROUP)) {
            EditorValueInput.text(tr("new_group"),"",maxLength=32) { name -> onCreateGroup(name,n.id,m.field==Field.MATERIAL,m.slot.takeIf { m.field==Field.INPUT_GROUP } ?: -1) }
            return
        }
        safe {
            val next=when(m.field) {
                Field.ROTATION_AXIS->n.copy(rotationAxis=RotationAxis.valueOf(id));Field.TURN->n.copy(quarterTurns=id.toInt());
                Field.INPUT_GROUP->n.copy(inputGroups=List(n.inputs.size) { i -> if(i==m.slot) id else n.inputGroups.getOrNull(i).orEmpty() });Field.TOP->n.copy(part=n.part.copy(top=id.toBoolean()));Field.SIZE->n.copy(part=n.part.copy(size=id.toInt()))
                Field.GROUP->n.copy(part=n.part.copy(group=id));Field.TILT->n.copy(part=n.part.copy(tilt=id.toInt()));Field.MATERIAL->n.copy(materialGroup=id);Field.PREVIEW->n.copy(previewGroup=id);else->n
            }
            if(next!=n) edit(next)
        }
    }
    fun openSourceSelection(nodeId:String) {
        if(readOnly) return
        val node=graph.nodes.firstOrNull { it.id==nodeId && it.kind==NodeKind.INPUT } ?: return
        menu=null;view.wire=null
        CopycatSelectionScreen.open(BaseShapes.find(node.part.source,node.part.discCut)) { shape ->
            val current=graph.nodes.firstOrNull { it.id==nodeId && it.kind==NodeKind.INPUT } ?: return@open
            if(shape.id!=BaseShapes.find(current.part.source,current.part.discCut).id) safe {
                edit(current.copy(part=current.part.copy(source=shape.source,discCut=shape.discCut,size=1,offset=P(),tilt=0,spin=0)))
            }
        }
    }
    private fun activate(n:GraphNode,c:Control,px:Double,button:Int) {
        if(!c.enabled) return
        if(c.field==Field.SOURCE) { openSourceSelection(n.id);return }
        if(c.field==Field.ROTATE) { rotateSource(n.id,n.rotationAxis,c.slot);return }
        if(dropdown(c.field)) { openMenu(n,c);return }
        if(c.field in setOf(Field.SIZE,Field.X,Field.Y,Field.Z)) {
            val p=n.part
            val value=when(c.field) { Field.SIZE->p.size;Field.X->p.offset.x;Field.Y->p.offset.y;else->p.offset.z }
            EditorValueInput.integer(Component.literal(c.label),value,if(c.field==Field.SIZE) 1..Adapters.get(p.source).max else -15..15) { number ->
                val updated=when(c.field) {
                    Field.SIZE->p.copy(size=number);Field.X->p.copy(offset=p.offset.copy(x=number));Field.Y->p.copy(offset=p.offset.copy(y=number));else->p.copy(offset=p.offset.copy(z=number))
                }
                if(updated!=p) edit(n.copy(part=updated))
            }
            return
        }
        safe {
            val next=when(c.field) {
                Field.GROUPED->n.copy(materialMode=MaterialMode.GROUPED,materialGroup=if(uniformGroupWarning(n)) GraphCompiler.inputMaterialGroups(graph,n,0,definition().groups).single() else n.materialGroup);Field.SEPARATED->n.copy(materialMode=MaterialMode.SEPARATED)
                Field.ADD->n.copy(inputs=n.inputs+"");Field.REMOVE->n.copy(inputs=n.inputs.dropLast(1),inputGroups=n.inputGroups.take(n.inputs.size-1));Field.SWAP->n.copy(inputs=n.inputs.reversed());else->n
            }
            if(next!=n) edit(next)
        }
    }
    private fun text(g:GuiGraphics,s:String,x:Int,y:Int,c:Int=0xffe3eaf0.toInt()) { g.drawString(font,s,x,y,c,false) }
    private fun drawControl(g:GuiGraphics,c:Control) {
        val over=if(inspector) c.contains(screenMouseX,screenMouseY) else c.contains(mouseX,mouseY)
        val bg=when { !c.enabled->0xff293944.toInt();over && menu==null->0xff49606b.toInt();c.active->0xff526158.toInt();else->0xff354854.toInt() }
        if(!c.floating || over) g.fill(c.x,c.y,c.x+c.w,c.y+18,if(c.floating) 0x6049606b else bg)
        if(c.floating) g.renderOutline(c.x,c.y,c.w,18,if(c.enabled) 0x8068818f.toInt() else 0x4050626b)
        if(c.active) g.fill(c.x,c.y+16,c.x+c.w,c.y+18,0xffffc66d.toInt())
        if(c.icon.isNotEmpty()) g.renderItem(net.minecraft.world.item.ItemStack(de.teutonstudio.copycatarchitect.server.Economy.item(c.icon)),c.x+2,c.y+1)
        val inset=if(c.icon.isEmpty()) 3 else 21
        if(c.field==Field.SOURCE) g.renderOutline(c.x,c.y,c.w,18,if(over && c.enabled) 0xffffc66d.toInt() else 0xff68818f.toInt())
        text(g,font.plainSubstrByWidth(c.label,(c.w-inset-if(dropdown(c.field) && c.enabled) 14 else 3).coerceAtLeast(0)),c.x+inset,c.y+5,if(c.enabled) 0xffe3eaf0.toInt() else 0xff9aadb7.toInt())
        if(dropdown(c.field) && c.enabled) text(g,"▾",c.x+c.w-11,c.y+5,0xffffc66d.toInt())
    }
    private fun drawMenu(g:GuiGraphics) {
        val m=menu ?: return;val options=choices(m)
        if(options.isEmpty()) { menu=null;return }
        val (xx,yy,w,rows)=menuBox(m);m.first=m.first.coerceIn(0,(options.size-rows).coerceAtLeast(0))
        g.flush();g.pose().pushPose();g.pose().translate(0f,0f,400f)
        g.fill(xx-1,yy-1,xx+w+1,yy+rows*18+1,0xffd4bb83.toInt())
        for(i in 0 until rows) {
            val choice=options[i+m.first];val hover=screenMouseX>=xx && screenMouseX<xx+w && screenMouseY>=yy+i*18 && screenMouseY<yy+(i+1)*18
            g.fill(xx,yy+i*18,xx+w,yy+(i+1)*18,when { hover->0xff49606b.toInt();selected(m)==choice.id->0xff576157.toInt();else->0xff263b48.toInt() })
            if(m.keyboard && m.cursor==i+m.first) g.fill(xx,yy+i*18,xx+2,yy+(i+1)*18,0xffffc66d.toInt())
            text(g,font.plainSubstrByWidth(choice.label,w-16),xx+5,yy+i*18+5)
        }
        if(rows<options.size) { if(m.first>0) text(g,"↑",xx+w-9,yy+3,0xffffc66d.toInt());if(m.first+rows<options.size) text(g,"↓",xx+w-9,yy+rows*18-12,0xffffc66d.toInt()) }
        g.flush();g.pose().popPose()
    }
    private fun drawPreview(g:GuiGraphics,n:GraphNode,px:Int,py:Int,w:Int) {
        val ready=result()
        ready.getOrNull()?.let { f ->
            val parts=Geometry.previewParts(f.parts,f.groups,n.previewGroup)
            Meshes.preview(g,px+w/2,py+101,48f,view.yaw,parts,groups=f.groups,surfaces=surfaces())
            if(parts.isEmpty()) text(g,ui("empty_group"),px+2,py+37,0xffb4c6ce.toInt())
            text(g,ui("total_cost",Extrusion.cost(f.parts,f.groups)),px+2,py+145,0xffffc66d.toInt())
        } ?: run { font.split(Component.literal(ready.exceptionOrNull()?.message.orEmpty()),w-8).take(10).forEachIndexed { i,line -> g.drawString(font,line,px+4,py+28+i*11,0xffffba98.toInt(),false) } }
        text(g,ui("node_preview_hint"),px+2,py+158,0xffa4b9c4.toInt())
    }
    private fun curve(g:GuiGraphics,a:Pair<Double,Double>,b:Pair<Double,Double>,color:Int) {
        val span=maxOf(40.0,abs(a.first-b.first)*0.45);var last=a
        for(i in 1..50) {
            val t=i/50.0;val k=1-t
            val p=Pair(k*k*k*a.first+3*k*k*t*(a.first+span)+3*k*t*t*(b.first-span)+t*t*t*b.first,k*k*k*a.second+3*k*k*t*a.second+3*k*t*t*b.second+t*t*t*b.second)
            val steps=ceil(max(abs(p.first-last.first),abs(p.second-last.second))).toInt().coerceAtLeast(1)
            for(j in 0..steps) { val v=j.toDouble()/steps;val xx=(last.first+(p.first-last.first)*v).roundToInt();val yy=(last.second+(p.second-last.second)*v).roundToInt();g.fill(xx-1,yy-1,xx+2,yy+2,color) };last=p
        }
    }
    override fun renderWidget(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
        if(!inspector && !view.fitted) fit()
        screenMouseX=mx.toDouble();screenMouseY=my.toDouble();mouseX=localX(screenMouseX);mouseY=localY(screenMouseY)
        g.fill(x,y,x+width,y+height,if(WorldPeek.active) 0x30101b24 else 0xff101b24.toInt());g.enableScissor(x,y,x+width,y+height)
        if(inspector) renderInspector(g) else renderGraph(g)
        drawMenu(g);g.flush();g.disableScissor()
        if(menu==null && inspector && mx>=x && mx<x+width && my>=y+42 && my<y+height) {
            val node=selectedNode()
            inspectorControls(node).firstOrNull { it.contains(mx.toDouble(),my.toDouble()) && it.field in setOf(Field.ROTATE,Field.ROTATION_AXIS) }?.let { control ->
                val tooltip=if(control.field==Field.ROTATION_AXIS) tr("rotation_axis_hint") else if(!control.enabled) tr("rotation_unavailable")
                    else tr("rotate_about_axis",control.slot*90,axisLabel(node.rotationAxis))
                g.renderTooltip(font,tooltip,mx,my)
            }
        }
    }
    private fun renderGraph(g:GuiGraphics) {
        g.pose().pushPose();g.pose().translate(x+view.panX,y+view.panY,0.0);g.pose().scale(view.zoom.toFloat(),view.zoom.toFloat(),1f)
        val gx=floor((-view.panX/view.zoom)/24).toInt()*24;val gy=floor((-view.panY/view.zoom)/24).toInt()*24
        for(xx in gx..(gx+(width/view.zoom).toInt()+48) step 24) for(yy in gy..(gy+(height/view.zoom).toInt()+48) step 24) g.fill(xx,yy,xx+1,yy+1,0xff2a3943.toInt())
        graph.nodes.forEach { n -> n.inputs.forEachIndexed { i,id -> graph.nodes.firstOrNull { it.id==id }?.let { curve(g,outputSocket(it),inputSocket(n,i),0xff76b9c7.toInt()) } } }
        view.wire?.let { id -> graph.nodes.firstOrNull { it.id==id }?.let { curve(g,outputSocket(it),Pair(mouseX,mouseY),0xffffc66d.toInt()) } }
        graph.nodes.forEach { n ->
            val (nx,ny)=position(n);val w=nodeWidth(n);val h=nodeHeight(n)
            val c=when(n.kind) { NodeKind.INPUT->0xff365b68.toInt();NodeKind.OUTPUT->0xff74613b.toInt();else->0xff514969.toInt() }
            if(n.id==view.selected) g.fill(nx-2,ny-2,nx+w+2,ny+h+2,0xffffc66d.toInt())
            g.fill(nx,ny,nx+w,ny+h,0xff23323e.toInt());g.fill(nx,ny,nx+w,ny+23,c);text(g,nodeLabel(n),nx+8,ny+8)
            n.inputs.forEachIndexed { i,id -> val (_,sy)=inputSocket(n,i)
                g.fill(nx-5,sy.toInt()-5,nx+5,sy.toInt()+5,if(id.isEmpty()) 0xff9ba9ad.toInt() else 0xff72d3c0.toInt())
                text(g,if(n.kind==NodeKind.DIFFERENCE) if(i==0) "A" else "B" else "${i+1}",nx+10,sy.toInt()-3)
            }
            if(n.kind!=NodeKind.OUTPUT) { val (_,sy)=outputSocket(n);g.fill(nx+w-5,sy.toInt()-5,nx+w+5,sy.toInt()+5,0xff72d3c0.toInt()) }
            graphControls(n).forEach { drawControl(g,it) }
            if(n.kind==NodeKind.OUTPUT) drawPreview(g,n,nx+7,ny+29,w-14)
        }
        g.flush();g.pose().popPose()
        text(g,ui("node_insert_hint"),x+7,y+height-13,0xffa4b9c4.toInt())
    }
    private fun renderInspector(g:GuiGraphics) {
        val n=selectedNode();val propertyY=inspectorPropertyY(n)
        inspectorContentHeight=propertyY+inspectorScroll-y-47+propertyHeight(n)+14
        inspectorPreviewBox(n)?.let { (_,py,_) -> inspectorContentHeight=maxOf(inspectorContentHeight,py+(if(width<540) inspectorScroll else 0)+171+14-y-47) }
        inspectorScroll=inspectorScroll.coerceIn(0,(inspectorContentHeight-(height-49)).coerceAtLeast(0))
        g.enableScissor(x,y+42,x+width,y+height)
        if(n.inputs.isNotEmpty()) text(g,ui("node_connections"),x+12,y+47-inspectorScroll,0xffffc66d.toInt())
        text(g,ui("node_properties"),x+12,inspectorPropertyY(n)-15,0xffffc66d.toInt())
        inspectorControls(n).filter { it.field!=Field.SELECT }.forEach { drawControl(g,it) }
        if(uniformGroupWarning(n)) {
            val warningY=inspectorPropertyY(n)+42+21*n.inputs.size+5
            font.split(tr("separated_same_group_warning"),inspectorColumnWidth(n)).forEachIndexed { i,line ->
                g.drawString(font,line,x+12,warningY+i*11,0xffffc66d.toInt(),false)
            }
        }
        if(n.kind==NodeKind.OUTPUT) drawPreview(g,n,x+12,inspectorPropertyY(n),width-24)
        inspectorPreviewBox(n)?.let { (px,py,w) ->
            val output=graph.nodes.single { it.kind==NodeKind.OUTPUT }
            g.fill(px-5,py-22,px+w+5,py+175,0xff23323e.toInt());text(g,nodeLabel(output),px,py-15,0xffffc66d.toInt())
            propertyControls(output,px,py,w).forEach { drawControl(g,it) };drawPreview(g,output,px,py,w)
        }
        g.disableScissor()
        g.fill(x,y,x+width,y+41,0xff23323e.toInt());drawControl(g,inspectorControls(n).first())
        g.fill(x+12,y+36,x+width-12,y+37,0xff526158.toInt())
        if(inspectorContentHeight>height-49) {
            val track=height-49;val thumb=maxOf(12,track*track/inspectorContentHeight);val top=y+45+(track-thumb)*inspectorScroll/(inspectorContentHeight-track).coerceAtLeast(1)
            g.fill(x+width-5,y+45,x+width-3,y+height-4,0xff263b48.toInt());g.fill(x+width-5,top,x+width-3,top+thumb,0xffb79963.toInt())
        }
    }
    override fun mouseClicked(mx:Double,my:Double,button:Int):Boolean {
        menu?.let { m ->
            val (xx,yy,w,rows)=menuBox(m);val options=choices(m);menu=null
            if(button==0 && mx>=xx && mx<xx+w && my>=yy && my<yy+rows*18) options.getOrNull(m.first+((my-yy)/18).toInt())?.let { choose(m,it.id) }
            return true
        }
        if(!isMouseOver(mx,my)) return false
        screenMouseX=mx;screenMouseY=my
        if(inspector) {
            val n=selectedNode();val controls=inspectorControls(n)
            controls.firstOrNull { it.contains(mx,my) && (it.field==Field.SELECT || my>=y+42) }?.let { if(button==0 || button==1) activate(n,it,mx,button);return true }
            inspectorPreviewBox(n)?.let { (px,py,w) ->
                val output=graph.nodes.single { it.kind==NodeKind.OUTPUT }
                propertyControls(output,px,py,w).firstOrNull { it.contains(mx,my) && my>=y+42 }?.let { if(button==0 || button==1) activate(output,it,mx,button);return true }
                if(button==1 && mx>=px && mx<px+w && my>=maxOf(y+42,py+20) && my<py+175) orbit=true
            }
            if(button==1 && n.kind==NodeKind.OUTPUT && my>=inspectorPropertyY(n)+20) orbit=true
            return true
        }
        val lx=localX(mx);val ly=localY(my);mouseX=lx;mouseY=ly
        if(button==2) { panning=true;return true }
        for(n in graph.nodes.asReversed()) {
            n.inputs.indices.forEach { i -> val s=inputSocket(n,i)
                if(abs(lx-s.first)<=9 && abs(ly-s.second)<=8) {
                    view.selected=n.id
                    if(button==1) { connect("",n.id,i);view.wire=null }
                    else if(button==0) { view.wire?.let { connect(it,n.id,i);view.wire=null } ?: run { view.wire=n.inputs[i].ifEmpty { null } } }
                    return true
                }
            }
            if(n.kind!=NodeKind.OUTPUT) { val s=outputSocket(n)
                if(abs(lx-s.first)<=9 && abs(ly-s.second)<=8 && button==0) { view.wire=n.id;view.selected=n.id;return true }
            }
            val (nx,ny)=position(n);val px=lx-nx;val py=ly-ny
            if(px<0 || px>=nodeWidth(n) || py<0 || py>=nodeHeight(n)) continue
            view.selected=n.id
            if(button==0 && py<23) { if(!readOnly) { dragging=n.id;moveX=n.x;moveY=n.y };return true }
            graphControls(n).firstOrNull { it.contains(lx,ly) }?.let { if(button==0 || button==1) activate(n,it,lx,button);return true }
            if(button==1 && n.kind==NodeKind.OUTPUT) orbit=true
            return true
        }
        if(button==1 && !readOnly) contextStart=mx to my else if(button==0) view.wire=null
        return true
    }
    override fun mouseDragged(mx:Double,my:Double,button:Int,dx:Double,dy:Double):Boolean {
        if(menu!=null) return true
        contextStart?.let { start ->
            if(hypot(mx-start.first,my-start.second)>=4) { contextStart=null;panning=true;view.panX+=mx-start.first;view.panY+=my-start.second };return true
        }
        when { dragging!=null -> { moveX=(moveX+dx/view.zoom).roundToInt().coerceIn(-8192,8192);moveY=(moveY+dy/view.zoom).roundToInt().coerceIn(-8192,8192) }
            panning->{view.panX+=dx;view.panY+=dy};orbit->view.yaw+=dx.toFloat();else->return view.wire!=null }
        return true
    }
    override fun mouseReleased(mx:Double,my:Double,button:Int):Boolean {
        contextStart?.let { start ->
            contextStart=null
            if(button==1 && hypot(mx-start.first,my-start.second)<4 && isMouseOver(mx,my)) {
                view.wire=null;menu=Menu(Field.INSERT,"",mx.roundToInt(),my.roundToInt(),insertX=localX(mx).roundToInt(),insertY=localY(my).roundToInt())
            }
        }
        dragging?.let { id -> val node=graph.nodes.firstOrNull { it.id==id };dragging=null;if(node!=null && (node.x!=moveX || node.y!=moveY)) edit(node.copy(x=moveX,y=moveY)) }
        if(!inspector && button==0 && view.wire!=null) {
            val lx=localX(mx);val ly=localY(my)
            for(n in graph.nodes) for(i in n.inputs.indices) { val s=inputSocket(n,i);if(abs(lx-s.first)<=9 && abs(ly-s.second)<=8) { val source=view.wire!!;view.wire=null;connect(source,n.id,i);panning=false;orbit=false;return true } }
        }
        panning=false;orbit=false;return true
    }
    override fun mouseScrolled(mx:Double,my:Double,dx:Double,dy:Double):Boolean {
        menu?.let { m ->
            val (xx,yy,w,rows)=menuBox(m)
            if(mx>=xx && mx<xx+w && my>=yy && my<yy+rows*18) m.first=(m.first-dy.sign.toInt()).coerceIn(0,(choices(m).size-rows).coerceAtLeast(0)) else menu=null
            return true
        }
        if(!isMouseOver(mx,my)) return false
        if(inspector) { inspectorScroll=(inspectorScroll-(dy*21).roundToInt()).coerceIn(0,(inspectorContentHeight-(height-49)).coerceAtLeast(0));return true }
        val lx=localX(mx);val ly=localY(my)
        view.zoom=(view.zoom*1.12.pow(dy)).coerceIn(0.25,1.75)
        view.panX=mx-x-lx*view.zoom;view.panY=my-y-ly*view.zoom;return true
    }
    override fun keyPressed(key:Int,scan:Int,mods:Int):Boolean {
        if(menu!=null) {
            if(key==256) { menu=null;return true }
            val m=menu!!;val options=choices(m);val rows=menuBox(m)[3]
            if(key==257 || key==335) { menu=null;options.getOrNull(m.cursor)?.let { choose(m,it.id) };return true }
            if(key in listOf(264,265,266,267,268,269)) {
                m.keyboard=true
                m.cursor=when(key) { 264->m.cursor+1;265->m.cursor-1;266->m.cursor-rows;267->m.cursor+rows;268->0;else->options.lastIndex }.coerceIn(0,options.lastIndex.coerceAtLeast(0))
                m.first=m.first.coerceIn(maxOf(0,m.cursor-rows+1),m.cursor).coerceIn(0,(options.size-rows).coerceAtLeast(0));return true
            }
            return true
        }
        if(key==261 || key==259) { deleteSelected();return true }
        if(key==256 && view.wire!=null) { view.wire=null;return true };return false
    }
    override fun updateWidgetNarration(output:NarrationElementOutput) { output.add(NarratedElementType.TITLE,tr("node_graph"));output.add(NarratedElementType.USAGE,tr("graph_help")) }
}
