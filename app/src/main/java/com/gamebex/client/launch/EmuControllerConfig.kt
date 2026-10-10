package com.gamebex.client.launch

/**
 * Pure per-emulator controller writers: (existing file text, pad) → new file
 * text. Each sets only the keys it owns and returns the input unchanged when
 * nothing needs to change, so callers can skip the write (idempotent). Every
 * key here is one seen in a real config pulled from one phone
 * (GameSir-X5s); nothing is guessed beyond those files. IO lives in
 * [EmuControllerSetup].
 */
object EmuControllerConfig {
    // RetroArch: /sdcard/RetroArch/retroarch.cfg.

    /** Same values the managed CONFIGFILE carries (see GraphicsTuner). */
    val RETROARCH_KEYS = linkedMapOf(
        "input_overlay_hide_when_gamepad_connected" to "true",
        "input_autodetect_enable" to "true",
    )

    fun retroArchCfg(text: String): String {
        val trailing = text.endsWith("\n")
        val body = if (trailing) text.dropLast(1) else text
        val lines = if (body.isEmpty()) emptyList() else body.split("\n")
        val (merged, changed) = RetroArchTuner.mergeConfig(lines, RETROARCH_KEYS)
        if (!changed) return text
        return merged.joinToString("\n") + "\n"
    }

    // Dolphin: files/Config/Dolphin.ini.

    /**
     * Touch overlay off. Button bindings are deliberately NOT written: the
     * real GCPadNew.ini holds only `Device = Android/<n>/<name>` plus sensor
     * defaults — no button expressions to copy — and the `<n>` is Dolphin's
     * per-name index, not the Android device id, so it can't be derived.
     */
    fun dolphinIni(text: String): String = IniText.set(text, "Android", "ShowInputOverlay", "False")

    /**
     * Stream host only: touch overlay ON. Local play hides it (a physical
     * pad drives P1), but a stream guest has no pad — both sides need
     * Dolphin's own overlay, which the guest then copies 1:1.
     */
    fun dolphinStreamIni(text: String): String = IniText.set(text, "Android", "ShowInputOverlay", "True")

    // Vita3K: files/config.yml.

    /**
     * The real config.yml has no on-screen-controls key (only keyboard
     * binds, `controller-binds` and `controller-axis-binds`, which already
     * hold the identity mapping), so there is nothing to write: Vita3K reads
     * the pad natively. Identity by design; kept so the pipeline is uniform.
     */
    fun vita3kYaml(text: String): String = text

    /** True when Vita3K's own pad mapping is present in the config. */
    fun vita3kHasPadBinds(text: String): Boolean =
        text.lineSequence().any { it.trimEnd() == "controller-binds:" }

    // Flycast: files/mappings/Android_<device>.cfg.

    /**
     * Flycast names mapping files `<api>_<device name>`; path separators and
     * reserved characters can't appear in a file name.
     */
    fun flycastFileName(deviceName: String): String =
        "Android_" + deviceName.map { if (it in "/\\:*?\"<>|") '-' else it }.joinToString("") + ".cfg"

    /**
     * Flycast mapping built from the axes the connected pad actually reports
     * (layouts differ by model): left stick X/Y; right stick Z/RZ, else
     * RX/RY; analog triggers LTRIGGER/RTRIGGER, else BRAKE/GAS; D-pad on
     * the hat axes when present plus the D-pad keycodes. Buttons use
     * Android's standard gamepad keycodes, identical on every pad, with
     * L2/R2 also bound as buttons for pads without analog triggers.
     * Dreamcast A/B/X/Y = Android A/B/X/Y; L1/R1 → Z/C, Select → menu.
     */
    fun flycastMappingFor(axes: Set<Int>): String {
        val analog = mutableListOf<String>()
        if (0 in axes && 1 in axes) {
            analog += listOf("0-:btn_analog_left", "0+:btn_analog_right", "1-:btn_analog_up", "1+:btn_analog_down")
        }
        val right = when {
            11 in axes && 14 in axes -> 11 to 14 // AXIS_Z / AXIS_RZ
            12 in axes && 13 in axes -> 12 to 13 // AXIS_RX / AXIS_RY
            else -> null
        }
        right?.let { (x, y) -> analog += listOf("$x-:axis2_left", "$x+:axis2_right", "$y-:axis2_up", "$y+:axis2_down") }
        val triggers = when {
            17 in axes && 18 in axes -> 17 to 18 // AXIS_LTRIGGER / AXIS_RTRIGGER
            23 in axes && 22 in axes -> 23 to 22 // AXIS_BRAKE / AXIS_GAS
            else -> null
        }
        triggers?.let { (l, r) -> analog += listOf("$l+:btn_trigger_left", "$r+:btn_trigger_right") }
        if (15 in axes && 16 in axes) {
            analog += listOf("15-:btn_dpad1_left", "15+:btn_dpad1_right", "16-:btn_dpad1_up", "16+:btn_dpad1_down")
        }
        val digital = listOf(
            "19:btn_dpad1_up", "20:btn_dpad1_down", "21:btn_dpad1_left", "22:btn_dpad1_right",
            "96:btn_a", "97:btn_b", "99:btn_x", "100:btn_y",
            "102:btn_z", "103:btn_c", "104:btn_trigger_left", "105:btn_trigger_right",
            "108:btn_start", "109:btn_menu",
        )
        val triggerAxes = listOfNotNull(triggers?.first, triggers?.second).sorted().joinToString(",")
        return buildString {
            append("[analog]\n")
            analog.forEachIndexed { i, b -> append("bind$i = $b\n") }
            append("\n[digital]\n")
            digital.forEachIndexed { i, b -> append("bind$i = $b\n") }
            append("\n[emulator]\ndead_zone = 10\nmapping_name = GameBEX\nrumble_power = 100\nsaturation = 100\n")
            append("triggers = ${triggerAxes.ifEmpty { "17,18,22,23" }}\nversion = 4\n")
        }
    }

    /** A mapping the user (or Flycast) already saved is kept as-is. */
    fun flycastMapping(existing: String?, axes: Set<Int>): String =
        existing?.takeIf { it.isNotBlank() } ?: flycastMappingFor(axes)

    // NetherSX2: files/inputprofiles/gamebex.ini.

    /**
     * Touch controls hide while a pad is connected. No bindings: NetherSX2
     * keeps them in private SharedPreferences, and its device keys
     * ("<name>[11]/Button96") use an index that isn't the Android id.
     */
    fun netherProfile(existing: String?, template: String): String =
        IniText.set(existing?.takeIf { it.isNotBlank() } ?: template, "TouchscreenController", "AutoHide", "true")
}

/**
 * Minimal INI editor that keeps everything it doesn't own byte-for-byte:
 * comments, ordering, blank lines, `key = value` spacing and line endings.
 */
object IniText {
    fun get(text: String, section: String, key: String): String? {
        val lines = splitLines(text).first
        val range = sectionRange(lines, section) ?: return null
        for (i in range) keyMatch(lines[i], key)?.let { return it.groupValues[4] }
        return null
    }

    fun set(text: String, section: String, key: String, value: String): String {
        val (lines, nl, trailing) = splitLines(text)
        val out = lines.toMutableList()
        val range = sectionRange(out, section)
        if (range == null) {
            out += "[$section]"
            out += "$key = $value"
            return join(out, nl, trailing = true)
        }
        for (i in range) {
            val m = keyMatch(out[i], key) ?: continue
            if (m.groupValues[4] == value) return text
            out[i] = m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + value
            return join(out, nl, trailing)
        }
        // Insert after the section's last non-blank line (before the gap).
        val header = range.first - 1
        val at = range.lastOrNull { out[it].isNotBlank() }?.plus(1) ?: (header + 1)
        out.add(at, "$key = $value")
        return join(out, nl, trailing)
    }

    fun remove(text: String, section: String, key: String): String {
        val (lines, nl, trailing) = splitLines(text)
        val range = sectionRange(lines, section) ?: return text
        val out = lines.toMutableList()
        var changed = false
        for (i in range.reversed()) {
            if (keyMatch(out[i], key) != null) {
                out.removeAt(i)
                changed = true
            }
        }
        return if (changed) join(out, nl, trailing) else text
    }

    fun removeEmptySection(text: String, section: String): String {
        val (lines, nl, trailing) = splitLines(text)
        val start = lines.indexOfFirst { it.trim() == "[$section]" }
        if (start < 0) return text
        val range = sectionRange(lines, section) ?: return text
        if (range.any { lines[it].isNotBlank() }) return text
        val out = lines.toMutableList()
        out.removeAt(start)
        return join(out, nl, trailing)
    }

    private fun keyMatch(line: String, key: String): MatchResult? =
        Regex("""^(\s*)(${Regex.escape(key)})(\s*=\s*)(.*?)\s*$""").find(line)

    /** Body line indices of [section] (header excluded), or null if absent. */
    private fun sectionRange(lines: List<String>, section: String): IntRange? {
        val start = lines.indexOfFirst { it.trim() == "[$section]" }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size && !lines[end].trimStart().startsWith("[")) end++
        return (start + 1) until end
    }

    private fun splitLines(text: String): Triple<List<String>, String, Boolean> {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val trailing = text.endsWith(nl)
        val body = if (trailing) text.dropLast(nl.length) else text
        return Triple(if (body.isEmpty()) emptyList() else body.split(nl), nl, trailing)
    }

    private fun join(lines: List<String>, nl: String, trailing: Boolean): String =
        lines.joinToString(nl) + if (trailing && lines.isNotEmpty()) nl else ""
}
