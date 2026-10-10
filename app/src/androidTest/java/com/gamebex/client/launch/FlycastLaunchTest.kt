package com.gamebex.client.launch

import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.data.Emu
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlycastLaunchTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.context
    private val uri get() = GameDocumentsProvider.uriFor(42, "owned-launch.chd")

    @Test fun fileOnlyLauncherAliasStillResolvesExplicitContentViewToItsActualTarget() {
        val facts = Json.parseToJsonElement(ctx.assets.open("emulator-facts/flycast-installed-launch-contract.json").bufferedReader().use { it.readText() }).jsonObject
        val pkg = ctx.packageName
        val pm = ctx.packageManager
        fun matches(data: Uri) = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, data).setPackage(pkg), PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(facts.getValue("content_uri_implicit_view_resolves").jsonPrimitive.boolean, matches(uri).isNotEmpty())
        assertEquals(facts.getValue("file_uri_chd_view_resolves").jsonPrimitive.boolean, matches(Uri.parse("file:///owned-launch.chd")).isNotEmpty())
        val launcher = requireNotNull(pm.getLaunchIntentForPackage(pkg)?.component)
        val alias = pm.getActivityInfo(launcher, 0)
        assertNotNull(alias.targetActivity)
        val actual = ComponentName(pkg, alias.targetActivity)
        val info = pm.getActivityInfo(actual, 0)
        assertTrue(info.enabled); assertTrue(info.exported)
        assertNotEquals(launcher, actual)

        val intent = requireNotNull(EmuRouter.flycastViewIn(ctx, pkg, uri)) { "file-only alias must not disqualify explicit content delivery" }
        assertEquals(actual, intent.component)
        assertEquals(facts.getValue("required_action").jsonPrimitive.content, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("content", intent.data?.scheme)
        assertNull(intent.selector)
        assertNull(intent.type)
    }

    @Test fun flycastGrantsOnlyItsSelectedDocumentTreeReadPrefixBeforeStart() {
        val events = mutableListOf<String>()
        val grants = mutableListOf<Triple<String, Uri, Int>>()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            component = ComponentName(Emu.FLYCAST.pkg, OwnedFlycastTargetActivity::class.java.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
                assertEquals(Emu.FLYCAST.pkg, toPackage)
                assertEquals(GameDocumentsProvider.treeForDocument(intent.data!!), uri)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, modeFlags)
                grants += Triple(toPackage, uri, modeFlags)
                events += "grant"
            }
            override fun startActivity(outgoing: Intent) {
                assertEquals(listOf("grant"), events)
                assertSame(intent, outgoing)
                assertEquals(Emu.FLYCAST.pkg, outgoing.component?.packageName)
                assertEquals(uri, outgoing.data)
                assertEquals(Intent.ACTION_VIEW, outgoing.action)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK, outgoing.flags)
                events += "start"
            }
        }
        EmuRouter.startStandalone(context, Emu.FLYCAST, intent)
        assertEquals(listOf("grant", "start"), events)
        assertEquals(listOf(Triple(Emu.FLYCAST.pkg, GameDocumentsProvider.treeUri(42), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)), grants)
    }

    @Test fun missingInstalledLauncherStaysBoundedAndRomProviderStaysPrivate() {
        assertNull(EmuRouter.flycastViewIn(ctx, "com.gamebex.owned.missing", uri))
        val provider = requireNotNull(instrumentation.targetContext.packageManager.resolveContentProvider(RomProvider.AUTH, 0))
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }

    @Test fun runtimeTargetValidationRejectsMissingForeignPrivateDisabledAndAliasMetadata() {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val actual = pm.getActivityInfo(ComponentName(pkg, OwnedFlycastTargetActivity::class.java.name), 0)
        assertEquals(ComponentName(pkg, actual.name), EmuRouter.flycastLauncherTarget(pkg, actual))
        assertNull(EmuRouter.flycastLauncherTarget(pkg, null))
        assertNull(EmuRouter.flycastLauncherTarget(pkg, ActivityInfo(actual).apply { packageName = "com.gamebex.owned.foreign" }))
        assertNull(EmuRouter.flycastLauncherTarget(pkg, pm.getActivityInfo(ComponentName(pkg, OwnedPrivateTargetActivity::class.java.name), 0)))
        assertNull(EmuRouter.flycastLauncherTarget(pkg, pm.getActivityInfo(ComponentName(pkg, OwnedDisabledTargetActivity::class.java.name), PackageManager.MATCH_DISABLED_COMPONENTS)))
        assertNull(EmuRouter.flycastLauncherTarget(pkg, ActivityInfo(actual).apply { name = "" }))
        assertNull(EmuRouter.flycastLauncherTarget(pkg, ActivityInfo(actual).apply { applicationInfo = ApplicationInfo(actual.applicationInfo).apply { enabled = false } }))
        assertNull(EmuRouter.flycastLauncherTarget(pkg, ActivityInfo(actual).apply { applicationInfo = ApplicationInfo(actual.applicationInfo).apply { packageName = "com.gamebex.owned.foreign" } }))
        val launcher = requireNotNull(pm.getLaunchIntentForPackage(pkg)?.component)
        assertNull(EmuRouter.flycastLauncherTarget(pkg, pm.getActivityInfo(launcher, 0)))
    }

    @Test fun switchingFlycastGamesRevokesThePreviousTreeAndAFailedStartRevokesTheNewGrant() {
        val prefs = instrumentation.targetContext.getSharedPreferences("flycast_document_grant", Context.MODE_PRIVATE)
        val previous = GameDocumentsProvider.treeUri(41)
        val next = GameDocumentsProvider.treeUri(42)
        prefs.edit().putString("tree", previous.toString()).commit()
        val events = mutableListOf<String>()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun revokeUriPermission(targetPackage: String, uri: Uri, modeFlags: Int) {
                assertEquals(Emu.FLYCAST.pkg, targetPackage)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, modeFlags)
                events += "revoke:$uri"
            }
            override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
                assertEquals(Emu.FLYCAST.pkg, toPackage)
                assertEquals(next, uri)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, modeFlags)
                events += "grant:$uri"
            }
            override fun startActivity(intent: Intent) {
                events += "start"
                throw ActivityNotFoundException("Owned failed-launch fixture")
            }
        }
        try {
            assertThrows(ActivityNotFoundException::class.java) { EmuRouter.startStandalone(context, Emu.FLYCAST, Intent(Intent.ACTION_VIEW, uri)) }
            assertEquals(listOf("revoke:$previous", "grant:$next", "start", "revoke:$next"), events)
            assertNull(prefs.getString("tree", null))
        } finally { prefs.edit().clear().commit() }
    }
}
