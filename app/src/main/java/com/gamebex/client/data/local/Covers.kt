package com.gamebex.client.data.local

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Cover cache in app-private storage, so covers survive SD-card removal. Files are named by the key's sha1. */
object Covers {
    fun dir(context: Context): File = File(context.filesDir, "covers").apply { mkdirs() }

    fun fileFor(context: Context, key: String): File {
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir(context), "$sha1.jpg")
    }

}
