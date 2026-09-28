package com.gamebex.client.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Notification deep links: validated routes, autoplay only with the private token. */
class GameRoutesTest {
    @Test fun topLevelRoutesPassThrough() {
        assertEquals(GameRoutes.Target.Top("downloads"), GameRoutes.parse("downloads"))
        assertEquals(GameRoutes.Target.Top("allgames"), GameRoutes.parse("allgames"))
    }

    @Test fun localRouteOpensDetailWithoutAutoplay() {
        assertEquals(GameRoutes.Target.Game(42, autoPlay = false), GameRoutes.parse(GameRoutes.local(42)))
        assertEquals(GameRoutes.Target.Game(42, autoPlay = false), GameRoutes.parse("local/42", play = true))
    }

    @Test fun autoplayNeedsMatchingToken() {
        assertEquals(
            GameRoutes.Target.Game(7, autoPlay = true),
            GameRoutes.parse("local/7", play = true, token = "s3cret", expectedToken = "s3cret"),
        )
        assertEquals(
            GameRoutes.Target.Game(7, autoPlay = false),
            GameRoutes.parse("local/7", play = true, token = "guess", expectedToken = "s3cret"),
        )
        assertEquals(
            GameRoutes.Target.Game(7, autoPlay = false),
            GameRoutes.parse("local/7", play = true, token = "", expectedToken = ""),
        )
        assertEquals(
            GameRoutes.Target.Game(7, autoPlay = false),
            GameRoutes.parse("local/7", play = false, token = "s3cret", expectedToken = "s3cret"),
        )
    }

    @Test fun malformedRoutesAreDropped() {
        listOf(
            null, "", "sources", "detail/abc", "local/", "local/abc", "local/-1", "local/0",
            "local/1/../x", "local/99999999999999999999", "local/1?play=1",
        ).forEach { assertNull(it, GameRoutes.parse(it)) }
    }
}
