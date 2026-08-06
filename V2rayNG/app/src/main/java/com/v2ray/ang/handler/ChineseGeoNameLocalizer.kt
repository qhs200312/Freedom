package com.v2ray.ang.handler

import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.dto.GeoLocation
import com.v2ray.ang.util.JsonUtil
import java.util.Locale

internal object ChineseGeoNameLocalizer {
    private data class Catalog(
        val names: Map<String, String>,
        val englishAliases: Map<String, String>,
    )

    private val catalog: Catalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            val json = AngApplication.application.resources
                .openRawResource(R.raw.cldr_subdivisions_zh)
                .bufferedReader()
                .use { it.readText() }
            val root = JsonUtil.parseString(json)
            Catalog(
                names = root?.getAsJsonObject("names")?.entrySet()
                    ?.associate { it.key to it.value.asString }
                    .orEmpty(),
                englishAliases = root?.getAsJsonObject("englishAliases")?.entrySet()
                    ?.associate { it.key to it.value.asString }
                    .orEmpty(),
            )
        }.getOrDefault(Catalog(emptyMap(), emptyMap()))
    }

    fun localize(location: GeoLocation, locale: Locale): GeoLocation {
        if (locale.language != Locale.CHINESE.language) return location

        val countryCode = location.countryCode.trim().uppercase(Locale.ROOT)
        val localizedCountry = countryDisplayName(countryCode, locale)
            .ifBlank { location.country }
        val localizedRegion = subdivisionDisplayName(
            countryCode = countryCode,
            regionCode = location.regionCode,
            englishRegion = location.region,
        ).ifBlank { location.region.takeIf { it.containsHan() }.orEmpty() }
        val localizedCity = location.city.takeIf { it.containsHan() }.orEmpty()

        return location.copy(
            country = localizedCountry,
            region = localizedRegion,
            city = localizedCity,
        )
    }

    private fun countryDisplayName(countryCode: String, locale: Locale): String {
        if (countryCode.length != 2) return ""
        return runCatching {
            Locale.Builder()
                .setRegion(countryCode)
                .build()
                .getDisplayCountry(locale)
        }.getOrDefault("")
    }

    private fun subdivisionDisplayName(
        countryCode: String,
        regionCode: String,
        englishRegion: String,
    ): String {
        if (countryCode.length != 2) return ""
        val country = countryCode.lowercase(Locale.ROOT)
        val directCode = normalizeSubdivisionCode(country, regionCode)
        val subdivisionCode = directCode.takeIf(catalog.names::containsKey)
            ?: catalog.englishAliases["$country|${normalizeEnglishName(englishRegion)}"]
        return subdivisionCode?.let(catalog.names::get).orEmpty()
    }

    private fun normalizeSubdivisionCode(country: String, rawCode: String): String {
        val region = rawCode.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        if (region.isBlank()) return ""
        return if (region.startsWith(country)) region else country + region
    }

    private fun normalizeEnglishName(value: String): String =
        value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun String.containsHan(): Boolean = any {
        Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN
    }
}
