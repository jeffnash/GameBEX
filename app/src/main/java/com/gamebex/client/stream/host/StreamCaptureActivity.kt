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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val prompt = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
        runCatching { startActivityForResult(prompt, REQUEST) }.onFailure { answer(RESULT_CANCELED, null) }
    }

    @Deprecated("Activity result API is not used for this one-shot system prompt")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) answer(resultCode, data)
    }

    override fun onDestroy() {
        if (isFinishing && !answered) StreamAppCapture.onConsent(RESULT_CANCELED, null)
        super.onDestroy()
    }

    private fun answer(code: Int, data: Intent?) {
        answered = true
        StreamAppCapture.onConsent(code, data)
        finish()
    }

    private companion object {
        const val REQUEST = 1
    }
}
