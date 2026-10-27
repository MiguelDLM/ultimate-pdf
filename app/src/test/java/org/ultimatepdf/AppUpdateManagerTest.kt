package org.ultimatepdf

import org.junit.Assert.*
import org.junit.Test
import org.ultimatepdf.core.updater.AppUpdateManager

class AppUpdateManagerTest {

    @Test
    fun `test parseVersionNumbers`() {
        assertEquals(listOf(1, 0), AppUpdateManager.parseVersionNumbers("v1.0"))
        assertEquals(listOf(1, 2, 3), AppUpdateManager.parseVersionNumbers("1.2.3"))
        assertEquals(listOf(2, 0, 1), AppUpdateManager.parseVersionNumbers("V2.0.1-beta2"))
        assertEquals(listOf(1, 4), AppUpdateManager.parseVersionNumbers("1.4+build10"))
    }

    @Test
    fun `test isNewerVersion detects updates correctly`() {
        // Minor bump
        assertTrue(AppUpdateManager.isNewerVersion(current = "1.0", remote = "1.1"))
        assertTrue(AppUpdateManager.isNewerVersion(current = "1.0", remote = "v1.1"))

        // Patch bump
        assertTrue(AppUpdateManager.isNewerVersion(current = "1.0", remote = "v1.0.1"))
        assertTrue(AppUpdateManager.isNewerVersion(current = "1.0.5", remote = "v1.0.10"))

        // Major bump
        assertTrue(AppUpdateManager.isNewerVersion(current = "1.9.9", remote = "2.0"))
        assertTrue(AppUpdateManager.isNewerVersion(current = "1.0", remote = "v2.0.0"))
    }

    @Test
    fun `test isNewerVersion ignores same or older versions`() {
        // Same versions
        assertFalse(AppUpdateManager.isNewerVersion(current = "1.0", remote = "1.0"))
        assertFalse(AppUpdateManager.isNewerVersion(current = "1.0", remote = "v1.0"))
        assertFalse(AppUpdateManager.isNewerVersion(current = "1.0.0", remote = "v1.0"))
        assertFalse(AppUpdateManager.isNewerVersion(current = "1.0", remote = "v1.0.0"))

        // Older versions
        assertFalse(AppUpdateManager.isNewerVersion(current = "1.1", remote = "1.0.9"))
        assertFalse(AppUpdateManager.isNewerVersion(current = "2.0", remote = "v1.9.9"))
        assertFalse(AppUpdateManager.isNewerVersion(current = "1.0.10", remote = "1.0.5"))
    }
}
