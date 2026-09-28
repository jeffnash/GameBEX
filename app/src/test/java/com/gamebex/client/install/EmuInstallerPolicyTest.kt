package com.gamebex.client.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Attempt admission, stale-callback, and recovery-merge policy. */
class EmuInstallerPolicyTest {
    @Test fun admitsFreshStart() {
        assertTrue(EmuInstaller.shouldAdmitStart(false, InstallState.Idle))
        assertTrue(EmuInstaller.shouldAdmitStart(false, InstallState.Failed("x")))
        assertTrue(EmuInstaller.shouldAdmitStart(false, InstallState.Done("1", 1)))
        assertTrue(EmuInstaller.shouldAdmitStart(false, InstallState.NeedsUnknownSources))
    }

    @Test fun refusesWhileJobWindsDown() {
        // Covers both the active job and the cancelling-but-incomplete job:
        // no replacement until the previous writer is fully done.
        assertFalse(EmuInstaller.shouldAdmitStart(true, InstallState.Idle))
        assertFalse(EmuInstaller.shouldAdmitStart(true, InstallState.Downloading(1, 2)))
    }

    @Test fun refusesWhileApprovalPends() {
        // The coroutine is gone after commit, but the package stays busy
        // until the terminal session result — no second session.
        assertFalse(EmuInstaller.shouldAdmitStart(false, InstallState.Installing))
        assertFalse(EmuInstaller.shouldAdmitStart(false, InstallState.AwaitingApproval))
    }

    @Test fun acceptsCallbackForLiveSession() {
        assertTrue(EmuInstaller.shouldAcceptCallback(InstallState.Installing, false))
        assertTrue(EmuInstaller.shouldAcceptCallback(InstallState.AwaitingApproval, false))
    }

    @Test fun ignoresStaleCallback() {
        // Cancelled (Idle) or retried (Downloading/Failed) attempts void
        // earlier sessions' callbacks.
        assertFalse(EmuInstaller.shouldAcceptCallback(InstallState.Idle, false))
        assertFalse(EmuInstaller.shouldAcceptCallback(InstallState.Downloading(1, 2), false))
        assertFalse(EmuInstaller.shouldAcceptCallback(InstallState.Failed("x"), false))
        assertFalse(EmuInstaller.shouldAcceptCallback(InstallState.Done("1", 1), false))
    }

    @Test fun acceptsCallbackAfterProcessDeath() {
        // States are in-memory, so post-restart they read Idle; the
        // persisted marker proves the session is still ours.
        assertTrue(EmuInstaller.shouldAcceptCallback(InstallState.Idle, true))
    }
}
