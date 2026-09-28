package com.gamebex.source

import java.security.MessageDigest

/** Lowercase SHA-256 of exact bytes: the revision identity for definitions. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
