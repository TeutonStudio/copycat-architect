package de.teutonstudio.copycatarchitect.server

import com.google.gson.JsonElement
import de.teutonstudio.copycatarchitect.domain.*
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.*
import net.minecraft.util.profiling.ProfilerFiller
import net.neoforged.neoforge.event.AddReloadListenerEvent

object ExtrusionReload:SimpleJsonResourceReloadListener(Json.gson,"extrusion") {
    fun register(e:AddReloadListenerEvent) { e.addListener(this) }
    override fun apply(files:Map<ResourceLocation,JsonElement>,resources:ResourceManager,profiler:ProfilerFiller) {
        val default=files[ResourceLocation.parse("copycat_architect:default")]?.asJsonObject ?: error("Missing extrusion/default.json")
        require(default.get("schema").asInt==1 && default.get("units_per_ingot").asInt==256) { "Unsupported extrusion schema or unit scale" }
        val outputs=default.getAsJsonObject("outputs").entrySet().associate { it.key to it.value.asInt }
        require(outputs.keys==Adapters.all.keys && outputs.values.all { it in 1..256 && 256%it==0 }) { "Extrusion outputs must exactly cover supported adapters with integral costs" }
        require((256 / outputs.getValue(BaseShapes.DISC_SOURCE)) % 4 == 0) { "Centered pane recipes must allow integral half and quarter costs" }
        Extrusion.outputs=outputs
    }
}
