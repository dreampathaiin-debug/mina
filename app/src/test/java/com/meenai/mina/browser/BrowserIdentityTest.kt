package com.meenai.mina.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserIdentityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun userBrowsingDefaultsToMobile() {
        assertTrue(BrowserIdentity.mobile(loginForMina = false, agentMobile = false, userDesktop = false))
        //Which version does Agent use without affecting normal browsing?
        assertTrue(BrowserIdentity.mobile(loginForMina = false, agentMobile = true, userDesktop = false))
    }

    @Test
    fun perSiteOverrideSwitchesToDesktop() {
        assertFalse(BrowserIdentity.mobile(loginForMina = false, agentMobile = true, userDesktop = true))
    }

    @Test
    fun loginForMinaUsesTheAgentIdentity() {
        assertFalse(BrowserIdentity.mobile(loginForMina = true, agentMobile = false, userDesktop = false))
        assertTrue(BrowserIdentity.mobile(loginForMina = true, agentMobile = true, userDesktop = true))
    }

    @Test
    fun onlyNotLoggedInFavoritesGoThroughLogin() {
        assertTrue(BrowserIdentity.loginFlow(FavoriteSite("example.com", loggedIn = false)))
        assertFalse(BrowserIdentity.loginFlow(FavoriteSite("example.com", loggedIn = true)))
    }

    @Test
    fun userSiteModesFollowParentDomainAndPersist() {
        val m = UserSiteModes(tmp.root)
        assertFalse(m.isDesktop("example.com"))
        m.set("www.example.com", true)
        assertTrue(m.isDesktop("example.com"))
        assertTrue(m.isDesktop("m.example.com"))
        assertFalse(m.isDesktop("example.org"))
        assertTrue(UserSiteModes(tmp.root).isDesktop("shop.example.com"))
        //Switch back to the mobile version on the subdomain:Delete even the records of superiors
        m.set("m.example.com", false)
        assertFalse(m.isDesktop("example.com"))
        assertFalse(UserSiteModes(tmp.root).isDesktop("m.example.com"))
    }
}
