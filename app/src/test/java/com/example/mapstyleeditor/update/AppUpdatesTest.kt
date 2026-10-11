package com.example.mapstyleeditor.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which release file each build installs, and how tags read as versions. */
class AppUpdatesTest {
    private val aospRelease = listOf(
        "MinMap-aosp-1.4.2-arm64-v8a.apk", "MinMap-aosp-1.4.2-armeabi-v7a.apk", "MinMap-aosp-1.4.2-universal.apk", "SHA256SUMS",
    )

    @Test
    fun aospTakesItsOwnCpusBuild() {
        assertEquals("MinMap-aosp-1.4.2-arm64-v8a.apk", AppUpdates.pickApk(aospRelease, aosp = true, abi = "arm64-v8a"))
        assertEquals("MinMap-aosp-1.4.2-armeabi-v7a.apk", AppUpdates.pickApk(aospRelease, aosp = true, abi = "armeabi-v7a"))
        assertEquals("MinMap-aosp-1.4.2-universal.apk", AppUpdates.pickApk(aospRelease, aosp = true, abi = "x86_64"))
    }

    @Test
    fun theBuildsNeverTakeEachOthersApk() {
        assertNull(AppUpdates.pickApk(aospRelease, aosp = false, abi = "arm64-v8a"))
        assertNull(AppUpdates.pickApk(listOf("MinMap-1.4.1.apk", "SHA256SUMS"), aosp = true, abi = "arm64-v8a"))
        assertEquals("MinMap-1.4.1.apk", AppUpdates.pickApk(listOf("MinMap-1.4.1.apk", "SHA256SUMS"), aosp = false, abi = "arm64-v8a"))
    }

    @Test
    fun aospTagsReadAsTheirVersion() {
        assertEquals("1.4.2", AppUpdates.releaseVersion("v1.4.2-aosp"))
        assertEquals("1.4.1", AppUpdates.releaseVersion("v1.4.1"))
        assertTrue(AppUpdates.newer(AppUpdates.releaseVersion("v1.4.2-aosp"), "1.4.1"))
        // The pre-release already installed isn't offered again.
        assertFalse(AppUpdates.newer(AppUpdates.releaseVersion("v1.4.1-aosp"), "1.4.1"))
    }
}
