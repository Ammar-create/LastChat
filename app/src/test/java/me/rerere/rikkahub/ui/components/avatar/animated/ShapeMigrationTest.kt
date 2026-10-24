package me.rerere.rikkahub.ui.components.avatar.animated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Engine17.2: house and wedge (triangle/cone) are retired; saved avatars migrate to blob. */
class ShapeMigrationTest {
    @Test
    fun retiredShapesLeaveThePickerAndMigrate() {
        assertFalse("house" in MarkShapes.PICKER_IDS)
        assertFalse("wedge" in MarkShapes.PICKER_IDS)
        assertEquals("blob", MarkShapes.migrate("house"))
        assertEquals("blob", MarkShapes.migrate("wedge"))
        assertEquals("heart", MarkShapes.migrate("heart"))
        assertEquals(10, MarkShapes.PICKER_IDS.size)
    }
}
