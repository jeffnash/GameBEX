package com.gamebex.client.together

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherBackupRulesTest {
    @Test fun togetherPrefsAreExcludedFromBackupAndTransfer() {
        val module = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/res/xml/backup_rules.xml").isFile }
        val backup = File(module, "src/main/res/xml/backup_rules.xml").readText()
        val extraction = File(module, "src/main/res/xml/data_extraction_rules.xml").readText()
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        assertTrue(backup.contains("path=\"together.xml\""))
        assertTrue(extraction.contains("path=\"together.xml\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
