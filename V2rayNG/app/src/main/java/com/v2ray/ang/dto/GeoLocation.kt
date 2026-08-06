package com.v2ray.ang.dto

data class GeoLocation(
    val ip: String,
    val countryCode: String = "",
    val country: String = "",
    val regionCode: String = "",
    val region: String = "",
    val city: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val placeLabel: String
        get() = listOf(city, region, country)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
            .ifBlank { countryCode.ifBlank { "Unknown location" } }

    val hasCoordinates: Boolean
        get() = latitude != null && longitude != null &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}
