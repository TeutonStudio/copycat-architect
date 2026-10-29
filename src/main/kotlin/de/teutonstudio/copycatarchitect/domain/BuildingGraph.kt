package de.teutonstudio.copycatarchitect.domain

import java.util.concurrent.ConcurrentHashMap

enum class BuildingKind { COPYCAT, SEGMENT, PATTERN, FLOOR, CEILING, ROOM, STOREY, ROOF, BUILDING }
data class BuildingNode(
    val id:String="",val kind:BuildingKind=BuildingKind.SEGMENT,val name:String="",val x:Int=0,val y:Int=0,
    val inputs:List<String> = emptyList(),val module:String="window@1",val block:String="",val length:Int=3,val height:Int=4,
    val rectangles:List<Rect> = listOf(Rect(width=3,length=3)),val walls:Map<String,String> = emptyMap(),
    val floor:String="",val ceiling:String="",val offsets:List<P> = emptyList(),
    val form:String="full@1",val thickness:Int=1,val occupied:Boolean=false,val finish:String="",
    val levels:Map<Int,String> = emptyMap(),val roof:String="",val roofType:String="flat",val ridge:Int=0,val overhang:Int=0,
    val materialMode:MaterialMode=MaterialMode.SEPARATED,val role:String="wall",val preview:String="all",val storeyMode:String="rooms",val corner:String=""
)
/** Optional Copycat sequences grow through spare ports, so an unused tail is not a required input. */
internal fun BuildingNode.trimCopycatInputs():BuildingNode {
    if(kind !in setOf(BuildingKind.SEGMENT,BuildingKind.FLOOR,BuildingKind.CEILING,BuildingKind.ROOF)) return this
    val trimmed=inputs.dropLastWhile(String::isEmpty)
    return if(trimmed.size==inputs.size) this else copy(inputs=trimmed,offsets=offsets.take(trimmed.size))
}
data class BuildingGraph(val schema:Int=1,val nodes:List<BuildingNode> = emptyList()) {
    fun replace(n:BuildingNode)=copy(nodes=nodes.map { if(it.id==n.id) n else it })
    fun remove(id:String):BuildingGraph {
        require(id!="empty_wall" && nodes.first { it.id==id }.kind!=BuildingKind.BUILDING) { "Keep the result and Empty wall source" }
        return copy(nodes=nodes.filter { it.id!=id }.map { n -> n.copy(
            inputs=n.inputs.map { if(it==id) "" else it },walls=n.walls.filterValues { it!=id },
            floor=if(n.floor==id) "" else n.floor,ceiling=if(n.ceiling==id) "" else n.ceiling,corner=if(n.corner==id) "" else n.corner,
            levels=compactLevels(n.levels.filterValues { it!=id }),roof=if(n.roof==id) "" else n.roof).trimCopycatInputs() })
    }
    fun connect(target:String,key:String,source:String):BuildingGraph {
        val n=nodes.first { it.id==target }
        require(BuildingPorts.of(n,true).any { it.key==key }) { "Unknown building socket" }
        val changed=when {
            key=="floor" -> n.copy(floor=source)
            key=="ceiling" -> n.copy(ceiling=source)
            key=="corner" -> n.copy(corner=source)
            key=="roof" -> n.copy(roof=source)
            key.startsWith("wall:") -> {
                val edge=key.removePrefix("wall:")
                n.copy(walls=if(n.kind==BuildingKind.ROOF && source.isEmpty()) n.walls-edge else n.walls+(edge to source.ifEmpty { "empty_wall" }))
            }
            key.startsWith("level:") -> {
                val level=key.removePrefix("level:").toInt();require(level in -8..8)
                n.copy(levels=compactLevels(if(source.isEmpty()) n.levels-level else n.levels+(level to source)))
            }
            key.startsWith("in:") -> {
                val i=key.removePrefix("in:").toInt();require(i in 0 until 16 && i<=n.inputs.size)
                val inputs=n.inputs.toMutableList();if(i==inputs.size) inputs+="";inputs[i]=source
                n.copy(inputs=inputs,offsets=List(inputs.size) { n.offsets.getOrNull(it) ?: P() })
            }
            else -> error("Unknown building socket")
        }
        val graph=replace(changed.trimCopycatInputs());BuildingGraphCompiler.structure(graph);return graph
    }
    companion object {
        fun compactLevels(levels:Map<Int,String>):Map<Int,String> = buildMap {
            levels[0]?.takeIf { it.isNotEmpty() }?.let { put(0,it) }
            levels.filter { it.key>0 && it.value.isNotEmpty() }.toSortedMap().values.forEachIndexed { i,s -> put(i+1,s) }
            levels.filter { it.key<0 && it.value.isNotEmpty() }.toSortedMap(reverseOrder()).values.forEachIndexed { i,s -> put(-i-1,s) }
        }
        fun starter()=BuildingGraph(nodes=listOf(
            BuildingNode("empty_wall",BuildingKind.SEGMENT,"Empty wall",20,20,block="minecraft:air",length=1),
            BuildingNode("wall",BuildingKind.SEGMENT,"Solid wall",20,175,module=""),
            BuildingNode("floor",BuildingKind.FLOOR,"Floor",20,350),
            BuildingNode("ceiling",BuildingKind.CEILING,"Ceiling",20,525),
            BuildingNode("room",BuildingKind.ROOM,"Room",300,60,walls=RoomGeometry.edges(listOf(Rect(width=3,length=3))).associate { it.id to "wall" },floor="floor",ceiling="ceiling"),
            BuildingNode("storey",BuildingKind.STOREY,"Storey",650,60,inputs=listOf("room"),offsets=listOf(P())),
            BuildingNode("roof",BuildingKind.ROOF,"Roof",650,460,role="roof"),
            BuildingNode("building",BuildingKind.BUILDING,"Building",1000,100,levels=mapOf(0 to "storey"),roof="roof")
        ))
        /** The current starter exposes every assembly step; saved room graphs retain their mode. */
        fun assembledStarter()=BuildingGraph(nodes=listOf(
            BuildingNode("empty_wall",BuildingKind.SEGMENT,"Empty wall",20,20,block="minecraft:air",length=1),
            BuildingNode("wall_copycat",BuildingKind.COPYCAT,"Wall Copycat",20,200),
            BuildingNode("roof_copycat",BuildingKind.COPYCAT,"Roof Copycat",20,550,role="roof"),
            BuildingNode("wall",BuildingKind.SEGMENT,"Wall segment",290,30,inputs=listOf("wall_copycat"),module=""),
            BuildingNode("floor",BuildingKind.FLOOR,"Floor",290,220,inputs=listOf("wall_copycat")),
            BuildingNode("ceiling",BuildingKind.CEILING,"Ceiling",290,440,inputs=listOf("wall_copycat")),
            BuildingNode("gable_wall",BuildingKind.SEGMENT,"Gable segment",290,660,inputs=listOf("wall_copycat"),module="",length=5,height=2),
            BuildingNode("storey",BuildingKind.STOREY,"Storey",600,30,walls=RoomGeometry.edges(listOf(Rect(width=3,length=3))).associate { it.id to "wall" },floor="floor",ceiling="ceiling",storeyMode="walls"),
            BuildingNode("roof",BuildingKind.ROOF,"Roof",600,560,inputs=listOf("roof_copycat"),walls=mapOf("0" to "gable_wall","2" to "gable_wall"),roofType="gable",role="roof"),
            BuildingNode("building",BuildingKind.BUILDING,"Building",970,100,levels=mapOf(0 to "storey"),roof="roof")
        ))
    }
}
data class WallRun(val id:String,val direction:Int,val cells:List<P>) { val length get()=cells.size }
object RoomGeometry {
    val directions=listOf(P(0,0,-1),P(1,0,0),P(0,0,1),P(-1,0,0))
    private data class Shape(val cells:Set<P>,val edges:List<WallRun>,val corners:Map<P,Pair<P,P>>)
    private val cache=ConcurrentHashMap<List<Rect>,Shape>()
    private fun shape(rectangles:List<Rect>):Shape=cache.getOrPut(rectangles.toList()) {
        require(rectangles.size in 1..16) { "A room needs 1–16 rectangles" }
        rectangles.forEach { require(it.width in 1..32 && it.length in 1..32 && it.x in -32..32 && it.z in -32..32) { "Room rectangle exceeds grid bounds" } }
        val cells=buildSet { rectangles.forEach { r -> for(x in r.x until r.x+r.width) for(z in r.z until r.z+r.length) add(P(x,0,z)) } }
        require(cells.size<=4096) { "Room exceeds 4096 cells" }
        val edges=boundary(cells);val corners=linkedMapOf<P,Pair<P,P>>()
        for(q in 0..3) {
            val normal=directions[q];val next=directions[(q+1)%4]
            cells.filter { it+normal !in cells && it+next !in cells && it+normal+next !in cells }.forEach { p -> corners[p+normal+next]=(p+normal) to (p+next) }
        }
        require(edges.size<=64) { "Room has more than 64 wall runs" }
        if(cache.size>512) cache.clear();Shape(cells,edges,corners)
    }
    /** Wall runs lie immediately outside the supplied footprint, including irregular roofs. */
    fun boundary(cells:Set<P>):List<WallRun> {
        val edges=mutableListOf<WallRun>()
        for(q in 0..3) {
            val normal=directions[q];val along=P(-normal.z,0,normal.x)
            val boundary=cells.filter { it+normal !in cells }.toSet();val done=mutableSetOf<P>()
            for(p in boundary.sortedWith(compareBy({it.z},{it.x}))) {
                if(p in done) continue
                var start=p;while(start+P(-along.x,0,-along.z) in boundary) start+=P(-along.x,0,-along.z)
                val run=mutableListOf<P>();var cursor=start
                while(cursor in boundary) { run+=cursor+normal;done+=cursor;cursor+=along }
                edges+=WallRun("$q:${start.x}:${start.z}",q,run)
            }
        }
        return edges
    }
    fun cells(rects:List<Rect>)=shape(rects).cells
    fun edges(rects:List<Rect>)=shape(rects).edges
    fun corners(rects:List<Rect>)=shape(rects).corners
    fun shell(rects:List<Rect>)=cells(rects)+edges(rects).flatMap { it.cells }+corners(rects).keys
    fun reshape(node:BuildingNode,rects:List<Rect>):BuildingNode {
        val old=edges(node.rectangles);val used=mutableSetOf<String>()
        val walls=edges(rects).associate { edge ->
            val previous=old.filter { it.direction==edge.direction && it.id !in used }.minWithOrNull(compareBy<WallRun>({ if(it.id==edge.id) 0 else if(it.length==edge.length) 1 else 2 },{ kotlin.math.abs(it.cells.first().x-edge.cells.first().x)+kotlin.math.abs(it.cells.first().z-edge.cells.first().z) }))
            if(previous!=null) used+=previous.id
            edge.id to (previous?.let { node.walls[it.id] }.orEmpty().ifEmpty { "empty_wall" })
        }
        return node.copy(rectangles=rects,walls=walls)
    }
}
data class BuildingPort(val key:String,val source:String,val accepts:Set<BuildingKind>,val number:Int=0,val length:Int=0,val height:Int=0)
object BuildingPorts {
    private val segments=setOf(BuildingKind.SEGMENT,BuildingKind.PATTERN)
    private fun copies(n:BuildingNode,expanding:Boolean)=inputs(n,setOf(BuildingKind.COPYCAT),expanding)
    private fun inputs(n:BuildingNode,kinds:Set<BuildingKind>,expanding:Boolean)=
        (n.inputs+if(expanding && n.inputs.size<16 && n.inputs.none { it.isEmpty() }) listOf("") else emptyList()).mapIndexed { i,s -> BuildingPort("in:$i",s,kinds,i) }
    private fun enclosure(n:BuildingNode)=RoomGeometry.edges(n.rectangles).mapIndexed { i,e -> BuildingPort("wall:${e.id}",n.walls[e.id].orEmpty().ifEmpty { "empty_wall" },segments,i,e.length,n.height) }+
        listOf(BuildingPort("floor",n.floor,setOf(BuildingKind.FLOOR)),BuildingPort("ceiling",n.ceiling,setOf(BuildingKind.CEILING)),BuildingPort("corner",n.corner,setOf(BuildingKind.COPYCAT)))
    fun of(n:BuildingNode,expanding:Boolean=false):List<BuildingPort> = when(n.kind) {
        BuildingKind.SEGMENT -> if(n.id=="empty_wall") emptyList() else copies(n,expanding)
        BuildingKind.FLOOR,BuildingKind.CEILING -> copies(n,expanding)
        BuildingKind.PATTERN -> n.inputs.mapIndexed { i,s -> BuildingPort("in:$i",s,segments,i) }
        BuildingKind.ROOM -> enclosure(n)
        BuildingKind.STOREY -> if(n.storeyMode=="walls") enclosure(n) else inputs(n,setOf(BuildingKind.ROOM),expanding)
        BuildingKind.ROOF -> copies(n,expanding)+(0..3).map { BuildingPort("wall:$it",n.walls[it.toString()].orEmpty(),segments,it) }
        BuildingKind.BUILDING -> {
            val levels=n.levels.keys+0+(if(expanding) setOf((n.levels.keys.maxOrNull() ?: 0)+1,(n.levels.keys.minOrNull() ?: 0)-1) else emptySet())
            listOf(BuildingPort("roof",n.roof,setOf(BuildingKind.ROOF)))+levels.filter { it in -8..8 }.sortedDescending().map { i -> BuildingPort("level:$i",n.levels[i].orEmpty(),setOf(BuildingKind.STOREY),i) }
        }
        else -> emptyList()
    }
}
data class SegmentCell(val cell:Cell,val overrideRole:String="")
data class FixedSegment(val length:Int,val height:Int,val cells:List<SegmentCell>,val air:Boolean=false)
data class CompiledRoom(val plan:Plan,val footprint:Set<P>,val shell:Set<P>,val minimum:Int,val maximum:Int,val bottomThickness:Int,val topThickness:Int,val usableHeight:Int)
data class CompiledStorey(val plan:Plan,val footprint:Set<P>,val minimum:Int,val maximum:Int,val bottomThickness:Int,val topThickness:Int)
data class StoreyPosition(val level:Int,val base:Int,val minimum:Int,val maximum:Int)
data class CompiledBuilding(val plan:Plan,val storeys:List<StoreyPosition>)
object BuildingGraphCompiler {
    fun structure(graph:BuildingGraph) {
        require(graph.schema==1 && graph.nodes.size in 1..96) { "Building graph needs 1–96 nodes" }
        val nodes=graph.nodes.associateBy { it.id }
        require(nodes.size==graph.nodes.size && nodes.keys.all { it.matches(Regex("[a-zA-Z0-9_-]{1,36}")) }) { "Building node IDs must be unique" }
        require(graph.nodes.count { it.kind==BuildingKind.BUILDING }==1) { "Keep exactly one building result" }
        require(nodes["empty_wall"]?.let { it.kind==BuildingKind.SEGMENT && it.block=="minecraft:air" && it.inputs.isEmpty() }==true) { "Keep the standard Empty wall source" }
        for(n in graph.nodes) {
            require(n.preview in listOf("all","walls","floor","ceiling","roof")) { "Invalid building preview filter" }
            require(n.name.length<=64 && n.x in -8192..8192 && n.y in -8192..8192 && n.materialMode in MaterialMode.entries) { "Invalid building node" }
            require(n.role.matches(Regex("[a-zA-Z0-9_-]{1,32}"))) { "Invalid node material role" }
            require(n.length in 1..128 && n.height in 1..16 && n.thickness in 1..4 && n.offsets.size<=16 && n.inputs.size<=16) { "Invalid fixed dimensions" }
            require(n.offsets.all { it.y==0 && it.x in -64..64 && it.z in -64..64 }) { "Room offset exceeds storey bounds" }
            if(n.kind==BuildingKind.PATTERN) require(n.inputs.size in 2..16) { "A pattern needs 2–16 ordered segment inputs" }
            if(n.kind==BuildingKind.STOREY) {
                require(n.storeyMode in listOf("rooms","walls")) { "Invalid storey assembly mode" }
                if(n.storeyMode=="rooms") require(n.inputs.size in 1..16) { "A storey needs 1–16 room inputs" }
            }
            if(n.kind==BuildingKind.ROOM || n.kind==BuildingKind.STOREY && n.storeyMode=="walls") { RoomGeometry.cells(n.rectangles);require(n.walls.size<=64) }
            if(n.kind==BuildingKind.BUILDING) require(n.levels.size<=17 && n.levels.keys.all { it in -8..8 }) { "At most eight upper and eight lower storeys" }
            if(n.kind==BuildingKind.ROOF) require(n.roofType in listOf("flat","gable") && n.ridge in 0..1 && n.overhang in 0..2 && n.walls.keys.all { it in setOf("0","1","2","3") })
            require(n.finish.isEmpty() || n.kind==BuildingKind.FLOOR && n.finish in CARPETS && n.thickness>=2) { "Carpet floors need a structural layer below the carpet" }
            for(p in BuildingPorts.of(n)) if(p.source.isNotEmpty()) {
                val source=nodes[p.source] ?: error("Missing source for ${n.name.ifEmpty { n.kind.name }}")
                require(source.kind in p.accepts) { "Incompatible ${source.kind} → ${n.kind} socket ${p.key}" }
            }
        }
        val done=mutableSetOf<String>();val active=mutableSetOf<String>()
        fun visit(id:String) { if(id in done) return;require(active.add(id)) { "Cycle in building graph" };BuildingPorts.of(nodes.getValue(id)).filter { it.source.isNotEmpty() }.forEach { visit(it.source) };active.remove(id);done+=id }
        nodes.keys.forEach { visit(it) }
    }
    val CARPETS=listOf("white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black").map { "minecraft:${it}_carpet" }
    private fun override(plan:Plan,n:BuildingNode,palette:Map<String,String>):Plan {
        if(n.materialMode!=MaterialMode.GROUPED) return plan
        val material=palette[n.role] ?: error("Missing material role ${n.role}")
        val resolver=Resolver();plan.placements.forEach { p -> resolver.add(if(p.block.isEmpty()) p.copy(material=material,surfaces=p.groups.associate { it.id to material }) else p) };plan.clear.forEach { resolver.clear(it) };return resolver.plan(plan.warnings)
    }
    class Evaluation(val graph:BuildingGraph,val palette:Map<String,String>,val forms:Map<String,Form>,val modules:Map<String,Module>) {
        private val nodes=graph.nodes.associateBy { it.id }
        private val segments=mutableMapOf<String,FixedSegment>();private val rooms=mutableMapOf<String,CompiledRoom>();private val storeys=mutableMapOf<String,CompiledStorey>()
        init { structure(graph) }
        fun node(id:String,kind:BuildingKind?=null):BuildingNode { val n=nodes[id] ?: error("Connect the missing input");require(kind==null || n.kind==kind);return n }
        private fun copycats(n:BuildingNode):List<BuildingNode> {
            require(n.inputs.all { it.isNotEmpty() }) { "Connect every Copycat input" }
            return n.inputs.map { node(it,BuildingKind.COPYCAT) }
        }
        fun segment(id:String):FixedSegment=segments.getOrPut(id) {
            val n=node(id);require(n.kind in setOf(BuildingKind.SEGMENT,BuildingKind.PATTERN))
            if(n.kind==BuildingKind.SEGMENT) {
                if(n.inputs.isNotEmpty()) {
                    val copies=copycats(n)
                    FixedSegment(n.length,n.height,(0 until n.length).flatMap { x -> (0 until n.height).map { y ->
                        val source=copies[(y*n.length+x)%copies.size]
                        SegmentCell(Cell(P(x,y,0),form=source.form,role=source.role),if(n.materialMode==MaterialMode.GROUPED) n.role else "")
                    } })
                }
                else if(n.block.isNotEmpty()) FixedSegment(n.length,n.height,if(n.block=="minecraft:air") emptyList() else (0 until n.length).flatMap { x -> (0 until n.height).map { y -> SegmentCell(Cell(P(x,y,0),block=n.block,role=n.role)) } },n.block=="minecraft:air")
                else if(n.module.isEmpty()) FixedSegment(n.length,n.height,(0 until n.length).flatMap { x -> (0 until n.height).map { y -> SegmentCell(Cell(P(x,y,0),form=n.form,role=n.role)) } })
                else { val m=modules[n.module]?.validate() ?: error("Missing wall segment ${n.module}");require(m.depth==1) { "Wall segments must have depth 1" };FixedSegment(m.width,m.height,m.cells.map { SegmentCell(it) }) }
            } else {
                require(n.inputs.all { it.isNotEmpty() }) { "Connect every wall pattern input" }
                val values=n.inputs.map { segment(it) };val heights=values.filter { !it.air }.map { it.height }.distinct()
                require(heights.size<=1) { "Pattern segments must have the same fixed height" }
                val height=heights.firstOrNull() ?: n.height
                val total=values.sumOf { it.length };require(total<=128) { "Wall pattern exceeds 128 blocks" }
                var cursor=0;val cells=mutableListOf<SegmentCell>()
                for(v in values) {
                    if(v.air) for(x in 0 until v.length) for(y in 0 until height) cells+=SegmentCell(Cell(P(cursor+x,y,0),block="minecraft:air",empty=true))
                    else cells+=v.cells.map { it.copy(cell=it.cell.copy(pos=it.cell.pos+P(cursor,0,0))) }
                    cursor+=v.length
                }
                FixedSegment(total,height,cells.map { if(n.materialMode==MaterialMode.GROUPED) it.copy(overrideRole=n.role) else it })
            }
        }
        private fun placement(pos:P,form:String,role:String,type:String,turn:Int=0,forced:String=""):Placement {
            val f=Geometry.validate(forms[form] ?: error("Missing form $form"));val material=palette[forced.ifEmpty { role }] ?: error("Missing material role $role")
            return Placement(pos,Geometry.rotate(f.parts,turn),material,component=type,groups=f.groups,surfaces=f.groups.associate { it.id to if(forced.isNotEmpty()) material else palette[if(it.role=="surface") role else it.role].orEmpty().ifEmpty { error("Missing material role ${it.role}") } })
        }
        fun room(id:String):CompiledRoom=rooms.getOrPut(id) { enclosure(node(id,BuildingKind.ROOM)) }
        private fun enclosure(n:BuildingNode):CompiledRoom {
            val floor=node(n.floor,BuildingKind.FLOOR);val ceiling=node(n.ceiling,BuildingKind.CEILING)
            val floorCopies=copycats(floor);val ceilingCopies=copycats(ceiling)
            val usable=n.height-(if(floor.occupied) 1 else 0)-(if(ceiling.occupied) 1 else 0);require(usable>0) { "Room has no buildable height after floor/ceiling layers" }
            if(floor.finish.isNotEmpty()) {
                for(source in floorCopies.ifEmpty { listOf(floor) }) {
                    val support=Geometry.solid(Geometry.validate(forms[source.form] ?: error("Missing floor form ${source.form}")).parts)
                    require((0..15).all { x -> (0..15).all { z -> support.has(x,15,z) } }) { "Carpet finish needs a floor with a complete top face" }
                }
            }
            val footprint=RoomGeometry.cells(n.rectangles);val shell=RoomGeometry.shell(n.rectangles);val r=Resolver();val wallPlacements=linkedMapOf<P,Placement>()
            for(edge in RoomGeometry.edges(n.rectangles)) {
                val source=n.walls[edge.id].orEmpty().ifEmpty { "empty_wall" };val s=segment(source)
                require(s.air || s.length==edge.length && s.height==n.height) { "Wall ${edge.id}: needs ${edge.length} × ${n.height}, got ${s.length} × ${s.height}" }
                if(s.air) for(p in edge.cells) for(y in 0 until n.height) r.clear(p+P(0,y,0))
                else for(entry in s.cells) {
                    val c=entry.cell;val p=edge.cells[c.pos.x]+P(0,c.pos.y,0)
                    if(c.empty || c.block=="minecraft:air") r.clear(p)
                    else {
                        val wall=if(c.block.isEmpty()) placement(p,c.form,c.role,"wall",edge.direction+c.turn,entry.overrideRole) else Placement(p,block=c.block,material="",component="wall")
                        r.add(wall,true);wallPlacements[p]=wall
                    }
                }
            }
            // Close convex joints only where an incident wall actually contributes geometry.
            val cornerSource=n.corner.takeIf(String::isNotEmpty)?.let { node(it,BuildingKind.COPYCAT) }
            for((corner,adjacent) in RoomGeometry.corners(n.rectangles)) for(y in 0 until n.height) {
                val first=wallPlacements[adjacent.first+P(0,y,0)];val second=wallPlacements[adjacent.second+P(0,y,0)]
                val template=first ?: second;val target=corner+P(0,y,0)
                if(template==null) r.clear(target)
                else if(cornerSource!=null) {
                    val dx=adjacent.first.x+adjacent.second.x-2*corner.x;val dz=adjacent.first.z+adjacent.second.z-2*corner.z
                    val turn=when { dx>0 && dz>0->0;dx<0 && dz>0->1;dx<0 && dz<0->2;else->3 }
                    r.add(placement(target,cornerSource.form,cornerSource.role,"trim",turn),true)
                } else {
                    r.add(template.copy(pos=target),true)
                    // A panel needs both perpendicular faces; a full cube already covers the joint.
                    if(first!=null && second!=null && first.block.isEmpty() && second.block.isEmpty() &&
                        first.material==second.material && first.groups==second.groups && first.surfaces==second.surfaces)
                        r.add(second.copy(pos=target),true)
                }
            }
            val minimum=(if(floor.occupied) 1 else 0)-floor.thickness
            val ceilingStart=n.height-(if(ceiling.occupied) 1 else 0);val maximum=ceilingStart+ceiling.thickness
            fun layer(layer:BuildingNode,isFloor:Boolean) {
                val copies=(if(isFloor) floorCopies else ceilingCopies).ifEmpty { listOf(layer) }
                val first=if(isFloor) minimum else ceilingStart
                for(t in 0 until layer.thickness) {
                    val y=first+t;val area=if(y in 0 until n.height) footprint else shell
                    for((index,p) in area.sortedWith(compareBy({it.z},{it.x})).withIndex()) {
                        val target=p+P(0,y,0)
                        if(isFloor && layer.finish.isNotEmpty() && t==layer.thickness-1) r.add(Placement(target,block=layer.finish,material="",component="floor"))
                        else {
                            val source=copies[(t*area.size+index)%copies.size]
                            r.add(placement(target,source.form,source.role,if(isFloor) "floor" else "ceiling",forced=if(layer.materialMode==MaterialMode.GROUPED) layer.role else ""))
                        }
                    }
                }
            }
            layer(floor,true);layer(ceiling,false)
            for(p in footprint) for(y in (if(floor.occupied) 1 else 0) until n.height-(if(ceiling.occupied) 1 else 0)) r.clear(p+P(0,y,0))
            return CompiledRoom(override(r.plan(emptyList()),n,palette),footprint,shell,minimum,maximum,(floor.thickness-if(floor.occupied || floor.finish.isNotEmpty()) 1 else 0).coerceAtLeast(0),(ceiling.thickness-if(ceiling.occupied) 1 else 0).coerceAtLeast(0),usable)
        }
        fun storey(id:String):CompiledStorey=storeys.getOrPut(id) {
            val n=node(id,BuildingKind.STOREY)
            if(n.storeyMode=="walls") {
                val enclosed=enclosure(n)
                return@getOrPut CompiledStorey(enclosed.plan,enclosed.shell,enclosed.minimum,enclosed.maximum,enclosed.bottomThickness,enclosed.topThickness)
            }
            require(n.inputs.isNotEmpty() && n.inputs.all { it.isNotEmpty() }) { "Connect every room input of the storey" }
            val r=Resolver();val footprint=linkedSetOf<P>();val shell=linkedSetOf<P>();var minimum=0;var maximum=0;var bottom=4;var top=4
            n.inputs.forEachIndexed { i,source ->
                val room=room(source);val offset=n.offsets.getOrNull(i) ?: P()
                val translated=room.footprint.map { it+offset }.toSet()
                require(translated.none { it in footprint }) { "Room interiors overlap; drag them apart in the storey preview" };footprint+=translated;shell+=room.shell.map { it+offset }
                room.plan.placements.forEach { r.add(it.copy(pos=it.pos+offset),true) };room.plan.clear.forEach { r.clear(it+offset) }
                minimum=minOf(minimum,room.minimum);maximum=maxOf(maximum,room.maximum);bottom=minOf(bottom,room.bottomThickness);top=minOf(top,room.topThickness)
            }
            CompiledStorey(override(r.plan(emptyList()),n,palette),shell,minimum,maximum,bottom,top)
        }
        fun building():CompiledBuilding {
            val n=graph.nodes.single { it.kind==BuildingKind.BUILDING };require(n.levels[0]?.isNotEmpty()==true) { "Connect the ground floor" };require(n.levels.values.all { it.isNotEmpty() })
            val roof=node(n.roof,BuildingKind.ROOF);val r=Resolver();val positions=mutableListOf<StoreyPosition>();var previous:CompiledStorey?=null;var previousBase=0
            var last:CompiledStorey?=null;var roofBase=0
            for((level,source) in n.levels.toSortedMap()) {
                val story=storey(source)
                val base=previous?.let { previousBase+it.maximum-story.minimum-minOf(it.topThickness,story.bottomThickness) } ?: -story.minimum
                story.plan.placements.forEach { r.add(it.copy(pos=it.pos+P(0,base,0)),true) };story.plan.clear.forEach { r.clear(it+P(0,base,0)) }
                positions+=StoreyPosition(level,base,base+story.minimum,base+story.maximum)
                previous=story;previousBase=base;last=story;roofBase=base+story.maximum
            }
            val footprint=last!!.footprint;val coverage=buildSet { for(p in footprint) for(dx in -roof.overhang..roof.overhang) for(dz in -roof.overhang..roof.overhang) add(p+P(dx,0,dz)) }
            require(coverage.size<=8192) { "Roof exceeds 8192 cells" }
            val copies=copycats(roof)
            val ordered=coverage.sortedWith(compareBy({it.z},{it.x}))
            val heights=linkedMapOf<P,Pair<Int,Int>>()
            if(roof.roofType=="flat") for((index,p) in ordered.withIndex()) for(t in 0 until roof.thickness) {
                val source=copies.getOrNull((t*ordered.size+index)%copies.size.coerceAtLeast(1)) ?: roof
                r.add(placement(p+P(0,roofBase+t,0),source.form,source.role,"roof",forced=if(roof.materialMode==MaterialMode.GROUPED) roof.role else ""))
            }
            else {
                for(p in coverage) {
                    val d=if(roof.ridge==0) P(1,0,0) else P(0,0,1);var a=p;var b=p
                    while(a+P(-d.x,0,-d.z) in coverage) a+=P(-d.x,0,-d.z)
                    while(b+d in coverage) b+=d
                    val left=if(roof.ridge==0) p.x-a.x else p.z-a.z;val right=if(roof.ridge==0) b.x-p.x else b.z-p.z
                    heights[p]=minOf(left,right) to if(left==right) -1 else if(roof.ridge==0) if(left<right) 3 else 1 else if(left<right) 0 else 2
                }
                for((index,p) in ordered.withIndex()) {
                    val profile=heights.getValue(p)
                    val (h,turn)=profile
                    val part=Part(if(turn<0) "copycats:copycat_slab" else "copycats:copycat_stairs",turn.coerceAtLeast(0))
                    for(t in 0 until roof.thickness) {
                        val source=copies.getOrNull((t*ordered.size+index)%copies.size.coerceAtLeast(1))
                        r.add(if(source==null) Placement(p+P(0,roofBase+h+t,0),listOf(part),palette[roof.role] ?: error("Missing roof material"),component="roof")
                            else placement(p+P(0,roofBase+h+t,0),source.form,source.role,"roof",turn.coerceAtLeast(0),if(roof.materialMode==MaterialMode.GROUPED) roof.role else ""))
                    }
                    val lowest=RoomGeometry.directions.minOf { heights[p+it]?.first ?: -1 }
                    val explicitWall=RoomGeometry.directions.indices.any { q -> p+RoomGeometry.directions[q] !in coverage && roof.walls[q.toString()].orEmpty().isNotEmpty() }
                    if(!explicitWall) for(y in (lowest+1).coerceAtLeast(0) until h) r.add(placement(p+P(0,roofBase+y,0),"full@1",if(lowest<0) "gable" else roof.role,if(lowest<0) "gable" else "roof"))
                }
            }
            for(edge in RoomGeometry.boundary(coverage)) {
                val source=roof.walls[edge.direction.toString()].orEmpty();if(source.isEmpty()) continue
                val s=segment(source);val normal=RoomGeometry.directions[edge.direction]
                val cells=edge.cells.map { it+P(-normal.x,0,-normal.z) }
                require(s.air || s.length==edge.length) { "Roof wall ${edge.direction}: needs length ${edge.length}, got ${s.length}" }
                val needed=if(roof.roofType=="flat") s.height else cells.maxOf { heights.getValue(it).first }
                require(s.air || s.height>=needed) { "Roof wall ${edge.direction}: needs height $needed, got ${s.height}" }
                val base=roofBase+if(roof.roofType=="flat") roof.thickness else 0
                if(s.air) for(p in cells) for(y in 0 until if(roof.roofType=="flat") needed else heights.getValue(p).first) r.clear(p+P(0,base+y,0))
                else for(entry in s.cells) {
                    val c=entry.cell;val p=cells[c.pos.x]
                    if(roof.roofType!="flat" && c.pos.y>=heights.getValue(p).first) continue
                    val target=p+P(0,base+c.pos.y,0)
                    if(c.empty || c.block=="minecraft:air") r.clear(target)
                    else r.add(if(c.block.isEmpty()) placement(target,c.form,c.role,if(roof.roofType=="flat") "roof" else "gable",edge.direction+c.turn,
                        if(roof.materialMode==MaterialMode.GROUPED) roof.role else entry.overrideRole) else Placement(target,block=c.block,material="",component=if(roof.roofType=="flat") "roof" else "gable"),true)
                }
            }
            return CompiledBuilding(override(r.plan(emptyList()),n,palette),positions)
        }
    }
    fun compile(b:Building,forms:Map<String,Form>,modules:Map<String,Module>):CompiledBuilding {
        require(b.name.length in 1..64 && b.palette.size<=16 && b.schema in 1..2)
        return Evaluation(b.graph ?: error("Missing building graph"),b.palette,forms,modules).building()
    }
}
