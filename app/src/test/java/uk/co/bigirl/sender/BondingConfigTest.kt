package uk.co.bigirl.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BondingConfigTest {

    @Test
    fun parsesReceiverEndpointsForBothMembers() {
        val config = BondingConfig.parseEndpoint(
            "srt-bond://145.40.153.191:10010,10011/live",
        )

        assertEquals(BondingConfig(), config)
    }

    @Test
    fun parsesBracketedIpv6Endpoint() {
        val config = BondingConfig.parseEndpoint(
            "srt-bond://[2001:db8::1]:10010,10011/live",
        )

        assertEquals("[2001:db8::1]", config?.host)
        assertEquals(10010, config?.wifiPort)
        assertEquals(10011, config?.cellularPort)
    }

    @Test
    fun rejectsInvalidMemberPortsAndScheme() {
        assertNull(BondingConfig.parseEndpoint("srt-bond://host:10010,10010/live"))
        assertNull(BondingConfig.parseEndpoint("srt-bond://host:0,10011/live"))
        assertNull(BondingConfig.parseEndpoint("srt://host:10010,10011/live"))
    }
}
