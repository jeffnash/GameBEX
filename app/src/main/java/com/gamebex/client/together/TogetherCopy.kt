package com.gamebex.client.together

import com.gamebex.client.data.CoreNames
import com.gamebex.client.data.Emu
import com.gamebex.client.launch.EmuRouter
import com.gamebex.client.stream.StreamError

object TogetherCopy {
    const val eyebrow = "Play together"
    const val twoP = "2P"
    const val twoPHint = "Play together"
    const val deviceMenuOption = "Play together"
    fun modeLabel(mode: TogetherMode?): String = when (mode) {
        TogetherMode.RA_NETPLAY -> "RetroArch netplay"
        TogetherMode.PSP_ADHOC -> "PPSSPP ad hoc"
        TogetherMode.GB_LINK -> "Game Boy link"
        TogetherMode.STREAM -> "Stream"
        null -> ""
    }
    const val checkingPhone = "Checking this phone…"
    const val checking = "Checking"
    const val notReady = "This phone needs one thing first"
    const val hostWaitingTitle = "Waiting for the other phone"
    const val hostWaitingSub = "Open GameBEX on the other phone. It will ask to join. Both phones need to be on the same Wi-Fi."
    const val waitingForPlayer2 = "Waiting for player 2"
    const val qrCaption = "Or scan this with the other phone's camera."
    fun thisPhone(ip: String) = "This phone: $ip"
    const val thisPhoneLabel = "This phone"
    const val noWifiShort = "Not on Wi-Fi"
    fun guestJoined(name: String) = "$name joined"
    const val start = "Start"
    const val starting = "Starting"
    const val playAgain = "Play again"
    const val end = "End"
    const val cancel = "Cancel"
    const val leave = "Leave"
    const val back = "Back"
    const val recheck = "Check again"
    const val join = "Join"
    const val notNow = "Not now"
    const val removeHint = "Remove player 2"
    fun playingWith(name: String) = "Playing with $name"
    const val inTheGame = "In the game"
    fun phaseLine(phase: GuestPhase?, detail: String?, progress: Int?): String = when (phase) {
        GuestPhase.CHECKING -> "Checking their copy of the game"
        GuestPhase.NEEDS_EMULATOR -> "They need to install an emulator"
        GuestPhase.NEEDS_SETUP -> "They need to finish setup" + (detail?.let { ": $it" } ?: "")
        GuestPhase.NEEDS_CORE -> "They need a RetroArch core"
        GuestPhase.CORE_SYNC -> if (progress == null) "Their core is a different build" else "Copying your core to their phone… $progress%"
        GuestPhase.INSTALLING_CORE -> "They're installing your core"
        GuestPhase.NEEDS_GAME -> "They don't have this game yet"
        GuestPhase.DOWNLOADING -> "They're downloading it… ${progress ?: 0}%"
        GuestPhase.PICKING_GAME -> "They're picking their game"
        GuestPhase.MISMATCH -> "Their copy is a different version"
        GuestPhase.READY -> "Ready"
        GuestPhase.PLAYING -> "Playing"
        null -> ""
    }
    /** Shown while player 2's first report waits for the host pad's first input. */
    const val pressControllerPrompt = "When the game starts, press any button on your controller once."

    fun hostInstructions(mode: TogetherMode?, p2SlotPrompt: Boolean = false): List<String> = when (mode) {
        TogetherMode.RA_NETPLAY -> listOf(
            "When the game starts, hold SELECT and press R1 to open the room. The game restarts once.",
            "Pressing SELECT and R1 again closes the room.",
            "Player 2 joins from their phone.",
        )
        TogetherMode.PSP_ADHOC -> listOf(
            "The game starts in PPSSPP on both phones.",
            "On both phones, go into the game's ad hoc or multiplayer mode.",
        )
        TogetherMode.GB_LINK -> listOf(
            "The game starts on both phones, linked.",
            "Use the game's link cable menu, for example Cable Club to trade.",
            "Only one of you picks 2 player. If it jumps back to 1 player, try again.",
            "Keep RetroArch open on both phones. Opening its menu or leaving the app on one phone freezes the other.",
        )
        TogetherMode.STREAM -> listOf(
            "The game runs on this phone. The other phone shows your screen and plays its sound.",
            "Player 2 uses the other phone's controller. On this phone it's called $streamPadName.",
            "End stops the stream and removes player 2's controller.",
        ) + if (p2SlotPrompt) listOf(pressControllerPrompt) else emptyList()
        null -> emptyList()
    }
    /** [menuButton]: the printed label of the pad button RetroArch's menu uses (keycode 100). */
    fun guestInstructions(mode: TogetherMode?, host: String, menuButton: String): List<String> = when (mode) {
        TogetherMode.RA_NETPLAY -> listOf(
            "The game starts on this phone.",
            "When $host opens the room, you'll get a notification.",
            "Then press SELECT and $menuButton, go back to the Main Menu, open Netplay and choose Connect to Netplay Host. The game restarts and you're player 2.",
        )
        TogetherMode.PSP_ADHOC -> listOf(
            "The game starts in PPSSPP once $host's is ready.",
            "On both phones, go into the game's ad hoc or multiplayer mode.",
        )
        TogetherMode.GB_LINK -> listOf(
            "The game starts a few seconds after $host's, linked.",
            "Use the game's link cable menu, for example Cable Club to trade.",
            "Only one of you picks 2 player. If it jumps back to 1 player, try again.",
            "Keep RetroArch open on both phones. Opening its menu or leaving the app on one phone freezes the other.",
        )
        TogetherMode.STREAM -> listOf(
            "You'll see $host's screen on this phone. Your controller is player 2.",
            "To leave the stream, hold SELECT and START for 2 seconds or press your controller's home button.",
            "$openStream brings it back while $host is still playing.",
        )
        null -> emptyList()
    }
    fun connecting(host: String) = "Connecting to $host…"
    const val joining = "Joining"
    fun withHost(host: String) = "With $host"
    fun checkingCopy(title: String?) = "Checking your copy of ${title ?: "the game"}…"
    fun readyWaiting(host: String) = "Ready. Waiting for $host to start."
    fun startingIn(n: Int?) = if (n == null) "Starting" else "Starting in $n"
    fun downloading(title: String?) = "Downloading ${title ?: "the game"}"
    const val copyingCore = "Copying the core"
    const val installingCoreHeadline = "Installing the core"
    const val installingCore = "RetroArch opens to install the core. When its menu or the game appears, come back to GameBEX."
    const val pickTitle = "Pick your game"
    const val pickSub = "Link cable games can be different, like Pokémon Red and Blue."
    const val ended = "Session ended"
    fun actionHeadline(phase: GuestPhase?): String = when (phase) {
        GuestPhase.NEEDS_GAME -> "You need this game"
        GuestPhase.MISMATCH -> "Your copy is different"
        GuestPhase.NEEDS_EMULATOR -> "This phone needs an emulator"
        GuestPhase.NEEDS_CORE -> "This phone needs a core"
        GuestPhase.CORE_SYNC -> "Your core is a different build"
        GuestPhase.NEEDS_SETUP, null, GuestPhase.CHECKING, GuestPhase.INSTALLING_CORE,
        GuestPhase.DOWNLOADING, GuestPhase.PICKING_GAME, GuestPhase.READY, GuestPhase.PLAYING -> "This phone needs one thing first"
    }
    fun actionLabel(action: TogetherAction): String = when (action) {
        TogetherAction.RECHECK -> "Check again"
        TogetherAction.OPEN_SYSTEM -> "Open System"
        TogetherAction.OPEN_PPSSPP -> "Open PPSSPP"
        TogetherAction.OPEN_RETROARCH -> "Open RetroArch"
        TogetherAction.INSTALL_CORE -> "Install core"
        TogetherAction.COPY_CORE -> "Copy core"
        TogetherAction.DOWNLOAD -> "Download"
        TogetherAction.USE_ANYWAY -> "Use mine anyway"
        TogetherAction.OPEN_STREAM -> openStream
        TogetherAction.CANCEL_CORE_INSTALL -> "Cancel"
        TogetherAction.RETRY_CORE_INSTALL -> "Retry"
    }
    fun problemFor(r: Readiness): Problem = when (r) {
        is Readiness.NeedsEmulator -> Problem(needsEmulator(r.emu.label), TogetherAction.OPEN_SYSTEM)
        is Readiness.NeedsUpdate -> Problem(updateEmulator(r.emu.label), TogetherAction.OPEN_SYSTEM)
        is Readiness.NeedsCore -> Problem(needsCore(CoreNames.display(r.core)), TogetherAction.INSTALL_CORE)
        is Readiness.NeedsShizuku -> Problem(if (r.running) allowShizuku else startShizuku, TogetherAction.RECHECK)
        is Readiness.NeedsOpenOnce ->
            if (r.emu == Emu.RETROARCH) Problem(retroArchFileAccess, TogetherAction.OPEN_RETROARCH)
            else Problem(openOnce(r.emu.label), TogetherAction.OPEN_PPSSPP)
        is Readiness.Blocked -> Problem(r.text, TogetherAction.RECHECK)
        Readiness.Ready -> Problem(notSupported, null)
    }
    fun outcomeText(o: EmuRouter.Outcome): String = when (o) {
        is EmuRouter.Outcome.Blocked -> o.reason
        is EmuRouter.Outcome.NeedsSetup -> o.text
        is EmuRouter.Outcome.Failed -> o.msg
        is EmuRouter.Outcome.MissingEmu -> needsEmulator(o.emu.label)
        is EmuRouter.Outcome.Launched -> ""
    }
    fun needsEmulator(label: String) = "$label isn't installed on this phone. Install it from System, then come back."
    fun needsCore(core: String) = "RetroArch needs the $core core on this phone."
    fun updateEmulator(label: String) = "This $label is too old to play online together. Update it from System, then come back."
    const val startShizuku = "This needs Shizuku. Start it on this phone, then check again."
    const val allowShizuku = "Allow GameBEX in Shizuku, then check again."
    const val shizukuRequired = "Playing PSP together needs Shizuku running on both phones."
    const val shizukuRequiredRa = "Playing together in RetroArch needs Shizuku on this phone."
    fun openOnce(label: String) = "Open $label once and finish its first-time setup, then come back."
    /** RetroArch never opened, so it hasn't asked for file access yet. */
    const val retroArchFileAccess = "Open RetroArch once and allow it to access files, then come back."
    const val notSupported = "This game can't be played together yet."
    const val gameFileMissing = "The game file isn't on this phone right now (SD card?)."
    const val noWifi = "Connect to Wi-Fi first. Both phones need to be on the same network."
    fun fetchingCore(pct: Int) = "Downloading the core… $pct%"
    const val coreInstallBlocked = "RetroArch didn't open to install the core. Update RetroArch and try again."
    fun missingDownloadable(title: String, source: String) = "You don't have $title. Download it from $source?"
    fun missingNotDownloadable(title: String) = "You don't have $title on this phone. Add it, then join again."
    const val noGbGames = "You don't have any Game Boy games on this phone."
    fun mismatchRa(title: String, host: String) = "Your copy of $title is a different version from $host's, so the game would go out of sync."
    fun mismatchPsp(title: String, host: String) = "Your copy of $title looks different from $host's. Ad hoc play usually needs the same region."
    fun coreDiffers(host: String, core: String) = "$host's $core core is a different build. Copy it to this phone so the game stays in sync?"
    fun needsCoreFromHost(host: String, core: String) = "This phone doesn't have the $core core. Copy $host's so the game stays in sync?"
    const val coreCopyFailed = "The core didn't copy over correctly. Try again."
    fun coreSyncUnavailable(host: String) = "Couldn't copy $host's core. You can still play; RetroArch warns if it goes out of sync."
    const val downloadFailed = "The download didn't finish."
    const val downloadNeedsChoice = "This game has several versions. Download it from its page first, then join again."
    const val downloadNeedsAssist = "The download needs a check. Open Downloads on this phone to finish it."
    const val downloadParkedSd = "The download is waiting for the SD card."
    fun alreadyDownloading(title: String) = "$title is already downloading."
    fun launchFailed(text: String) = "Couldn't start the game: $text"
    const val configUnwritable = "GameBEX couldn't write the session settings to storage."
    const val ppssppWriteFailed = "GameBEX couldn't change PPSSPP's network settings."
    const val ppssppRestoreFailed = "GameBEX couldn't put PPSSPP's settings back from last time. Start Shizuku and try again."
    fun peerLeft(name: String) = "$name left."
    fun linkLost(name: String) = "Lost the connection to $name."
    fun cantReach(name: String) = "Couldn't reach $name. Both phones need to be on the same Wi-Fi."
    fun byeText(reason: ByeReason, name: String): String = when (reason) {
        ByeReason.LEFT -> "$name ended the session."
        ByeReason.REMOVED -> "$name removed you from the session."
        ByeReason.FULL -> "$name already has a player 2."
        ByeReason.VERSION -> "Update GameBEX on both phones to play together."
        ByeReason.ERROR -> "The session closed."
    }
    fun roomOpen(host: String) = "$host's room is open. Connect from RetroArch's Netplay menu."
    fun alertRoomOpenTitle(host: String) = "$host's room is open"
    fun alertRoomOpenText(menuButton: String) = "SELECT + $menuButton, Main Menu, Netplay, Connect to Netplay Host."
    const val alertTapToOpen = "Tap to open GameBEX."
    fun alertTapToStartTitle(host: String) = "Ready to continue with $host"
    const val alertTapToStartText = "Tap to open GameBEX and carry on."
    const val channelOngoing = "Play together"
    const val channelAlerts = "Play together alerts"
    const val ongoingTitle = "Play together"
    const val ongoingWaiting = "Waiting for the other phone"
    fun ongoingJoined(name: String) = "$name joined"
    fun ongoingConnected(host: String) = "Connected to $host"
    fun ongoingPlaying(name: String, game: String) = "With $name · $game"
    const val endAction = "End"
    const val nearby = "Nearby"
    const val hubEmpty = "No one's hosting nearby. On the other phone, open a game and press 2P. Both phones need to be on the same Wi-Fi."
    const val joinByAddress = "Join by address"
    const val addressTitle = "Join by address"
    const val addressPrompt = "Type the address shown on the other phone."
    const val addressHint = "10.42.1.20"
    const val addressInvalid = "That isn't an address GameBEX can use. It looks like 10.42.1.20."
    /** The hub's "This phone" line: the name other phones see. */
    fun shownAs(name: String) = "Shown as $name"
    const val rename = "Rename"
    const val renameTitle = "Console name"
    const val renamePrompt = "Other phones see this name when you host or join."
    const val renameHint = "Up to 24 characters"
    const val usePhoneName = "Use phone name"
    const val save = "Save"
    const val linkInvalid = "That join link doesn't work. Ask for a new one."
    const val otherPhone = "the other phone"
    const val inviteTitle = "Play together?"
    fun inviteBody(name: String, game: String, system: String) = "$name is hosting $game ($system)."

    const val streamPadName = "GameBEX Player 2"
    fun useModeInstead(mode: TogetherMode) = "Use ${modeLabel(mode)} instead"
    const val openStream = "Open stream"
    const val showStats = "Show stats"
    const val hideStats = "Hide stats"
    fun watching(host: String) = "Watching $host's game"
    const val streamClosed = "The stream is closed on this phone. Open it again to keep playing."
    const val streamUnavailable = "Streaming isn't available in this build of GameBEX."
    const val streamNeedsAndroid13 = "Streaming from this phone needs Android 13 or newer."
    const val streamNeedsAndroid10 = "Watching a stream needs Android 10 or newer."
    const val streamNoController = "This phone won't let GameBEX add a controller for player 2."
    const val streamNoScreen = "This phone won't let GameBEX share its screen."
    const val streamNoEncoder = "This phone has no video encoder GameBEX can use."
    const val streamNoDecoder = "This phone can't play the stream: it has no H.264 video decoder."
    const val streamNoSound = "Streaming without sound: this phone won't share the game's audio."
    const val streamNetwork = "Couldn't open the stream on this phone's Wi-Fi. Check Wi-Fi and try again."
    const val streamSharingCancelled = "Sharing was cancelled. Start again to share the game."
    const val streamHostStoppedSharing = "The host stopped sharing."
    const val streamStopped = "The stream stopped. Press Play again to restart it."
    const val streamNotReady = "The stream from the other phone didn't start in time."
    const val streamShellNoAnswer = "Shizuku didn't start GameBEX's stream service. Check again; if it keeps happening, restart Shizuku."
    fun streamCantOpen(host: String) = "Couldn't open the stream from $host. Both phones need to be on the same Wi-Fi."
    fun streamLost(host: String) = "Lost the stream from $host."
    fun streamEnded(host: String) = "$host stopped the stream."
    const val streamRefused = "The other phone didn't accept this stream. Leave and join the session again."
    const val streamUpdateBoth = "Update GameBEX on both phones to use Stream."
    const val streamReplaced = "The stream opened again on this phone, so this copy closed."
    const val tapForMenu = "Tap the screen for Leave and stats"
    const val streamReconnecting = "Reconnecting…"
    const val streamWaitingForHost = "Waiting for the host to share the game"
    fun streamLinkLostHost(name: String, seconds: Int) = "Lost the connection to $name. The stream stays up for $seconds seconds in case it comes back."
    /** Host: the grace is over and the guest is still watching. */
    fun streamLinkLostStill(name: String) = "Lost the connection to $name, but the stream is still running."
    /** Host: the guest's phone went quiet while the stream is up. */
    fun streamPeerSilent(name: String) = "$name's phone stopped responding. The stream stays on while it tries to come back."
    /** Guest viewer: the stream recovered but the link to the host did not. */
    fun streamLinkLostStillGuest(host: String) = "Lost the connection to $host, but the stream is still running."
    fun streamLinkLostGuest(host: String) = "Lost the connection to $host. Trying to keep the stream going…"
    const val weakWifi = "Weak Wi-Fi: lowering the picture quality"
    const val leaveStreamHint = "Hold SELECT and START to leave"
    const val leaveStream = "Leave stream"
    const val hostStreamWaiting = "Stream is on. Waiting for the other phone to connect."
    fun hostStreamLine(guest: String, fps: Int, bitrate: Int) = "Streaming to $guest · $fps fps · ${mbps(bitrate)} Mbps"
    fun mbps(bps: Int): String = String.format(java.util.Locale.US, "%.1f", bps / 1_000_000.0)
    const val netherPlayer2 = "One time only: in NetherSX2 open Settings, Controllers, Controller Port 2. Choose DualShock 2, then Automatic Mapping, and pick GameBEX Player 2."
    /** Flycast assigns controllers to ports in first-use order (InputDeviceManager.createDevice); no setting reassigns them. */
    const val flycastPlayer2 = "One time only: Flycast gives player 1 to the first controller used. Play on a controller on this phone first and GameBEX Player 2 becomes player 2. On touch alone, the guest shares player 1."
    fun streamPlayer2Other(emu: String) = "In $emu, set controller port 2 to $streamPadName. GameBEX can't set it for you."
    fun streamStartFailed(error: StreamError?): String = when (error) {
        StreamError.OLD_ANDROID -> streamNeedsAndroid13
        StreamError.SHARING_CANCELLED -> streamSharingCancelled
        StreamError.DISPLAY_CAPTURE -> streamNoScreen
        StreamError.ENCODER -> streamNoEncoder
        StreamError.UHID -> streamNoController
        StreamError.NETWORK -> streamNetwork
        StreamError.SHELL_UNAVAILABLE -> startShizuku
        StreamError.BAD_CONFIG, null -> streamStopped
    }

    /** The rail cell and Home header pill that open the hub. One word: the rail cell is 96 wide. */
    const val navLabel = "Together"
    const val hostTitle = "Host a game"
    /** The A-button action on a "Host a game" row. */
    const val hostAction = "Host"
    const val hostEmpty = "NES, SNES, Genesis, Game Boy, PSP, PlayStation, N64 and GameCube games can be played together, and they show here when they're on this phone."
}
