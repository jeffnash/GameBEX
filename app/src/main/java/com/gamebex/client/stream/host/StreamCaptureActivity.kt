package com.gamebex.client.stream.host

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/**
 * Shows Android's share prompt over the game (its own task, so GameBEX's screens stay behind); the
 * host picks the game's app and [StreamAppCapture] gets the answer. Android 14 delivers the result
 * even though the picked app comes to the front over this activity.
 */
class StreamCaptureActivity : Activity() {
    private var answered = false
    private var promptClosed = false
    private var requestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = savedInstanceState?.getString(EXTRA_REQUEST_ID) ?: intent.getStringExtra(EXTRA_REQUEST_ID)
        requestId = id
        if (id == null) {
            closePrompt()
            return
        }
        StreamAppCapture.attachConsent(id, this) {
            runCatching {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val prompt = if (Build.VERSION.SDK_INT >= 34) {
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
                } else {
                    manager.createScreenCaptureIntent()
                }
                startActivityForResult(prompt, REQUEST)
            }.onFailure { answer(RESULT_CANCELED, null) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        requestId?.let { outState.putString(EXTRA_REQUEST_ID, it) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Activity result API is not used for this one-shot system prompt")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) answer(resultCode, data)
    }

    override fun onDestroy() {
        requestId?.let { StreamAppCapture.detachConsent(it, this, isFinishing && !answered) }
        super.onDestroy()
    }

    private fun answer(code: Int, data: Intent?) {
        if (answered) return
        answered = true
        requestId?.let { StreamAppCapture.onConsent(it, this, code, data) }
        closePrompt()
    }

    internal fun closePrompt() {
        if (promptClosed) return
        promptClosed = true
        answered = true
        runCatching { finishActivity(REQUEST) }
        finish()
    }

    internal companion object {
        const val EXTRA_REQUEST_ID = "com.gamebex.client.stream.host.CONSENT_REQUEST_ID"
        private const val REQUEST = 1
    }
}
