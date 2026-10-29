package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.network.chat.Component
import net.minecraft.client.renderer.texture.OverlayTexture
import com.mojang.math.Axis
import java.util.UUID
import kotlin.math.*

class BuildingGraphView {
    var panX=10.0;var panY=10.0;var zoom=0.65;var fitted=false;var selected="building";var wire:String?=null;var yaw=35f
    var inspectorScroll=0.0;var inspectorNode=""
    val rectangle=mutableMapOf<String,Int>();val room=mutableMapOf<String,Int>()
}
object BuildingScene {
    fun draw(g:GuiGraphics,plan:Plan,x:Int,y:Int,w:Int,h:Int,yaw:Float,filter:String="all") {
        val cells=plan.placements.filter { filter=="all" || if(filter=="walls") it.component in listOf("wall","corner","trim","gable") else it.component==filter }
        if(cells.isEmpty()) return
        val lo=P(cells.minOf { it.pos.x },cells.minOf { it.pos.y },cells.minOf { it.pos.z });val hi=P(cells.maxOf { it.pos.x }+1,cells.maxOf { it.pos.y }+1,cells.maxOf { it.pos.z }+1)
        val sx=(hi.x-lo.x).toFloat();val sy=(hi.y-lo.y).toFloat();val sz=(hi.z-lo.z).toFloat()
        val q=org.joml.Quaternionf().rotationX(Math.toRadians(25.0).toFloat()).rotateY(Math.toRadians(yaw.toDouble()).toFloat())
        val corners=buildList { for(a in listOf(-sx/2,sx/2)) for(b in listOf(-sy/2,sy/2)) for(c in listOf(-sz/2,sz/2)) add(q.transform(org.joml.Vector3f(a,b,c))) }
        val scale=minOf(w/(corners.maxOf { it.x }-corners.minOf { it.x }),h/(corners.maxOf { it.y }-corners.minOf { it.y }))*0.88f
        g.flush();val pose=g.pose();pose.pushPose();pose.translate(x+w/2.0,y+h/2.0,160.0);pose.scale(scale,-scale,scale);pose.mulPose(q);pose.translate(-(hi.x+lo.x)/2.0,-(hi.y+lo.y)/2.0,-(hi.z+lo.z)/2.0)
        for(p in cells) {
            pose.pushPose();pose.translate(p.pos.x.toDouble(),p.pos.y.toDouble(),p.pos.z.toDouble())
            if(p.block.isEmpty()) Meshes.draw(p.parts,p.material,pose,g.bufferSource(),15728880,groups=p.groups,surfaces=p.surfaces)
            else Minecraft.getInstance().blockRenderer.renderSingleBlock(Economy.nativeState(p.block),pose,g.bufferSource(),15728880,OverlayTexture.NO_OVERLAY)
            pose.popPose()
        }
        g.flush();pose.popPose()
    }
}
class BuildingGraphCanvas(x:Int,y:Int,w:Int,h:Int,private val definition:()->Building,private val library:()->Library,
    val view:BuildingGraphView,private val commit:(BuildingGraph)->Unit,private val failure:(String)->Unit,private val inspector:Boolean=false,
    private val onCreateRole:(String)->Unit={},private val preview:(()->Result<CompiledBuilding>)?=null,
    private val readOnly:Boolean=false):AbstractWidget(x,y,w,h,tr("building_graph")) {
    private val font get()=Minecraft.getInstance().font
    private val graph get()=definition().graph!!
    private var oldBuilding:Building?=null;private var oldLibrary:Library?=null;private var oldRecipes=0;private var evaluator:Result<BuildingGraphCompiler.Evaluation>?=null;private var compiled:Result<CompiledBuilding>?=null
    private var headerDrag:String?=null;private var moving:BuildingNode?=null;private var panning=false;private var orbit=false
    private var rightStart:Pair<Double,Double>?=null;private var rightGraph:Pair<Double,Double>?=null
    private var inspectorScroll:Double
        get()=view.inspectorScroll
        set(value) { view.inspectorScroll=value }
    private var pointerX=Double.NEGATIVE_INFINITY;private var pointerY=Double.NEGATIVE_INFINITY
    private val selected get()=graph.nodes.firstOrNull { it.id==view.selected } ?: graph.nodes.first()
    private val sidePreview get()=inspector && width>=600 && selected.kind!=BuildingKind.BUILDING
    private val inspectorWidth get()=if(sidePreview) minOf(420,(width*0.48).roundToInt()) else width
    private val scale get()=if(inspector) minOf(1.0,(inspectorWidth-28.0)/nodeWidth(selected)).coerceAtLeast(0.2) else view.zoom
    private val panX get()=if(inspector) (inspectorWidth-nodeWidth(selected)*scale)/2 else view.panX
    private val panY get()=if(inspector) 42.0-inspectorScroll else view.panY
    private data class Projection(val x:Double,val y:Double,val minX:Int,val minZ:Int,val step:Double) {
        fun px(v:Int)=x+(v-minX)*step;fun py(v:Int)=y+(v-minZ)*step
    }
    private data class MapDrag(val original:BuildingNode,val index:Int,val resize:Boolean,val startX:Double,val startY:Double,val projection:Projection)
    private var mapDrag:MapDrag?=null;private val projections=mutableMapOf<String,Projection>()
    private data class Menu(val node:String,val type:String,val anchor:Int,var first:Int=0,val column:Int=8,val width:Int=0,val at:Pair<Double,Double>?=null,val socket:String="",val insertion:Pair<Double,Double>?=null,var choices:List<Pair<String,String>>?=null,var focus:Int=0)
    private var menu:Menu?=null
    fun hasOpenMenu()=menu!=null
    private fun evaluation():Result<BuildingGraphCompiler.Evaluation> {
        val b=definition();val lib=library()
        if(oldBuilding!=b || oldLibrary!=lib || oldRecipes!=Extrusion.outputs.hashCode()) { oldBuilding=b;oldLibrary=lib;oldRecipes=Extrusion.outputs.hashCode();evaluator=runCatching { BuildingGraphCompiler.Evaluation(graph,b.palette,lib.forms,lib.modules) };compiled=null }
        return evaluator!!
    }
    fun result():Result<CompiledBuilding> { preview?.let { return it() };evaluation();if(compiled==null) compiled=runCatching { evaluation().getOrThrow().building() };return compiled!! }
    private fun shown(n:BuildingNode):BuildingNode { val actual=moving?.takeIf { it.id==n.id } ?: n;return if(inspector) actual.copy(x=0,y=0) else actual }
    private fun ports(n:BuildingNode):List<BuildingPort> = BuildingPorts.of(n,true)
    private fun layoutKind(n:BuildingNode)=if(n.kind==BuildingKind.STOREY && n.storeyMode=="walls") BuildingKind.ROOM else n.kind
    fun nodeWidth(n:BuildingNode)=when(n.kind) { BuildingKind.ROOM->274;BuildingKind.STOREY->302;BuildingKind.BUILDING->324;else->210 }
    private fun body(n:BuildingNode)=(if(ports(n).isEmpty()) 29 else 45+ports(n).size*19)+(if(n.kind==BuildingKind.STOREY) 22 else 0)
    fun nodeHeight(n:BuildingNode)=body(n)+when(layoutKind(n)) { BuildingKind.COPYCAT->64;BuildingKind.SEGMENT->100;BuildingKind.PATTERN->92;BuildingKind.FLOOR,BuildingKind.CEILING->132;BuildingKind.ROOM->275;BuildingKind.STOREY->235;BuildingKind.ROOF->120;BuildingKind.BUILDING->284 }
    private fun nx(mx:Double)=(mx-x-panX)/scale
    private fun ny(my:Double)=(my-y-panY)/scale
    private fun point(n:BuildingNode,i:Int)=Pair(n.x.toDouble(),n.y+36.0+i*19)
    private fun output(n:BuildingNode)=Pair(n.x+nodeWidth(n).toDouble(),n.y+36.0)
    private fun kindName(kind:BuildingKind)=ui("bg_"+kind.name.lowercase())
    private fun name(n:BuildingNode):String {
        val label=if(n.id=="empty_wall") ui("empty_wall") else if(n.name in listOf("Room","Storey","Floor","Ceiling","Roof","Building")) kindName(n.kind) else if(n.name=="Solid wall") ui("bg_solid_wall") else n.name.ifEmpty { kindName(n.kind) }
        return "${graph.nodes.indexOfFirst { it.id==n.id }+1}. $label"
    }
    private fun roleLabel(role:String)=if(net.minecraft.client.resources.language.I18n.exists("gui.copycat_architect.role_$role")) ui("role_$role") else role
    private fun text(g:GuiGraphics,s:String,x:Int,y:Int,c:Int=0xffe5edf1.toInt()) { g.drawString(font,s,x,y,c,false) }
    private fun row(g:GuiGraphics,s:String,x:Int,y:Int,w:Int) { val hover=!readOnly && pointerX>=x && pointerX<x+w && pointerY>=y && pointerY<y+17;val marker=s.endsWith(" ▾");val dropdown=marker && !readOnly;g.fill(x,y,x+w,y+17,if(hover) 0xff48616e.toInt() else 0xff354854.toInt());text(g,font.plainSubstrByWidth(if(marker) s.dropLast(2) else s,w-if(dropdown) 17 else 6),x+3,y+5,if(readOnly) 0xff9aadb7.toInt() else 0xffe5edf1.toInt());if(dropdown) text(g,"▾",x+w-11,y+5,0xffffce7d.toInt()) }
    private fun color(k:BuildingKind)=when(k) { BuildingKind.SEGMENT,BuildingKind.PATTERN->0xffb79963.toInt();BuildingKind.ROOM->0xff60a69c.toInt();BuildingKind.STOREY->0xff9381b3.toInt();BuildingKind.ROOF->0xffb77865.toInt();else->0xff739cab.toInt() }
    fun fit() {
        if(inspector) { inspectorScroll=0.0;return }
        val a=graph.nodes.minOf { it.x };val b=graph.nodes.minOf { it.y };val width=graph.nodes.maxOf { it.x+nodeWidth(it) }-a+50;val height=graph.nodes.maxOf { it.y+nodeHeight(it) }-b+50
        view.zoom=minOf(this.width.toDouble()/width,this.height.toDouble()/height,1.0).coerceIn(0.2,1.75)
        view.panX=(this.width-width*view.zoom)/2+(25-a)*view.zoom;view.panY=(this.height-height*view.zoom)/2+(25-b)*view.zoom;view.fitted=true
    }
    fun add(kind:BuildingKind) = addAt(kind,((width/2-view.panX)/view.zoom),((height/2-view.panY)/view.zoom))
    fun addAt(kind:BuildingKind,atX:Double,atY:Double) {
        if(readOnly) return
        safe {
            require(graph.nodes.size<96 && kind!=BuildingKind.BUILDING) { ui("bg_node_limit") }
            val n=BuildingNode(UUID.randomUUID().toString(),kind,kindName(kind),atX.roundToInt().coerceIn(-8000,8000),atY.roundToInt().coerceIn(-8000,8000),
                inputs=when(kind) { BuildingKind.PATTERN->listOf("","");else->emptyList() },storeyMode=if(kind==BuildingKind.STOREY) "walls" else "rooms",floor=if(kind in setOf(BuildingKind.ROOM,BuildingKind.STOREY)) graph.nodes.firstOrNull { it.kind==BuildingKind.FLOOR }?.id.orEmpty() else "",ceiling=if(kind in setOf(BuildingKind.ROOM,BuildingKind.STOREY)) graph.nodes.firstOrNull { it.kind==BuildingKind.CEILING }?.id.orEmpty() else "",role=if(kind==BuildingKind.ROOF) "roof" else "wall")
            view.selected=n.id;inspectorScroll=0.0;commit(graph.copy(nodes=graph.nodes+n))
        }
    }
    fun deleteSelected() { if(readOnly) return;safe { if(graph.nodes.any { it.id==view.selected && it.kind!=BuildingKind.BUILDING && it.id!="empty_wall" }) { val changed=graph.remove(view.selected);view.wire=null;view.selected=changed.nodes.first { it.kind==BuildingKind.BUILDING }.id;inspectorScroll=0.0;commit(changed) } } }
    private fun safe(action:()->Unit) { try { action() } catch(e:Exception) { failure(e.message ?: "Invalid building graph") } }
    private fun edit(n:BuildingNode) { if(readOnly) return;val original=graph.nodes.first { it.id==n.id };val changed=graph.replace(if(inspector) n.copy(x=original.x,y=original.y) else n);BuildingGraphCompiler.structure(changed);commit(changed) }
    fun connect(source:String,target:String,key:String):Boolean = if(readOnly) false else try {
        var changed=graph.connect(target,key,source)
        val n=graph.nodes.first { it.id==target }
        if(source.isNotEmpty() && n.kind==BuildingKind.STOREY && key.startsWith("in:")) {
            val i=key.removePrefix("in:").toInt()
            if(i>0 && n.inputs.getOrNull(i).isNullOrEmpty()) {
                val next=changed.nodes.first { it.id==target };val room=graph.nodes.first { it.id==source }
                val max=n.inputs.mapIndexedNotNull { j,s -> graph.nodes.firstOrNull { it.id==s }?.takeIf { it.kind==BuildingKind.ROOM }?.let { RoomGeometry.cells(it.rectangles).maxOf { it.x }+(n.offsets.getOrNull(j)?.x ?: 0) } }.maxOrNull() ?: -2
                val offset=P(max+2-RoomGeometry.cells(room.rectangles).minOf { it.x },0,0)
                changed=changed.replace(next.copy(offsets=List(next.inputs.size) { j -> if(j==i) offset else next.offsets.getOrNull(j) ?: P() }))
            }
        }
        BuildingGraphCompiler.structure(changed);commit(changed);true
    } catch(e:Exception) { failure(e.message.orEmpty());false }
    private fun curve(g:GuiGraphics,a:Pair<Double,Double>,b:Pair<Double,Double>,c:Int) {
        val d=maxOf(35.0,abs(a.first-b.first)*0.4);var last=a
        for(i in 1..40) { val t=i/40.0;val k=1-t;val p=Pair(k*k*k*a.first+3*k*k*t*(a.first+d)+3*k*t*t*(b.first-d)+t*t*t*b.first,k*k*k*a.second+3*k*k*t*a.second+3*k*t*t*b.second+t*t*t*b.second)
            val steps=ceil(max(abs(p.first-last.first),abs(p.second-last.second))).toInt().coerceAtLeast(1)
            for(j in 0..steps) { val u=j.toDouble()/steps;val xx=(last.first+(p.first-last.first)*u).roundToInt();val yy=(last.second+(p.second-last.second)*u).roundToInt();g.fill(xx-1,yy-1,xx+2,yy+2,c) };last=p }
    }
    private fun label(p:BuildingPort):String=when {
        p.key.startsWith("wall:") -> if(p.length>0) ui("bg_wall_port",ui(listOf("north","east","south","west")[p.key.removePrefix("wall:").substringBefore(':').toInt()]),p.length,p.height) else ui("bg_roof_wall_port",ui(listOf("north","east","south","west")[p.key.removePrefix("wall:").toInt()]))
        p.key.startsWith("level:") -> if(p.number==0) ui("bg_ground") else ui(if(p.number>0) "bg_upper" else "bg_lower",abs(p.number))
        p.key=="floor"->ui("bg_floor");p.key=="ceiling"->ui("bg_ceiling");p.key=="roof"->ui("bg_roof")
        p.key=="corner"->ui("bg_corner_copycat")
        else->ui(if(BuildingKind.COPYCAT in p.accepts) "bg_copycat_index" else if(BuildingKind.ROOM in p.accepts) "bg_room_index" else "bg_segment_index",p.number+1)
    }
    private fun materialRows(g:GuiGraphics,n:BuildingNode,y:Int) {
        val w=nodeWidth(n)-16;row(g,ui("bg_material_mode",ui(if(n.materialMode==MaterialMode.GROUPED) "grouped" else "separated"))+" ▾",n.x+8,y,w)
        row(g,ui("role",roleLabel(n.role)),n.x+8,y+21,w)
    }
    private fun projection(g:GuiGraphics,n:BuildingNode,points:Set<P>,y:Int,h:Int):Projection {
        val minX=(points.minOfOrNull { it.x } ?: 0)-1;val maxX=(points.maxOfOrNull { it.x } ?: 3)+2;val minZ=(points.minOfOrNull { it.z } ?: 0)-1;val maxZ=(points.maxOfOrNull { it.z } ?: 3)+2
        val step=minOf((nodeWidth(n)-24).toDouble()/(maxX-minX), (h-12).toDouble()/(maxZ-minZ),18.0)
        val p=mapDrag?.takeIf { it.original.id==n.id }?.projection ?: Projection(n.x+12.0,y+6.0,minX,minZ,step);projections[n.id]=p
        g.fill(n.x+8,y,n.x+nodeWidth(n)-8,y+h,0xff14232c.toInt())
        for(xx in minX..maxX) for(zz in minZ..maxZ) g.fill(p.px(xx).toInt(),p.py(zz).toInt(),p.px(xx).toInt()+1,p.py(zz).toInt()+1,0xff42606b.toInt())
        return p
    }
    fun gridPoint(id:String,cellX:Double,cellZ:Double):Pair<Double,Double> {
        val p=projections[id] ?: error("Map has not rendered")
        return Pair(x+panX+(p.x+(cellX-p.minX)*p.step)*scale,y+panY+(p.y+(cellZ-p.minZ)*p.step)*scale)
    }
    private fun mapScissor(g:GuiGraphics,n:BuildingNode,y:Int,h:Int) {
        g.enableScissor((x+panX+(n.x+8)*scale).toInt(),(this.y+panY+y*scale).toInt(),(x+panX+(n.x+nodeWidth(n)-8)*scale).toInt(),(this.y+panY+(y+h)*scale).toInt())
    }
    private fun roomMap(g:GuiGraphics,n:BuildingNode,y:Int) {
        val p=projection(g,n,RoomGeometry.cells(n.rectangles),y,126);mapScissor(g,n,y,126);val selected=(view.rectangle[n.id] ?: 0).coerceIn(0,n.rectangles.lastIndex)
        n.rectangles.forEachIndexed { i,r ->
            val a=p.px(r.x).toInt();val b=p.py(r.z).toInt();val c=p.px(r.x+r.width).toInt();val d=p.py(r.z+r.length).toInt()
            g.fill(a,b,c,d,if(i==selected) 0xff739d91.toInt() else 0xff3e6e77.toInt())
            if(i==selected) { g.renderOutline(a,b,c-a,d-b,0xffffce7d.toInt());g.fill(c-5,d-5,c+2,d+2,0xffffce7d.toInt()) }
            text(g,"${i+1}",a+2,b+2)
        }
        g.flush();g.disableScissor()
    }
    private fun storeyMap(g:GuiGraphics,n:BuildingNode,y:Int) {
        val rooms=n.inputs.mapIndexedNotNull { i,s -> graph.nodes.firstOrNull { it.id==s && it.kind==BuildingKind.ROOM }?.let { Triple(i,it,n.offsets.getOrNull(i) ?: P()) } }
        val cells=rooms.flatMap { (_,room,offset) -> RoomGeometry.cells(room.rectangles).map { it+offset } }.toSet()
        val p=projection(g,n,cells,y,143);mapScissor(g,n,y,143)
        for((i,room,offset) in rooms) for(cell in RoomGeometry.cells(room.rectangles)) {
            val a=cell+offset;val xx=p.px(a.x).toInt();val yy=p.py(a.z).toInt()
            g.fill(xx,yy,p.px(a.x+1).toInt(),p.py(a.z+1).toInt(),if(i==(view.room[n.id] ?: 0)) 0xffbda166.toInt() else color(BuildingKind.ROOM))
        }
        for((i,room,offset) in rooms) { val a=RoomGeometry.cells(room.rectangles).minWith(compareBy({it.z},{it.x}))+offset;text(g,"${i+1}",p.px(a.x).toInt()+2,p.py(a.z).toInt()+2,0xff15212a.toInt()) }
        g.flush();g.disableScissor()
    }
    override fun renderWidget(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
        if(inspector) { if(view.inspectorNode!=selected.id) { view.inspectorNode=selected.id;inspectorScroll=0.0 };inspectorScroll=inspectorScroll.coerceIn(0.0,maxOf(0.0,nodeHeight(shown(selected))*scale-height+54)) }
        if(!view.fitted && !inspector) fit();val lx=nx(mx.toDouble());val ly=ny(my.toDouble());pointerX=lx;pointerY=ly
        g.fill(x,y,x+width,y+height,0xff101b24.toInt());g.enableScissor(x,y+if(inspector) 35 else 0,x+width,y+height);g.pose().pushPose();g.pose().translate(x+panX,y+panY,0.0);g.pose().scale(scale.toFloat(),scale.toFloat(),1f)
        if(!inspector) {
            for(xx in floor(-panX/scale/24).toInt()*24..((width-panX)/scale).toInt()+24 step 24) for(yy in floor(-panY/scale/24).toInt()*24..((height-panY)/scale).toInt()+24 step 24) g.fill(xx,yy,xx+1,yy+1,0xff293943.toInt())
            graph.nodes.forEach { raw -> val n=shown(raw);ports(n).forEachIndexed { i,p -> graph.nodes.firstOrNull { it.id==p.source }?.let { curve(g,output(shown(it)),point(n,i),color(it.kind)) } } }
            view.wire?.let { id -> graph.nodes.firstOrNull { it.id==id }?.let { curve(g,output(shown(it)),lx to ly,0xffffce7d.toInt()) } }
        }
        for(raw in if(inspector) listOf(selected) else graph.nodes) {
            val n=shown(raw);val w=nodeWidth(n);val h=nodeHeight(n);val by=n.y+body(n)
            if(n.id==view.selected) g.fill(n.x-2,n.y-2,n.x+w+2,n.y+h+2,0xffffc66d.toInt())
            g.fill(n.x,n.y,n.x+w,n.y+h,0xff23323e.toInt());g.fill(n.x,n.y,n.x+w,n.y+23,color(n.kind));text(g,font.plainSubstrByWidth(name(n),w-12),n.x+7,n.y+8,0xff12212a.toInt())
            ports(n).forEachIndexed { i,p -> val sy=n.y+36+i*19;val source=graph.nodes.firstOrNull { it.id==p.source };row(g,ui("bg_connection",label(p),source?.let { name(it) } ?: ui("bg_no_connection"))+" ▾",n.x+9,sy-8,w-18);if(!inspector) g.fill(n.x-5,sy-5,n.x+5,sy+5,if(p.source.isEmpty()) 0xffa6b4bb.toInt() else color(p.accepts.first())) }
            if(!inspector && n.kind!=BuildingKind.BUILDING) g.fill(n.x+w-5,n.y+31,n.x+w+5,n.y+41,color(n.kind))
            if(n.kind==BuildingKind.STOREY) row(g,ui("bg_storey_mode",ui("bg_storey_"+n.storeyMode))+" ▾",n.x+8,by-22,w-16)
            when(layoutKind(n)) {
                BuildingKind.COPYCAT -> {
                    row(g,(library().forms[n.form]?.name ?: n.form)+" ▾",n.x+8,by,w-16)
                    row(g,ui("role",roleLabel(n.role)),n.x+8,by+22,w-16)
                }
                BuildingKind.SEGMENT -> {
                    val source=if(n.inputs.any { it.isNotEmpty() }) ui("bg_connected_copycats") else if(n.block=="minecraft:air") ui("empty_wall") else if(n.block.isNotEmpty()) n.block.substringAfter(':') else library().modules[n.module]?.name ?: ui("bg_solid_wall")
                    row(g,source+if(n.id!="empty_wall") " ▾" else "",n.x+8,by,w-16)
                    val s=evaluation().getOrNull()?.let { runCatching { it.segment(n.id) }.getOrNull() }
                    row(g,ui("bg_fixed_length",s?.length ?: n.length),n.x+8,by+22,94);row(g,ui("height",s?.height ?: n.height),n.x+108,by+22,94)
                    row(g,ui("role",roleLabel(n.role)),n.x+8,by+44,w-16)
                    text(g,if(n.block=="minecraft:air") ui("bg_adaptive_air") else ui("bg_fixed_segment"),n.x+8,by+71,0xffb4c8d2.toInt())
                }
                BuildingKind.PATTERN -> {
                    val s=evaluation().getOrNull()?.let { runCatching { it.segment(n.id) }.getOrNull() };text(g,s?.let { ui("bg_dimensions",it.length,it.height) } ?: ui("bg_connect_pattern"),n.x+8,by)
                    row(g,"+ ${ui("input")}",n.x+8,by+17,94);row(g,"− ${ui("input")}",n.x+108,by+17,94);materialRows(g,n,by+40)
                }
                BuildingKind.FLOOR,BuildingKind.CEILING -> {
                    row(g,(if(n.inputs.any { it.isNotEmpty() }) ui("bg_connected_copycats") else library().forms[n.form]?.name ?: n.form)+" ▾",n.x+8,by,w-16)
                    row(g,ui("bg_thickness",n.thickness),n.x+8,by+22,w-16)
                    row(g,ui(if(n.occupied) "bg_boundary_occupied" else "bg_boundary_free")+" ▾",n.x+8,by+44,w-16)
                    if(n.kind==BuildingKind.FLOOR) row(g,ui("bg_finish",n.finish.substringAfter(':').ifEmpty { ui("none") })+" ▾",n.x+8,by+66,w-16)
                    row(g,ui("role",roleLabel(n.role)),n.x+8,by+88,w-16)
                }
                BuildingKind.ROOM -> {
                    row(g,ui("room_height",n.height),n.x+8,by,115)
                    val floor=graph.nodes.firstOrNull { it.id==n.floor };val ceiling=graph.nodes.firstOrNull { it.id==n.ceiling }
                    text(g,ui("bg_buildable",n.height-(if(floor?.occupied==true) 1 else 0)-(if(ceiling?.occupied==true) 1 else 0)),n.x+132,by+5)
                    val chosen=(view.rectangle[n.id] ?: 0).coerceIn(0,n.rectangles.lastIndex);val rect=n.rectangles[chosen]
                    row(g,ui("bg_rect_index",chosen+1)+" ▾",n.x+8,by+22,100);row(g,"+",n.x+113,by+22,35);row(g,"−",n.x+153,by+22,35)
                    row(g,ui("width",rect.width),n.x+8,by+44,100);row(g,ui("bg_depth",rect.length),n.x+113,by+44,100)
                    roomMap(g,n,by+67);text(g,ui("bg_rectangle_drag"),n.x+8,by+198,0xffa7bec7.toInt());materialRows(g,n,by+216)
                }
                BuildingKind.STOREY -> {
                    val index=(view.room[n.id] ?: 0).coerceIn(0,n.inputs.lastIndex.coerceAtLeast(0));val offset=n.offsets.getOrNull(index) ?: P()
                    row(g,ui("bg_room_index",index+1)+" ▾",n.x+8,by,100);row(g,"+",n.x+114,by,35);row(g,"−",n.x+154,by,35)
                    row(g,"X ${offset.x}",n.x+8,by+22,100);row(g,"Z ${offset.z}",n.x+114,by+22,100)
                    storeyMap(g,n,by+45);materialRows(g,n,by+193)
                }
                BuildingKind.ROOF -> {
                    row(g,ui(n.roofType)+" ▾",n.x+8,by,w-16);row(g,ui("bg_ridge",if(n.ridge==0) "Z" else "X")+" ▾",n.x+8,by+22,w-16)
                    row(g,ui("overhang",n.overhang),n.x+8,by+44,94);row(g,ui("bg_thickness",n.thickness),n.x+108,by+44,94)
                    row(g,ui("role",roleLabel(n.role)),n.x+8,by+66,w-16)
                }
                BuildingKind.BUILDING -> {
                    row(g,ui("bg_filter_"+n.preview)+" ▾",n.x+8,by,w-16)
                    val ready=result()
                    ready.getOrNull()?.let { b -> BuildingScene.draw(g,b.plan,n.x+8,by+23,w-16,150,view.yaw,n.preview);text(g,ui("bg_plan_cost",b.plan.placements.size,b.plan.raw),n.x+8,by+180,0xffffc66d.toInt()) }
                        ?: font.split(Component.literal(ready.exceptionOrNull()?.message.orEmpty()),w-20).take(12).forEachIndexed { i,line -> g.drawString(font,line,n.x+10,by+33+i*11,0xffffb295.toInt(),false) }
                    text(g,ui("bg_level_drag"),n.x+8,by+199,0xffafc6ce.toInt());text(g,ui("node_preview_hint"),n.x+8,by+213,0xffafc6ce.toInt());materialRows(g,n,by+235)
                }
            }
        }
        g.flush();g.pose().popPose();g.disableScissor()
        if(inspector) {
            g.fill(x,y,x+width,y+35,0xff1a2a35.toInt());g.fill(x,y+34,x+width,y+35,0xff536873.toInt());val hover=mx>=x+10 && mx<x+width-10 && my>=y+8 && my<y+27;g.fill(x+10,y+8,x+width-10,y+27,if(hover) 0xff48616e.toInt() else 0xff354854.toInt());text(g,font.plainSubstrByWidth(ui("bg_node_picker",name(selected)),width-45),x+15,y+14);text(g,"▾",x+width-23,y+14,0xffffce7d.toInt())
            val overflow=maxOf(0.0,nodeHeight(shown(selected))*scale-height+54)
            if(overflow>0) { val track=(height-49).coerceAtLeast(1);val thumb=maxOf(14,(track*track/(track+overflow)).toInt()).coerceAtMost(track);val sy=y+42+((track-thumb)*inspectorScroll/overflow).toInt();g.fill(x+inspectorWidth-7,y+42,x+inspectorWidth-4,y+42+track,0xff2a3f4b.toInt());g.fill(x+inspectorWidth-7,sy,x+inspectorWidth-4,sy+thumb,0xffb89c66.toInt()) }
            if(sidePreview) {
                val px=x+inspectorWidth+8;val py=y+43;val pw=width-inspectorWidth-20;val ph=height-55
                g.fill(px,py,px+pw,py+ph,0xff172832.toInt());g.renderOutline(px,py,pw,ph,0xff354b59.toInt());text(g,ui("preview3d"),px+10,py+10,0xffffce7d.toInt())
                g.enableScissor(px+1,py+25,px+pw-1,py+ph-1)
                val ready=result();val resultNode=graph.nodes.first { it.kind==BuildingKind.BUILDING }
                ready.getOrNull()?.let { b -> BuildingScene.draw(g,b.plan,px+8,py+28,pw-16,(ph-66).coerceAtLeast(1),view.yaw,resultNode.preview);text(g,font.plainSubstrByWidth(ui("bg_plan_cost",b.plan.placements.size,b.plan.raw),pw-20),px+10,py+ph-29,0xffffce7d.toInt());text(g,font.plainSubstrByWidth(ui("node_preview_hint"),pw-20),px+10,py+ph-15,0xffafc6ce.toInt()) }
                    ?: font.split(Component.literal(ready.exceptionOrNull()?.message.orEmpty()),(pw-20).coerceAtLeast(1)).take(((ph-35)/11).coerceAtLeast(0)).forEachIndexed { i,line -> g.drawString(font,line,px+10,py+29+i*11,0xffffb295.toInt(),false) }
                g.flush();g.disableScissor()
            }
        }
        g.enableScissor(x,y,x+width,y+height);drawMenu(g,mx,my);g.disableScissor()
    }
    private fun options(m:Menu):List<Pair<String,String>> {
        m.choices?.let { return it }
        val n=graph.nodes.firstOrNull { it.id==m.node }
        return when(m.type) {
            "insert" -> BuildingKind.entries.filter { it!=BuildingKind.BUILDING }.map { it.name to kindName(it) }
            "nodes" -> graph.nodes.map { it.id to (name(it)+" · "+kindName(it.kind)) }
            "input" -> {
                val port=n?.let { ports(it).firstOrNull { p -> p.key==m.socket } }
                listOf("" to ui("bg_no_connection"))+graph.nodes.filter { candidate -> candidate.id!=m.node && candidate.kind in port?.accepts.orEmpty() && runCatching { graph.connect(m.node,m.socket,candidate.id) }.isSuccess }.map { it.id to name(it) }
            }
            "source" -> listOf("_solid" to ui("bg_solid_wall"),"_air" to ui("empty_wall"))+library().modules.map { it.key to it.value.name }
            "form" -> library().forms.map { it.key to it.value.name }
            "finish" -> listOf("" to ui("none"))+BuildingGraphCompiler.CARPETS.map { it to Economy.item(it).description.string }
            "roof" -> listOf("flat","gable").map { it to ui(it) }
            "ridge" -> listOf("0" to ui("bg_ridge","Z"),"1" to ui("bg_ridge","X"))
            "occupied" -> listOf("false" to ui("bg_boundary_free"),"true" to ui("bg_boundary_occupied"))
            "storey_mode" -> listOf("walls","rooms").map { it to ui("bg_storey_"+it) }
            "mode" -> listOf("GROUPED" to ui("grouped"),"SEPARATED" to ui("separated"))
            "rectangle" -> n!!.rectangles.indices.map { it.toString() to ui("bg_rect_index",it+1) }
            "room" -> n!!.inputs.indices.map { it.toString() to ui("bg_room_index",it+1) }
            else -> listOf("all","walls","floor","ceiling","roof").map { it to ui("bg_filter_"+it) }
        }.also { m.choices=it }
    }
    private fun current(m:Menu):String {
        val n=graph.nodes.firstOrNull { it.id==m.node } ?: return ""
        return when(m.type) {
            "nodes" -> view.selected
            "input" -> ports(n).firstOrNull { it.key==m.socket }?.source.orEmpty()
            "source" -> if(n.block=="minecraft:air") "_air" else n.module.ifEmpty { "_solid" }
            "storey_mode" -> n.storeyMode;"form" -> n.form;"finish" -> n.finish;"role" -> n.role;"roof" -> n.roofType;"ridge" -> n.ridge.toString();"occupied" -> n.occupied.toString();"mode" -> n.materialMode.name
            "rectangle" -> (view.rectangle[n.id] ?: 0).toString();"room" -> (view.room[n.id] ?: 0).toString()
            "number:length" -> n.length.toString();"number:height" -> n.height.toString();"number:thickness" -> n.thickness.toString();"number:overhang" -> n.overhang.toString()
            "number:width","number:depth" -> n.rectangles[(view.rectangle[n.id] ?: 0).coerceIn(0,n.rectangles.lastIndex)].let { if(m.type.endsWith("width")) it.width else it.length }.toString()
            "number:x","number:z" -> (n.offsets.getOrNull(view.room[n.id] ?: 0) ?: P()).let { if(m.type.endsWith("x")) it.x else it.z }.toString()
            else -> n.preview
        }
    }
    private fun openMenu(m:Menu) {
        if(readOnly && m.type!="nodes") return
        if(m.type.startsWith("number:")) {
            val n=graph.nodes.first { it.id==m.node };val number=current(m).toInt()
            val range=when(m.type) {
                "number:length"->1..128;"number:height"->1..16;"number:thickness"->(if(n.finish.isNotEmpty()) 2 else 1)..4
                "number:overhang"->0..2;"number:width","number:depth"->1..32;else->-64..64
            }
            val title=when(m.type) {
                "number:length"->ui("bg_fixed_length",number);"number:height"->ui(if(n.kind==BuildingKind.ROOM) "room_height" else "height",number)
                "number:thickness"->ui("bg_thickness",number);"number:overhang"->ui("overhang",number);"number:width"->ui("width",number);"number:depth"->ui("bg_depth",number)
                else->"${m.type.last().uppercase()} $number"
            }
            EditorValueInput.integer(Component.literal(title),number,range) { value -> if(value!=number) select(m,value.toString()) }
            return
        }
        if(m.type=="role") {
            val n=graph.nodes.first { it.id==m.node }
            EditorValueInput.text(tr("role_identifier"),n.role,maxLength=32,validate={ it.matches(Regex("[a-zA-Z0-9_-]{1,32}")) }) { role ->
                if(role!=n.role) { onCreateRole(role);select(m,role) }
            }
            return
        }
        menu=m;val entries=options(m);val rows=menuBox(m)[3];val index=entries.indexOfFirst { it.first==current(m) }.coerceAtLeast(0);m.focus=index;m.first=(index-rows/2).coerceIn(0,(entries.size-rows).coerceAtLeast(0))
    }
    private fun menuBox(m:Menu):IntArray {
        val n=graph.nodes.firstOrNull { it.id==m.node }?.let { shown(it) }
        val w=(if(m.width>0) m.width else if(m.type=="insert") 210 else if(m.type=="nodes") width-20 else n?.let { nodeWidth(it)-16 } ?: 210).coerceIn(1,(width-8).coerceAtLeast(1))
        val rows=minOf(options(m).size,((height-8)/19).coerceAtLeast(1))
        val ax=m.at?.first?.roundToInt() ?: (x+panX+((n?.x ?: 0)+m.column)*scale).roundToInt()
        val ay=m.at?.second?.roundToInt() ?: (y+panY+((n?.y ?: 0)+m.anchor+18)*scale).roundToInt()
        val top=y+4;val bottom=y+height-4
        val yy=if(ay+rows*19<=bottom) ay else (ay-rows*19-(if(m.at==null) (18*scale).toInt() else 0)).coerceAtLeast(top)
        return intArrayOf(ax.coerceIn(x+4,maxOf(x+4,x+width-w-4)),yy.coerceIn(top,maxOf(top,bottom-rows*19)),w,rows)
    }
    private fun drawMenu(g:GuiGraphics,mx:Int,my:Int) {
        val m=menu ?: return;val options=options(m);if(options.isEmpty()) return
        val (a,b,w,rows)=menuBox(m);m.first=m.first.coerceIn(0,(options.size-rows).coerceAtLeast(0));val chosen=current(m)
        g.flush();g.pose().pushPose();g.pose().translate(0f,0f,500f);g.fill(a-1,b-1,a+w+1,b+rows*19+1,0xffdfc58e.toInt())
        repeat(rows) { i -> val entry=options[i+m.first];val hover=mx>=a && mx<a+w && my>=b+i*19 && my<b+(i+1)*19;g.fill(a,b+i*19,a+w,b+(i+1)*19,if(hover || i+m.first==m.focus) 0xff48616e.toInt() else if(entry.first==chosen && m.type!="insert") 0xff465b61.toInt() else 0xff263b48.toInt());text(g,font.plainSubstrByWidth(entry.second,w-12),a+5,b+i*19+5,if(entry.first==chosen && m.type!="insert") 0xffffce7d.toInt() else 0xffe5edf1.toInt()) }
        if(options.size>rows) { val track=rows*19;val thumb=maxOf(10,track*rows/options.size);val sy=b+(track-thumb)*m.first/(options.size-rows);g.fill(a+w-3,sy,a+w-1,sy+thumb,0xffdfc58e.toInt()) }
        g.flush();g.pose().popPose()
    }
    private fun select(m:Menu,value:String) {
        if(m.type=="insert") { val at=m.insertion ?: return;addAt(BuildingKind.valueOf(value),at.first,at.second);return }
        if(m.type=="nodes") { view.selected=value;view.wire=null;inspectorScroll=0.0;return }
        if(m.type=="input") { connect(value,m.node,m.socket);return }
        if(m.type=="rectangle") { view.rectangle[m.node]=value.toInt();return }
        if(m.type=="room") { view.room[m.node]=value.toInt();return }
        val n=graph.nodes.first { it.id==m.node }
        edit(when(m.type) {
            "source" -> if(n.id=="empty_wall") n else when(value) { "_air"->n.copy(block="minecraft:air",module="");"_solid"->n.copy(block="",module="");else->n.copy(block="",module=value,length=library().modules.getValue(value).width,height=library().modules.getValue(value).height) }
            "storey_mode"->n.copy(storeyMode=value,inputs=if(value=="rooms") n.inputs.ifEmpty { listOf("") } else n.inputs)
            "form"->n.copy(form=value);"finish"->n.copy(finish=value,thickness=if(value.isNotEmpty()) maxOf(n.thickness,2) else n.thickness)
            "role"->n.copy(role=value);"roof"->n.copy(roofType=value);"ridge"->n.copy(ridge=value.toInt());"occupied"->n.copy(occupied=value.toBoolean());"mode"->n.copy(materialMode=MaterialMode.valueOf(value))
            "number:length"->n.copy(length=value.toInt());"number:height"->n.copy(height=value.toInt());"number:thickness"->n.copy(thickness=value.toInt());"number:overhang"->n.copy(overhang=value.toInt())
            "number:width","number:depth" -> { val index=(view.rectangle[n.id] ?: 0).coerceIn(0,n.rectangles.lastIndex);RoomGeometry.reshape(n,n.rectangles.mapIndexed { i,r -> if(i!=index) r else if(m.type.endsWith("width")) r.copy(width=value.toInt()) else r.copy(length=value.toInt()) }) }
            "number:x","number:z" -> { val index=(view.room[n.id] ?: 0).coerceIn(0,n.inputs.lastIndex);val old=n.offsets.getOrNull(index) ?: P();n.copy(offsets=List(n.inputs.size) { i -> if(i!=index) n.offsets.getOrNull(i) ?: P() else if(m.type.endsWith("x")) old.copy(x=value.toInt()) else old.copy(z=value.toInt()) }) }
            else->n.copy(preview=value)
        })
    }
    override fun mouseClicked(mx:Double,my:Double,button:Int):Boolean {
        menu?.let { m -> val (a,b,w,rows)=menuBox(m);menu=null
            if(button==0 && mx>=a && mx<a+w && my>=b && my<b+rows*19) safe { select(m,options(m)[m.first+((my-b)/19).toInt()].first) }
            view.wire=null
            return true
        }
        if(!isMouseOver(mx,my)) return false
        val lx=nx(mx);val ly=ny(my)
        if(inspector && my<y+35) { if(button==0) openMenu(Menu(selected.id,"nodes",0,width=width-20,at=(x+10.0 to y+29.0)));return true }
        if(sidePreview && mx>=x+inspectorWidth+8 && button==1) { orbit=true;return true }
        if(button==2) { if(!inspector) panning=true;return true }
        for(raw in if(inspector) listOf(selected) else graph.nodes.asReversed()) {
            val n=shown(raw);val ports=ports(n)
            for((i,p) in ports.withIndex()) { val s=point(n,i)
                if(!inspector && abs(lx-s.first)<=8 && abs(ly-s.second)<=8) {
                    if(button==1) { view.wire=null;connect("",n.id,p.key) }
                    else if(button==0) view.wire?.let { source -> if(connect(source,n.id,p.key)) view.wire=null } ?: run { view.wire=p.source.ifEmpty { null } }
                    view.selected=n.id;return true
                }
                if(button==0 && lx>=n.x+9 && lx<n.x+nodeWidth(n)-9 && ly>=s.second-8 && ly<s.second+9) { view.selected=n.id;openMenu(Menu(n.id,"input",(s.second-n.y-8).toInt(),socket=p.key));return true }
            }
            if(!inspector && n.kind!=BuildingKind.BUILDING) { val s=output(n);if(abs(lx-s.first)<=8 && abs(ly-s.second)<=8 && button==0) { view.wire=n.id;view.selected=n.id;return true } }
            val px=lx-n.x;val py=ly-n.y;val by=body(n);val w=nodeWidth(n)
            if(px !in 0.0..w.toDouble() || py !in 0.0..nodeHeight(n).toDouble()) continue
            view.selected=n.id
            if(button==0 && py<=23) { if(!inspector && !readOnly) { headerDrag=n.id;moving=n };return true }
            if(n.kind==BuildingKind.BUILDING && button==1) { orbit=true;return true }
            if(readOnly) return true
            if(button!=0 && button!=1) return true
            safe {
                fun inside(row:Int)=py>=by+row && py<by+row+17
                fun open(type:String,row:Int,column:Int=8,menuWidth:Int=0) { openMenu(Menu(n.id,type,by+row,column=column,width=menuWidth)) }
                fun material(row:Int):Boolean {
                    if(inside(row)) { open("mode",row);return true }
                    if(inside(row+21)) { open("role",row+21);return true };return false
                }
                if(n.kind==BuildingKind.STOREY && inside(-22)) { open("storey_mode",-22);return@safe }
                when(layoutKind(n)) {
                    BuildingKind.COPYCAT -> when { inside(0)->open("form",0);inside(22)->open("role",22) }
                    BuildingKind.SEGMENT -> when {
                        inside(0) && n.id!="empty_wall"->open("source",0)
                        inside(22) && (n.inputs.any { it.isNotEmpty() } || n.module.isEmpty() || n.block.isNotEmpty())->if(px<105) open("number:length",22,8,190) else open("number:height",22,108,190)
                        inside(44)->open("role",44)
                    }
                    BuildingKind.PATTERN -> when {
                        inside(17)-> { val inputs=if(px<105 && n.inputs.size<16) n.inputs+"" else if(px>=105 && n.inputs.size>2) n.inputs.dropLast(1) else n.inputs;edit(n.copy(inputs=inputs)) }
                        else->material(40)
                    }
                    BuildingKind.FLOOR,BuildingKind.CEILING -> when {
                        inside(0)->open("form",0);inside(22)->open("number:thickness",22)
                        inside(44)->open("occupied",44);inside(66) && n.kind==BuildingKind.FLOOR->open("finish",66);inside(88)->open("role",88)
                    }
                    BuildingKind.ROOM -> {
                        val index=(view.rectangle[n.id] ?: 0).coerceIn(0,n.rectangles.lastIndex);val rect=n.rectangles[index]
                        when {
                            inside(0) && px<125->open("number:height",0,8,200)
                            inside(22)->when { px<110->open("rectangle",22,8,190);px<150 && n.rectangles.size<16->{ view.rectangle[n.id]=n.rectangles.size;edit(RoomGeometry.reshape(n,n.rectangles+Rect(rect.x+rect.width,rect.z,3,3))) };px>=150 && n.rectangles.size>1->{view.rectangle[n.id]=0;edit(RoomGeometry.reshape(n,n.rectangles.filterIndexed { i,_ -> i!=index }))} }
                            inside(44)->if(px<110) open("number:width",44,8,190) else open("number:depth",44,113,190)
                            py>=by+67 && py<by+193 -> {
                                val p=projections[n.id] ?: return@safe
                                val hit=n.rectangles.indices.reversed().firstOrNull { i -> val r=n.rectangles[i];lx>=p.px(r.x) && lx<=p.px(r.x+r.width)+5 && ly>=p.py(r.z) && ly<=p.py(r.z+r.length)+5 }
                                if(hit!=null && button==0) { view.rectangle[n.id]=hit;val r=n.rectangles[hit];mapDrag=MapDrag(n,hit,abs(lx-p.px(r.x+r.width))<8 && abs(ly-p.py(r.z+r.length))<8,lx,ly,p);moving=n }
                            }
                            else->material(216)
                        }
                    }
                    BuildingKind.STOREY -> {
                        val index=(view.room[n.id] ?: 0).coerceIn(0,n.inputs.lastIndex.coerceAtLeast(0))
                        when {
                            inside(0)->when { px<110->open("room",0,8,190);px<150 && n.inputs.size<16->edit(n.copy(inputs=n.inputs+"",offsets=n.offsets+P()));px>=150 && n.inputs.size>1->{view.room[n.id]=0;edit(n.copy(inputs=n.inputs.filterIndexed { i,_ -> i!=index },offsets=List(n.inputs.size) { n.offsets.getOrNull(it) ?: P() }.filterIndexed { i,_ -> i!=index }))} }
                            inside(22)->if(px<110) open("number:x",22,8,190) else open("number:z",22,114,190)
                            py>=by+45 && py<by+188 -> {
                                val projection=projections[n.id] ?: return@safe
                                val cell=P(floor((lx-projection.x)/projection.step).toInt()+projection.minX,0,floor((ly-projection.y)/projection.step).toInt()+projection.minZ)
                                val hit=n.inputs.indices.reversed().firstOrNull { i -> val room=graph.nodes.firstOrNull { it.id==n.inputs[i] && it.kind==BuildingKind.ROOM };room!=null && cell+P(-(n.offsets.getOrNull(i)?.x ?: 0),0,-(n.offsets.getOrNull(i)?.z ?: 0)) in RoomGeometry.cells(room.rectangles) }
                                if(hit!=null && button==0) { view.room[n.id]=hit;mapDrag=MapDrag(n,hit,false,lx,ly,projection);moving=n }
                            }
                            else->material(193)
                        }
                    }
                    BuildingKind.ROOF -> when { inside(0)->open("roof",0);inside(22)->open("ridge",22);inside(44)->if(px<105) open("number:overhang",44,8,190) else open("number:thickness",44,108,190);inside(66)->open("role",66) }
                    BuildingKind.BUILDING -> if(inside(0)) open("preview",0) else material(235)
                }
            };return true
        }
        if(button==1 && !inspector && !readOnly) { rightStart=mx to my;rightGraph=lx to ly;panning=false } else view.wire=null
        return true
    }
    override fun mouseDragged(mx:Double,my:Double,button:Int,dx:Double,dy:Double):Boolean {
        if(menu!=null) return true
        if(headerDrag!=null) { moving=moving!!.copy(x=(moving!!.x+dx/scale).roundToInt().coerceIn(-8192,8192),y=(moving!!.y+dy/scale).roundToInt().coerceIn(-8192,8192));return true }
        mapDrag?.let { drag ->
            val du=((nx(mx)-drag.startX)/drag.projection.step).roundToInt();val dv=((ny(my)-drag.startY)/drag.projection.step).roundToInt();val n=drag.original
            val next=if(layoutKind(n)==BuildingKind.ROOM) {
                val r=n.rectangles[drag.index];val rect=if(drag.resize) r.copy(width=(r.width+du).coerceIn(1,32),length=(r.length+dv).coerceIn(1,32)) else r.copy(x=(r.x+du).coerceIn(-32,32),z=(r.z+dv).coerceIn(-32,32))
                RoomGeometry.reshape(n,n.rectangles.mapIndexed { i,r -> if(i==drag.index) rect else r })
            } else { val old=n.offsets.getOrNull(drag.index) ?: P();n.copy(offsets=List(n.inputs.size) { i -> if(i==drag.index) P((old.x+du).coerceIn(-64,64),0,(old.z+dv).coerceIn(-64,64)) else n.offsets.getOrNull(i) ?: P() }) }
            moving=next;return true
        }
        rightStart?.let { start -> if(!panning && hypot(mx-start.first,my-start.second)>=4.0) { panning=true;view.panX+=mx-start.first;view.panY+=my-start.second;return true } }
        if(panning) { view.panX+=dx;view.panY+=dy;return true };if(orbit) { view.yaw+=dx.toFloat();return true };return view.wire!=null || rightStart!=null
    }
    override fun mouseReleased(mx:Double,my:Double,button:Int):Boolean {
        val changed=moving;val context=button==1 && rightStart?.let { hypot(mx-it.first,my-it.second)<4.0 }==true && !panning && isMouseOver(mx,my);val insertion=rightGraph;headerDrag=null;mapDrag=null;moving=null;panning=false;orbit=false;rightStart=null;rightGraph=null
        if(context && insertion!=null) { view.wire=null;openMenu(Menu("","insert",0,at=mx to my,insertion=insertion));return true }
        if(changed!=null) { safe { edit(changed) };return true }
        if(menu!=null) return true
        if(!inspector && button==0 && view.wire!=null) {
            val lx=nx(mx);val ly=ny(my)
            for(n in graph.nodes.asReversed()) for((i,p) in ports(n).withIndex()) { val s=point(n,i);if(abs(lx-s.first)<=8 && abs(ly-s.second)<=8) { val source=view.wire!!;if(connect(source,n.id,p.key)) view.wire=null;return true } }
        };return true
    }
    override fun mouseScrolled(mx:Double,my:Double,dx:Double,dy:Double):Boolean {
        menu?.let { m ->
            val (a,b,w,rows)=menuBox(m)
            if(mx>=a && mx<a+w && my>=b && my<b+rows*19) m.first=(m.first-dy.sign.toInt()).coerceIn(0,(options(m).size-rows).coerceAtLeast(0)) else menu=null
            return true
        }
        if(!isMouseOver(mx,my)) return false
        if(inspector) { inspectorScroll=(inspectorScroll-dy*25).coerceIn(0.0,maxOf(0.0,nodeHeight(shown(selected))*scale-height+54));return true }
        val a=nx(mx);val b=ny(my);view.zoom=(view.zoom*1.12.pow(dy)).coerceIn(0.2,1.75);view.panX=mx-x-a*view.zoom;view.panY=my-y-b*view.zoom;return true
    }
    override fun keyPressed(key:Int,scan:Int,mods:Int):Boolean {
        if(key==256 && (menu!=null || view.wire!=null || rightStart!=null)) { menu=null;view.wire=null;rightStart=null;rightGraph=null;return true }
        menu?.let { m ->
            val entries=options(m);val rows=menuBox(m)[3]
            if(entries.isEmpty()) return true
            if(key==257 || key==335) { menu=null;safe { select(m,entries[m.focus].first) };view.wire=null;return true }
            val next=when(key) { 264->m.focus+1;265->m.focus-1;268->0;269->entries.lastIndex;266->m.focus-rows;267->m.focus+rows;else->return true }
            m.focus=next.coerceIn(0,entries.lastIndex);m.first=m.first.coerceIn((m.focus-rows+1).coerceAtLeast(0),m.focus).coerceAtMost((entries.size-rows).coerceAtLeast(0));return true
        }
        if(key==261 || key==259) { deleteSelected();return true };return false
    }
    override fun updateWidgetNarration(output:NarrationElementOutput) { output.add(NarratedElementType.TITLE,tr("building_graph"));output.add(NarratedElementType.USAGE,tr("bg_help")) }
}
