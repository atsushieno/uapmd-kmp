package dev.atsushieno.uapmd.cmp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Details-window prune is what makes deleting a plug-in instance close its
 * Details window, from every path that deletes one. It runs against whatever
 * windows happen to be open, so it has to leave everything else alone.
 */
class FloatingWindowManagerTest {
    private fun managerWith(vararg keys: String) = FloatingWindowManager().apply {
        keys.forEach { open(it, it) {} }
    }

    @Test
    fun closesDetailsWindowsForInstancesThatAreGone() {
        val windows = managerWith(detailsWindowKey(1), detailsWindowKey(2), detailsWindowKey(3))
        windows.closeDetailsWindowsExcept(setOf(2))
        assertFalse(windows.isOpen(detailsWindowKey(1)))
        assertTrue(windows.isOpen(detailsWindowKey(2)))
        assertFalse(windows.isOpen(detailsWindowKey(3)))
    }

    @Test
    fun leavesOtherWindowsAlone() {
        // Every instance is gone, so the prune is at its most aggressive: only
        // Details windows may be taken.
        val windows = managerWith("plugins", "devices", "graph:0", "dump:0:7", detailsWindowKey(1))
        windows.closeDetailsWindowsExcept(emptySet())
        assertFalse(windows.isOpen(detailsWindowKey(1)))
        listOf("plugins", "devices", "graph:0", "dump:0:7").forEach {
            assertTrue(windows.isOpen(it), "$it should have survived")
        }
    }

    @Test
    fun ignoresKeysThatOnlyLookLikeDetailsWindows() {
        val windows = managerWith("details:", "details:master")
        windows.closeDetailsWindowsExcept(emptySet())
        assertEquals(2, listOf("details:", "details:master").count { windows.isOpen(it) })
    }

    @Test
    fun keepsEveryWindowWhenNothingWasRemoved() {
        val windows = managerWith(detailsWindowKey(4), detailsWindowKey(5))
        windows.closeDetailsWindowsExcept(setOf(4, 5))
        assertTrue(windows.isOpen(detailsWindowKey(4)))
        assertTrue(windows.isOpen(detailsWindowKey(5)))
    }
}
