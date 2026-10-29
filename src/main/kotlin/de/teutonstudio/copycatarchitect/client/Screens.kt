package de.teutonstudio.copycatarchitect.client

import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.*
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.*
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import com.mojang.math.Axis
import java.util.UUID

fun ui(key:String,vararg args:Any)=net.minecraft.client.resources.language.I18n.get("gui.copycat_architect.$key",*args)
fun tr(key:String,vararg args:Any)=Component.translatable("gui.copycat_architect.$key",*args)
abstract class EditorScreen(title:Component):Screen(title) {
    var yaw=35f
    private var dropdown:EditorDropdown?=null
    protected fun resetDropdown() { dropdown=null }
    protected fun button(x:Int,y:Int,w:Int,label:Component,action:()->Unit):Button = addRenderableWidget(EditorButton(x,y,w,label,action).apply { setTooltip(Tooltip.create(label)) })
    protected fun button(x:Int,y:Int,w:Int,key:String,action:()->Unit)=button(x,y,w,tr(key),action)
    protected fun label(g:GuiGraphics,s:String,x:Int,y:Int,color:Int=0xffdddddd.toInt())=g.drawString(font,s,x,y,color,false)
    protected fun field(x:Int,y:Int,w:Int,value:String):EditBox = addRenderableWidget(EditBox(font,x,y,w,18,Component.empty()).apply { setMaxLength(64);this.value=value })
    protected fun cycle(x:Int,y:Int,w:Int,text:String,action:()->Unit)=button(x,y,w,Component.literal(text),action)
    protected fun choice(x:Int,y:Int,w:Int,text:String,options:List<Pair<String,()->Unit>>,selected:Int=-1):Button {
        lateinit var anchor:Button
        anchor=button(x,y,w,Component.literal(text)) { dropdown=EditorDropdown(anchor,options,selected) }
        (anchor as EditorButton).dropdown=true;anchor.active=options.isNotEmpty()
        anchor.setTooltip(Tooltip.create(Component.literal(text)))
        return anchor
    }
    protected fun <T> select(x:Int,y:Int,w:Int,value:T,values:List<T>,display:(T)->String,action:(T)->Unit)=
        choice(x,y,w,display(value),values.map { v -> display(v) to { if(v!=value) action(v) } },values.indexOf(value))
    protected fun number(x:Int,y:Int,w:Int,value:Int,range:IntRange,display:(Int)->String,action:(Int)->Unit)=
        button(x,y,w,Component.literal(display(value))) { EditorValueInput.integer(Component.literal(display(value)),value,range,action) }
    protected fun dropdownClick(x:Double,y:Double,button:Int):Boolean {
        val menu=dropdown ?: return false;dropdown=null;menu.click(x,y,button,width,height)?.invoke();return true
    }
    protected fun dropdownKey(key:Int):Boolean {
        val menu=dropdown ?: return false
        if(key==256) dropdown=null else { val action=menu.key(key,width,height);if(action!=null) { dropdown=null;action() } };return true
    }
    protected fun error(e:Exception) { ClientState.snapshot=ClientState.snapshot.copy(message=e.message ?: "Invalid design") }
    protected fun safe(action:()->Unit) { try { action() } catch(e:Exception) { error(e) } }
    protected open fun revealWorld(mx:Int,my:Int)=false
    protected open fun titleAvailableWidth()=(width-270).coerceAtLeast(60)
    override fun render(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
        WorldPeek.active=revealWorld(mx,my)
        g.fill(0,0,width,height,if(WorldPeek.active) 0x3015212a else 0xff15212a.toInt());g.fill(0,0,width,24,0xff233b48.toInt())
        g.drawString(font,font.plainSubstrByWidth(title.string,titleAvailableWidth()),8,8,0xffffc66d.toInt(),false)
        g.drawString(font,ClientState.snapshot.message.take((width/6)-3),8,height-12,0xffffd38a.toInt(),false)
        super.render(g,mx,my,delta)
        renderContents(g,mx,my,delta)
        dropdown?.render(g,mx,my,width,height)
    }
    protected open fun renderContents(g:GuiGraphics,mx:Int,my:Int,delta:Float) {}
    override fun mouseScrolled(x:Double,y:Double,dx:Double,dy:Double):Boolean {
        dropdown?.let { it.scroll(dy,width,height);return true };return super.mouseScrolled(x,y,dx,dy)
    }
    override fun renderBackground(g:GuiGraphics,mx:Int,my:Int,delta:Float) {}
    override fun isPauseScreen()=false
    override fun mouseDragged(x:Double,y:Double,button:Int,dx:Double,dy:Double):Boolean {
        if(button==1) { yaw+=dx.toFloat();return true };return super.mouseDragged(x,y,button,dx,dy)
    }
}
class ShapeScreen(private val parent:BuildingScreen?=null,private val printerSession:PrinterView?=null):EditorScreen(tr(if(printerSession!=null) "printer_editor" else "shape_editor")) {
    override fun titleAvailableWidth()=if(printerSession!=null) (width-337).coerceAtLeast(24) else super.titleAvailableWidth()
    var form=Form(id=UUID.randomUUID().toString(),name="Inner corner",parts=listOf(Part(),Part(turn=1)))
    private var eyeButton:PrinterEyeButton?=null
    private var previewSent=""
    private var nextPreview=0L
    val printerMaterials=mutableMapOf<String,String>()
    private var placementTurn=0;private var selected=0;private var view=0;var material=0
    var groupMode=false;var groupIndex=0
    var graphMode=false
    var graphInspector=true
    val graphView=GraphView()
    var graphCanvas:NodeGraphCanvas?=null;private set
    val kilaGraphState=KilaGraphState()
    var kilaGraph:KilaGraphEditor?=null;private set
    private val shapeInspectorVisible get()=graphInspector || width>=700
    private var previewKey:Form?=null;private var previewCache:Result<Form>?=null
    private fun sourceParts(f:Form)=f.graph?.nodes?.filter { it.kind==NodeKind.INPUT }?.map { it.part } ?: f.parts
    private var shapeProjectionKey:Form?=null
    private var shapeProjection:NodeGraph?=null
    fun shapeDisplayGraph():NodeGraph {
        form.graph?.let { return it }
        if(shapeProjectionKey!=form) { shapeProjectionKey=form;shapeProjection=GraphPresentation.shape(form) }
        return shapeProjection!!
    }
    fun displayShape():Form=if(form.graph!=null) form else form.copy(graph=shapeDisplayGraph())
    fun selectShapeNode(id:String) { graphView.selected=id;graphView.inspectorScroll=0 }
    private fun shapeDiagram():KilaGraphData {
        val graph=shapeDisplayGraph()
        return KilaGraphData(graph.nodes.mapIndexed { index,n ->
            KilaNode(n.id,"${index+1} · ${ui("node_"+n.kind.name.lowercase())}",n.x,n.y,n.inputs.indices.map { i ->
                if(n.kind==NodeKind.ROTATE) ui(if(i==0) "port_copycat" else "port_rotation") else if(n.kind==NodeKind.DIFFERENCE) if(i==0) "A" else "B" else "${ui("input")} ${i+1}"
            },n.kind!=NodeKind.OUTPUT,n.kind!=NodeKind.OUTPUT,inputTypes=n.inputs.indices.map { if(n.kind==NodeKind.ROTATE && it==1) "rotation" else "shape" },outputType=if(n.kind==NodeKind.ROTATION) "rotation" else "shape",
                source=if(n.kind==NodeKind.INPUT) n.part.source else "",discCut=n.part.discCut,part=if(n.kind==NodeKind.INPUT) n.part else null,rotatable=n.kind==NodeKind.ROTATION || n.kind==NodeKind.ROTATE && n.inputs.getOrNull(1).isNullOrEmpty(),rotationAxis=n.rotationAxis)
        },graph.nodes.flatMap { n -> n.inputs.mapIndexedNotNull { i,id -> id.takeIf { it.isNotEmpty() }?.let { KilaLink(it,n.id,i) } } })
    }
    private fun commitShapeDiagram(data:KilaGraphData) {
        commitShapeGraph(DiagramEdits.shape(shapeDisplayGraph(),form.groups,data.nodes.map { DiagramPosition(it.id,it.x,it.y) },data.links.map { DiagramConnection(it.source,it.target,it.slot.toString()) }))
    }
    private fun shapeInsertions():List<KilaInsertion> = BaseShapes.all.map { shape ->
        val category=when(shape.name) {
            "full","slab","stairs","beam","byte"->"copycat_group_basic"
            "half_layer","vertical_half_layer","stacked_half_layer"->"copycat_group_half_layers"
            "flat_pane","half_disc","quarter_disc"->"copycat_group_discs"
            else->"copycat_group_trim"
        }
        KilaInsertion(ui(shape.name),ui(category),shape.source) { x,y -> graphCanvas?.addAt(NodeKind.INPUT,shape.source,x,y,shape.discCut) }
    }+listOf(NodeKind.UNION,NodeKind.INTERSECTION,NodeKind.DIFFERENCE,NodeKind.SYMMETRIC_DIFFERENCE,NodeKind.ROTATE,NodeKind.ROTATION).map { kind ->
        KilaInsertion(ui("node_"+kind.name.lowercase()),ui("copycat_group_operations")) { x,y -> graphCanvas?.addAt(kind,Adapters.all.keys.first(),x,y) }
    }
    private fun addShapeNode(kind:NodeKind,source:String=Adapters.all.keys.first(),discCut:Int=0) {
        val point=kilaGraph?.insertionPoint()
        if(point==null) graphCanvas?.add(kind,source,discCut) else graphCanvas?.addAt(kind,source,point.first,point.second,discCut)
    }
    fun commitShapeGraph(graph:NodeGraph) {
        if(graph==shapeDisplayGraph()) return
        if(form.graph==null && form.parts.any { it.cutMask.isNotEmpty() || it.recipeSource.isNotEmpty() }) {
            shapeProjection=graph;return
        }
        safe { edit { it.copy(graph=graph) } }
    }
    init { printerSession?.let { form=it.form;placementTurn=it.turn;printerMaterials.putAll(it.surfaces) } }
    private var groupTextEdit=""
    private fun editGroupText(id:String,property:String,value:String) {
        val group=form.groups.first { it.id==id }
        if(property=="name" && (value.isBlank() || value==group.name)) return
        if(property=="role" && (!value.matches(Regex("[a-zA-Z0-9_-]{1,32}")) || value==group.role)) return
        val key="$id:$property"
        if(groupTextEdit!=key) { undo.addLast(editState());redo.clear();groupTextEdit=key }
        form=form.copy(groups=form.groups.map { if(it.id!=id) it else if(property=="name") it.copy(name=value) else it.copy(role=value) })
    }
    private fun createGroup(groupName:String,nodeId:String="",materialGroup:Boolean=false,slot:Int=-1) {
        require(groupName.isNotBlank() && groupName.length<=32)
        require(form.groups.size<8) { ui("group_limit") }
        edit { f ->
            val priorities=f.groups.map { it.priority }.toSet()
            val priority=((f.groups.maxOf { it.priority }+1)..32).firstOrNull { it !in priorities }
                ?: (-32..32).last { it !in priorities }
            val group=MaterialGroup(UUID.randomUUID().toString(),groupName,"surface",priority)
            val graph=if(nodeId.isEmpty()) f.graph else shapeDisplayGraph().let { displayed -> displayed.replace(displayed.nodes.first { it.id==nodeId }.let { n ->
                if(slot>=0) n.copy(inputGroups=List(n.inputs.size) { i -> if(i==slot) group.id else n.inputGroups.getOrNull(i).orEmpty() }) else if(materialGroup) n.copy(materialMode=MaterialMode.GROUPED,materialGroup=group.id) else n.copy(part=n.part.copy(group=group.id))
            }) }
            groupIndex=f.groups.size
            f.copy(groups=f.groups+group,graph=graph)
        }
    }
    private fun preview():Result<Form> { if(previewKey!=form) { previewKey=form;previewCache=runCatching { Geometry.validate(form) } };return previewCache!! }
    private fun changeParts(f:Form,parts:List<Part>):Form {
        val graph=f.graph ?: return f.copy(parts=parts)
        val leaves=graph.nodes.filter { it.kind==NodeKind.INPUT }
        var updated=graph
        if(parts.size==leaves.size) updated=graph.copy(nodes=graph.nodes.map { n -> val i=leaves.indexOf(n);if(i<0) n else n.copy(part=parts[i]) })
        else if(parts.size<leaves.size) updated=graph.remove(leaves[selected].id)
        else {
            val node=GraphNode(UUID.randomUUID().toString(),part=parts.last(),x=20,y=leaves.size*150)
            val output=graph.nodes.single { it.kind==NodeKind.OUTPUT };val old=output.inputs[0]
            val join=GraphNode(UUID.randomUUID().toString(),NodeKind.UNION,listOf(old,node.id),x=280,y=node.y)
            updated=graph.copy(nodes=graph.nodes+node+(if(old.isEmpty()) emptyList() else listOf(join)))
                .replace(output.copy(inputs=listOf(if(old.isEmpty()) node.id else join.id)))
        }
        GraphCompiler.structure(updated,f.groups)
        return f.copy(parts=parts,graph=updated)
    }
    fun openGraph(inspector:Boolean=false) {
        graphMode=true;graphInspector=inspector;groupMode=false;graphView.wire=null
        rebuildWidgets()
    }
    val surfaceMaterials=mutableMapOf<String,Int>()
    private val activeGroup get()=form.groups[groupIndex.coerceIn(0,form.groups.lastIndex)]
    private data class FormEditState(val form:Form,val materials:Map<String,String>,val turn:Int)
    private val undo=ArrayDeque<FormEditState>();private val redo=ArrayDeque<FormEditState>();private lateinit var name:EditBox
    private fun editState()=FormEditState(form,printerMaterials.toMap(),placementTurn)
    private fun restore(state:FormEditState) { form=state.form;printerMaterials.clear();printerMaterials.putAll(state.materials);placementTurn=state.turn }
    private fun editPrinterSettings(action:()->Unit) {
        val before=editState();action()
        if(editState()!=before) { undo.addLast(before);redo.clear();groupTextEdit="";rebuildWidgets() }
    }
    private fun assignments():Map<String,String> = if(printerSession!=null) form.groups.associate { it.id to printerMaterials[it.id].orEmpty() } else form.groups.associate { it.id to Economy.palette[surfaceMaterials[it.id] ?: material] }
    private fun printerSurfaceChoices():List<String> {
        val inventory=minecraft?.player?.inventory
        return (listOf("")+Economy.palette+printerMaterials.values+(0 until (inventory?.containerSize ?: 0)).mapNotNull { i -> inventory?.getItem(i)?.takeIf { !it.isEmpty }?.let { net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.item).toString() } })
            .distinct().filter { it.isEmpty() || runCatching { Economy.state(it) }.isSuccess }
    }
    fun loadPrinterDraft(view:PrinterView?) {
        if(printerSession==null || view==null || view.session!=printerSession.session) return
        val incoming=view.form.groups.associate { it.id to view.surfaces[it.id].orEmpty() }
        if(form.copy(parts=emptyList())==view.form.copy(parts=emptyList()) && assignments()==incoming && placementTurn==view.turn) return
        undo.addLast(editState());redo.clear();form=view.form;printerMaterials.clear();printerMaterials.putAll(view.surfaces);placementTurn=view.turn;rebuildWidgets()
    }
    fun printerCommand(action:String)=Command(action,payload=Json.encode(form.copy(parts=if(form.graph!=null) emptyList() else form.parts)),key=printerSession?.session.orEmpty(),turn=placementTurn,surfaces=assignments())
    private fun canPrint():Boolean {
        val ready=preview().getOrNull() ?: return false
        val player=minecraft?.player ?: return false
        if(player.isCreative) return true
        val raw=Extrusion.cost(ready.parts,ready.groups)
        val slots=ClientState.snapshot.printer?.slots.orEmpty()
        val available=slots.sumOf { if(it.creative) Int.MAX_VALUE.toLong() else it.units.toLong() }+(0 until player.inventory.containerSize).sumOf { i -> val stack=player.inventory.getItem(i);when { Economy.isCreativeCartridge(stack)->Int.MAX_VALUE.toLong();Economy.isCartridge(stack)->Economy.units(stack).toLong();net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString()=="create:zinc_ingot"->stack.count*256L;else->0L } }
        if(available<raw) return false
        return Placement(parts=ready.parts,material="",groups=ready.groups,surfaces=assignments()).surfaceItems().all { (id,count) -> (0 until player.inventory.containerSize).sumOf { val stack=player.inventory.getItem(it);if(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString()==id) stack.count else 0 }>=count }
    }
    override fun revealWorld(mx:Int,my:Int):Boolean {
        val peek=printerSession!=null && eyeButton?.isMouseOver(mx.toDouble(),my.toDouble())==true
        kilaGraph?.setWorldPeek(peek)
        return peek
    }
    override fun tick() {
        super.tick()
        if(printerSession!=null && System.currentTimeMillis()>=nextPreview) {
            val command=printerCommand("printerPreview");val json=Json.encode(command)
            if(json!=previewSent) { previewSent=json;ClientState.send(command) }
            nextPreview=System.currentTimeMillis()+200
        }
        if(printerSession!=null) children().filterIsInstance<net.minecraft.client.gui.components.Button>().firstOrNull { it.message.string==ui("printer_print") }?.active=canPrint()
    }
    private fun edit(action:(Form)->Form) {
        if(::name.isInitialized) form=form.copy(name=name.value)
        val updated=action(form);if(updated==form) return;undo.addLast(editState());redo.clear();form=updated;groupTextEdit=""
        selected=selected.coerceIn(0,sourceParts(form).lastIndex.coerceAtLeast(0));groupIndex=groupIndex.coerceIn(0,form.groups.lastIndex);rebuildWidgets()
    }
    private fun part(action:(Part)->Part)=edit { f -> changeParts(f,sourceParts(f).mapIndexed { i,p -> if(i==selected) action(p) else p }) }
    private fun moveGroup(direction:Int) {
        val ordered=form.groups.sortedBy { it.priority };val index=ordered.indexOfFirst { it.id==activeGroup.id };val other=ordered.getOrNull(index+direction) ?: return
        val current=activeGroup
        edit { it.copy(groups=it.groups.map { g -> when(g.id) { current.id->g.copy(priority=other.priority);other.id->g.copy(priority=current.priority);else->g } }) }
    }
    override fun init() {
        kilaGraph?.dispose();kilaGraph=null
        resetDropdown()
        groupTextEdit=""
        if(!groupMode) graphMode=!(graphInspector && form.graph==null && form.parts.any { it.cutMask.isNotEmpty() || it.recipeSource.isNotEmpty() })
        selected=selected.coerceIn(0,sourceParts(form).lastIndex.coerceAtLeast(0));groupIndex=groupIndex.coerceIn(0,form.groups.lastIndex)
        name=field(8,28,119,form.name);name.setResponder { form=form.copy(name=it) }
        button(132,28,54,"save") { safe { val checked=Geometry.validate(form.copy(name=name.value));form=checked
            ClientState.send(Command("saveForm",Json.encode(if(checked.graph==null) checked else checked.copy(parts=emptyList())),
                key=printerSession?.session.orEmpty(),surfaces=if(printerSession!=null) assignments() else emptyMap(),turn=if(printerSession!=null) placementTurn else 0));rebuildWidgets() } }
        choice(189,28,48,ui("load"),ClientState.snapshot.library.forms.entries.map { (key,saved) -> "${saved.name} · v${saved.version}" to {
            if(printerSession!=null) {
                val settings=ClientState.snapshot.library.printSettings[key] ?: PrintSettings()
                loadPrinterDraft(printerSession.copy(form=saved,surfaces=settings.surfaces,turn=settings.turn))
            } else edit { saved }
        } })
        button(240,28,48,"copy") { edit { it.copy(id=UUID.randomUUID().toString(),version=1,name=it.name+" copy") } }
        button(291,28,48,"undo") { undoEdit() }.active=undo.isNotEmpty()
        button(342,28,48,"redo") { redoEdit() }.active=redo.isNotEmpty()
        button(width-252,3,74,"control_panel") { openGraph(true) }.apply { (this as EditorButton).selected=!groupMode && graphInspector }
        button(width-176,3,54,"groups") { groupMode=true;graphMode=false;rebuildWidgets() }.apply { (this as EditorButton).selected=groupMode }
        button(width-120,3,58,"node_graph") { openGraph() }.apply { (this as EditorButton).selected=graphMode && !graphInspector }
        button(width-60,3,54,if(printerSession!=null) "cancel" else "close") { onClose() }
        if(printerSession!=null) {
            eyeButton=addRenderableWidget(PrinterEyeButton(width-285,3))
            addRenderableWidget(PrinterMemoryButton(width-321,3) { ClientState.send(printerCommand("printerStorage")) })
        }
        graphCanvas=null
        if(graphMode) {
            val types=listOf(NodeKind.UNION,NodeKind.INTERSECTION,NodeKind.DIFFERENCE,NodeKind.SYMMETRIC_DIFFERENCE,NodeKind.ROTATE,NodeKind.ROTATION)
            choice(8,51,192,ui("insert_node"),BaseShapes.all.map { shape -> ui(shape.name) to { addShapeNode(NodeKind.INPUT,shape.source,shape.discCut) } }+
                types.map { kind -> ui("node_"+kind.name.lowercase()) to { addShapeNode(kind) } })
            button(204,51,62,"fit_graph") { kilaGraph?.fit() ?: graphCanvas?.fit() }
            button(270,51,62,"remove") { if(kilaGraph!=null) kilaGraph!!.deleteSelection() else graphCanvas?.deleteSelected() }
            button(336,51,24,Component.literal("?")) {}.setTooltip(Tooltip.create(tr("graph_controls")))
            val side=if(!graphInspector && width>=700) minOf(340,width/3) else width-16
            val left=if(!graphInspector && width>=700) width-side-8 else 8
            val legacy=form.graph==null && form.parts.any { it.cutMask.isNotEmpty() || it.recipeSource.isNotEmpty() }
            graphCanvas=NodeGraphCanvas(left,75,side,height-133,{displayShape()},{assignments()},graphView,::commitShapeGraph,{ message -> ClientState.snapshot=ClientState.snapshot.copy(message=message) },true,onCreateGroup={ value,node,material,slot -> safe { createGroup(value,node,material,slot) } },preview=if(legacy) ({ Result.success(form) }) else null,readOnly=legacy)
            if(shapeInspectorVisible) addRenderableWidget(graphCanvas!!)
            if(!graphInspector) kilaGraph=addRenderableWidget(KilaGraphEditor(8,75,if(width>=700) left-12 else width-16,height-133,this,kilaGraphState,::shapeDiagram,::selectShapeNode,::commitShapeDiagram,{ message -> ClientState.snapshot=ClientState.snapshot.copy(message=message) },legacy,insertions=shapeInsertions(),selectSource={ node -> graphCanvas?.openSourceSelection(node) },rotateSource={ node,axis,turns -> graphCanvas?.rotateSource(node,axis,turns) }))
            val gw=(width/4).coerceIn(80,180);val ew=146;val sw=(width-gw-ew-32).coerceAtLeast(40)
            choice(8,height-53,gw,activeGroup.name,form.groups.mapIndexed { i,group -> group.name to { groupIndex=i;rebuildWidgets() } }+
                if(form.groups.size<8) listOf(ui("new_group") to { EditorValueInput.text(tr("new_group"),"") { value -> safe { createGroup(value) } } }) else emptyList(),groupIndex)
            if(printerSession!=null) {
                val surface=printerMaterials[activeGroup.id].orEmpty()
                select(12+gw,height-53,sw,surface,printerSurfaceChoices(),{ if(it.isEmpty()) ui("printer_uncoated") else Economy.item(it).description.string }) { value -> editPrinterSettings { printerMaterials[activeGroup.id]=value } }
                button(16+gw+sw,height-53,ew,"printer_print") { ClientState.send(printerCommand("printerPrint")) }.active=canPrint()
            } else {
                val surface=Economy.palette[surfaceMaterials[activeGroup.id] ?: material]
                select(12+gw,height-53,sw,surface,Economy.palette,{ Economy.item(it).description.string }) { surfaceMaterials[activeGroup.id]=Economy.palette.indexOf(it);rebuildWidgets() }
                button(16+gw+sw,height-53,ew,"equip") { equip() }
            }
            return
        }
        if(groupMode) groupWidgets() else partWidgets()
        // One live material assignment per group, independent of saved geometry.
        select(274,55,125,groupIndex,form.groups.indices.toList(),{ form.groups[it].name }) { groupIndex=it;rebuildWidgets() }
        select(274,78,125,view,(0..2).toList(),{ listOf(ui("union"),ui("selected_part"),ui("collision"))[it] }) { view=it;rebuildWidgets() }
        if(printerSession!=null) {
            val surface=printerMaterials[activeGroup.id].orEmpty()
            select(274,height-75,(width-282).coerceAtLeast(110),surface,printerSurfaceChoices(),{ if(it.isEmpty()) ui("printer_uncoated") else Economy.item(it).description.string }) { value -> editPrinterSettings { printerMaterials[activeGroup.id]=value } }
            button(274,height-54,(width-282).coerceAtLeast(110),"printer_print") { ClientState.send(printerCommand("printerPrint")) }.active=canPrint()
        } else {
            val surface=Economy.palette[surfaceMaterials[activeGroup.id] ?: material]
            select(274,height-75,(width-282).coerceAtLeast(110),surface,Economy.palette,{ Economy.item(it).description.string }) { surfaceMaterials[activeGroup.id]=Economy.palette.indexOf(it);rebuildWidgets() }
            button(274,height-54,(width-282).coerceAtLeast(110),"equip") { equip() }
        }
    }
    private fun partWidgets() {
        Adapters.all.values.forEachIndexed { i,a -> button(8,55+i*20,120,tr(a.name)) { if(sourceParts(form).size<32) edit { selected=sourceParts(it).size;changeParts(it,sourceParts(it)+Part(a.source,group=activeGroup.id)) } } }
        val first=(selected/4)*4
        for(i in first until minOf(first+4,sourceParts(form).size)) {
            val p=sourceParts(form)[i];button(136,55+(i-first)*20,130,Component.literal("${if(i==selected) ">" else " "}${i+1}: ").append(tr(BaseShapes.find(p.source,p.discCut).name))) { selected=i;groupIndex=form.groups.indexOfFirst { it.id==sourceParts(form)[i].group }.coerceAtLeast(0);rebuildWidgets() }
        }
        button(136,137,32,Component.literal("<")) { selected=(selected-1).mod(sourceParts(form).size.coerceAtLeast(1));rebuildWidgets() }
        button(171,137,32,Component.literal(">")) { selected=(selected+1).mod(sourceParts(form).size.coerceAtLeast(1));rebuildWidgets() }
        button(206,137,60,"remove") { if(sourceParts(form).size>1) edit { changeParts(it,sourceParts(it).filterIndexed { i,_ -> i!=selected }) } }
        button(136,158,62,"duplicate") { if(sourceParts(form).isNotEmpty() && sourceParts(form).size<32) edit { val duplicate=sourceParts(it)[selected];val next=changeParts(it,sourceParts(it)+duplicate);selected=sourceParts(it).size;next } }
        button(201,158,65,"rotate") { part { it.copy(turn=(it.turn+1)%4) } }
        val p=sourceParts(form).getOrNull(selected) ?: return;val a=Adapters.get(p.source)
        cycle(136,179,62,(if(p.top) ui("top") else ui("bottom"))+if(p.tilt>0) " ${p.tilt*90}°" else "") { part { it.copy(top=!it.top,tilt=if(it.top && Adapters.get(it.source).tiltable) (it.tilt+1)%4 else it.tilt) } }
        cycle(201,179,65,ui("size",p.size)) { part { it.copy(size=it.size%a.max+1) } }

    }
    private fun groupWidgets() {
        val first=(groupIndex/4)*4
        for(i in first until minOf(first+4,form.groups.size)) {
            val group=form.groups[i]
            cycle(8,55+(i-first)*20,120,"${group.priority}: ${group.name}") { groupIndex=i;rebuildWidgets() }
        }
        button(8,141,58,Component.literal("<")) { groupIndex=(groupIndex-1).mod(form.groups.size);rebuildWidgets() }
        button(70,141,58,Component.literal(">")) { groupIndex=(groupIndex+1).mod(form.groups.size);rebuildWidgets() }
        button(8,163,120,"new_group") {
            EditorValueInput.text(tr("new_group"),"",32) { value -> safe { createGroup(value) } }
        }.active=form.groups.size<8
        val id=activeGroup.id
        val groupName=field(136,55,130,activeGroup.name);groupName.setMaxLength(32)
        groupName.setResponder { value -> editGroupText(id,"name",value) }
        field(136,78,130,activeGroup.role).apply { setMaxLength(32);setTooltip(Tooltip.create(tr("role_identifier")));setResponder { value -> editGroupText(id,"role",value) } }
        button(136,101,62,"forward") { moveGroup(1) };button(201,101,65,"backward") { moveGroup(-1) }
        if(form.graph==null) {
            select(136,124,130,selected,sourceParts(form).indices.toList(),{ ui("part_number",it+1) }) { selected=it;rebuildWidgets() }
            button(136,147,130,"assign_group") { part { it.copy(group=id) } }
        }
        button(136,170,130,"delete_group") { if(form.groups.size>1) edit { f -> val target=f.groups.first { it.id!=id }.id;val changed=changeParts(f,sourceParts(f).map { if(it.group==id) it.copy(group=target) else it });changed.copy(groups=f.groups.filter { it.id!=id },graph=changed.graph?.let { graph -> graph.copy(nodes=graph.nodes.map { n -> n.copy(inputGroups=n.inputGroups.map { if(it==id) target else it },materialGroup=if(n.materialGroup==id) target else n.materialGroup,previewGroup=if(n.previewGroup==id) "" else n.previewGroup) }) }) } }
    }
    override fun renderContents(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
        kilaGraph?.selectNode(graphView.selected)
        if(graphMode) {
            label(g,font.plainSubstrByWidth(ui(if(graphInspector) "node_control_hint" else "graph_help"),width-16),8,height-26,0xffa4b9c4.toInt())
            kilaGraph?.renderPopupOverlay(g,mx,my,delta)
            return
        }
        safe {
            val compiled=preview().getOrThrow()
            val parts=Geometry.rotate(if(view==1) listOfNotNull(sourceParts(form).getOrNull(selected)) else compiled.parts,placementTurn)
            val surfaces=if(view==2) form.groups.associate { it.id to "minecraft:glass" } else assignments()
            Meshes.preview(g,(width+274)/2,127,50f,yaw,parts,if(view==2) "minecraft:glass" else Economy.palette[material],groups=form.groups,surfaces=surfaces)
            val count=Placement(parts=compiled.parts,groups=form.groups,surfaces=assignments()).surfaceItems().size
            label(g,ui("group_cost",Extrusion.cost(compiled.parts,form.groups),count),8,height-26)
        }
        if(groupMode) label(g,ui("foreground_priority",activeGroup.priority),8,190)
        else sourceParts(form).getOrNull(selected)?.let { p -> label(g,ui("part_group",form.groups.first { it.id==p.group }.name).take(21),136,201) }
    }
    override fun mouseClicked(x:Double,y:Double,button:Int):Boolean {
        if(dropdownClick(x,y,button)) return true
        if(graphCanvas?.hasOpenMenu()==true) return graphCanvas!!.mouseClicked(x,y,button)
        if(kilaGraph?.mouseClicked(x,y,button)==true) { focused=kilaGraph;return true }
        if(graphMode && shapeInspectorVisible && graphCanvas?.mouseClicked(x,y,button)==true) { focused=graphCanvas;return true }
        return super.mouseClicked(x,y,button)
    }
    override fun mouseDragged(x:Double,y:Double,button:Int,dx:Double,dy:Double):Boolean {
        if(focused===kilaGraph && kilaGraph!=null) return kilaGraph!!.mouseDragged(x,y,button,dx,dy)
        if(graphMode && shapeInspectorVisible && focused===graphCanvas) return graphCanvas?.mouseDragged(x,y,button,dx,dy) ?: false
        return super.mouseDragged(x,y,button,dx,dy)
    }
    override fun mouseReleased(x:Double,y:Double,button:Int):Boolean {
        if(focused===kilaGraph && kilaGraph!=null) return kilaGraph!!.mouseReleased(x,y,button)
        if(graphMode && shapeInspectorVisible && focused===graphCanvas) return graphCanvas?.mouseReleased(x,y,button) ?: false
        return super.mouseReleased(x,y,button)
    }
    override fun mouseScrolled(x:Double,y:Double,dx:Double,dy:Double):Boolean {
        if(graphCanvas?.hasOpenMenu()==true) { graphCanvas!!.mouseScrolled(x,y,dx,dy);return true }
        if(kilaGraph?.hasOpenMenu()==true) { kilaGraph!!.mouseScrolled(x,y,dx,dy);return true }
        return super.mouseScrolled(x,y,dx,dy)
    }
    override fun keyPressed(key:Int,scan:Int,mods:Int):Boolean {
        if(dropdownKey(key)) return true
        if(hasControlDown() && focused !is EditBox && key==90) { if(hasShiftDown()) redoEdit() else undoEdit();return true }
        if(hasControlDown() && focused !is EditBox && key==89) { redoEdit();return true }
        if(focused===kilaGraph && kilaGraph?.keyPressed(key,scan,mods)==true) return true
        if(graphMode && focused !is EditBox && graphCanvas?.keyPressed(key,scan,mods)==true) return true
        if(key==82 && !name.isFocused && !hasControlDown() && focused !is EditBox) {
            if(printerSession!=null) editPrinterSettings { placementTurn=(placementTurn+1)%4 } else placementTurn=(placementTurn+1)%4
            return true
        }
        if(hasControlDown() && key==67 && focused !is EditBox) { minecraft?.keyboardHandler?.clipboard=Json.encode(form.copy(name=name.value,parts=if(form.graph!=null) emptyList() else form.parts));return true }
        if(hasControlDown() && key==86 && focused !is EditBox) { safe { val text=minecraft?.keyboardHandler?.clipboard.orEmpty();require(text.length<=28000);edit { Geometry.validate(Json.decode<Form>(text)).copy(id=UUID.randomUUID().toString(),version=1) } };return true }
        return super.keyPressed(key,scan,mods)
    }
    override fun onClose() {
        if(printerSession!=null) ClientState.send(Command("printerCancel",key=printerSession.session))
        WorldPeek.active=false;minecraft?.setScreen(parent)
    }
    override fun removed() { kilaGraph?.dispose();super.removed() }
    private fun equip() {
        val saved=ClientState.snapshot.library.forms.entries.filter { it.value.id==form.id }.maxByOrNull { it.value.version }
        if(saved!=null) {
            val palettes=saved.value.groups.associate { it.id to (assignments()[it.id] ?: Economy.palette[material]) }
            ClientState.send(Command("select",key=saved.key,material=Economy.palette[material],turn=placementTurn,surfaces=palettes))
        } else ClientState.snapshot=ClientState.snapshot.copy(message=ui("save_before_equip"))
    }
    private fun undoEdit() { if(undo.isNotEmpty()) { redo.addLast(editState());restore(undo.removeLast());rebuildWidgets() } }
    private fun redoEdit() { if(redo.isNotEmpty()) { undo.addLast(editState());restore(redo.removeLast());rebuildWidgets() } }
}
class BuildingScreen:EditorScreen(tr("building_editor")) {
    var graphMode=false
    var graphInspector=false
    val buildingGraphView=BuildingGraphView()
    var buildingGraphCanvas:BuildingGraphCanvas?=null;private set
    val kilaBuildingGraphState=KilaGraphState()
    var kilaBuildingGraph:KilaGraphEditor?=null;private set
    private val buildingInspectorVisible get()=graphInspector || width>=700
    private val buildingUndo=ArrayDeque<Building>();private val buildingRedo=ArrayDeque<Building>()
    private var buildingProjectionKey:Building?=null
    private var buildingProjection:BuildingGraph?=null
    private var lastClassicTab=0
    private var presentationInitialized=false
    fun buildingDisplayGraph():BuildingGraph {
        building.graph?.let { return it }
        if(buildingProjectionKey!=building) { buildingProjectionKey=building;buildingProjection=GraphPresentation.building(building) }
        return buildingProjection!!
    }
    fun displayBuilding():Building=if(building.graph!=null) building else building.copy(graph=buildingDisplayGraph())
    fun selectBuildingNode(id:String) {
        if(building.graph==null) { id.removePrefix("classic_").toIntOrNull()?.let { lastClassicTab=if(it==6) 0 else it };return }
        buildingGraphView.selected=id;buildingGraphView.inspectorScroll=0.0
    }
    private fun buildingDiagram():KilaGraphData {
        if(building.graph==null) {
            val labels=listOf(ui("footprint")+" · "+building.wings.joinToString(" + ") { "${it.width}×${it.length}" },
                ui("modules"),ui("walls"),ui("plates")+" · ${building.floorThickness}",ui("roof")+" · "+ui(building.roof),ui("palette"),ui("floors",building.floors))
            val nodes=labels.mapIndexed { i,title -> KilaNode("classic_$i",title,20+(i/4)*230,20+(i%4)*100,emptyList(),true,false) }+
                KilaNode("classic_result",building.name,520,140,labels.indices.map { labels[it] },false,false)
            return KilaGraphData(nodes,labels.indices.map { KilaLink("classic_$it","classic_result",it) })
        }
        val graph=buildingDisplayGraph()
        return KilaGraphData(graph.nodes.mapIndexed { index,n ->
            KilaNode(n.id,"${index+1} · ${if(n.id=="empty_wall") ui("empty_wall") else ui("bg_"+n.kind.name.lowercase())}",n.x,n.y,BuildingPorts.of(n,true).map { p -> buildingPortLabel(p) },n.kind!=BuildingKind.BUILDING,n.kind!=BuildingKind.BUILDING && n.id!="empty_wall",inputTypes=BuildingPorts.of(n,true).map { it.accepts.map(::buildingDiagramType).distinct().joinToString("|") },outputType=buildingDiagramType(n.kind))
        },graph.nodes.flatMap { n -> BuildingPorts.of(n,true).mapIndexedNotNull { i,p -> p.source.takeIf { it.isNotEmpty() }?.let { KilaLink(it,n.id,i) } } })
    }
    private fun buildingDiagramType(kind:BuildingKind)=if(kind in listOf(BuildingKind.SEGMENT,BuildingKind.PATTERN)) "WALL" else kind.name
    private fun buildingInsertions():List<KilaInsertion> = BuildingKind.entries.filter { it!=BuildingKind.BUILDING }.map { kind ->
        KilaInsertion(ui("bg_"+kind.name.lowercase())) { x,y -> buildingGraphCanvas?.addAt(kind,x.toDouble(),y.toDouble()) }
    }
    private fun addBuildingNode(kind:BuildingKind) {
        val point=kilaBuildingGraph?.insertionPoint()
        if(point==null) buildingGraphCanvas?.add(kind) else buildingGraphCanvas?.addAt(kind,point.first.toDouble(),point.second.toDouble())
    }
    private fun buildingPortLabel(p:BuildingPort):String=when {
        p.key.startsWith("wall:") -> if(p.length>0) ui("bg_wall_port",ui(listOf("north","east","south","west")[p.key.removePrefix("wall:").substringBefore(':').toInt()]),p.length,p.height) else ui("bg_roof_wall_port",ui(listOf("north","east","south","west")[p.key.removePrefix("wall:").toInt()]))
        p.key.startsWith("level:") -> if(p.number==0) ui("bg_ground") else ui(if(p.number>0) "bg_upper" else "bg_lower",kotlin.math.abs(p.number))
        p.key in listOf("floor","ceiling","roof") -> ui("bg_"+p.key)
        p.key=="corner" -> ui("bg_corner_copycat")
        else -> ui(if(BuildingKind.COPYCAT in p.accepts) "bg_copycat_index" else if(BuildingKind.ROOM in p.accepts) "bg_room_index" else "bg_segment_index",p.number+1)
    }
    private fun commitBuildingDiagram(data:KilaGraphData) {
        val graph=buildingDisplayGraph()
        val ports=graph.nodes.associate { it.id to BuildingPorts.of(it,true) }
        val wires=data.links.map { link -> DiagramConnection(link.source,link.target,ports.getValue(link.target).getOrNull(link.slot)?.key ?: error("Unknown building input")) }
        commitBuildingGraph(DiagramEdits.building(graph,data.nodes.map { DiagramPosition(it.id,it.x,it.y) },wires))
    }
    fun openBuildingGraph(inspector:Boolean=false) {
        graphInspector=inspector;buildingGraphView.wire=null
        if(inspector && building.graph==null) { graphMode=false;tab=lastClassicTab }
        else { if(!graphMode && tab !in listOf(1,5,6)) lastClassicTab=tab;graphMode=true;tab=if(inspector) 7 else 0 }
        presentationInitialized=true;rebuildWidgets()
    }
    fun commitBuildingGraph(graph:BuildingGraph) {
        if(graph==buildingDisplayGraph()) return
        if(building.graph==null) { buildingProjection=graph;return }
        safe { editBuildingGraph(graph) }
    }
    private fun editBuildingGraph(graph:BuildingGraph) {
        if(graph==building.graph) return
        BuildingGraphCompiler.structure(graph)
        val missing=graph.nodes.map { it.role }.distinct().filter { it !in building.palette }
        require(building.palette.size+missing.size<=16) { ui("role_limit") }
        val palette=building.palette+missing.associateWith { building.palette["wall"] ?: "minecraft:stone" }
        buildingUndo.addLast(building);if(buildingUndo.size>64) buildingUndo.removeFirst();buildingRedo.clear()
        building=building.copy(schema=2,graph=graph,palette=palette);rebuildWidgets()
    }
    var building=ClientState.snapshot.library.buildings.values.lastOrNull() ?: Building(id=UUID.randomUUID().toString())
    private var module=ClientState.snapshot.library.modules["window@1"] ?: Module()
    var tab=0;private var level=0;private var role="wall";private var cellForm="full@1";private var cellTurn=0;private var cellMode=0
    var module3d=false;private var wall=0;private var selectedCell:P?=null;private var jobIndex=0;private var visibleFloor=-1;private var component=0
    private var origin=ClientState.snapshot.position+P(5,0,5);private var dock=origin+P(-5,1,-5);private var rotation=0;private var computer=-1
    private lateinit var name:EditBox
    private var planKey=""
    private var planCache:Result<Plan>?=null
    private fun plan():Result<Plan> {
        val key=building.hashCode().toString()+":"+lib.hashCode()+":"+Extrusion.outputs.hashCode()
        if(key!=planKey || planCache==null) { planKey=key;planCache=runCatching { Generator.generate(building,lib.forms,lib.modules) } }
        return planCache!!
    }
    private var coordinateFields=emptyList<EditBox>()
    private val lib get()=ClientState.snapshot.library
    private fun update(b:Building) {
        if(b!=building) { buildingUndo.addLast(building);buildingRedo.clear() }
        building=b
        if(tab !in listOf(1,5,6)) {
            if(b.graph==null && (graphMode || tab==7)) { graphMode=false;tab=lastClassicTab }
            else if(b.graph!=null && !graphMode) { graphMode=true;graphInspector=true;tab=7 }
        }
        rebuildWidgets()
    }
    private fun createRole(value:String) {
        require(value.matches(Regex("[a-zA-Z0-9_-]{1,32}")))
        if(value in building.palette) return
        require(building.palette.size<16) { ui("role_limit") }
        buildingUndo.addLast(building);buildingRedo.clear()
        building=building.copy(palette=building.palette+(value to (building.palette["wall"] ?: "minecraft:stone")))
    }
    private fun validateRole(value:String) {
        require(value.matches(Regex("[a-zA-Z0-9_-]{1,32}")))
        require(value in building.palette || building.palette.size<16) { ui("role_limit") }
    }
    private fun roleName(value:String):String {
        val key="role_"+value
        return if(net.minecraft.client.resources.language.I18n.exists("gui.copycat_architect.$key")) ui(key) else value
    }
    private fun modUpdate(m:Module) { module=m;rebuildWidgets() }
    private fun pattern(action:(Pattern)->Pattern) = update(building.copy(patterns=building.patterns.mapIndexed { i,p -> if(i==wall) action(p) else p }))
    override fun init() {
        kilaBuildingGraph?.dispose();kilaBuildingGraph=null
        resetDropdown()
        if(!presentationInitialized) { graphMode=building.graph!=null && tab !in listOf(1,5,6);presentationInitialized=true }
        buildingGraphCanvas=null
        val tabs=if(building.graph!=null) listOf(0 to "node_graph",7 to "control_panel",1 to "modules",5 to "palette",6 to "build")
            else listOf("footprint","modules","walls","plates","roof","palette","build").mapIndexed { i,key -> i to key }
        val tw=(width-16)/tabs.size
        tabs.forEachIndexed { index,(i,k) -> button(8+index*tw,28,tw-2,k) {
            if(::name.isInitialized) { if(tab==1) module=module.copy(name=name.value) else building=building.copy(name=name.value) }
            tab=i;if(i==0 || i==7) graphInspector=i==7
            graphMode=building.graph!=null && i !in listOf(1,5,6);if(building.graph==null) lastClassicTab=i
            buildingGraphView.wire=null;rebuildWidgets()
        }.apply { (this as EditorButton).selected=if(graphMode) (i==7)==graphInspector && i in listOf(0,7) else tab==i } }
        name=field(8,51,110,if(tab==1) module.name else building.name)
        name.setResponder { if(tab==1) module=module.copy(name=it) else building=building.copy(name=it) }
        button(122,51,42,"save") { if(tab==1) { module=module.copy(name=name.value);ClientState.send(Command("saveModule",Json.encode(module))) } else { building=building.copy(name=name.value);ClientState.send(Command("saveBuilding",Json.encode(building))) } }
        choice(167,51,42,ui("load"),if(tab==1) lib.modules.values.map { saved -> "${saved.name} · v${saved.version}" to { modUpdate(saved) } }
            else lib.buildings.values.map { saved -> "${saved.name} · v${saved.version}" to { update(saved) } })
        choice(212,51,68,ui("document"),listOf(ui("copy") to {
            if(tab==1) modUpdate(module.copy(id=UUID.randomUUID().toString(),version=1,name=module.name+" copy"))
            else update(building.copy(id=UUID.randomUUID().toString(),version=1,name=building.name+" copy"))
        })+if(tab==1) listOf(ui("new_module") to { modUpdate(Module(id=UUID.randomUUID().toString(),name=ui("new_module"))) }) else listOf(
            ui("new_classic_building") to { graphMode=false;tab=0;update(Building(id=UUID.randomUUID().toString(),name=ui("new_classic_building"))) },
            ui("new_graph_document") to { graphMode=true;graphInspector=false;tab=0;kilaBuildingGraphState.fitted=false;update(Building(schema=2,id=UUID.randomUUID().toString(),name=ui("new_graph_document"),graph=BuildingGraph.assembledStarter())) }
        ))
        button(width-252,3,94,"control_panel") { openBuildingGraph(true) }.apply { (this as EditorButton).selected=!graphMode || graphInspector }
        button(width-154,3,90,"node_graph") { openBuildingGraph() }.apply { (this as EditorButton).selected=graphMode && !graphInspector }
        button(width-60,3,54,"close") { onClose() }
        if(graphMode) {
            val kinds=BuildingKind.entries.filter { it!=BuildingKind.BUILDING }
            choice(8,76,164,ui("insert_node"),kinds.map { kind -> ui("bg_"+kind.name.lowercase()) to { addBuildingNode(kind) } }).active=building.graph!=null
            button(176,76,40,"fit_graph") { kilaBuildingGraph?.fit() ?: buildingGraphCanvas?.fit() }
            button(220,76,48,"remove") { if(kilaBuildingGraph!=null) kilaBuildingGraph!!.deleteSelection() else buildingGraphCanvas?.deleteSelected() }.active=building.graph!=null
            button(272,76,48,"undo") { undoEdit() }.active=buildingUndo.isNotEmpty()
            button(324,76,48,"redo") { redoEdit() }.active=buildingRedo.isNotEmpty()
            button(376,76,24,Component.literal("?")) {}.setTooltip(Tooltip.create(tr("bg_controls")))
            val side=if(!graphInspector && width>=700) minOf(340,width/3) else width-16
            val left=if(!graphInspector && width>=700) width-side-8 else 8
            buildingGraphCanvas=BuildingGraphCanvas(left,100,side,height-132,{displayBuilding()},{lib},buildingGraphView,::commitBuildingGraph,{ message -> ClientState.snapshot=ClientState.snapshot.copy(message=message) },true,onCreateRole=::validateRole,preview=if(building.graph==null) ({ plan().map { CompiledBuilding(it,emptyList()) } }) else null,readOnly=building.graph==null)
            if(buildingInspectorVisible) addRenderableWidget(buildingGraphCanvas!!)
            if(!graphInspector) kilaBuildingGraph=addRenderableWidget(KilaGraphEditor(8,100,if(width>=700) left-12 else width-16,height-132,this,kilaBuildingGraphState,::buildingDiagram,::selectBuildingNode,::commitBuildingDiagram,{ message -> ClientState.snapshot=ClientState.snapshot.copy(message=message) },building.graph==null,insertions=buildingInsertions()))
            return
        }
        when(tab) {
            0 -> {
                select(8,78,135,building.wings.size>1,listOf(false,true),{ ui(if(it) "l_footprint" else "rectangle") }) { isL -> update(building.copy(wings=if(isL) listOf(building.wings[0],Rect(0,building.wings[0].length,5,6)) else listOf(building.wings[0]))) }
                for(i in building.wings.indices) {
                    val r=building.wings[i]
                    number(8,102+i*22,68,r.width,3..20,{ ui("width",it) }) { value -> val wings=building.wings.toMutableList();wings[i]=r.copy(width=value);update(building.copy(wings=wings)) }
                    number(79,102+i*22,68,r.length,3..20,{ ui("bg_depth",it) }) { value -> val wings=building.wings.toMutableList();wings[i]=r.copy(length=value);if(i==0 && wings.size==2) wings[1]=wings[1].copy(z=wings[0].length);update(building.copy(wings=wings)) }
                }
                number(8,153,135,building.floors,1..4,{ ui("floors",it) }) { update(building.copy(floors=it)) }
                number(8,175,135,building.roomHeight,2..8,{ ui("room_height",it) }) { update(building.copy(roomHeight=it)) }
            }
            1 -> {
                number(8,78,74,module.width,1..8,{ ui("width",it) }) { modUpdate(module.copy(width=it,cells=emptyList(),minWidth=it,maxWidth=it)) }
                number(85,78,74,module.height,1..8,{ ui("height",it) }) { modUpdate(module.copy(height=it,cells=emptyList())) }
                cycle(164,100,84,if(module3d) ui("preview3d") else ui("edit_grid")) { module3d=!module3d;rebuildWidgets() }
                select(8,100,74,level,(0 until module.depth).toList(),{ ui("layer_index",it) }) { level=it;rebuildWidgets() }
                select(85,100,74,cellTurn,(0..3).toList(),{ "${it*90}°" }) { cellTurn=it;rebuildWidgets() }
                select(8,122,151,cellForm,lib.forms.keys.toList(),{ lib.forms[it]?.name ?: it }) { cellForm=it;rebuildWidgets() }
                button(8,144,151,Component.literal(ui("role",roleName(role)))) {
                    EditorValueInput.text(tr("role_identifier"),role,32,{ it.matches(Regex("[a-zA-Z0-9_-]{1,32}")) }) { value -> safe { createRole(value);role=value;rebuildWidgets() } }
                }
                select(8,166,151,cellMode,(0..4).toList(),{ listOf(ui("place_form"),ui("require_opening"),ui("erase"),ui("flexible_fill"),ui("native_block"))[it] }) { cellMode=it;rebuildWidgets() }
                button(8,188,151,"edit_shape") { minecraft?.setScreen(ShapeScreen(this)) }
                number(164,78,84,module.maxWidth,module.width..8,{ ui("variable_width",it) }) { modUpdate(module.copy(minWidth=module.width,maxWidth=it)) }
                button(251,78,65,"new") { modUpdate(Module(id=UUID.randomUUID().toString(),name="New module",width=3,height=4)) }
            }
            2 -> {
                val p=building.patterns[wall]
                select(8,78,160,wall,(0..3).toList(),{ listOf(ui("north"),ui("east"),ui("south"),ui("west"))[it] }) { wall=it;rebuildWidgets() }
                select(8,100,160,p.mode,(0..2).toList(),{ listOf(ui("repeat_segment"),ui("segment_interlude"),ui("repeat_sequence"))[it] }) { value -> pattern { it.copy(mode=value) } }
                for(i in p.sequence.indices.take(3)) select(8,122+i*22,160,p.sequence[i],lib.modules.keys.toList(),{ "${('A'.code+i).toChar()}: ${lib.modules[it]?.name ?: it}" }) { value -> pattern { it.copy(sequence=it.sequence.mapIndexed { j,k -> if(j==i) value else k }) } }
                select(174,78,140,p.interlude,lib.modules.keys.toList(),{ ui("pattern_interlude",lib.modules[it]?.name ?: it) }) { value -> pattern { it.copy(interlude=value) } }
                select(174,100,140,p.remainder,Remainder.entries,{ ui("pattern_remainder",ui("remainder_"+it.name.lowercase())) }) { value -> pattern { it.copy(remainder=value) } }
                select(174,122,140,p.start,listOf("")+lib.modules.keys,{ ui("pattern_start",lib.modules[it]?.name ?: ui("none")) }) { value -> pattern { it.copy(start=value) } }
                select(174,144,140,p.end,listOf("")+lib.modules.keys,{ ui("pattern_end",lib.modules[it]?.name ?: ui("none")) }) { value -> pattern { it.copy(end=value) } }
                select(174,166,140,p.filler,lib.modules.keys.toList(),{ ui("pattern_filler",lib.modules[it]?.name ?: it) }) { value -> pattern { it.copy(filler=value) } }
                cycle(8,195,77,"+ sequence") { if(p.sequence.size<3) pattern { it.copy(sequence=it.sequence+lib.modules.keys.first()) } }
                cycle(88,195,80,"- sequence") { if(p.sequence.size>1) pattern { it.copy(sequence=it.sequence.dropLast(1)) } }
                select(174,188,140,p.partial,listOf(false,true),{ ui("partial_cycle",ui(if(it) "yes" else "no")) }) { value -> pattern { it.copy(partial=value) } }
            }
            3 -> {
                select(8,78,190,building.floorForm,lib.forms.keys.toList(),{ ui("plate_form",lib.forms[it]?.name ?: it) }) { update(building.copy(floorForm=it,ceilingForm=it)) }
                number(8,100,190,building.floorThickness,1..2,{ ui("plate_thickness",it) }) { update(building.copy(floorThickness=it,ceilingThickness=it)) }
                select(8,122,190,level,(0..building.floors).toList(),{ ui("cutout_layer",it) }) { level=it;rebuildWidgets() }
            }
            4 -> {
                select(8,78,165,building.roof,listOf("flat","gable"),{ ui("roof_type",ui(it)) }) { update(building.copy(roof=it)) }
                select(8,100,165,building.ridge,listOf(0,1),{ ui("bg_ridge",if(it==0) "Z" else "X") }) { update(building.copy(ridge=it)) }
                number(8,122,165,building.overhang,0..2,{ ui("overhang",it) }) { update(building.copy(overhang=it)) }
                number(8,144,165,building.roofThickness,1..2,{ ui("roof_thickness",it) }) { update(building.copy(roofThickness=it)) }
                select(8,166,165,building.trimForm,listOf("")+lib.forms.keys,{ ui("trim_form",lib.forms[it]?.name ?: ui("none")) }) { update(building.copy(trimForm=it)) }
            }
            5 -> {
                if(role !in building.palette) role=building.palette.keys.first()
                choice(8,78,200,roleName(role),building.palette.keys.map { value -> roleName(value) to { role=value;rebuildWidgets() } }+
                    if(building.palette.size<16) listOf(ui("new_material_role") to { EditorValueInput.text(tr("role_identifier"),"",32,{ it.matches(Regex("[a-zA-Z0-9_-]{1,32}")) }) { value -> createRole(value);role=value;rebuildWidgets() } }) else emptyList(),building.palette.keys.indexOf(role))
                val material=building.palette.getValue(role)
                select(8,101,200,material,Economy.palette,{ Economy.item(it).description.string }) { update(building.copy(palette=building.palette+(role to it))) }
            }
            6 -> {
                val vals=listOf(origin.x,origin.y,origin.z,dock.x,dock.y,dock.z,computer)
                coordinateFields=vals.mapIndexed { i,v -> field(8+(i%3)*61,89+(i/3)*32,57,v.toString()).apply { setResponder { text -> text.toIntOrNull()?.let { value -> when(i) {
                    0->origin=origin.copy(x=value);1->origin=origin.copy(y=value);2->origin=origin.copy(z=value)
                    3->dock=dock.copy(x=value);4->dock=dock.copy(y=value);5->dock=dock.copy(z=value);else->computer=value
                } } } } }
                select(8,178,178,rotation,(0..3).toList(),{ ui("rotation")+": ${it*90}°" }) { rotation=it;rebuildWidgets() }
                button(8,200,178,"create_job") { safe {
                    val v=coordinateFields.map { it.value.toInt() };origin=P(v[0],v[1],v[2]);dock=P(v[3],v[4],v[5]);computer=v[6]
                    building=building.copy(name=name.value);ClientState.send(Command("createJob",Json.encode(building),origin=origin,dock=dock,turn=rotation,computer=computer))
                } }
                select(194,78,218,jobIndex,ClientState.snapshot.jobs.indices.toList(),{ index -> ClientState.snapshot.jobs.getOrNull(index)?.let { "${it.name}: ${it.index}/${it.total}" } ?: ui("no_job") }) { jobIndex=it;rebuildWidgets() }
                listOf("start","pause","resume","cancel").forEachIndexed { i,a -> button(194+i*55,101,53,a) { ClientState.snapshot.jobs.getOrNull(jobIndex)?.let { ClientState.send(Command(a,key=it.id)) } } }
                button(194,124,106,"refresh") { ClientState.send(Command()) }
                button(303,124,106,"export") { minecraft?.keyboardHandler?.clipboard=Json.encode(building);ClientState.snapshot=ClientState.snapshot.copy(message="Building copied to clipboard") }
                button(303,146,106,"import") { safe { val text=minecraft?.keyboardHandler?.clipboard.orEmpty();require(text.length<=28000);update(Json.decode<Building>(text));Generator.generate(building,lib.forms,lib.modules) } }
            }
        }
        if(tab!=1 && tab!=2 && tab!=6) {
            select(width-146,51,138,visibleFloor,(-1..building.floors).toList(),{ ui("preview_floor",if(it<0) ui("all_groups") else it) }) { visibleFloor=it;rebuildWidgets() }
            select(width-146,height-36,138,component,(0..3).toList(),{ listOf(ui("all_components"),ui("walls"),ui("plates"),ui("roof"))[it] }) { component=it;rebuildWidgets() }
        }
    }
    override fun mouseDragged(x:Double,y:Double,button:Int,dx:Double,dy:Double):Boolean {
        if(focused===kilaBuildingGraph && kilaBuildingGraph!=null) return kilaBuildingGraph!!.mouseDragged(x,y,button,dx,dy)
        if(graphMode && buildingInspectorVisible && focused===buildingGraphCanvas) return buildingGraphCanvas?.mouseDragged(x,y,button,dx,dy) ?: false
        return super.mouseDragged(x,y,button,dx,dy)
    }
    override fun mouseReleased(x:Double,y:Double,button:Int):Boolean {
        if(focused===kilaBuildingGraph && kilaBuildingGraph!=null) return kilaBuildingGraph!!.mouseReleased(x,y,button)
        if(graphMode && buildingInspectorVisible && focused===buildingGraphCanvas) return buildingGraphCanvas?.mouseReleased(x,y,button) ?: false
        return super.mouseReleased(x,y,button)
    }
    override fun keyPressed(key:Int,scan:Int,mods:Int):Boolean {
        if(dropdownKey(key)) return true
        if(hasControlDown() && focused !is EditBox && key==90) { if(hasShiftDown()) redoEdit() else undoEdit();return true }
        if(hasControlDown() && focused !is EditBox && key==89) { redoEdit();return true }
        if(focused===kilaBuildingGraph && kilaBuildingGraph?.keyPressed(key,scan,mods)==true) return true
        if(graphMode && focused !is EditBox && buildingGraphCanvas?.keyPressed(key,scan,mods)==true) return true
        if(hasControlDown() && key==67 && focused !is EditBox) { minecraft?.keyboardHandler?.clipboard=if(tab==1) Json.encode(module.copy(name=name.value)) else Json.encode(building.copy(name=name.value));return true }
        if(hasControlDown() && key==86 && focused !is EditBox) { safe {
            val text=minecraft?.keyboardHandler?.clipboard.orEmpty();require(text.length<=28000)
            if(tab==1) modUpdate(Json.decode<Module>(text).validate().copy(id=UUID.randomUUID().toString(),version=1))
            else update(Json.decode<Building>(text).copy(id=UUID.randomUUID().toString(),version=1))
        };return true }
        return super.keyPressed(key,scan,mods)
    }
    private fun grid(g:GuiGraphics) {
        val gx=174;val gy=125;val s=minOf(22,(width-gx-12)/module.width,(height-gy-30)/module.height).coerceAtLeast(8)
        for(y in 0 until module.height) for(x in 0 until module.width) {
            val p=P(x,y,level);val c=module.cells.firstOrNull { it.pos==p }
            val color=when { c==null->0xff30414c.toInt();c.empty->0xff683a3a.toInt();c.flexible->0xff618852.toInt();c.role=="glass"->0xff71b9c2.toInt();else->0xffd6ad68.toInt() }
            g.fill(gx+x*s,gy+(module.height-1-y)*s,gx+(x+1)*s-1,gy+(module.height-y)*s-1,color)
        }
    }
    override fun mouseClicked(x:Double,y:Double,button:Int):Boolean {
        if(dropdownClick(x,y,button)) return true
        if(buildingGraphCanvas?.hasOpenMenu()==true) return buildingGraphCanvas!!.mouseClicked(x,y,button)
        if(kilaBuildingGraph?.mouseClicked(x,y,button)==true) { focused=kilaBuildingGraph;return true }
        if(graphMode && buildingInspectorVisible && buildingGraphCanvas?.mouseClicked(x,y,button)==true) { focused=buildingGraphCanvas;return true }
        if(tab==1 && !module3d && button==0) {
            val gx=174;val gy=125;val s=minOf(22,(width-gx-12)/module.width,(height-gy-30)/module.height).coerceAtLeast(8)
            val u=((x-gx)/s).toInt();val v=module.height-1-((y-gy)/s).toInt()
            if(x>=gx && y>=gy && u in 0 until module.width && v in 0 until module.height) {
                val p=P(u,v,level);selectedCell=p
                val cells=module.cells.filter { it.pos!=p }.toMutableList()
                if(cellMode!=2) cells+=Cell(p,cellForm,if(cellMode==4) building.palette[role] ?: "minecraft:stone" else "",role,cellTurn,cellMode==1,cellMode==3)
                module=module.copy(cells=cells);return true
            }
        }
        if(tab==3 && button==0 && x>220 && y>85) {
            val u=((x-225)/8).toInt();val z=((y-85)/8).toInt();val p=P(u,level*(building.roomHeight+building.floorThickness),z)
            if(building.wings.any { it.has(u,z) }) { update(building.copy(holes=if(p in building.holes) building.holes-p else building.holes+p));return true }
        }
        return super.mouseClicked(x,y,button)
    }
    override fun renderContents(g:GuiGraphics,mx:Int,my:Int,delta:Float) {
        if(building.graph!=null) kilaBuildingGraph?.selectNode(buildingGraphView.selected)
        if(graphMode) {
            label(g,font.plainSubstrByWidth(ui(if(building.graph==null) "classic_graph_hint" else if(graphInspector) "bg_inspector_hint" else "bg_help"),width-16),8,height-26,0xffadc3ce.toInt())
            kilaBuildingGraph?.renderPopupOverlay(g,mx,my,delta)
            return
        }
        if(tab==1) {
            if(!module3d) { grid(g);label(g,"Local origin: lower left • click cells",164,height-29) }
            else {
                g.flush();val pose=g.pose();pose.pushPose();pose.translate((width+170)/2.0,(height+105)/2.0,120.0)
                val size=maxOf(module.width,module.height,module.depth);val scale=(height-120).toFloat()/size*0.7f
                pose.scale(scale,-scale,scale);pose.mulPose(Axis.XP.rotationDegrees(20f));pose.mulPose(Axis.YP.rotationDegrees(yaw));pose.translate(-module.width/2.0,-module.height/2.0,-module.depth/2.0)
                for(c in module.cells.filter { !it.empty }) {
                    val f=if(c.block.isEmpty()) lib.forms[c.form] ?: continue else Form(parts=listOf(Part("copycats:copycat_block")));pose.pushPose();pose.translate(c.pos.x.toDouble(),c.pos.y.toDouble(),c.pos.z.toDouble())
                    Meshes.draw(Geometry.rotate(f.parts,c.turn),if(c.block.isEmpty()) building.palette[c.role] ?: "minecraft:stone" else c.block,pose,g.bufferSource(),15728880,groups=f.groups,surfaces=f.groups.associate { it.id to (building.palette[if(it.role=="surface") c.role else it.role] ?: "minecraft:stone") });pose.popPose()
                };g.flush();pose.popPose()
            };return
        }
        if(tab==2) {
            safe {
                val d=Patterns.distribute(11,building.patterns[wall],lib.modules)
                label(g,"W=11",322,80);label(g,"Rest ${d.remainder}",322,92)
                val scale=(width-16)/11f
                for((i,span) in d.spans.withIndex()) {
                    val left=8+(span.start*scale).toInt();val right=8+((span.start+span.width)*scale).toInt()
                    g.fill(left,height-25,right-1,height-15,if(span.key==building.patterns[wall].filler) 0xff547786.toInt() else if(i%2==0) 0xffad8b52.toInt() else 0xff635e48.toInt())
                    label(g,lib.modules[span.key]?.name?.take(3) ?: "?",left+2,height-24)
                }
            };return
        }
        if(tab==3) {
            for(r in building.wings) for(x in r.x until r.x+r.width) for(z in r.z until r.z+r.length) {
                val hole=P(x,level*(building.roomHeight+building.floorThickness),z) in building.holes
                g.fill(225+x*8,85+z*8,232+x*8,92+z*8,if(hole) 0xff9f4444.toInt() else 0xff7e9eab.toInt())
            };return
        }
        val plan=plan()
        if(plan.isFailure) { label(g,plan.exceptionOrNull()?.message?.take(65) ?: "Invalid design",8,height-28,0xffff7777.toInt());return }
        val p=plan.getOrThrow()
        if(tab==6) {
            label(g,ui("origin_xyz"),8,78);label(g,ui("dock_xyz"),8,110);label(g,"Turtle ID (-1 = auto)",8,142)
            val j=ClientState.snapshot.jobs.getOrNull(jobIndex);label(g,j?.state.orEmpty()+": "+j?.message.orEmpty().take(46),194,170)
            label(g,"${p.placements.size} positions • ${p.raw/256} + ${p.raw%256}/256 zinc",194,185)
            p.materials.entries.take(3).forEachIndexed { i,e -> label(g,"${e.key.substringAfter(':')}: ${e.value}",194,198+i*10) }
            return
        }
        g.flush();g.enableScissor(178,74,width-8,height-42)
        val lo=P(p.placements.minOf { it.pos.x },p.placements.minOf { it.pos.y },p.placements.minOf { it.pos.z })
        val hi=P(p.placements.maxOf { it.pos.x }+1,p.placements.maxOf { it.pos.y }+1,p.placements.maxOf { it.pos.z }+1)
        val sx=(hi.x-lo.x).toFloat();val sy=(hi.y-lo.y).toFloat();val sz=(hi.z-lo.z).toFloat()
        val orientation=org.joml.Quaternionf().rotationX(Math.toRadians(25.0).toFloat()).rotateY(Math.toRadians(yaw.toDouble()).toFloat())
        val corners=buildList { for(x in listOf(-sx/2,sx/2)) for(y in listOf(-sy/2,sy/2)) for(z in listOf(-sz/2,sz/2)) add(orientation.transform(org.joml.Vector3f(x,y,z))) }
        val scale=minOf((width-198)/(corners.maxOf { it.x }-corners.minOf { it.x }),(height-126)/(corners.maxOf { it.y }-corners.minOf { it.y }))*0.92f
        val pose=g.pose();pose.pushPose();pose.translate((width+170)/2.0,(height+32)/2.0,120.0)
        pose.scale(scale,-scale,scale);pose.mulPose(orientation);pose.translate(-(lo.x+hi.x)/2.0,-(lo.y+hi.y)/2.0,-(lo.z+hi.z)/2.0)
        for(cell in p.placements) {
            if(visibleFloor>=0 && cell.pos.y/(building.roomHeight+building.floorThickness)!=visibleFloor) continue
            if(component==1 && cell.component !in listOf("wall","corner","trim","gable")) continue
            if(component==2 && cell.component !in listOf("floor","ceiling")) continue
            if(component==3 && cell.component!="roof") continue
            pose.pushPose();pose.translate(cell.pos.x.toDouble(),cell.pos.y.toDouble(),cell.pos.z.toDouble())
            if(cell.block.isEmpty()) Meshes.draw(cell.parts,cell.material,pose,g.bufferSource(),15728880,groups=cell.groups,surfaces=cell.surfaces)
            else minecraft!!.blockRenderer.renderSingleBlock(Economy.nativeState(cell.block),pose,g.bufferSource(),15728880,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
            pose.popPose()
        };g.flush();pose.popPose();g.disableScissor()
    }
    override fun mouseScrolled(x:Double,y:Double,dx:Double,dy:Double):Boolean {
        if(buildingGraphCanvas?.hasOpenMenu()==true) { buildingGraphCanvas!!.mouseScrolled(x,y,dx,dy);return true }
        if(kilaBuildingGraph?.hasOpenMenu()==true) { kilaBuildingGraph!!.mouseScrolled(x,y,dx,dy);return true }
        return super.mouseScrolled(x,y,dx,dy)
    }
    override fun removed() { kilaBuildingGraph?.dispose();super.removed() }
    private fun undoEdit() { if(buildingUndo.isNotEmpty()) { buildingRedo.addLast(building);building=buildingUndo.removeLast();rebuildWidgets() } }
    private fun redoEdit() { if(buildingRedo.isNotEmpty()) { buildingUndo.addLast(building);building=buildingRedo.removeLast();rebuildWidgets() } }
}
