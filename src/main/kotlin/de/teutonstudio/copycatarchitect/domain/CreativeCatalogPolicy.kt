package de.teutonstudio.copycatarchitect.domain

enum class CreativeCatalogSection(val key:String) { PROCEDURAL("procedural"), BASIC("basic"), FREQUENT("frequent"), SAVED("saved") }

/** Registry IDs determine discoverability only; registrations and crafting recipes stay intact. */
object CreativeCatalogPolicy {
    private val geometry=BaseShapes.all.map { it.source }.toSet()
    fun isCopycat(namespace:String,path:String)=namespace=="copycats" || path.startsWith("copycat_") || path=="copycat" || path=="wrapped_copycat"
    fun section(namespace:String,path:String):CreativeCatalogSection? {
        if(!isCopycat(namespace,path)) return null
        return if("$namespace:$path" in geometry) CreativeCatalogSection.BASIC else CreativeCatalogSection.FREQUENT
    }
}
