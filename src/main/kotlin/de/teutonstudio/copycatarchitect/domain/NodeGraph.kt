package de.teutonstudio.copycatarchitect.domain

/** Edges point to source node IDs. Empty strings are unconnected editor sockets. */
enum class NodeKind { INPUT, UNION, INTERSECTION, DIFFERENCE, SYMMETRIC_DIFFERENCE, ROTATE, ROTATION, OUTPUT }
enum class MaterialMode { SEPARATED, GROUPED }
data class GraphNode(val id:String="",val kind:NodeKind=NodeKind.INPUT,val inputs:List<String> = emptyList(),
                     val part:Part=Part(),val x:Int=0,val y:Int=0,
                     val materialMode:MaterialMode=MaterialMode.SEPARATED,val materialGroup:String="",val previewGroup:String="",
                     val inputGroups:List<String> = emptyList(),val rotationAxis:RotationAxis=RotationAxis.NORMAL,val quarterTurns:Int=0)
data class NodeGraph(val schema:Int=2,val nodes:List<GraphNode> = listOf(GraphNode("result",NodeKind.OUTPUT,listOf(""),x=520,y=70))) {
    fun replace(node:GraphNode)=copy(nodes=nodes.map { if(it.id==node.id) node else it })
    fun remove(id:String)=copy(nodes=nodes.filter { it.id!=id }.map { it.copy(inputs=it.inputs.map { input -> if(input==id) "" else input }) })
    companion object {
        fun fromParts(parts:List<Part>):NodeGraph {
            val nodes=parts.mapIndexed { i,p -> GraphNode("source$i",part=p,x=20+(i/4)*185,y=20+(i%4)*152) }.toMutableList()
            var level=nodes.map { it.id };var count=0;var x=250
            while(level.size>1) {
                level=level.chunked(16).mapIndexed { i,ids ->
                    if(ids.size==1) ids[0] else {
                        val n=GraphNode("union${count++}",NodeKind.UNION,ids,x=x,y=40+i*260);nodes+=n;n.id
                    }
                };x+=200
            }
            nodes+=GraphNode("result",NodeKind.OUTPUT,listOf(level.firstOrNull().orEmpty()),x=x+30,y=65)
            return NodeGraph(nodes=nodes)
        }
    }
}

/** Boolean operations preserve leaf provenance, so materials and paid recipes follow the surviving solids. */
object GraphCompiler {
    private data class Key(val graph:NodeGraph,val groups:List<MaterialGroup>)
    private val cache=java.util.concurrent.ConcurrentHashMap<Key,List<Part>>()
    private fun supportsInputGroups(kind:NodeKind)=kind==NodeKind.UNION || kind==NodeKind.SYMMETRIC_DIFFERENCE
    /** Empty saved assignments inherit the connected branch, including legacy source materials. */
    fun inputMaterialGroups(graph:NodeGraph,node:GraphNode,slot:Int,groups:List<MaterialGroup> = DEFAULT_GROUPS):Set<String> {
        val nodes=graph.nodes.associateBy { it.id }
        val active=mutableSetOf<String>()
        fun assigned(n:GraphNode)=n.materialGroup.ifEmpty { groups.first().id }
        lateinit var output:(String)->Set<String>
        fun input(n:GraphNode,index:Int):Set<String> {
            val explicit=if(supportsInputGroups(n.kind)) n.inputGroups.getOrNull(index).orEmpty() else ""
            if(explicit.isNotEmpty()) return setOf(explicit)
            return n.inputs.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { output(it) } ?: setOf(groups.first().id)
        }
        output={ id ->
            val n=nodes[id]
            if(n==null || !active.add(id)) emptySet() else {
                val result=when(n.kind) {
                    NodeKind.INPUT->setOf(n.part.group)
                    NodeKind.ROTATION->emptySet()
                    NodeKind.OUTPUT,NodeKind.ROTATE->input(n,0)
                    else->if(n.materialMode==MaterialMode.GROUPED) setOf(assigned(n)) else when(n.kind) {
                        NodeKind.INTERSECTION,NodeKind.DIFFERENCE->input(n,0)
                        else->n.inputs.indices.flatMap { input(n,it) }.toSet()
                    }
                }
                active.remove(id);result
            }
        }
        return input(node,slot)
    }
    fun uniformSeparatedGroups(graph:NodeGraph,node:GraphNode,groups:List<MaterialGroup> = DEFAULT_GROUPS):Boolean =
        supportsInputGroups(node.kind) && node.materialMode==MaterialMode.SEPARATED && node.inputs.size>=2 &&
            node.inputs.indices.map { inputMaterialGroups(graph,node,it,groups) }.let { values -> values.all { it.size==1 } && values.distinct().size==1 }

    fun structure(graph:NodeGraph,groups:List<MaterialGroup>) {
        require(graph.schema in 1..2 && graph.nodes.size in 1..64) { "Node graph needs 1–64 nodes (schema 1–2)" }
        val nodes=graph.nodes.associateBy { it.id }
        require(nodes.size==graph.nodes.size && nodes.keys.all { it.matches(Regex("[a-zA-Z0-9_-]{1,36}")) }) { "Node IDs must be unique" }
        require(graph.nodes.count { it.kind==NodeKind.OUTPUT }==1) { "Node graph needs exactly one result node" }
        val leaves=graph.nodes.filter { it.kind==NodeKind.INPUT }
        require(leaves.size<=32) { "At most 32 source nodes" };Geometry.validateGroups(groups,leaves.map { it.part })
        graph.nodes.forEach { n ->
            require(n.materialMode in MaterialMode.entries) { "Invalid node material mode" }
            require(n.materialGroup.isEmpty() || groups.any { it.id==n.materialGroup }) { "Unknown node material group" }
            require(n.previewGroup.isEmpty() || groups.any { it.id==n.previewGroup }) { "Unknown preview material group" }
            require(n.inputGroups.size<=n.inputs.size && n.inputGroups.all { id -> id.isEmpty() || groups.any { it.id==id } }) { "Unknown input material group" }
            require(n.rotationAxis in RotationAxis.entries && n.quarterTurns in 0..3) { "Invalid node rotation" }
            require(n.x in -8192..8192 && n.y in -8192..8192) { "Node position exceeds graph bounds" }
            require(when(n.kind) {
                NodeKind.INPUT,NodeKind.ROTATION -> n.inputs.isEmpty()
                NodeKind.OUTPUT -> n.inputs.size==1
                NodeKind.DIFFERENCE,NodeKind.ROTATE -> n.inputs.size==2
                NodeKind.UNION,NodeKind.INTERSECTION,NodeKind.SYMMETRIC_DIFFERENCE -> n.inputs.size in 2..16
            }) { "Invalid socket count on ${n.id}" }
            if(n.kind==NodeKind.INPUT) {
                require(n.part.cutMask.isEmpty() && n.part.recipeSource.isEmpty()) { "Source nodes must use standard Copycat shapes" };Solid.part(n.part)
            }
            n.inputs.forEachIndexed { slot,input -> if(input.isNotEmpty()) {
                require(nodes[input]!=null && nodes[input]?.kind!=NodeKind.OUTPUT) { "Unknown or invalid source at ${n.id}" }
                val rotationSocket=n.kind==NodeKind.ROTATE && slot==1
                require((nodes.getValue(input).kind==NodeKind.ROTATION)==rotationSocket) { "Incompatible socket types at ${n.id}" }
            } }
        }
        val active=mutableSetOf<String>();val done=mutableSetOf<String>()
        fun visit(id:String) {
            if(id in done) return
            require(active.add(id)) { "Cycle in node graph at $id" }
            nodes.getValue(id).inputs.filter { it.isNotEmpty() }.forEach { visit(it) }
            active.remove(id);done.add(id)
        }
        nodes.keys.forEach { visit(it) }
    }
    fun compile(graph:NodeGraph,groups:List<MaterialGroup> = DEFAULT_GROUPS):List<Part> {
        val key=Key(graph,groups.toList())
        cache[key]?.let { return it }
        structure(graph,groups)
        val nodes=graph.nodes.associateBy { it.id }
        data class Contribution(val leaf:String,val group:String,val part:Part)
        val memo=mutableMapOf<String,Map<Contribution,Solid>>()
        fun total(value:Map<Contribution,Solid>)=value.values.fold(Solid()) { a,b -> a.union(b) }
        fun evaluate(id:String):Map<Contribution,Solid> = memo.getOrPut(id) {
            val n=nodes.getValue(id)
            if(n.kind==NodeKind.INPUT) mapOf(Contribution(id,n.part.group,n.part) to Solid.part(n.part)) else {
                require(n.inputs.withIndex().all { (slot,input) -> input.isNotEmpty() || (n.kind==NodeKind.ROTATE && slot==1) }) { "Connect every input of ${n.kind.name.lowercase()} node (${n.id.take(8)})" }
                if(n.kind==NodeKind.ROTATE) {
                    val rotation=n.inputs[1].takeIf { it.isNotEmpty() }?.let(nodes::getValue) ?: n
                    val contributions=linkedMapOf<Contribution,Solid>()
                    evaluate(n.inputs[0]).forEach { (source,solid) ->
                        val clipped=source.part.copy(cutMask=if(solid.contains(Solid.part(source.part))) "" else solid.mask())
                        val rotated=PartRotations.rotate(clipped,rotation.rotationAxis,rotation.quarterTurns)
                            ?: error("Copycat ${source.part.source} cannot rotate around ${rotation.rotationAxis}")
                        val key=source.copy(part=rotated.copy(cutMask=""))
                        contributions[key]=(contributions[key] ?: Solid()).union(Solid.part(rotated))
                    }
                    contributions
                } else {
                require(n.kind!=NodeKind.ROTATION) { "A rotation value needs a rotation input" }
                val args=n.inputs.map { evaluate(it) }
                if(n.kind==NodeKind.OUTPUT) args[0] else {
                    val visible=when(n.kind) {
                        NodeKind.INTERSECTION -> args.drop(1).fold(total(args[0])) { a,b -> a.intersect(total(b)) }
                        NodeKind.DIFFERENCE -> total(args[0]).minus(total(args[1]))
                        NodeKind.SYMMETRIC_DIFFERENCE -> args.fold(Solid()) { a,b -> val other=total(b);a.minus(other).union(other.minus(a)) }
                        else -> args.fold(Solid()) { a,b -> a.union(total(b)) }
                    }
                    val contributions=linkedMapOf<Contribution,Solid>()
                    (if(n.kind==NodeKind.DIFFERENCE || n.kind==NodeKind.INTERSECTION) args.take(1) else args).forEachIndexed { slot,value -> value.forEach { (source,s) ->
                        val clipped=s.intersect(visible)
                        val assigned=if(supportsInputGroups(n.kind)) n.inputGroups.getOrNull(slot).orEmpty() else ""
                        val key=when {
                            n.materialMode==MaterialMode.GROUPED->source.copy(group=n.materialGroup.ifEmpty { groups.first().id })
                            assigned.isNotEmpty()->source.copy(group=assigned)
                            else->source
                        }
                        if(clipped.count()>0) contributions[key]=(contributions[key] ?: Solid()).union(clipped)
                    } }
                    contributions
                }
                }
            }
        }
        val result=evaluate(graph.nodes.single { it.kind==NodeKind.OUTPUT }.id)
        require(result.isNotEmpty()) { "Result is empty; change the connected shapes" }
        val splitLeaves=result.keys.groupingBy { it.leaf }.eachCount().filterValues { it>1 }.keys
        // Keep an already shared source ahead of a redundant cutter contribution.
        // Its material regions must share one recipe even after normalization.
        val parts=result.entries.sortedWith(compareByDescending<Map.Entry<Contribution,Solid>> { it.key.leaf in splitLeaves }.thenBy { it.key.leaf }.thenBy { it.key.group }).map { (source,solid) ->
            val p=source.part
            p.copy(group=source.group,cutMask=if(solid.contains(Solid.part(p))) "" else solid.mask(),recipeSource=if(source.leaf in splitLeaves) source.leaf else "")
        }
        val normalized=Geometry.normalized(parts,groups)
        if(cache.size>512) cache.clear()
        cache[key]=normalized
        return normalized
    }
}
