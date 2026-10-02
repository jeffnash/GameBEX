package com.gamebex.client.stream.shell;

import android.view.Surface;

/**
 * The app's side of app-only capture: the shell hands it the encoder's input
 * surface, which the app's single-app MediaProjection renders into.
 */
oneway interface IStreamCapture {
    /** The encoder's current input surface at [width]x[height], or null when it goes away. */
    void onSurface(in Surface surface, int width, int height) = 1;
}
