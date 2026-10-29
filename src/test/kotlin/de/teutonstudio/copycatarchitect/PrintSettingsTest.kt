package de.teutonstudio.copycatarchitect

import de.teutonstudio.copycatarchitect.domain.*
import de.teutonstudio.copycatarchitect.server.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class PrintSettingsTest {
    private fun form()=Form(id="printed",name="Printed form",parts=listOf(Part("copycats:copycat_stairs")))
    @Test fun `older libraries have no saved appearance and keep their geometry`() {
        val restored=Json.decode<Library>("""{"forms":{"printed@1":{"id":"printed","name":"Printed form"}},"history":[]}""")
        assertTrue(restored.printSettings.isEmpty());assertEquals("Printed form",restored.forms.getValue("printed@1").name)
    }
    @Test fun `named history stores its printed palette and rotation under the new saved key`() {
        val palette=mapOf("body" to "minecraft:oak_planks")
        val library=Library(history=mutableListOf(PrintRecord(id="record",form=form(),surfaces=palette,turn=3)))
        val key=library.savePrintHistory("record"," Named result ")
        assertEquals("Named result",library.forms.getValue(key).name)
        assertEquals(PrintSettings(palette,3),library.printSettings[key])
        val restored=Json.decode<Library>(Json.encode(library))
        assertEquals(library,restored)
        assertEquals(palette,restored.printSettings.getValue(key).surfaces)
    }
    @Test fun `invalid history settings are rejected before the saved library changes`() {
        val library=Library(history=mutableListOf(PrintRecord(id="bad",form=form(),surfaces=mapOf("missing" to "minecraft:stone"),turn=1)))
        val before=Json.encode(library)
        assertFails { library.savePrintHistory("bad","Invalid") }
        assertEquals(before,Json.encode(library))
        assertFails { PrintSettings(turn=4).validate(form()) }
    }
}
