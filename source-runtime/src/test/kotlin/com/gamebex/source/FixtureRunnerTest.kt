package com.gamebex.source

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class FixtureRunnerTest {
    private val json = Json {}

    private fun repo(block: File.() -> Unit): File {
        val root = createTempDirectory("sourcerepo").toFile()
        root.block()
        return root
    }

    private fun File.writeDefinition(def: SourceDefinition = TestFixtures.minimalDefinition()) {
        resolve("acme.source.json").writeText(json.encodeToString(SourceDefinition.serializer(), def))
    }

    private fun File.writeCase(name: String, operation: String, input: String, requests: String, expected: String) {
        resolve("fixtures").mkdirs()
        resolve("fixtures/cases.json").writeText(
            """[{"name":${json.encodeToString(name)},"operation":"$operation","input":$input,"requests":$requests,"expected":${json.encodeToString(expected)}}]""",
        )
    }

    @Test fun passingCaseReturnsZero() {
        val root = repo {
            writeDefinition()
            writeCase("empty-types", "types", "{}", "[]", "fixtures/expected/types.json")
            resolve("fixtures/expected/types.json").apply {
                parentFile.mkdirs()
                writeText("[]")
            }
        }
        try {
            assertEquals(0, FixtureRunner.run(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun extraRequestFails() {
        val root = repo {
            writeDefinition()
            // latest always issues its catalog GET; no scripts allow it.
            writeCase("latest-no-scripts", "latest", """{"cursor":null}""", "[]", "fixtures/expected/never.json")
        }
        try {
            assertNotEquals(0, FixtureRunner.run(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun missingRequestFails() {
        val root = repo {
            writeDefinition()
            resolve("fixtures/empty.json").apply {
                parentFile.mkdirs()
                writeText("[]")
            }
            // types issues no request, but one is scripted.
            writeCase(
                "types-unused-script", "types", "{}", """[{"method":"GET","url":"https://catalog.example.com/x",
                  |"query":{},"form":{},"headers":{},"responseStatus":200,"responseHeaders":{},"responseFile":"fixtures/empty.json"}]""".trimMargin(),
                "fixtures/expected/types.json",
            )
            resolve("fixtures/expected/types.json").apply {
                parentFile.mkdirs()
                writeText("[]")
            }
        }
        try {
            assertNotEquals(0, FixtureRunner.run(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun outputMismatchFails() {
        val root = repo {
            writeDefinition()
            writeCase("types-wrong", "types", "{}", "[]", "fixtures/expected/types.json")
            resolve("fixtures/expected/types.json").apply {
                parentFile.mkdirs()
                writeText("""[{"id":"x","name":"y"}]""")
            }
        }
        try {
            assertNotEquals(0, FixtureRunner.run(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun pathTraversalRejected() {
        val root = repo {
            writeDefinition()
            writeCase("evil", "types", "{}", "[]", "../evil.json")
        }
        try {
            assertNotEquals(0, FixtureRunner.run(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun invalidDefinitionFails() {
        val root = repo {
            resolve("acme.source.json").writeText("{oops")
            resolve("fixtures/cases.json").apply {
                parentFile.mkdirs()
                writeText("[]")
            }
        }
        try {
            assertNotEquals(0, FixtureRunner.run(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun platformDriftFails() {
        val root = repo {
            writeDefinition()
            resolve("fixtures/registry-platforms.json").apply {
                parentFile.mkdirs()
                writeText("""{"snes-slug":"snes"}""")
            }
            resolve("fixtures/cases.json").apply {
                parentFile.mkdirs()
                writeText("[]")
            }
        }
        try {
            // Minimal definition maps two aliases; the registry claims one.
            assertNotEquals(0, FixtureRunner.run(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun expectedErrorFormPasses() {
        // latest with a 500 and no fallback throws HTTP; assert the typed form.
        val ops = TestFixtures.minimalDefinition().operations
        val root = repo {
            writeDefinition(TestFixtures.minimalDefinition(ops))
            resolve("fixtures/err.txt").apply {
                parentFile.mkdirs()
                writeText("boom")
            }
            writeCase(
                "latest-500", "latest", """{"cursor":null}""",
                """[{"method":"GET","url":"https://catalog.example.com/items","query":{},
                  |"form":{},"headers":{},"responseStatus":500,"responseHeaders":{},"responseFile":"fixtures/err.txt"}]""".trimMargin(),
                "fixtures/expected/latest-500.json",
            )
            resolve("fixtures/expected/latest-500.json").apply {
                parentFile.mkdirs()
                writeText("""{"error":"HTTP"}""")
            }
        }
        try {
            assertEquals(0, FixtureRunner.run(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
