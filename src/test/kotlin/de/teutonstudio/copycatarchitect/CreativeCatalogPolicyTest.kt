package de.teutonstudio.copycatarchitect

import de.teutonstudio.copycatarchitect.domain.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class CreativeCatalogPolicyTest {
    @Test fun `all graph supported base sources belong to geometry`() {
        for(shape in BaseShapes.all) {
            val (namespace,path)=shape.source.split(':')
            assertEquals(CreativeCatalogSection.BASIC,CreativeCatalogPolicy.section(namespace,path),shape.id)
        }
    }
    @Test fun `functional Copycats include kinetic blocks and third party control desks`() {
        for(path in listOf("copycat_fluid_pipe","copycat_glass_fluid_pipe","copycat_cogwheel","copycat_large_cogwheel","copycat_shaft","copycat_sliding_door","copycat_stone_button"))
            assertEquals(CreativeCatalogSection.FREQUENT,CreativeCatalogPolicy.section("copycats",path),path)
        assertEquals(CreativeCatalogSection.FREQUENT,CreativeCatalogPolicy.section("aeroworks","copycat_control_desk"))
        assertEquals(CreativeCatalogSection.FREQUENT,CreativeCatalogPolicy.section("aeroworks","copycat_control_stand"))
        assertNull(CreativeCatalogPolicy.section("create","fluid_pipe"))
        assertNull(CreativeCatalogPolicy.section("minecraft","stone"))
    }
    @Test fun `decorative Copycats remain available without implying a graph adapter`() {
        assertEquals(CreativeCatalogSection.FREQUENT,CreativeCatalogPolicy.section("copycats","copycat_slope"))
        assertEquals(CreativeCatalogSection.FREQUENT,CreativeCatalogPolicy.section("copycats","copycat_board"))
        assertTrue(CreativeCatalogPolicy.isCopycat("create","copycat_panel"))
        assertFalse(CreativeCatalogPolicy.isCopycat("copycat_architect","composite"))
    }
}
