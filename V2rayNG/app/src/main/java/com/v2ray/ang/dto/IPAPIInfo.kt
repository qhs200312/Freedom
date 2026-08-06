package com.v2ray.ang.dto

data class IPAPIInfo(
    var ip: String? = null,
    var clientIp: String? = null,
    var ip_addr: String? = null,
    var query: String? = null,
    var country: String? = null,
    var country_name: String? = null,
    var country_code: String? = null,
    var countryCode: String? = null,
    var region: String? = null,
    var region_name: String? = null,
    var regionName: String? = null,
    var region_code: String? = null,
    var regionCode: String? = null,
    var city: String? = null,
    var latitude: Double? = null,
    var longitude: Double? = null,
    var lat: Double? = null,
    var lon: Double? = null,
    var location: LocationBean? = null
) {
    data class LocationBean(
        var country_code: String? = null
    )
}
