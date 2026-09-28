package com.gamebex.client.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CtaHintTest {
    @Test fun primaryCtaUsesItsLabel() { assertEquals("Continue", ctaHint(0, 0, "Continue", true)) }
    @Test fun startFreshNamesTheAction() { assertEquals("Start fresh", ctaHint(1, 0, "Continue", true)) }
    @Test fun togetherNamesTheAction() { assertEquals("Play together", ctaHint(2, 0, "Continue", true)) }
    @Test fun disabledCtaHasNoHint() { assertNull(ctaHint(0, 0, "Continue", false)) }
    @Test fun otherZonesKeepTheirLabel() { assertEquals("Continue", ctaHint(2, 1, "Continue", true)) }
}
