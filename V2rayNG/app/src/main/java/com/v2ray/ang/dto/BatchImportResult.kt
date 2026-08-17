package com.v2ray.ang.dto

data class BatchImportResult(
    val configCount: Int = 0,
    val subscriptionCount: Int = 0,
    val subscriptionUpdate: SubscriptionUpdateResult = SubscriptionUpdateResult(),
)
