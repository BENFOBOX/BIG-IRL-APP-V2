package uk.co.bigirl.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SrtlaConfigTest {

    @Test
    fun parsesReceiverAndEncodedStreamId() {
        val config = SrtlaConfig.parseEndpoint("srtla://receiver.example:10012?streamid=publish%3Amy%2Bkey")

        assertEquals("receiver.example", config?.host)
        assertEquals(10012, config?.port)
        assertEquals("publish:my+key", config?.streamId)
    }

    @Test
    fun rejectsInvalidOrAmbiguousEndpoints() {
        assertNull(SrtlaConfig.parseEndpoint("srt://receiver.example:10012?streamid=live"))
        assertNull(SrtlaConfig.parseEndpoint("srtla://receiver.example:0?streamid=live"))
        assertNull(SrtlaConfig.parseEndpoint("srtla://receiver.example:10012?streamid=one&streamid=two"))
        assertNull(SrtlaConfig.parseEndpoint("srtla://receiver.example:10012?streamid=live&passphrase=secret"))
    }
}
