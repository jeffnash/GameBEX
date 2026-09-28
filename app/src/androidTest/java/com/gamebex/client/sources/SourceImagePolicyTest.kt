package com.gamebex.client.sources

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SourceImagePolicyTest {
    private lateinit var scope: CoroutineScope
    private lateinit var registry: SourceRegistry

    @Before
    fun setup() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(ctx.cacheDir, "img-policy-${System.nanoTime()}")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        registry = SourceRegistry(SourceStore(root))
        registry.initialize()
        registry.install(MINIMAL_DEF.toByteArray(Charsets.UTF_8), null)
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    private fun loaders(): SourceImageLoaders {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return SourceImageLoaders(ctx, registry, scope)
    }

    @Test fun resolveEnabledReturnsLoader() = runBlocking {
        val loader = loaders().resolve(SourceImage("shop", "rev", "https://art.example.com/x.jpg"))
        assertNotNull(loader)
    }

    @Test fun resolveMissingReturnsNull() = runBlocking {
        assertNull(loaders().resolve(SourceImage("nope", "rev", "https://art.example.com/x.jpg")))
    }

    @Test fun resolveDisabledReturnsNull() = runBlocking {
        registry.setEnabled("shop", false)
        assertNull(loaders().resolve(SourceImage("shop", "rev", "https://art.example.com/x.jpg")))
    }

    @Test fun reenableResolvesAgain() = runBlocking {
        registry.setEnabled("shop", false)
        assertNull(loaders().resolve(SourceImage("shop", "rev", "https://art.example.com/x.jpg")))
        registry.setEnabled("shop", true)
        assertNotNull(loaders().resolve(SourceImage("shop", "rev", "https://art.example.com/x.jpg")))
    }

    @Test fun staleCapturedRevisionStillResolvesCurrent() = runBlocking {
        val loader = loaders().resolve(SourceImage("shop", "ancient", "https://art.example.com/x.jpg"))
        assertNotNull(loader)
    }

    companion object {
        private fun op(vararg inputs: String): String {
            val ins = inputs.joinToString(",") { "\"$it\"" }
            return "{\"inputs\":[$ins],\"steps\":[],\"result\":{\"op\":\"literal\",\"value\":[]}}"
        }

        private val MINIMAL_DEF = """
        {
          "schemaVersion": 1, "id": "shop", "name": "Shop", "author": "tests", "version": "1.0.0",
          "requires": ["http-v1", "expressions-v1", "catalog-v1"],
          "origins": {
            "catalog": ["https://catalog.example.com"],
            "download": ["https://dl.example.com"],
            "artwork": ["https://art.example.com"]
          },
          "platforms": {"slug-a": "gb"},
          "operations": {
            "consoles": ${op()},
            "types": ${op()},
            "latest": ${op("cursor")},
            "browse": ${op("consoleId", "typeId", "cursor")},
            "search": ${op("query", "platformId", "cursor")},
            "detail": ${op("ref")},
            "variants": ${op("ref")},
            "resolveDownload": ${op("file")}
          }
        }
        """.trimIndent()
    }
}
