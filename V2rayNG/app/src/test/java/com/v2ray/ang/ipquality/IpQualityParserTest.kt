package com.v2ray.ang.ipquality

import org.junit.Assert.*
import org.junit.Test

class IpQualityParserTest {
    private val ip = "203.0.113.8"
    private data class Case(
        val source: IpQualitySource, val json: String, val type: IpType,
        val organization: String? = null, val asn: String? = null,
        val inferred: Boolean = false, val flags: List<Boolean> = List(4) { false },
    )

    @Test
    fun sourceMappingTable() {
        val rows = listOf(
            Case(IpQualitySource.identMe, """{"ip":"$ip","type":"isp","aso":"Access","asn":64500}""",
                IpType.residential, "Access", "AS64500"),
            Case(IpQualitySource.identMe, """{"ip":"$ip","type":"hosting"}""", IpType.hosting),
            Case(IpQualitySource.identMe, """{"ip":"$ip"}""", IpType.residential, inferred = true),
            Case(IpQualitySource.ipApiCom,
                """{"status":"success","hosting":true,"mobile":true,"proxy":true,"org":"DC","isp":"fallback","as":"AS64501 Example"}""",
                IpType.hosting, "DC", "AS64501", flags = listOf(true, false, false, false)),
            Case(IpQualitySource.ipApiCom, """{"status":"success","hosting":false,"mobile":true,"isp":"Cellular"}""",
                IpType.mobile, "Cellular"),
            Case(IpQualitySource.ipApiCom, """{"status":"success","hosting":false,"mobile":false}""",
                IpType.residential, inferred = true),
            Case(IpQualitySource.ipQuery,
                """{"risk":{"is_datacenter":true,"is_mobile":true,"is_proxy":true,"is_vpn":true,"is_tor":true},"isp":{"org":"Cloud","asn":"AS64502"}}""",
                IpType.hosting, "Cloud", "AS64502", flags = listOf(true, true, true, false)),
            Case(IpQualitySource.ipQuery, """{"risk":{"is_mobile":true},"isp":{"isp":"Carrier"}}""", IpType.mobile, "Carrier"),
            Case(IpQualitySource.ipQuery, """{"ip":"$ip","risk":{}}""", IpType.residential, inferred = true),
            Case(IpQualitySource.ipLocate,
                """{"privacy":{"is_hosting":true,"is_proxy":true,"is_vpn":true,"is_tor":true,"is_abuser":true},"company":{"name":"Host","type":"isp"},"asn":{"asn":64503}}""",
                IpType.hosting, "Host", "AS64503", flags = List(4) { true }),
            Case(IpQualitySource.ipLocate, """{"company":{"type":"education"},"asn":{"name":"University","asn":"64504"}}""",
                IpType.business, "University", "AS64504"),
            Case(IpQualitySource.ipLocate, """{"company":{"type":"business"},"asn":{"type":"hosting"}}""", IpType.hosting),
            Case(IpQualitySource.ipLocate, """{"company":{"type":"unknown"},"asn":{"type":"government"}}""", IpType.business),
            Case(IpQualitySource.ipLocate, """{"privacy":{"is_proxy":false}}""", IpType.residential, inferred = true),
            Case(IpQualitySource.proxyCheck,
                """{"status":"ok","$ip":{"network":{"type":"Residential","organisation":"Home","asn":"AS64505"},"detections":{"proxy":true,"vpn":true,"tor":true}}}""",
                IpType.residential, "Home", "AS64505", flags = listOf(true, true, true, false)),
            Case(IpQualitySource.proxyCheck, """{"$ip":{"network":{"type":"Wireless"}}}""", IpType.mobile),
            Case(IpQualitySource.proxyCheck, """{"$ip":{"network":{"type":"Business"}}}""", IpType.business),
            Case(IpQualitySource.proxyCheck, """{"$ip":{"network":{"type":"Hosting"}}}""", IpType.hosting),
            Case(IpQualitySource.proxyCheck, """{"$ip":{"network":{"type":"Wireless"},"detections":{"hosting":true}}}""", IpType.hosting),
            Case(IpQualitySource.proxyCheck, """{"$ip":{"detections":{"proxy":false}}}""", IpType.residential, inferred = true),
            Case(IpQualitySource.ipApiIs,
                """{"is_datacenter":true,"is_mobile":true,"is_proxy":true,"is_vpn":true,"is_tor":true,"is_abuser":true,"company":{"name":"Cloud","type":"business"},"asn":{"asn":64506}}""",
                IpType.hosting, "Cloud", "AS64506", flags = List(4) { true }),
            Case(IpQualitySource.ipApiIs, """{"is_mobile":true,"company":{"type":"isp"},"asn":{"org":"Carrier"}}""",
                IpType.mobile, "Carrier"),
            Case(IpQualitySource.ipApiIs, """{"company":{"type":"banking"}}""", IpType.business),
            Case(IpQualitySource.ipApiIs, """{"is_proxy":"true","is_vpn":1,"is_tor":false,"is_abuser":null}""",
                IpType.residential, inferred = true),
        )
        rows.forEach { row ->
            val outcome = IpQualityParser.parse(row.source, ip, 200, row.json)
            assertTrue("${row.source}: $outcome", outcome is IpQualityOutcome.Success)
            val quality = (outcome as IpQualityOutcome.Success).quality
            assertEquals(row.source, quality.source)
            assertEquals(ip, quality.ip)
            assertEquals(row.json, row.type, quality.type)
            assertEquals(row.json, row.inferred, quality.inferred)
            assertEquals(row.organization, quality.organization)
            assertEquals(row.asn, quality.asn)
            assertEquals(row.flags, listOf(quality.isProxy, quality.isVpn, quality.isTor, quality.isAbuser))
        }
    }

    @Test
    fun httpErrorsMissingFieldsAndInvalidJsonTable() {
        IpQualitySource.entries.forEach { source ->
            assertEquals(IpQualityOutcome.Failure(IpQualityFailure.rateLimited), IpQualityParser.parse(source, ip, 429, "{}"))
            listOf(201, 403, 404, 500).forEach { status ->
                assertEquals(IpQualityOutcome.Failure(IpQualityFailure.failed), IpQualityParser.parse(source, ip, status, "{}"))
            }
            assertEquals(IpQualityOutcome.Failure(IpQualityFailure.failed), IpQualityParser.parse(source, ip, 200, "{"))
            assertEquals(IpQualityOutcome.Failure(IpQualityFailure.noType), IpQualityParser.parse(source, ip, 200, "[]"))
        }
        val rows = listOf(
            Triple(IpQualitySource.identMe, "{}", IpQualityFailure.ipMismatch),
            Triple(IpQualitySource.identMe, """{"ip":"203.0.113.9","type":"hosting"}""", IpQualityFailure.ipMismatch),
            Triple(IpQualitySource.ipApiCom, """{"status":"success"}""", IpQualityFailure.noType),
            Triple(IpQualitySource.ipApiCom, """{"status":"fail","message":"invalid query"}""", IpQualityFailure.failed),
            Triple(IpQualitySource.ipQuery, """{"risk":{},"isp":{}}""", IpQualityFailure.noType),
            Triple(IpQualitySource.ipLocate, """{"company":{},"privacy":{}}""", IpQualityFailure.noType),
            Triple(IpQualitySource.proxyCheck, """{"status":"ok","$ip":{}}""", IpQualityFailure.noType),
            Triple(IpQualitySource.proxyCheck, """{"status":"denied"}""", IpQualityFailure.failed),
            Triple(IpQualitySource.ipApiIs, "{}", IpQualityFailure.noType),
        )
        rows.forEach { (source, json, failure) ->
            assertEquals(json, IpQualityOutcome.Failure(failure), IpQualityParser.parse(source, ip, 200, json))
        }
    }

    @Test
    fun normalizationPriorityAndDeclaredTypeTable() {
        listOf("isp" to IpType.residential, "Hosting" to IpType.hosting, "mobile" to IpType.mobile,
            "business" to IpType.business, "education" to IpType.business, "government" to IpType.business,
            "banking" to IpType.business, "Residential" to IpType.residential,
        ).forEach { (declared, expected) ->
            assertEquals(expected to false, IpQualityParser.normalizeType(false, false, declared))
            assertEquals(IpType.hosting to false, IpQualityParser.normalizeType(true, true, declared))
        }
        assertEquals(IpType.hosting to false, IpQualityParser.normalizeType(false, true, "hosting"))
        assertEquals(IpType.mobile to false, IpQualityParser.normalizeType(false, true, "business"))
        listOf(null, "", "unknown").forEach {
            assertEquals(IpType.residential to true, IpQualityParser.normalizeType(false, false, it))
        }
    }

    @Test
    fun gradesFollowTypeExceptTorOrAbuse() {
        IpType.entries.forEach { type ->
            val quality = IpQuality(ip, IpQualitySource.ipQuery, type)
            assertEquals(if (type == IpType.hosting) IpQualityLevel.normal else IpQualityLevel.good, quality.level)
            assertEquals(quality.level, quality.copy(isProxy = true, isVpn = true).level)
            assertEquals(IpQualityLevel.risky, quality.copy(isTor = true).level)
            assertEquals(IpQualityLevel.risky, quality.copy(isAbuser = true).level)
        }
    }

    @Test
    fun ipValidationCanonicalizationAndIpv6MismatchComparison() {
        listOf("example.org", "127.0.0.1/?q=1", "1.2.3.256", "001.2.3.4", "fe80::1%eth0", "::::").forEach {
            assertNull(it, IpQualityAddress.normalize(it))
        }
        assertEquals("1.2.3.4", IpQualityAddress.normalize(" 1.2.3.4 "))
        assertEquals(IpQualityAddress.normalize("2001:db8::1"), IpQualityAddress.normalize("[2001:0db8:0:0:0:0:0:1]"))
        assertTrue(IpQualityParser.parse(
            IpQualitySource.identMe, "2001:db8::1", 200,
            """{"ip":"2001:0db8:0:0:0:0:0:1","type":"isp"}""",
        ) is IpQualityOutcome.Success)
    }
}
