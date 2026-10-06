package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreServiceManagerTest {
    @Test
    fun startupProbeRemovesGoogleEndpointsAndPreservesCustomFallbacks() {
        assertEquals(
            listOf(
                "https://cp.cloudflare.com/generate_204",
                "https://example.org/check",
            ),
            startupProbeUrls(listOf(
                "https://www.google.com",
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://example.org/check",
                "",
            )),
        )
    }
}
