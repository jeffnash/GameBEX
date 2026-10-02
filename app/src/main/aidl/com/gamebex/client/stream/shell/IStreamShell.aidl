package com.gamebex.client.stream.shell;

import com.gamebex.client.stream.shell.IStreamCapture;

/**
 * Stream service run by Shizuku as the shell uid (StreamShellService).
 * Arguments and results are JSON (com.gamebex.client.stream.StreamJson) so
 * the interface never changes shape when a field is added.
 */
interface IStreamShell {
    /** Shizuku's reserved UserService teardown code. */
    void destroy() = 16777114;

    /** StreamProbe JSON. */
    String probe() = 1;
    /** StreamConfig JSON in, StreamStarted JSON out. Stops any running stream first. */
    String start(String configJson) = 2;
    /** StreamStatus JSON; also feeds the app-silence watchdog. */
    String status() = 3;
    /** Stops the stream and removes player 2's pad. Idempotent. */
    void stop() = 4;
    /** The service process uid (2000 when Shizuku runs through adb). */
    int uid() = 5;
    /** App capture streams only: where the encoder's input surfaces go (null detaches). */
    void setCaptureListener(IStreamCapture listener) = 6;
    /** App capture streams only: the host declined app capture, so mirror the whole display. */
    void useMirror() = 7;
}
