package com.meenai.mina.cap.web

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginProbeTest {
    private fun signals(
        url: String = "https://shop.example.com/",
        pw: Boolean = false,
        account: List<String> = emptyList(),
        avatars: Int = 0,
    ) = LoginSignals(url, pw, account, avatars)

    private fun state(vararg account: String, pw: Boolean = false, avatars: Int = 0, url: String = "https://shop.example.com/") =
        LoginProbe.classify(signals(url, pw, account.toList(), avatars))

    @Test fun signedOutSignals() {
        assertEquals(LoginState.SignedOut, state(pw = true))
        assertEquals(LoginState.SignedOut, state("front page", "Sign in / register"))
        assertEquals(LoginState.SignedOut, state("Sign in"))
    }

    @Test fun signedInNeedsAProminentLogoutOrAccountEntry() {
        assertEquals(LoginState.SignedIn, state("front page", "Log out"))
        assertEquals(LoginState.SignedIn, state("Log out"))
        assertEquals(LoginState.SignedIn, state("log off"))
        assertEquals(LoginState.SignedIn, state("My account"))
        assertEquals(LoginState.SignedIn, state("Sign out ›"))
    }

    @Test fun helpAndLookalikeLinksAreNotLogout() {
        //Instructions and help pages for canceling your account,and other meanings starting with "exit"
        assertEquals(LoginState.Unknown, state("Account logout"))
        assertEquals(LoginState.Unknown, state("Logout instructions"))
        assertEquals(LoginState.Unknown, state("Exit full screen"))
        assertEquals(LoginState.Unknown, state("How to log out"))
        assertEquals(LoginState.Unknown, state("Log out of every device you have ever used"))
    }

    @Test fun avatarsAloneNeverDecide() {
        assertEquals(LoginState.Unknown, state(avatars = 3))
        //The avatar only blocks "Exited"
        assertEquals(LoginState.Unknown, state("Log in", avatars = 1))
    }

    @Test fun conflictsAndLoginPagesAreUnknown() {
        assertEquals(LoginState.Unknown, state())
        //Password change page:The password box and exit link are both there
        assertEquals(LoginState.Unknown, state("Log out", pw = true))
        assertEquals(LoginState.Unknown, state("Log in", "My account"))
        //Single sign-on and pass jump without drawing conclusions on the way,Even if there is a password box
        assertEquals(LoginState.Unknown, state(pw = true, url = "https://passport.example.com/login?return=/"))
        assertEquals(LoginState.Unknown, state("Log out", url = "https://example.com/sso/callback"))
    }

    @Test fun loginUrlMatchesPathSegmentsOnly() {
        assertTrue(LoginProbe.loginUrl("https://a.example.com/user/login"))
        assertTrue(LoginProbe.loginUrl("https://a.example.com/signin"))
        assertTrue(LoginProbe.loginUrl("https://a.example.com/oauth/authorize"))
        assertFalse(LoginProbe.loginUrl("https://a.example.com/blog-in-spring"))
        assertFalse(LoginProbe.loginUrl("https://a.example.com/search?q=login"))
        assertFalse(LoginProbe.loginUrl(null))
    }

    @Test fun parsesTheScriptResult() {
        val inner = """{"url":"https://a.example.com/","pw":true,"account":["Log in"],"avatars":2}"""
        val s = LoginProbe.parse(JsonPrimitive(inner).toString())!!
        assertEquals("https://a.example.com/", s.url)
        assertTrue(s.passwordVisible)
        assertEquals(listOf("Log in"), s.account)
        assertEquals(2, s.avatars)
        assertNull(LoginProbe.parse("null"))
        assertNull(LoginProbe.parse(null))
        assertNull(LoginProbe.parse("\"not json\""))
    }
}
