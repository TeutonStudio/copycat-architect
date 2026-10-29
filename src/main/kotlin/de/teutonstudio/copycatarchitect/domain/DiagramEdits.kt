package de.teutonstudio.copycatarchitect.domain

/** A presentation change contains references, never a replacement geometry or building recipe. */
data class DiagramPosition(val id:String,val x:Int,val y:Int)
data class DiagramConnection(val source:String,val target:String,val port:String)

object DiagramEdits {
    private fun validate(known:Set<String>,positions:List<DiagramPosition>,connections:List<DiagramConnection>) {
        val ids=positions.map { it.id }
        require(ids.size==ids.toSet().size && ids.all { it in known }) { "Unknown or duplicated diagram node" }
        require(connections.all { it.source in ids && it.target in ids }) { "Unknown diagram connection" }
        require(connections.map { it.target to it.port }.distinct().size==connections.size) { "An input accepts one connection" }
    }
    fun shape(original:NodeGraph,groups:List<MaterialGroup>,positions:List<DiagramPosition>,connections:List<DiagramConnection>):NodeGraph {
        validate(original.nodes.map { it.id }.toSet(),positions,connections)
        require(original.nodes.single { it.kind==NodeKind.OUTPUT }.id in positions.map { it.id }) { "Keep the result node" }
        val ports=original.nodes.associate { it.id to it.inputs.indices.map(Int::toString).toSet() }
        require(connections.all { it.port in ports.getValue(it.target) }) { "Unknown shape input" }
        val byId=positions.associateBy { it.id };val wires=connections.associateBy { it.target to it.port }
        val changed=original.copy(nodes=original.nodes.filter { it.id in byId }.map { n ->
            val p=byId.getValue(n.id)
            n.copy(x=p.x,y=p.y,inputs=n.inputs.mapIndexed { i,_ -> wires[n.id to i.toString()]?.source.orEmpty() })
        })
        GraphCompiler.structure(changed,groups)
        return changed
    }
    fun building(original:BuildingGraph,positions:List<DiagramPosition>,connections:List<DiagramConnection>):BuildingGraph {
        validate(original.nodes.map { it.id }.toSet(),positions,connections)
        val byId=positions.associateBy { it.id }
        require(original.nodes.single { it.kind==BuildingKind.BUILDING }.id in byId && "empty_wall" in byId) { "Keep the result and Empty wall source" }
        val ports=original.nodes.associate { it.id to BuildingPorts.of(it,true).associateBy(BuildingPort::key) }
        require(connections.all { it.port in ports.getValue(it.target) }) { "Unknown building input" }
        val wires=connections.associateBy { it.target to it.port }
        val changed=original.copy(nodes=original.nodes.filter { it.id in byId }.map { n ->
            val p=byId.getValue(n.id);var next=n.copy(x=p.x,y=p.y)
            val changedPorts=ports.getValue(n.id).values.filter { port ->
                val source=wires[n.id to port.key]?.source.orEmpty()
                source!=port.source && !(source.isEmpty() && port.source=="empty_wall" && port.key.startsWith("wall:"))
            }
            for(port in changedPorts) {
                val source=wires[n.id to port.key]?.source.orEmpty()
                if(source.isNotEmpty()) require(original.nodes.first { it.id==source }.kind in port.accepts) { "Incompatible building connection" }
                when {
                    port.key=="floor" -> next=next.copy(floor=source)
                    port.key=="ceiling" -> next=next.copy(ceiling=source)
                    port.key=="corner" -> next=next.copy(corner=source)
                    port.key=="roof" -> next=next.copy(roof=source)
                    port.key.startsWith("wall:") -> {
                        val edge=port.key.removePrefix("wall:")
                        next=next.copy(walls=if(n.kind==BuildingKind.ROOF && source.isEmpty()) next.walls-edge else next.walls+(edge to source.ifEmpty { "empty_wall" }))
                    }
                    port.key.startsWith("in:") -> {
                        val i=port.key.removePrefix("in:").toInt()
                        if(i<next.inputs.size || source.isNotEmpty()) {
                            val inputs=next.inputs.toMutableList();if(i==inputs.size) inputs+="";inputs[i]=source
                            var offset=next.offsets.getOrNull(i) ?: P()
                            if(n.kind==BuildingKind.STOREY && n.storeyMode=="rooms" && source.isNotEmpty() && n.inputs.getOrNull(i).isNullOrEmpty() && i>0) {
                                val room=original.nodes.first { it.id==source }
                                val max=next.inputs.mapIndexedNotNull { j,id -> original.nodes.firstOrNull { it.id==id && it.kind==BuildingKind.ROOM }?.let {
                                    RoomGeometry.cells(it.rectangles).maxOf { it.x }+(next.offsets.getOrNull(j)?.x ?: 0)
                                } }.maxOrNull() ?: -2
                                offset=P(max+2-RoomGeometry.cells(room.rectangles).minOf { it.x },0,0)
                            }
                            next=next.copy(inputs=inputs,offsets=if(n.kind==BuildingKind.STOREY) List(inputs.size) { j -> if(j==i) offset else next.offsets.getOrNull(j) ?: P() } else next.offsets)
                        }
                    }
                }
            }
            if(changedPorts.any { it.key.startsWith("level:") }) {
                val levels=ports.getValue(n.id).values.filter { it.key.startsWith("level:") }.mapNotNull { port ->
                    wires[n.id to port.key]?.source?.let { port.key.removePrefix("level:").toInt() to it }
                }.toMap()
                next=next.copy(levels=BuildingGraph.compactLevels(levels))
            }
            next.trimCopycatInputs()
        })
        BuildingGraphCompiler.structure(changed)
        return changed
    }
}
