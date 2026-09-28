package com.gamebex.client.ui.sources

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamebex.client.net.DownloadEngine
import com.gamebex.client.sources.InstalledSource
import com.gamebex.client.sources.SourceDetail
import com.gamebex.client.sources.SourceRegistry
import com.gamebex.client.sources.SourceSelection
import com.gamebex.client.sources.StaleSourcePreviewException
import com.gamebex.source.SourceException
import com.gamebex.source.SourceFailure
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Install picker flow. Every registry exception is mapped to text here. */
sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Preview(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val revision: String,
        val replacesRevision: String?,
        val addedOrigins: List<String>,
    ) : ImportState {
        val isReplacement: Boolean get() = replacesRevision != null
    }
    data object Installing : ImportState
    data class Error(val message: String) : ImportState
}

/** Disable/remove impact dialog. */
data class SourceImpact(val id: String, val name: String, val removing: Boolean, val jobCount: Int)

/**
 * Sources screen state: installed list + selection straight from the
 * registry, import preview/install, detail, and impact dialogs.
 */
class SourcesViewModel(
    private val sources: SourceRegistry,
    private val engine: DownloadEngine,
    private val appContext: android.content.Context,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val installed: StateFlow<List<InstalledSource>> = sources.list()
    val selected: StateFlow<SourceSelection?> = sources.selected

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _import

    private val _detail = MutableStateFlow<SourceDetail?>(null)
    val detail: StateFlow<SourceDetail?> = _detail

    private val _impact = MutableStateFlow<SourceImpact?>(null)
    val impact: StateFlow<SourceImpact?> = _impact

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Preview bytes live here (memory + staged file) until confirmed. */
    private var previewBytes: ByteArray? = null

    init {
        // Recreation during preview: re-read the staged bytes and show
        // the preview again (or Idle when they are gone); never install.
        if (saved.get<Boolean>(KEY_PREVIEWING) == true) {
            viewModelScope.launch(Dispatchers.IO) {
                val bytes = readStagedPreview()
                if (bytes == null) {
                    _import.value = ImportState.Idle
                } else {
                    showPreview(bytes)
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * Picker result. Null (cancelled) returns to Idle with no side
     * effects; bytes are staged and validated, never installed.
     */
    fun onPickerBytes(bytes: ByteArray?) {
        if (bytes == null) {
            _import.value = ImportState.Idle
            return
        }
        _import.value = ImportState.Reading
        viewModelScope.launch(Dispatchers.IO) {
            writeStagedPreview(bytes)
            showPreview(bytes)
        }
    }

    private suspend fun showPreview(bytes: ByteArray) {
        val preview = try {
            withContext(Dispatchers.Default) { sources.preview(bytes) }
        } catch (e: SourceException) {
            _import.value = ImportState.Error(importError(e))
            return
        } catch (e: Exception) {
            _import.value = ImportState.Error("Couldn't read that file.")
            return
        }
        previewBytes = bytes
        saved[KEY_PREVIEWING] = true
        _import.value = ImportState.Preview(
            id = preview.definition.id, name = preview.definition.name,
            author = preview.definition.author, version = preview.definition.version,
            revision = preview.revision, replacesRevision = preview.replacesRevision,
            addedOrigins = preview.addedOrigins.sorted(),
        )
    }

    fun cancelPreview() {
        previewBytes = null
        saved[KEY_PREVIEWING] = false
        clearStagedPreview()
        _import.value = ImportState.Idle
    }

    /** Install the staged preview bytes (replacement keeps its revision pin). */
    fun confirmImport() {
        val bytes = previewBytes ?: return
        val state = _import.value as? ImportState.Preview ?: return
        _import.value = ImportState.Installing
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sources.install(bytes, state.replacesRevision)
                previewBytes = null
                saved[KEY_PREVIEWING] = false
                clearStagedPreview()
                // Replacement from detail: refresh the open detail.
                _detail.value?.id?.let { loadDetailOnMain(it) }
                _import.value = ImportState.Idle
            } catch (e: StaleSourcePreviewException) {
                _import.value = ImportState.Error("Source changed; review again.")
            } catch (e: SourceException) {
                _import.value = ImportState.Error(importError(e))
            } catch (e: Exception) {
                _import.value = ImportState.Error("Couldn't install that file.")
            }
        }
    }

    fun dismissError() {
        if (_import.value is ImportState.Error) _import.value = ImportState.Idle
    }

    fun loadDetail(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _detail.value = runCatching { sources.describe(id) }.getOrNull()
        }
    }

    fun clearDetail() {
        _detail.value = null
    }

    fun select(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sources.select(id) }
                .onFailure { _message.value = actionError(it) }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sources.setEnabled(id, enabled) }
                .onFailure { _message.value = actionError(it) }
            _impact.value = null
            loadDetailOnMain(id)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sources.remove(id) }
                .onFailure { _message.value = actionError(it) }
            _impact.value = null
            _detail.value = null
        }
    }

    /** Impact dialog: affected nonterminal jobs; bytes and games are kept. */
    fun loadImpact(id: String, removing: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = sources.list().value.firstOrNull { it.id == id } ?: return@launch
            val count = engine.countAffectedJobs(id)
            _impact.value = SourceImpact(id, entry.name.ifBlank { id }, removing, count)
        }
    }

    fun clearImpact() {
        _impact.value = null
    }

    private fun previewFile(): File = File(appContext.cacheDir, "source_preview.json")

    private fun writeStagedPreview(bytes: ByteArray) {
        runCatching { previewFile().writeBytes(bytes) }
    }

    private fun readStagedPreview(): ByteArray? =
        runCatching { previewFile().takeIf { it.isFile }?.readBytes() }.getOrNull()

    private fun clearStagedPreview() {
        runCatching { previewFile().delete() }
    }

    private suspend fun loadDetailOnMain(id: String) {
        _detail.value = runCatching { sources.describe(id) }.getOrNull()
    }

    companion object {
        private const val KEY_PREVIEWING = "sources:previewing"

        /**
         * Human-readable install error: JSON path for invalid
         * definitions, never response bodies or secrets. Pure; tested.
         */
        fun importError(e: SourceException): String {
            val path = e.fieldPath
            return when (e.failure) {
                SourceFailure.INVALID_DEFINITION ->
                    if (path != null) "That file isn't a valid source ($path)."
                    else "That file isn't a valid source."
                SourceFailure.INCOMPATIBLE ->
                    "That source needs a newer GameBEX version."
                SourceFailure.UNAVAILABLE ->
                    "That source isn't available."
                else -> "Couldn't read that file."
            }
        }

        fun actionError(e: Throwable): String = when {
            e is SourceException && e.failure == SourceFailure.UNAVAILABLE ->
                "That source isn't available."
            e is StaleSourcePreviewException -> "Source changed; review again."
            else -> "That didn't work. Try again."
        }
    }
}
