package de.teutonstudio.copycatarchitect.server

import com.google.gson.GsonBuilder
import de.teutonstudio.copycatarchitect.domain.*
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

object Json {
    val gson=GsonBuilder().disableHtmlEscaping().create()
    fun encode(x:Any)=gson.toJson(x)
    fun materials(s:String):Map<String,String> {
        require(s.length<=2000);val obj=com.google.gson.JsonParser.parseString(s).asJsonObject
        require(obj.size()<=8) { "At most 8 material assignments" }
        return obj.entrySet().associate { require(it.key.length<=36 && it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString);it.key to it.value.asString }
    }
    inline fun <reified T> decode(s:String):T { require(s.length<=64_000_000);return gson.fromJson(s,T::class.java) }
}
data class PrintSettings(val surfaces:Map<String,String> = emptyMap(),val turn:Int=0) {
    fun validate(form:Form):PrintSettings {
        require(turn in 0..3 && surfaces.size<=8 && surfaces.keys.all { id -> form.groups.any { it.id==id } }) { "Invalid saved print settings" }
        return this
    }
}
data class Library(val forms:MutableMap<String,Form> = linkedMapOf(),val modules:MutableMap<String,Module> = linkedMapOf(),val buildings:MutableMap<String,Building> = linkedMapOf(),val history:MutableList<PrintRecord> = mutableListOf(),
    val printSettings:MutableMap<String,PrintSettings> = linkedMapOf()) {
    /** Preserve the printed appearance separately so older geometry-only libraries still load. */
    fun savePrintHistory(id:String,name:String):String {
        require(id.length<=64 && name.trim().length in 1..64) { "Use a history ID and a name of 1–64 characters" }
        val record=history.firstOrNull { it.id==id } ?: error("History entry not found")
        require(forms.size<128) { "Library form limit reached" }
        val form=Geometry.validate(record.form.copy(id=UUID.randomUUID().toString(),version=1,name=name.trim()));val key="${form.id}@1"
        val settings=PrintSettings(record.surfaces.toMap(),record.turn).validate(form)
        val candidate=copy(forms=(forms+(key to form)).toMutableMap(),printSettings=(printSettings+(key to settings)).toMutableMap())
        require(Json.encode(candidate).length<=180000) { "Library is too large" }
        forms[key]=form;printSettings[key]=settings;return key
    }
    fun seed() {
        if(forms.isNotEmpty()) return
        fun add(f:Form) { forms["${f.id}@${f.version}"]=f }
        add(Form(id="full",name="Full block",parts=listOf(Part("copycats:copycat_block"))))
        add(Form(id="inner",name="Inner trim corner",parts=listOf(Part(),Part(turn=1))))
        add(Form(id="bar",name="Straight trim",parts=listOf(Part())))
        add(Form(id="stair",name="Roof step",parts=listOf(Part("copycats:copycat_stairs"))))
        add(Form(id="multi_material",name="Two-material cap",parts=listOf(Part("copycats:copycat_block"),Part("copycats:copycat_layer",top=true,group="cap")),groups=listOf(MaterialGroup(),MaterialGroup("cap","Cap","frame",1))))
        val cells=buildList { for(x in 0..2) for(y in 0..3) add(Cell(P(x,y,0),role=if(y==0 || y==3) "wall" else "frame",empty=x==1 && y in 1..2)) }
        val window=Module(id="window",name="Window 3 × 4",cells=cells)
        val pier=Module(id="pier",name="Pier 1 × 4",width=1,cells=(0..3).map { Cell(P(0,it,0),flexible=true) },minWidth=1,maxWidth=4)
        modules[window.key]=window;modules[pier.key]=pier
        buildings["demo@1"]=Building(id="demo")
    }
}
data class Job(val schema:Int=1,val id:String=UUID.randomUUID().toString(),val owner:String="",val dimension:String="",val name:String="",
    val origin:P=P(),val rotation:Int=0,val dock:P=P(),val plan:Plan=Plan(emptyList(),emptyList(),emptyList(),0,emptyMap()),
    var computer:Int=-1,var index:Int=0,var state:String="paused",var message:String="Ready",val recipeOutputs:Map<String,Int> = Extrusion.outputs.toMap())
data class Database(val schema:Int=1,val libraries:MutableMap<String,Library> = linkedMapOf(),val jobs:MutableList<Job> = mutableListOf())
class ArchitectData(var db:Database=Database()):SavedData() {
    override fun save(tag:CompoundTag,registries:HolderLookup.Provider):CompoundTag = tag.apply { putByteArray("architect",Json.encode(db).toByteArray(Charsets.UTF_8)) }
    fun library(owner:UUID):Library = db.libraries.getOrPut(owner.toString()) { Library().also { it.seed();setDirty() } }
    companion object {
        fun get(level:ServerLevel):ArchitectData = level.server.overworld().dataStorage.computeIfAbsent(
            Factory({ ArchitectData() },{ tag,_ ->
                val db=Json.decode<Database>(if(tag.contains("architect",7)) String(tag.getByteArray("architect"),Charsets.UTF_8) else tag.getString("architect").ifEmpty { Json.encode(Database()) });require(db.schema==1);ArchitectData(db)
            }),"copycat_architect")
    }
}
