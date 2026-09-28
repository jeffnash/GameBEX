package com.gamebex.client.data

import com.gamebex.client.data.RetroBios.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dynamic RetroBIOS resolution: declarations, md5/name fallback, conflicts. */
class RetroBiosTest {
    private val platformsJson = """
        {"schema_version":1,"items":[
          {"id":"other","contract":{"systems":{}}},
          {"id":"retroarch","contract":{"systems":{
            "sega-dreamcast":{"files":[
              {"name":"dc_boot.bin","destination":"dc/dc_boot.bin",
               "md5":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sha1":"${"1".repeat(40)}","size":8}
            ]},
            "arcade":{"files":[
              {"name":"neogeo.zip","destination":"neogeo.zip",
               "md5":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","sha1":"${"2".repeat(40)}","size":4}
            ]},
            "snk-neogeo-cd":{"files":[
              {"name":"neocdz.zip","destination":"neocdz.zip",
               "md5":"cccccccccccccccccccccccccccccccc","sha1":"${"3".repeat(40)}","size":6}
            ]},
            "nintendo-gbc":{"files":[
              {"name":"gbc_bios.bin","destination":"gbc_bios.bin"}
            ]}
          }}}
        ]}
    """.trimIndent()

    private val databaseJson = """
        {"schema_version":1,"total_files":9,"total_size":99,"files":{
          "${"1".repeat(40)}":{"path":"bios/Sega/Dreamcast/dc_boot.bin","name":"dc_boot.bin",
            "size":8,"sha1":"${"1".repeat(40)}",
            "md5":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "sha256":"${"4".repeat(64)}","crc32":"12345678","adler32":"12345678"},
          "${"2".repeat(40)}":{"path":"bios/Arcade/Arcade/neogeo.zip","name":"neogeo.zip",
            "size":4,"sha1":"${"2".repeat(40)}",
            "md5":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "sha256":"${"5".repeat(64)}","crc32":"12345678","adler32":"12345678"},
          "${"a".repeat(40)}":{"path":"bios/Nintendo/Game Boy Advance/GBA_bios.rom","name":"GBA_bios.rom",
            "size":16,"sha1":"${"a".repeat(40)}",
            "md5":"a860e8c0b6d573d191e4ec7db1b1e4f6",
            "sha256":"${"6".repeat(64)}","crc32":"12345678","adler32":"12345678"},
          "${"b".repeat(40)}":{"path":"bios/Sony/PlayStation 2/SCPH-39001.bin","name":"SCPH-39001.bin",
            "size":32,"sha1":"${"b".repeat(40)}",
            "md5":"dddddddddddddddddddddddddddddddd",
            "sha256":"${"7".repeat(64)}","crc32":"12345678","adler32":"12345678"},
          "${"c".repeat(40)}":{"path":"bios/Nintendo/GameCube/GC/USA/IPL.bin","name":"IPL.bin",
            "size":64,"sha1":"${"c".repeat(40)}",
            "md5":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            "sha256":"${"8".repeat(64)}","crc32":"12345678","adler32":"12345678"},
          "${"d".repeat(40)}":{"path":"bios/Nintendo/GameCube/GC/EUR/IPL.bin","name":"IPL.bin",
            "size":64,"sha1":"${"d".repeat(40)}",
            "md5":"ffffffffffffffffffffffffffffffff",
            "sha256":"${"9".repeat(64)}","crc32":"12345678","adler32":"12345678"},
          "${"e".repeat(40)}":{"path":"bios/SNK/Neo Geo CD/neocdz.zip","name":"neocdz.zip",
            "size":7,"sha1":"${"e".repeat(40)}",
            "md5":"11111111111111111111111111111111",
            "sha256":"${"0".repeat(64)}","crc32":"12345678","adler32":"12345678"},
          "${"f".repeat(40)}":{"path":"bios/Arcade/Arcade/neocdz.zip","name":"neocdz.zip",
            "size":9,"sha1":"${"f".repeat(40)}",
            "md5":"22222222222222222222222222222222",
            "sha256":"${"1".repeat(64)}","crc32":"12345678","adler32":"12345678"},
          "${"9".repeat(40)}":{"path":"bios/Nintendo/Game Boy Color/gbc_bios.bin","name":"gbc_bios.bin",
            "size":2304,"sha1":"${"9".repeat(40)}",
            "md5":"dbfce9db9deaa2567f6a84fde55f9680",
            "sha256":"${"2".repeat(64)}","crc32":"12345678","adler32":"12345678"}
        },"indexes":{"by_md5":{},"by_name":{},"by_crc32":{},"by_sha256":{},"by_path_suffix":{}}}
    """.trimIndent()

    private val index = RetroBios.buildIndex(platformsJson, databaseJson)

    @Test fun declarationResolvesToRepoPath() {
        val want = BiosTable.BiosFile("dc_boot.bin", 8, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val r = index.resolve("dreamcast", want) as RetroBios.ResolveResult.Ok
        assertEquals("bios/Sega/Dreamcast/dc_boot.bin", r.resolved.repoPath)
        assertEquals("dc_boot.bin", r.resolved.saveName)
        assertEquals(8, r.resolved.size)
    }

    @Test fun md5FallbackFindsRenamedDump() {
        // Repo calls it GBA_bios.rom; same dump as gba_bios.bin.
        val want = BiosTable.BiosFile("gba_bios.bin", 16384, "a860e8c0b6d573d191e4ec7db1b1e4f6")
        val r = index.resolve("gba", want) as RetroBios.ResolveResult.Ok
        assertEquals("bios/Nintendo/Game Boy Advance/GBA_bios.rom", r.resolved.repoPath)
        assertEquals("gba_bios.bin", r.resolved.saveName)
    }

    @Test fun nameFallbackIsNormalized() {
        // Punctuation-insensitive: scph39001.bin <-> SCPH-39001.bin.
        val want = BiosTable.BiosFile("scph39001.bin")
        val r = index.resolve("ps2", want) as RetroBios.ResolveResult.Ok
        assertEquals("bios/Sony/PlayStation 2/SCPH-39001.bin", r.resolved.repoPath)
    }

    @Test fun unsourcedDeclarationFallsBackToSystemNativeDump() {
        // Declared neocdz dump isn't in the tree (upstream gap): name
        // fallback must prefer the system-native dump over the Arcade
        // romset member for a non-arcade system.
        val want = BiosTable.BiosFile("neocdz.zip")
        val r = index.resolve("neogeocd", want) as RetroBios.ResolveResult.Ok
        assertEquals("bios/SNK/Neo Geo CD/neocdz.zip", r.resolved.repoPath)
    }

    @Test fun regionPrefersUsa() {
        val want = BiosTable.BiosFile("IPL.bin")
        val r = index.resolve("gc", want) as RetroBios.ResolveResult.Ok
        assertEquals("bios/Nintendo/GameCube/GC/USA/IPL.bin", r.resolved.repoPath)
    }

    @Test fun declarationWithoutMd5FallsBackToName() {
        val want = BiosTable.BiosFile("gbc_bios.bin", 2304, "dbfce9db9deaa2567f6a84fde55f9680")
        val r = index.resolve("gbc", want) as RetroBios.ResolveResult.Ok
        assertEquals("bios/Nintendo/Game Boy Color/gbc_bios.bin", r.resolved.repoPath)
    }

    @Test fun pinConflictNeverDownloads() {
        val want = BiosTable.BiosFile("dc_boot.bin", 8, "0".repeat(32))
        val r = index.resolve("dreamcast", want)
        assertTrue(r is RetroBios.ResolveResult.Conflict)
    }

    @Test fun unknownFileIsMissing() {
        val r = index.resolve("dreamcast", BiosTable.BiosFile("nope.bin"))
        assertTrue(r is RetroBios.ResolveResult.Missing)
    }

    @Test fun rawUrlEncodesSegments() {
        assertEquals(
            "https://raw.githubusercontent.com/Abdess/retrobios/main/bios/SNK/Neo%20Geo%20CD/neocdz.zip",
            RetroBios.rawUrl("bios/SNK/Neo Geo CD/neocdz.zip"),
        )
        assertEquals(
            "http://localhost:9/bios/a%20b.bin",
            RetroBios.rawUrl("bios/a b.bin", "http://localhost:9"),
        )
    }
}
