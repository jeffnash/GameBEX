package com.gamebex.client.launch

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamebex.client.data.Emu
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmuRouterUriGrantTest {
    @Test fun dolphinGrantsTheExactOutgoingNamedUriReadOnlyBeforeLaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val vector = instrumentation.context.assets.open("emulator-facts/dolphin-named-uri-grant-observed.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val expectedUri = Uri.parse(vector.getValue("uri").jsonPrimitive.content)
        val events = mutableListOf<String>()
        val grants = mutableListOf<Triple<String, Uri, Int>>()
        val ctx = object : ContextWrapper(instrumentation.targetContext) {
            override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
                grants += Triple(toPackage, uri, modeFlags)
                events += "grant"
            }

            override fun startActivity(intent: Intent) {
                assertEquals(listOf("grant"), events)
                assertEquals(expectedUri, intent.data)
                assertEquals(Emu.DOLPHIN.pkg, intent.component?.packageName)
                assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                events += "launch"
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(Emu.DOLPHIN.pkg, "org.dolphinemu.dolphinemu.ui.main.MainActivity")
            data = expectedUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        EmuRouter.startStandalone(ctx, Emu.DOLPHIN, intent)

        assertEquals(listOf("grant", "launch"), events)
        assertEquals(listOf(Triple(Emu.DOLPHIN.pkg, intent.data, Intent.FLAG_GRANT_READ_URI_PERMISSION)), grants)
    }
}
