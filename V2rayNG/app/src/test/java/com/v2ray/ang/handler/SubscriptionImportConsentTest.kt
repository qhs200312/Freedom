package com.v2ray.ang.handler

import org.junit.Assert.*
import org.junit.Test

class SubscriptionImportConsentTest {
    @Test
    fun publicHttpRequiresConsentWhileHttpsAndNodeLinksDoNot() {
        assertTrue(requiresHttpSubscriptionConsent("http://example.com/sub?token=test") { false })
        assertTrue(requiresHttpSubscriptionConsent(" HTTP://example.com/sub ") { false })
        assertFalse(requiresHttpSubscriptionConsent("https://example.com/sub") { false })
        assertFalse(requiresHttpSubscriptionConsent("vless://id@example.com:443") { false })
    }

    @Test
    fun anAlreadyTrustedLocalHttpAddressKeepsItsExistingBehavior() {
        assertFalse(requiresHttpSubscriptionConsent("http://127.0.0.1/sub") { true })
        assertFalse(requiresHttpSubscriptionConsent("http://192.168.1.2/sub") { true })
    }

    @Test
    fun mixedBatchesRequireConsentOnlyForActualHttpSubscriptionLines() {
        assertTrue(requiresHttpSubscriptionConsent(
            "https://example.com/a\nhttp://example.com/b\nvless://id@example.com:443",
        ) { false })
        assertFalse(requiresHttpSubscriptionConsent("""{"url":"http://example.com/sub"}""") { false })
        assertFalse(requiresHttpSubscriptionConsent(null) { false })
        assertFalse(requiresHttpSubscriptionConsent("http://\nnot a URL") { false })
        assertFalse(isHttpSubscriptionUrl("file:///tmp/sub"))
    }
}
