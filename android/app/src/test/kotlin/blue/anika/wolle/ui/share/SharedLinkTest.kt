package blue.anika.wolle.ui.share

import blue.anika.wolle.data.api.ImportAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinkTest {
    private val on = ImportAvailability(url = true, ai = true)
    private val off = ImportAvailability(url = false, ai = false)

    @Test
    fun `extracts a plain URL`() {
        assertEquals(
            "https://www.garnstudio.com/pattern.php?id=12752&cid=9",
            SharedLink.extract("https://www.garnstudio.com/pattern.php?id=12752&cid=9"),
        )
    }

    @Test
    fun `extracts the URL from a title plus URL share`() {
        assertEquals(
            "https://example.com/a",
            SharedLink.extract("Cosy sweater pattern\nhttps://example.com/a"),
        )
        assertEquals(
            "http://example.com/x?y=1",
            SharedLink.extract("Look at this http://example.com/x?y=1 it is nice"),
        )
    }

    @Test
    fun `takes the first of several links and trims wrapping punctuation`() {
        assertEquals(
            "https://a.example/p",
            SharedLink.extract("see (https://a.example/p). also https://b.example"),
        )
        assertEquals("https://a.example/p", SharedLink.extract("<https://a.example/p>"))
    }

    @Test
    fun `returns null without a link`() {
        assertNull(SharedLink.extract(null))
        assertNull(SharedLink.extract(""))
        assertNull(SharedLink.extract("just some words, no link"))
        assertNull(SharedLink.extract("example.com/pattern"))
    }

    @Test
    fun `rejects non-http schemes`() {
        assertNull(SharedLink.extract("file:///sdcard/secret.txt"))
        assertNull(SharedLink.extract("content://media/external/1"))
        assertNull(SharedLink.extract("javascript:alert(1)"))
        assertNull(SharedLink.extract("ftp://example.com/x"))
        assertNull(SharedLink.extract("file:///a?u=https://example.com/x"))
        assertNull(SharedLink.extract("https://"))
    }

    @Test
    fun `host drops www`() {
        assertEquals("garnstudio.com", SharedLink.hostOf("https://www.garnstudio.com/p?id=1"))
    }

    @Test
    fun `intake needs a text share`() {
        assertEquals(
            ShareIntake.Link("https://example.com/a"),
            SharedLink.intake(
                "android.intent.action.SEND",
                "text/plain",
                "T https://example.com/a",
            ),
        )
        assertEquals(
            ShareIntake.NoLink,
            SharedLink.intake("android.intent.action.SEND", "text/plain", "no link"),
        )
        assertEquals(
            ShareIntake.NoLink,
            SharedLink.intake("android.intent.action.SEND", "text/plain", null),
        )
        assertEquals(
            ShareIntake.NotAShare,
            SharedLink.intake("android.intent.action.MAIN", null, "https://example.com"),
        )
        assertEquals(
            ShareIntake.NotAShare,
            SharedLink.intake("android.intent.action.SEND", "image/png", "https://example.com"),
        )
    }

    @Test
    fun `signed in and imports on asks which kind`() {
        assertEquals(
            ShareStage.Choose("https://example.com/a"),
            shareStage("https://example.com/a", isConfigured = true, availability = on),
        )
    }

    @Test
    fun `signed out keeps the link pending`() {
        assertEquals(
            ShareStage.AwaitingSignIn("https://example.com/a"),
            shareStage("https://example.com/a", isConfigured = false, availability = on),
        )
        // The same link continues to the chooser once signed in.
        assertEquals(
            ShareStage.Choose("https://example.com/a"),
            shareStage("https://example.com/a", isConfigured = true, availability = on),
        )
    }

    @Test
    fun `imports off shows the reason instead of the chooser`() {
        assertEquals(
            ShareStage.Unavailable("https://example.com/a"),
            shareStage("https://example.com/a", isConfigured = true, availability = off),
        )
    }

    @Test
    fun `nothing pending shows nothing and no stage starts an import`() {
        assertEquals(ShareStage.None, shareStage(null, isConfigured = true, availability = on))
        // The stages are only: nothing, wait for sign-in, choose, unavailable. There is no stage
        // that carries an import start, so a share can only ever prefill a dialog.
        val all =
            listOf(
                shareStage(null, true, on),
                shareStage("u", false, on),
                shareStage("u", true, on),
                shareStage("u", true, off),
            )
        assertEquals(4, all.map { it::class }.toSet().size)
    }
}
