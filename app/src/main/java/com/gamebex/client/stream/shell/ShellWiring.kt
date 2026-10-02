package com.gamebex.client.stream.shell

import android.content.Context

/** Which components the shell service builds: the real ones. */
object ShellWiring {
    fun components(context: Context?): ShellComponents = RealShellComponents(context)
}
