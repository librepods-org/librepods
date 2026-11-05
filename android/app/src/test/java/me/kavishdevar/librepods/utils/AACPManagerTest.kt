package me.kavishdevar.librepods.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AACPManagerTest {
    @Test
    fun regularMediaInformationUsesWireKeyCasing() {
        val packet = AACPManager().createMediaInformationPacket(
            "66:77:88:99:aa:bb",
            "00:11:22:33:44:55",
            true,
        )

        assertArrayEquals("playingApp".toByteArray(), packet.copyOfRange(13, 23))
        assertArrayEquals("hostStreamingState".toByteArray(), packet.copyOfRange(47, 65))
    }
}
