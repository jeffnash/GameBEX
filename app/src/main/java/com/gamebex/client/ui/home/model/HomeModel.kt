package com.gamebex.client.ui.home.model

import androidx.compose.runtime.Immutable
import com.gamebex.client.ui.LibSort
import com.gamebex.client.ui.presentation.LaunchStatus
import com.gamebex.source.RomEntry

/** Stable card identity; also the LazyGrid key and the focus-memory value. */
@Immutable
@JvmInline
value class CardKey(val value: String) {
    companion object {
        fun local(gameId: Long) = CardKey("local:$gameId")
        fun download(downloadId: Long) = CardKey("dl:$downloadId")
        fun site(sourceId: String, itemKey: String) = CardKey("site:$sourceId:$itemKey")
        fun all(group: HomeGroup) = CardKey("all:${group.name}")
        fun invite(kind: InviteKind) = CardKey("invite:${kind.name}")
        fun link(kind: LinkKind) = CardKey("link:${kind.name}")
    }
}

enum class HomeMode { HOME, SEARCH, SURPRISE, FIRST_RUN }
enum class HomeGroup { RESUME, DOWNLOADING, NEW, SURPRISE, SEARCH_YOURS, SEARCH_SITE, SETUP }
enum class CardKind { GAME, ALL_TILE, INVITE, QUIET_LINK }
enum class InviteKind { ADD_PLUGIN, SCAN_PHONE, CHECK_SYSTEMS, ROLL_AGAIN }
enum class LinkKind { NO_PLUGIN_HINT, SEE_ALL_ON_SITE, OFFLINE_NOTE, NOTHING_FOUND, SEARCH_FAILED, SEARCHING }

enum class ArtKind { SAVE_THUMB, COVER, NONE }

/**
 * [model] is a File or URL String. A non-null [sourceId] marks a plugin-hosted URL
 * that must go through that source's image loader. [revision] busts caches.
 *
 * [systemFolder] and [title] let an [ArtKind.NONE] ref still render a generated
 * game case. The factory sets COVER/SAVE_THUMB only when the File exists or
 * [model] is a URL.
 */
@Immutable
data class ArtRef(
    val model: Any?,
    val kind: ArtKind,
    val sourceId: String? = null,
    val revision: Long = 0,
    val systemFolder: String? = null,
    val title: String? = null,
) {
    companion object { val None = ArtRef(null, ArtKind.NONE) }
}

/** short = "GC"; name = "GameCube"; emulator = emulatorLabel(folder, overrides), e.g. "RetroArch · Dolphin". */
@Immutable
data class SystemTag(val folder: String, val short: String, val name: String, val emulator: String?)

@Immutable
data class SizeInfo(val bytes: Long?, val label: String)

@Immutable sealed interface CardState {
    @Immutable
    data class Saved(val atMs: Long, val count: Int) : CardState
    @Immutable
    data class Played(val atMs: Long) : CardState
    @Immutable
    data class Added(val atMs: Long) : CardState
    @Immutable
    data class NeedsBios(val files: List<String>) : CardState
    @Immutable
    data class NeedsEmulator(val what: String) : CardState
    @Immutable
    data class NeedsFix(val note: String) : CardState
    data object Preparing : CardState
    @Immutable
    data class Downloading(
        val read: Long,
        val total: Long,
        val etaSec: Long?,
        val paused: Boolean,
        val armed: Boolean,
    ) : CardState
    @Immutable
    data class Queued(val armed: Boolean) : CardState
    @Immutable
    data class DownloadProblem(val text: String, val verify: Boolean) : CardState
    @Immutable
    data class OffPhone(val size: SizeInfo?, val saves: Int) : CardState
    @Immutable
    data class OnSite(val size: SizeInfo?) : CardState
    @Immutable
    data class NoRoom(val neededBytes: Long, val freeBytes: Long) : CardState
    data object WaitingForConnection : CardState
    @Immutable
    data class Unsupported(val reason: String) : CardState
    /** Played game whose storage is gone (e.g. ejected SD card). */
    @Immutable
    data class Unavailable(val reason: String) : CardState
    data object None : CardState
}

@Immutable sealed interface HomeDestination {
    @Immutable
    data class Library(val sort: LibSort? = null) : HomeDestination
    data object Downloads : HomeDestination
    data object Discover : HomeDestination
    @Immutable
    data class SiteSearch(val query: String) : HomeDestination
    data object Sources : HomeDestination
    data object System : HomeDestination
    /** The Play together hub (Home header pill). */
    data object Together : HomeDestination
    @Immutable
    data class Game(val gameId: Long) : HomeDestination
    @Immutable
    data class SiteEntry(val entry: RomEntry) : HomeDestination
}

@Immutable sealed interface HomeAction {
    @Immutable
    data class Play(val gameId: Long) : HomeAction
    @Immutable
    data class DownloadAgain(val gameId: Long) : HomeAction
    @Immutable
    data class DownloadFromSite(val entry: RomEntry) : HomeAction
    @Immutable
    data class TogglePlayWhenReady(val downloadId: Long) : HomeAction
    @Immutable
    data class PauseDownload(val downloadId: Long) : HomeAction
    @Immutable
    data class ResumeDownload(val downloadId: Long) : HomeAction
    @Immutable
    data class Go(val dest: HomeDestination) : HomeAction
    data object RescanPhone : HomeAction
    data object RollAgain : HomeAction
    /** Disabled button (Preparing…, Download when online). */
    data object None : HomeAction
}

enum class TvTone { NORMAL, GOOD, WARN, MUTED }

/** Leading pixel glyph for [TvModel.stateKey] on the TV banner. */
enum class TvGlyph { RESUME, NEW, DOWNLOAD, CLOUD, WARN, OFFLINE, PLUG, DICE, FOLDER }

@Immutable sealed interface TvContent {
    /** [progress] 0..1 draws the download ring on the TV. */
    @Immutable
    data class Picture(val art: ArtRef, val dim: Boolean = false, val progress: Float? = null) : TvContent
    @Immutable
    data class Mosaic(val arts: List<ArtRef>) : TvContent
    @Immutable
    data class TestCard(val label: String) : TvContent
    @Immutable
    data class PlugCard(val label: String) : TvContent
    /** Invite / fix TV: pixel glyph plus label (plug, warn, dice…). */
    @Immutable
    data class Notice(val glyph: TvGlyph, val label: String, val tone: TvTone) : TvContent
}

@Immutable
data class TvModel(
    val content: TvContent,
    /** Uppercase headline, tone-coloured, Silkscreen ("RESUME", "DOWNLOADING 64%"). */
    val stateKey: String,
    /** Uppercase continuation in light gray ("SAVED 12 MIN AGO", "PLAYS WHEN READY"). */
    val stateDetail: String?,
    /** Leading glyph for [stateKey]. */
    val glyph: TvGlyph?,
    val factsLine: String?,
    val tone: TvTone = TvTone.NORMAL,
) {
    val stateLine: String get() = listOfNotNull(stateKey, stateDetail).joinToString(" · ")
}

enum class ButtonTone { PLAY, DOWNLOAD, FIX, ARMED, NEUTRAL }

@Immutable
data class HomeCard(
    val key: CardKey,
    val kind: CardKind,
    val title: String,
    /** Precomputed card state line (same `now` as the TV model). */
    val cardLine: String,
    /** Second line for INVITE / QUIET_LINK / ALL_TILE; game cards use [state]. */
    val body: String? = null,
    val system: SystemTag? = null,
    val thumb: ArtRef = ArtRef.None,
    /** ALL_TILE: up to 3 covers of the hidden games. */
    val stack: List<ArtRef> = emptyList(),
    val state: CardState = CardState.None,
    val primary: HomeAction,
    val primaryLabel: String,
    val primaryTone: ButtonTone = ButtonTone.PLAY,
    val secondary: HomeAction? = null,
    val secondaryLabel: String? = null,
    val tv: TvModel,
    /** 2 = full row (INVITE / QUIET_LINK). */
    val span: Int = 1,
    /**
     * Flat 0-based card index; the TV channel is position + 1. Set by
     * `HomeGrouping` when the sections are assembled.
     */
    val position: Int,
    val gameId: Long? = null,
    val downloadId: Long? = null,
)

@Immutable
data class HomeSection(
    val group: HomeGroup,
    val label: String,
    val detail: String?,
    val cards: List<HomeCard>,
)

@Immutable
data class HeaderModel(
    val searchPlaceholder: String,
    val browseLabel: String,
    /** "2", "offline", or null. */
    val downloadsBadge: String?,
    val systemWarnings: Int,
)

@Immutable
data class HomeScreenModel(
    val mode: HomeMode,
    val header: HeaderModel,
    val sections: List<HomeSection>,
    val query: String = "",
    val defaultKey: CardKey? = null,
    val status: LaunchStatus? = null,
    /** Store bumps this to force the cursor back to [defaultKey] (Roll again, mode resets). */
    val selectionToken: Long = 0,
) {
    val cards: List<HomeCard> get() = sections.flatMap { it.cards }
    fun card(key: CardKey?): HomeCard? = key?.let { k -> cards.firstOrNull { it.key == k } }
}

sealed interface HomeIntent {
    /** A, tap on the selected card, tap on the TV, primary button. */
    data class Activate(val key: CardKey) : HomeIntent
    data class Secondary(val key: CardKey) : HomeIntent
    data class Query(val text: String) : HomeIntent
    data object ClearSearch : HomeIntent
    data object Surprise : HomeIntent
    data object ExitSurprise : HomeIntent
    data class Nav(val dest: HomeDestination) : HomeIntent
    /** Screen entered or resumed: refresh saves, readiness, free space. */
    data object Entered : HomeIntent
    data object DismissStatus : HomeIntent
    data object RetryStatus : HomeIntent
    data object SecondaryStatus : HomeIntent
}

sealed interface HomeEffect {
    data class Navigate(val dest: HomeDestination) : HomeEffect
    data class Toast(val text: String) : HomeEffect
}
