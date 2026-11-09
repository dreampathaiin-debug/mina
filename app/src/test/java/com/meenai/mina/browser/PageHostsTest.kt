package com.meenai.mina.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageHostsTest {
    private val hosts = PageHosts<String, String>()
    private fun host(name: String) = PageHosts.Host<String, String>(name)

    @Test
    fun viewGoesToTheHostThatCameToFrontLast() {
        val under = host("under")
        val top = host("top")
        hosts.open(under)
        hosts.started(under)
        hosts.setContainer(under, "c-under")
        assertSame(under, hosts.target())
        //Another interface opened,Also stop at the browser page:View onto visible this
        hosts.open(top)
        hosts.started(top)
        hosts.setContainer(top, "c-top")
        assertSame(top, hosts.target())
        //The one that was covered later rebuilt the container,Don't grab it either
        hosts.setContainer(under, null)
        hosts.setContainer(under, "c-under-2")
        assertSame(top, hosts.target())
        //Back to the original one
        hosts.started(under)
        assertSame(under, hosts.target())
    }

    @Test
    fun onlyTheLastHostLeavingClosesThePage() {
        val a = host("a")
        val b = host("b")
        hosts.open(a)
        hosts.open(b)
        hosts.started(a)
        hosts.started(b)
        hosts.setContainer(a, "ca")
        hosts.setContainer(b, "cb")
        assertFalse(hosts.close(b))
        assertNull(b.container)
        assertSame(a, hosts.target())
        assertTrue(hosts.close(a))
        assertNull(hosts.target())
    }

    @Test
    fun hostsWithoutContainerOrNotOpenAreSkipped() {
        val a = host("a")
        val b = host("b")
        //The container is built before registration:Not counted before registration
        hosts.setContainer(a, "ca")
        assertNull(hosts.target())
        hosts.open(a)
        assertSame(a, hosts.target())
        //Back to the front desk but no container(on the start page):Doesn’t count
        hosts.open(b)
        hosts.started(b)
        assertSame(a, hosts.target())
        //Never returned to the front desk:Last comers first
        val c = host("c")
        hosts.open(c)
        hosts.setContainer(c, "cc")
        assertSame(c, hosts.target())
        hosts.close(c)
        assertSame(a, hosts.target())
    }
}
