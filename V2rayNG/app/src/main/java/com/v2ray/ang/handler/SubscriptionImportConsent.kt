package com.v2ray.ang.handler

import com.v2ray.ang.util.Utils
import java.net.URI

internal fun requiresHttpSubscriptionConsent(
    input: String?,
    isTrustedUrl: (String) -> Boolean = Utils::isValidSubUrl,
): Boolean = input.orEmpty().lineSequence().map(String::trim).any { line ->
    isHttpSubscriptionUrl(line) && !isTrustedUrl(line)
}

internal fun isHttpSubscriptionUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull()
    return uri?.scheme.equals("http", ignoreCase = true) && !uri?.host.isNullOrBlank()
}
