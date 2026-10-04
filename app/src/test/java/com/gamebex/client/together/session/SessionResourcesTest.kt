package com.gamebex.client.together.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionResourcesTest {
    @Test
    fun closeAllIsReverseOrderIdempotentAndRejectsLateCandidate() {
        val resources = SessionResources()
        val closed = mutableListOf<Int>()
        assertTrue(resources.register { closed += 1 })
        assertTrue(resources.register { closed += 2; throw IllegalStateException("close failed") })
        assertTrue(resources.register { closed += 3 })
        resources.closeAll()
        resources.closeAll()
        assertEquals(listOf(3, 2, 1), closed)
        assertFalse(resources.register { closed += 4 })
        assertEquals(listOf(3, 2, 1), closed)
    }
}
