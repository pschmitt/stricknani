package blue.anika.wolle.data.errorreporting

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentryEventScrubberTest {
    @Test
    fun removesUserRequestBreadcrumbsAndExtrasAndRedactsText() {
        val event =
            SentryEvent().apply {
                user = User().apply { email = "bob@example.test" }
                request = Request().apply { url = "https://s.test/x?token=abc" }
                serverName = "phone"
                breadcrumbs = listOf(Breadcrumb("clicked bob@example.test"))
                setExtra("body", "secret")
                message = Message().apply { formatted = "failed for bob@example.test" }
                exceptions =
                    listOf(SentryException().apply { value = "Authorization: Bearer abc.def.ghi" })
            }

        val scrubbed = SentryEventScrubber.scrub(event)

        assertNull(scrubbed.user)
        assertNull(scrubbed.request)
        assertNull(scrubbed.serverName)
        assertNull(scrubbed.breadcrumbs)
        assertEquals(emptyMap<String, Any>(), scrubbed.extras)
        assertEquals("failed for [email]", scrubbed.message?.formatted)
        assertEquals("Authorization: Bearer [REDACTED]", scrubbed.exceptions?.single()?.value)
    }
}
