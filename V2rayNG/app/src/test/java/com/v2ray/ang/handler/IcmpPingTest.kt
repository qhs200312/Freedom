package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Test

class IcmpPingTest {
    @Test
    fun parsesIpv4Ipv6AndSubMillisecondReplies() {
        assertEquals(23L, IcmpPing.parseReply("64 bytes from 192.0.2.1: icmp_seq=1 ttl=55 time=22.3 ms"))
        assertEquals(1L, IcmpPing.parseReply("64 bytes from ::1: icmp_seq=1 ttl=64 time<1 ms"))
        assertEquals(1L, IcmpPing.parseReply("64 bytes from ::1: icmp_seq=1 ttl=64 time=0.032 ms"))
    }

    @Test
    fun packetLossAndUnreachableMessagesAreNotParsedAsSuccessfulLatency() {
        assertEquals(-1L, IcmpPing.parseReply("1 packets transmitted, 0 received, 100% packet loss"))
        assertEquals(-1L, IcmpPing.parseReply("From 192.0.2.1 icmp_seq=1 Destination Host Unreachable"))
    }
}
