package com.gamebex.client.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preflight stops a RetroArch launch until RetroArch's own first run granted file access. */
class RetroArchFirstRunTest {
    private class FakePackages(
        val requested: List<String>?,
        val targetSdk: Int = 29,
        val granted: Set<String> = emptySet(),
        val allFiles: Boolean? = null,
    ) : EmuRouter.PackageFacts {
        override fun requestedPermissions(pkg: String): List<String>? = requested.takeIf { pkg == RA }
        override fun targetSdk(pkg: String): Int = targetSdk
        override fun isGranted(permission: String, pkg: String): Boolean = pkg == RA && permission in granted
        override fun allFilesAccess(pkg: String): Boolean? = allFiles.takeIf { pkg == RA }
    }

    @Test fun legacyStorageNotGrantedNeedsFirstRun() {
        assertTrue(EmuRouter.retroArchNeedsFirstRun(FakePackages(listOf(READ)), deviceSdk = 34))
        assertFalse(EmuRouter.retroArchNeedsFirstRun(FakePackages(listOf(READ), granted = setOf(READ)), deviceSdk = 34))
    }

    @Test fun mediaPermissionsCountForATargetSdk33Build() {
        val media = listOf(IMAGES, VIDEO, AUDIO)
        assertTrue(EmuRouter.retroArchNeedsFirstRun(FakePackages(media + READ, targetSdk = 33), deviceSdk = 34))
        assertFalse(EmuRouter.retroArchNeedsFirstRun(FakePackages(media, targetSdk = 33, granted = setOf(VIDEO)), deviceSdk = 34))
        // On Android 12, a targetSdk 33 build still uses the legacy permission.
        assertTrue(EmuRouter.retroArchNeedsFirstRun(FakePackages(media + READ, targetSdk = 33), deviceSdk = 32))
    }

    @Test fun notInstalledOrNoStoragePermissionIsNotAFirstRunProblem() {
        assertFalse(EmuRouter.retroArchNeedsFirstRun(FakePackages(null), deviceSdk = 34))
        assertFalse(EmuRouter.retroArchNeedsFirstRun(FakePackages(listOf("android.permission.INTERNET")), deviceSdk = 34))
    }

    @Test fun allFilesAccessBuildNeedsThatGrant() {
        // A build targeting Android 11+ that asks for All Files Access reads ROMs only
        // with it; the legacy storage grant it may still hold doesn't count.
        val nightly = listOf(READ, MANAGE)
        assertTrue(EmuRouter.retroArchNeedsFirstRun(FakePackages(nightly, targetSdk = 36, granted = setOf(READ), allFiles = false), deviceSdk = 36))
        assertFalse(EmuRouter.retroArchNeedsFirstRun(FakePackages(nightly, targetSdk = 36, allFiles = true), deviceSdk = 36))
        // A phone that can't tell never blocks the launch.
        assertFalse(EmuRouter.retroArchNeedsFirstRun(FakePackages(nightly, targetSdk = 36, allFiles = null), deviceSdk = 36))
    }

    @Test fun legacyBuildIgnoresAllFilesAccess() {
        // Stable 1.22.2 targets 28: the legacy grant is enough even though it lists All Files Access.
        assertFalse(EmuRouter.retroArchNeedsFirstRun(FakePackages(listOf(READ, MANAGE), targetSdk = 28, granted = setOf(READ), allFiles = false), deviceSdk = 36))
    }

    @Test fun allFilesAccessFollowsTheSystemRule() {
        // The Settings switch writes ALLOWED or ERRORED; a never-touched app is DEFAULT.
        assertEquals(true, EmuRouter.allFilesAccessFromMode(android.app.AppOpsManager.MODE_ALLOWED) { false })
        assertEquals(false, EmuRouter.allFilesAccessFromMode(android.app.AppOpsManager.MODE_ERRORED) { true })
        assertEquals(false, EmuRouter.allFilesAccessFromMode(android.app.AppOpsManager.MODE_IGNORED) { true })
        assertEquals(false, EmuRouter.allFilesAccessFromMode(android.app.AppOpsManager.MODE_DEFAULT) { false })
        assertEquals(true, EmuRouter.allFilesAccessFromMode(android.app.AppOpsManager.MODE_DEFAULT) { true })
        assertNull(EmuRouter.allFilesAccessFromMode(null) { true })
    }

    private companion object {
        const val RA = "com.retroarch.aarch64"
        const val READ = "android.permission.READ_EXTERNAL_STORAGE"
        const val IMAGES = "android.permission.READ_MEDIA_IMAGES"
        const val VIDEO = "android.permission.READ_MEDIA_VIDEO"
        const val AUDIO = "android.permission.READ_MEDIA_AUDIO"
        const val MANAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
    }
}
