package com.gamebex.client.ui.game

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import com.gamebex.client.ui.together.performKeyInput
import com.gamebex.client.ui.together.textIsCut
import com.gamebex.client.ui.together.TestDisplay
import com.gamebex.client.ui.together.setTogetherContentAt
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.ui.GameBexTheme
import com.gamebex.client.ui.LocalDetailViewModel
import com.gamebex.client.ui.console.ConsoleGeometryRoot
import com.gamebex.client.ui.console.ConsoleHints
import com.gamebex.client.ui.console.RailHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameDetailTwoPTest {
    @get:Rule val rule = createComposeRule()

    @Test fun snesRowRightRightAInvokesTogether() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDb::class.java).build()
        val restorePackages = showRetroArchInstalled()
        try {
            val id = runBlocking { db.games().upsert(GameEntity(title = "Super Mario World", folder = "snes", volumeId = "unmounted", relPath = "snes/mario.sfc", fileName = "mario.sfc", sizeBytes = 10, mtimeMs = 1, ext = "sfc", playCount = 1, prepState = "READY")) }
            val vm = LocalDetailViewModel(ApplicationProvider.getApplicationContext(), db)
            vm.load(id)
            var selected = 0L
            val hints = ConsoleHints()
            rule.setContent {
                GameBexTheme {
                    ConsoleGeometryRoot {
                        GameDetailScreen(vm, object : RailHost { override fun enterRail() = true }, hints, {}, {}, {}, onPlayTogether = { selected = it })
                    }
                }
            }
            rule.waitForIdle()
            rule.onNodeWithText("START FRESH").assertExists()
            // All three buttons share the row: no label may lose words.
            assertFalse("START FRESH is cut", rule.onNodeWithText("START FRESH", useUnmergedTree = true).textIsCut())
            assertFalse("2P is cut", rule.onNodeWithText(TogetherCopy.twoP.uppercase(), useUnmergedTree = true).textIsCut())
            rule.onRoot().performKeyInput {
                pressKey(Key.DirectionRight)
                pressKey(Key.DirectionRight)
                pressKey(Key.ButtonA)
            }
            rule.runOnIdle { assertEquals(id, selected) }
        } finally {
            restorePackages()
            db.close()
        }
    }

    @Test fun startFreshAndTwoPStayWholeAtLargerText() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDb::class.java).build()
        val restorePackages = showRetroArchInstalled()
        try {
            val id = runBlocking { db.games().upsert(GameEntity(title = "Super Mario World", folder = "snes", volumeId = "unmounted", relPath = "snes/mario.sfc", fileName = "mario.sfc", sizeBytes = 10, mtimeMs = 1, ext = "sfc", playCount = 1, prepState = "READY")) }
            val vm = LocalDetailViewModel(ApplicationProvider.getApplicationContext(), db)
            vm.load(id)
            val display = TestDisplay(textScale = 1.4f)
            rule.setTogetherContentAt(display) { hints ->
                GameDetailScreen(vm, object : RailHost { override fun enterRail() = true }, hints, {}, {}, {}, onPlayTogether = {})
            }
            for (fontScale in listOf(1f, 1.3f)) {
                rule.runOnIdle { display.fontScale = fontScale }
                assertFalse("font $fontScale: START FRESH is cut", rule.onNodeWithText("START FRESH", useUnmergedTree = true).textIsCut())
                assertFalse("font $fontScale: 2P is cut", rule.onNodeWithText(TogetherCopy.twoP.uppercase(), useUnmergedTree = true).textIsCut())
            }
        } finally {
            restorePackages()
            db.close()
        }
    }

    @Test fun snesWithoutResumeRightAInvokesTogether() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDb::class.java).build()
        try {
            val id = runBlocking { db.games().upsert(GameEntity(title = "Super Mario World", folder = "snes", volumeId = "internal", relPath = "snes/mario.sfc", fileName = "mario.sfc", sizeBytes = 10, mtimeMs = 1, ext = "sfc", playCount = 0)) }
            val vm = LocalDetailViewModel(ApplicationProvider.getApplicationContext(), db)
            vm.load(id)
            var selected = 0L
            rule.setContent {
                GameBexTheme {
                    ConsoleGeometryRoot {
                        GameDetailScreen(vm, object : RailHost { override fun enterRail() = true }, ConsoleHints(), {}, {}, {}, onPlayTogether = { selected = it })
                    }
                }
            }
            rule.waitForIdle()
            rule.onRoot().performKeyInput {
                pressKey(Key.DirectionRight)
                pressKey(Key.ButtonA)
            }
            rule.runOnIdle { assertEquals(id, selected) }
        } finally {
            db.close()
        }
    }

    @Test fun ps2RowDoesNotExposeTwoP() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDb::class.java).build()
        try {
            val id = runBlocking { db.games().upsert(GameEntity(title = "Shadow", folder = "ps2", volumeId = "internal", relPath = "ps2/shadow.iso", fileName = "shadow.iso", sizeBytes = 10, mtimeMs = 1, ext = "iso")) }
            val vm = LocalDetailViewModel(ApplicationProvider.getApplicationContext(), db)
            vm.load(id)
            rule.setContent { GameBexTheme { ConsoleGeometryRoot { GameDetailScreen(vm, object : RailHost { override fun enterRail() = true }, ConsoleHints(), {}, {}, {}) } } }
            rule.waitForIdle()
            rule.onNodeWithContentDescription(TogetherCopy.twoPHint).assertDoesNotExist()
        } finally {
            db.close()
        }
    }
}

private fun showRetroArchInstalled(): () -> Unit {
    val pkg = com.gamebex.client.data.Emu.RETROARCH.pkg
    val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
    fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
    org.junit.Assume.assumeTrue(
        "RetroArch stub installs only on an emulator",
        isInstrumentationEmulator(::shell),
    )
    val bytes = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        .context.assets.open("retroarch-stub.base64").use {
            android.util.Base64.decode(it.readBytes(), android.util.Base64.DEFAULT)
        }
    val apk = java.io.File(app.cacheDir, "retroarch-stub.apk")
    try {
        apk.writeBytes(bytes)
        val pm = app.packageManager
        val stubCerts = stubCertAlternatives(pm, apk)
        val installed = installedCerts(pm, pkg)
        if (installed != null && !matchesStub(installed, stubCerts)) return {}
        if (installed != null) {
            val removed = shell("pm uninstall $pkg")
            check(removed.contains("Success")) {
                removed.take(180).ifBlank { "leftover stub uninstall failed" }
            }
        }
        check(stubCerts.any { it.isNotEmpty() }) { "stub apk signing certificates were not readable" }
        val output = installApkFromStdin(bytes)
        check(output.contains("Success") && matchesStub(installedCerts(pm, pkg), stubCerts)) {
            output.take(180).ifBlank { "stub install failed" }
        }
        return {
            val current = installedCerts(app.packageManager, pkg)
            if (matchesStub(current, stubCerts)) shell("pm uninstall $pkg")
        }
    } finally {
        apk.delete()
        shell("rm -f /data/local/tmp/retroarch-stub.apk")
    }
}

private fun isInstrumentationEmulator(shell: (String) -> String): Boolean {
    val fingerprint = android.os.Build.FINGERPRINT
    val hardware = android.os.Build.HARDWARE
    val product = android.os.Build.PRODUCT
    fun has(token: String) =
        hardware.contains(token, ignoreCase = true) ||
            product.contains(token, ignoreCase = true) ||
            fingerprint.contains(token, ignoreCase = true)
    val genericSdk = fingerprint.startsWith("generic", ignoreCase = true) ||
        fingerprint.contains("sdk_gphone", ignoreCase = true) ||
        fingerprint.contains("google_sdk", ignoreCase = true) ||
        fingerprint.contains("/sdk_", ignoreCase = true)
    val qemu = has("qemu") ||
        shell("getprop ro.kernel.qemu").lineSequence().any { it.trim() == "1" } ||
        shell("getprop ro.boot.qemu").lineSequence().any { it.trim() == "1" }
    return has("goldfish") || has("ranchu") || genericSdk || qemu
}

private fun installApkFromStdin(bytes: ByteArray): String {
    val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
    val pipes = automation.executeShellCommandRw("pm install -r -t -S ${bytes.size}")
    android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
        it.write(bytes)
        it.flush()
    }
    return android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
}

private fun matchesStub(installed: Set<String>?, stubCerts: List<Set<String>>): Boolean =
    installed != null && installed.isNotEmpty() && stubCerts.any { it == installed }

private fun stubCertAlternatives(
    pm: android.content.pm.PackageManager,
    apk: java.io.File,
): List<Set<String>> {
    val sets = ArrayList<Set<String>>(2)
    val fromPackageManager = archiveCerts(pm, apk)
    if (fromPackageManager.isNotEmpty()) sets.add(fromPackageManager)
    val fromJar = runCatching { jarCertSha256(apk) }.getOrDefault(emptySet())
    if (fromJar.isNotEmpty() && fromJar != fromPackageManager) sets.add(fromJar)
    return sets
}

private fun archiveCerts(pm: android.content.pm.PackageManager, apk: java.io.File): Set<String> =
    certSha256(pm.getPackageArchiveInfo(apk.absolutePath, signingFlags()))

private fun installedCerts(pm: android.content.pm.PackageManager, pkg: String): Set<String>? {
    val info = runCatching { pm.getPackageInfo(pkg, signingFlags()) }.getOrNull() ?: return null
    return certSha256(info)
}

@Suppress("DEPRECATION")
private fun signingFlags(): Int =
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES or
            android.content.pm.PackageManager.GET_SIGNATURES
    } else {
        android.content.pm.PackageManager.GET_SIGNATURES
    }

private fun certSha256(info: android.content.pm.PackageInfo?): Set<String> {
    if (info == null) return emptySet()
    val modern = if (android.os.Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
    } else {
        emptyList()
    }
    val raw = if (modern.isNotEmpty()) {
        modern
    } else {
        @Suppress("DEPRECATION")
        info.signatures?.map { it.toByteArray() }.orEmpty()
    }
    if (raw.isEmpty()) return emptySet()
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return raw.map { bytes ->
        md.reset()
        md.digest(bytes).joinToString("") { "%02x".format(it) }
    }.toSet()
}

private fun jarCertSha256(apk: java.io.File): Set<String> {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    java.util.jar.JarFile(apk).use { jar ->
        val entry = jar.getJarEntry("AndroidManifest.xml") ?: return emptySet()
        jar.getInputStream(entry).use { it.readBytes() }
        val certs = entry.certificates ?: return emptySet()
        return certs.map { cert ->
            md.reset()
            md.digest(cert.encoded).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
