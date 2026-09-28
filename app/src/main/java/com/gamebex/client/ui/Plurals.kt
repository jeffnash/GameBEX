package com.gamebex.client.ui

/** "1 download" / "3 downloads"; pass [plural] for irregular nouns. */
fun countOf(n: Int, singular: String, plural: String = singular + "s"): String =
    "$n ${if (n == 1) singular else plural}"

/** Present-tense verb agreeing with [n] ("1 download uses", "2 downloads use"). */
fun verbFor(n: Int, singular: String, plural: String): String = if (n == 1) singular else plural
