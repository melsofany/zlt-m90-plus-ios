package com.zltm90plus.app

import com.zltm90plus.app.util.LocalNetworkChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device's LAN address differs by carrier build, so the search order is the only thing that
 * makes a first attempt likely to succeed. A regression here looks exactly like a broken app.
 */
class LocalNetworkCheckerTest {

    @Test
    fun `the gateway the phone actually routes through is tried first`() {
        val candidates = LocalNetworkChecker.discoveryCandidates(
            localIpv4 = "192.168.8.50",
            gatewayIpv4 = "192.168.8.1",
        )
        assertEquals("192.168.8.1", candidates.first())
    }

    @Test
    fun `a device on the 1921688 subnet is found without guessing the subnet`() {
        // The gateway is on a subnet the phone is not numbered in, which is what a carrier build
        // with an unusual LAN looks like. The gateway must still be searched.
        val candidates = LocalNetworkChecker.discoveryCandidates(
            localIpv4 = "10.1.2.3",
            gatewayIpv4 = "192.168.8.1",
        )
        assertTrue("192.168.8.1 must be searched", candidates.contains("192.168.8.1"))
    }

    @Test
    fun `the factory addresses are searched when the gateway is unknown`() {
        val candidates = LocalNetworkChecker.discoveryCandidates(
            localIpv4 = "192.168.1.20",
            gatewayIpv4 = null,
        )
        assertTrue("192.168.8.1 is a known factory address", candidates.contains("192.168.8.1"))
        assertTrue("the phone's own subnet is searched", candidates.contains("192.168.1.1"))
    }

    @Test
    fun `the phone's own subnet outranks the factory addresses`() {
        val candidates = LocalNetworkChecker.discoveryCandidates(
            localIpv4 = "192.168.20.5",
            gatewayIpv4 = null,
        )
        assertTrue(
            "the phone's subnet is the likeliest place for the device",
            candidates.indexOf("192.168.20.1") < candidates.indexOf("192.168.8.1"),
        )
    }

    @Test
    fun `a candidate is never repeated`() {
        // The gateway is also the phone's own .1, which puts it in two sources.
        val candidates = LocalNetworkChecker.discoveryCandidates(
            localIpv4 = "192.168.8.50",
            gatewayIpv4 = "192.168.8.1",
        )
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun `a host on another subnet is rejected before any request is made`() {
        assertFalse(LocalNetworkChecker.isPlausiblyLocal("192.168.8.1", "10.0.0.5"))
    }

    @Test
    fun `a device on the phone's own subnet is accepted`() {
        assertTrue(LocalNetworkChecker.isPlausiblyLocal("192.168.8.1", "192.168.8.50"))
    }

    @Test
    fun `an undetermined phone address does not block the device`() {
        // Without the phone's address the check cannot contradict the user, so it must allow it.
        assertTrue(LocalNetworkChecker.isPlausiblyLocal("192.168.8.1", null))
    }
}