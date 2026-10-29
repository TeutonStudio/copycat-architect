package de.teutonstudio.copycatarchitect.domain

/** Reproducible thin-wall house used by the design, world and native-render acceptance. */
object PanelBuildingExample {
    const val WIDTH=4
    const val DEPTH=4
    const val HEIGHT=4
    private data class Source(val id:String,val part:Part,val axis:RotationAxis=RotationAxis.Y,val turns:Int=0)
    private fun form(id:String,name:String,sources:List<Source>,groups:List<MaterialGroup> = DEFAULT_GROUPS):Form {
        val nodes=mutableListOf<GraphNode>();val inputs=mutableListOf<String>()
        for(s in sources) {
            nodes+=GraphNode(s.id,part=s.part,x=20,y=inputs.size*150)
            if(s.turns!=0) {
                val rotated=s.id+"_rotate"
                nodes+=GraphNode(rotated,NodeKind.ROTATE,listOf(s.id,""),x=230,y=inputs.size*150,rotationAxis=s.axis,quarterTurns=s.turns)
                inputs+=rotated
            } else inputs+=s.id
        }
        val output=if(inputs.size==1) inputs.single() else "join".also { nodes+=GraphNode(it,NodeKind.UNION,inputs.toList(),x=470,y=20) }
        nodes+=GraphNode("result",NodeKind.OUTPUT,listOf(output),x=700,y=20)
        return Geometry.validate(Form(id=id,name=name,groups=groups,graph=NodeGraph(nodes=nodes)))
    }
    private val wallGroups=listOf(MaterialGroup(),MaterialGroup("trim","Leisten","trim",1))
    private val windowGroups=listOf(MaterialGroup("glass","Glas","glass",0),MaterialGroup("trim","Fensterrahmen","trim",1))
    val forms:Map<String,Form> by lazy {
        listOf(
            form("full","Vollblock",listOf(Source("block",Part("copycats:copycat_block")))),
            form("panel_wall","Panelwand 3-16",listOf(Source("panel",Part("create:copycat_panel"),RotationAxis.X,3))),
            form("panel_lower","Panel mit unterer Kantenleiste",listOf(Source("panel",Part("create:copycat_panel"),RotationAxis.X,3),Source("rail",Part("copycats:copycat_slice",group="trim"))),wallGroups),
            form("panel_upper","Panel mit oberer Kantenleiste",listOf(Source("panel",Part("create:copycat_panel"),RotationAxis.X,3),Source("rail",Part("copycats:copycat_slice",top=true,group="trim"))),wallGroups),
            form("layer_window","Schichtfenster 2-16 mit Leistenrahmen",listOf(
                Source("glass",Part("copycats:copycat_layer",group="glass"),RotationAxis.X,3),
                Source("lower",Part("copycats:copycat_slice",group="trim")),Source("upper",Part("copycats:copycat_slice",top=true,group="trim")),
                Source("right",Part("copycats:copycat_vertical_slice",group="trim")),Source("left",Part("copycats:copycat_vertical_slice",group="trim"),RotationAxis.Y,1)
            ),windowGroups),
            form("corner_rail","Senkrechte Eckleiste 2x2-16",listOf(Source("rail",Part("copycats:copycat_vertical_slice"))))
        ).associateBy { "${it.id}@${it.version}" }
    }
    val modules:Map<String,Module> by lazy {
        fun column(id:String,window:Boolean)=Module(id=id,name=if(window) "Fensterspalte" else "Panelspalte",width=1,height=HEIGHT,
            cells=(0 until HEIGHT).map { y -> Cell(P(0,y,0),form=when { y==0->"panel_lower@1";y==HEIGHT-1->"panel_upper@1";window->"layer_window@1";else->"panel_wall@1" }) })
        listOf(column("panel_edge",false),column("panel_window",true)).associateBy { it.key }
    }
    val building:Building by lazy {
        val rects=listOf(Rect(width=WIDTH,length=DEPTH))
        Building(schema=2,id="panel_house",name="Panelhaus mit Schichtfenstern",
            palette=Building().palette+mapOf("floor" to "minecraft:oak_planks","ceiling" to "minecraft:oak_planks"),
            graph=BuildingGraph(nodes=listOf(
                BuildingNode("empty_wall",BuildingKind.SEGMENT,"Leere Wand",20,20,block="minecraft:air",length=1),
                BuildingNode("edge",BuildingKind.SEGMENT,"Panelspalte",20,190,module="panel_edge@1",length=1,height=HEIGHT),
                BuildingNode("window",BuildingKind.SEGMENT,"Fensterspalte",20,370,module="panel_window@1",length=1,height=HEIGHT),
                BuildingNode("wall",BuildingKind.PATTERN,"Panel-Fenster-Fassade",300,70,inputs=listOf("edge","window","window","edge")),
                BuildingNode("floor_copycat",BuildingKind.COPYCAT,"Boden",20,560,role="floor"),
                BuildingNode("ceiling_copycat",BuildingKind.COPYCAT,"Decke",20,730,role="ceiling"),
                BuildingNode("corner_copycat",BuildingKind.COPYCAT,"Senkrechte Eckleiste",300,520,form="corner_rail@1",role="trim"),
                BuildingNode("floor",BuildingKind.FLOOR,"Boden außerhalb Innenraum",300,720,inputs=listOf("floor_copycat"),occupied=false),
                BuildingNode("ceiling",BuildingKind.CEILING,"Decke außerhalb Innenraum",300,910,inputs=listOf("ceiling_copycat"),occupied=false),
                BuildingNode("storey",BuildingKind.STOREY,"4 x 4 x 4 bebaubar",640,50,height=HEIGHT,rectangles=rects,
                    walls=RoomGeometry.edges(rects).associate { it.id to "wall" },floor="floor",ceiling="ceiling",corner="corner_copycat",storeyMode="walls"),
                BuildingNode("roof",BuildingKind.ROOF,"Flachdach",640,750,role="roof"),
                BuildingNode("building",BuildingKind.BUILDING,"Panelhaus",990,80,levels=mapOf(0 to "storey"),roof="roof")
            )))
    }
    fun plan():Plan=Generator.generate(building,forms,modules)
}
