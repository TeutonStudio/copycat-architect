package de.teutonstudio.copycatarchitect

import de.teutonstudio.copycatarchitect.client.EditorValueDraft
import org.junit.jupiter.api.Test
import kotlin.test.*

class EditorValueDraftTest {
    private fun number(initial:String)=EditorValueDraft(initial) { text -> text.toIntOrNull()?.takeIf { it in -64..64 } }

    @Test fun `typing and cancel leave the document unchanged`() {
        var document=4
        val draft=number(document.toString())
        draft.value="-23"
        assertEquals(4,document)
        draft.cancel()
        assertFalse(draft.submit { document=it })
        assertEquals(4,document)
    }

    @Test fun `invalid input can be corrected without an intermediate document edit`() {
        val changes=mutableListOf<Int>()
        val draft=number("4")
        for(value in listOf("","-","1.5","65","999999999999999999999")) {
            draft.value=value
            assertFalse(draft.valid)
            assertFalse(draft.submit { changes+=it })
        }
        assertTrue(changes.isEmpty())
        draft.value="-64"
        assertTrue(draft.valid)
        assertTrue(draft.submit { changes+=it })
        assertEquals(listOf(-64),changes)
    }

    @Test fun `accept applies a completed edit only once`() {
        val changes=mutableListOf<Int>()
        val draft=number("4")
        draft.value="17"
        assertTrue(draft.submit { changes+=it })
        assertFalse(draft.submit { changes+=it })
        assertEquals(listOf(17),changes)
    }

    @Test fun `rejected document edit remains available for correction`() {
        var document="body"
        val draft=EditorValueDraft(document) { it.trim().takeIf { name -> name.isNotBlank() } }
        draft.value="new group"
        assertFailsWith<IllegalArgumentException> { draft.submit { require(it!="new group") } }
        assertEquals("body",document)
        draft.value="cap"
        assertTrue(draft.submit { document=it })
        assertEquals("cap",document)
    }
}
