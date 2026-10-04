package com.gamebex.client.ui.together

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamebex.client.data.local.GameEntity
import com.gamebex.client.data.local.LibraryDb
import com.gamebex.client.together.TogetherCopy
import com.gamebex.client.together.TogetherMode
import com.gamebex.client.together.TogetherSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A library game the hub offers to host, in its default mode ([TogetherSupport.modeFor]). */
data class HostableGame(
    val id: Long,
    val title: String,
    val folder: String,
    val mode: TogetherMode,
    val lastPlayedMs: Long,
)

/**
 * The hub's "Host a game" list: every game the game page shows a 2P button for,
 * most recently played first, then never-played games by title.
 */
internal fun hostableGames(games: List<GameEntity>): List<HostableGame> =
    games.mapNotNull { game ->
        TogetherSupport.modeFor(game)?.let { HostableGame(game.id, game.title, game.folder, it, game.lastPlayedMs) }
    }.sortedWith(
        compareByDescending<HostableGame> { it.lastPlayedMs }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            .thenBy { it.id },
    )

/** A host row's second line: "SNES · RetroArch netplay". */
internal fun hostRowLine(game: HostableGame): String =
    "${togetherPlatformName(game.folder)} · ${TogetherCopy.modeLabel(game.mode)}"

/** The hub's D-pad zones: the host list (left), the nearby list and the Join by address button (right). */
enum class HubZone { HOST, NEARBY, ADDRESS }

/** Where the hub's cursor sits: a [zone] plus the remembered row of each list. */
data class HubFocus(val zone: HubZone = HubZone.HOST, val host: Int = 0, val nearby: Int = 0)

/**
 * Pure hub focus. Every move starts from [resolve]: a list with no rows can't
 * hold the cursor, so an empty host list hands it to Nearby (or the address
 * button), and an empty Nearby hands it to the address button below it.
 */
object TogetherHubFocus {
    /** Hosts shown at once in the host list. */
    const val HOST_ROWS = 5

    /** Hosts shown at once in the Nearby list. */
    const val NEARBY_ROWS = 3

    fun resolve(focus: HubFocus, hostCount: Int, nearbyCount: Int): HubFocus {
        val zone = when {
            focus.zone == HubZone.HOST && hostCount > 0 -> HubZone.HOST
            focus.zone == HubZone.HOST && nearbyCount > 0 -> HubZone.NEARBY
            focus.zone == HubZone.NEARBY && nearbyCount > 0 -> HubZone.NEARBY
            else -> HubZone.ADDRESS
        }
        return HubFocus(
            zone,
            focus.host.coerceIn(0, (hostCount - 1).coerceAtLeast(0)),
            focus.nearby.coerceIn(0, (nearbyCount - 1).coerceAtLeast(0)),
        )
    }

    /** Left: the right column goes to the host list; the host list (or an empty one) leaves to the rail (null). */
    fun left(focus: HubFocus, hostCount: Int, nearbyCount: Int): HubFocus? {
        val at = resolve(focus, hostCount, nearbyCount)
        return if (at.zone != HubZone.HOST && hostCount > 0) at.copy(zone = HubZone.HOST) else null
    }

    /** Right: the host list goes to Nearby, or to the address button when no one is hosting. */
    fun right(focus: HubFocus, hostCount: Int, nearbyCount: Int): HubFocus {
        val at = resolve(focus, hostCount, nearbyCount)
        if (at.zone != HubZone.HOST) return at
        return at.copy(zone = if (nearbyCount > 0) HubZone.NEARBY else HubZone.ADDRESS)
    }

    /** Up: the previous row; the address button goes back to the Nearby row last selected. */
    fun up(focus: HubFocus, hostCount: Int, nearbyCount: Int): HubFocus {
        val at = resolve(focus, hostCount, nearbyCount)
        return when (at.zone) {
            HubZone.HOST -> at.copy(host = (at.host - 1).coerceAtLeast(0))
            HubZone.NEARBY -> at.copy(nearby = (at.nearby - 1).coerceAtLeast(0))
            HubZone.ADDRESS -> if (nearbyCount > 0) at.copy(zone = HubZone.NEARBY) else at
        }
    }

    /** Down: the next row; past the last Nearby row is the address button. */
    fun down(focus: HubFocus, hostCount: Int, nearbyCount: Int): HubFocus {
        val at = resolve(focus, hostCount, nearbyCount)
        return when (at.zone) {
            HubZone.HOST -> at.copy(host = (at.host + 1).coerceAtMost(hostCount - 1))
            HubZone.NEARBY ->
                if (at.nearby < nearbyCount - 1) at.copy(nearby = at.nearby + 1) else at.copy(zone = HubZone.ADDRESS)
            HubZone.ADDRESS -> at
        }
    }

    /** The A hint for [zone]. */
    fun aLabel(zone: HubZone): String = when (zone) {
        HubZone.HOST -> TogetherCopy.hostAction
        HubZone.NEARBY -> TogetherCopy.join
        HubZone.ADDRESS -> TogetherCopy.joinByAddress
    }
}

/** Feeds the hub's host list from the library, the same Room flow Library and Home observe. */
class TogetherHubViewModel(appContext: Context) : ViewModel() {
    /** Null until the library's first read lands, so the empty line never flashes in. */
    val hostable: StateFlow<List<HostableGame>?> = LibraryDb.get(appContext).games().observeAll()
        .map { hostableGames(it) }
        .flowOn(Dispatchers.Default)
        .stateIn<List<HostableGame>?>(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
