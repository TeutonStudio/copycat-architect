package de.teutonstudio.copycatarchitect.client

import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent

/** Opt-in UI acceptance advances only after a real frame has settled native widget layouts. */
object ClientSmoke {
    private var frames=0L
    private var lastFrame=-1L
    fun frame(event:RenderFrameEvent.Post) { frames++ }
    fun tick(event:ClientTickEvent.Post) {
        if(!java.lang.Boolean.getBoolean("architect.smoke") && !java.lang.Boolean.getBoolean("architect.kilaGraphSmoke") && !java.lang.Boolean.getBoolean("architect.printerSmoke") && !java.lang.Boolean.getBoolean("architect.panelBuildingSmoke")) return
        // Minecraft may execute several catch-up ticks before drawing another frame.
        if(frames<=lastFrame) return
        // A popup can request a bounded position after its first layout; allow that second layout too.
        val acted=when {
            java.lang.Boolean.getBoolean("architect.panelBuildingSmoke") -> PanelBuildingClientSmoke.tick()
            java.lang.Boolean.getBoolean("architect.printerSmoke") -> PrinterClientSmoke.tick()
            else -> KilaGraphSmoke.tick()
        }
        if(acted) lastFrame=frames+1
    }
}
