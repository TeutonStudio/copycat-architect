package de.teutonstudio.copycatarchitect.domain

/** Display adapters only: opening a presentation never converts or rewrites a document. */
object GraphPresentation {
    fun shape(form:Form):NodeGraph=form.graph ?: NodeGraph.fromParts(form.parts)

    /** Classic buildings keep their original generator and editable controls. */
    fun building(building:Building):BuildingGraph=building.graph ?: BuildingGraph(nodes=listOf(
        BuildingNode("empty_wall",BuildingKind.SEGMENT,"Empty wall",20,20,block="minecraft:air",length=1),
        BuildingNode("building",BuildingKind.BUILDING,building.name,300,20)
    ))
}
