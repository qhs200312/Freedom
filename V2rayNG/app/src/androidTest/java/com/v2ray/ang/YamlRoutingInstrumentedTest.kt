package com.v2ray.ang

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.routing.ClashYamlRules
import com.v2ray.ang.routing.YamlRuleNativeValidator
import com.v2ray.ang.ui.routing.RoutingSettingsViewModel
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import corebundle.Corebundle
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class YamlRoutingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sample = """
        payload:
          - DOMAIN,exact.example
          - DOMAIN-SUFFIX,example.com
          - DOMAIN-KEYWORD,service
          - 'DOMAIN-REGEX,^cdn[0-9]{1,3}\.example$'
          - IP-CIDR,192.0.2.0/24
          - IP-CIDR6,2001:db8::/32
          - DST-PORT,8443-8445
          - NETWORK,UDP
    """.trimIndent()

    @Test
    fun importedRulesAreAcceptedByBothBundledCores() {
        Seq.setContext(context)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(YamlRuleNativeValidator.regexIsValid("^cdn[0-9]{1,3}\\.example$"))
        assertFalse(YamlRuleNativeValidator.regexIsValid("(?=lookahead-not-supported)"))
        val rules = ClashYamlRules.parse(sample).toRules("YAML", AppConfig.TAG_PROXY, null)
        Corebundle.checkSingBoxConfig(SingBoxConfigGenerator.generate(
            profile = NodeProfile(
                remarks = "Test", protocol = EConfigType.HTTP, server = "127.0.0.1", port = 8080,
                tlsEnabled = false, insecure = false,
            ),
            socksPort = Utils.findRandomFreePort(), httpPort = Utils.findRandomFreePort(),
            logLevel = "warn", routingRules = rules,
        ))
        val root = JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("listen", "127.0.0.1")
                    addProperty("port", Utils.findRandomFreePort())
                    addProperty("protocol", "socks")
                    add("settings", JsonObject().apply { addProperty("auth", "noauth") })
                })
            })
            add("outbounds", JsonArray().apply {
                add(JsonObject().apply { addProperty("tag", "proxy"); addProperty("protocol", "freedom") })
            })
            add("routing", JsonObject().apply {
                add("rules", JsonArray().apply {
                    rules.forEach { rule ->
                        val item = JsonUtil.parseString(JsonUtil.toJson(rule))!!.asJsonObject
                        item.addProperty("type", "field")
                        add(item)
                    }
                })
            })
        }
        val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(l: Long, s: String?): Long = 0
        })
        try { controller.startLoop(root.toString(), 0) }
        finally { controller.stopLoop() }
    }

    @Test
    fun previewFailureKeepsStorageAndSuccessfulFileImportPreservesExistingRules() {
        val original = MmkvManager.decodeRoutingRulesets()
        lateinit var model: RoutingSettingsViewModel
        var created = false
        val file = File(context.cacheDir, "yaml-routing-test.yaml")
        try {
            val existing = RulesetItem(id = "existing", remarks = "Existing",
                domain = listOf("domain:existing.example"), outboundTag = "proxy", locked = true)
            MmkvManager.encodeRoutingRulesets(mutableListOf(existing))
            instrumentation.runOnMainSync {
                model = RoutingSettingsViewModel(context.applicationContext as Application)
                created = true
                model.openYamlImport()
                model.updateYamlInput("payload:\n- DOMAIN,valid.example\n- IP-ASN,12345")
                model.previewYaml()
            }
            await { model.yamlImport.value.error != null }
            assertEquals(listOf(existing), MmkvManager.decodeRoutingRulesets())
            assertNull(model.yamlImport.value.parsed)
            instrumentation.runOnMainSync {
                model.updateYamlInput("payload:\n- DOMAIN-REGEX,(?=lookahead-not-supported)")
                model.previewYaml()
            }
            await { model.yamlImport.value.error != null }
            assertEquals(2, model.yamlImport.value.error!!.line)
            assertEquals(listOf(existing), MmkvManager.decodeRoutingRulesets())
            file.writeText(sample)
            instrumentation.runOnMainSync { model.loadYamlFile(Uri.fromFile(file)) }
            await { model.yamlImport.value.parsed != null }
            assertEquals(8, model.yamlImport.value.parsed!!.count)
            instrumentation.runOnMainSync { model.saveYamlImport("direct", null) }
            await { !model.yamlImport.value.visible }
            val saved = MmkvManager.decodeRoutingRulesets()!!
            assertEquals(existing, saved.last())
            assertEquals(4, saved.count { it.outboundTag == "direct" })
            assertTrue(saved.none { !it.domain.isNullOrEmpty() && !it.ip.isNullOrEmpty() })
            assertFalse(model.yamlImport.value.visible)
        } finally {
            if (created) instrumentation.runOnMainSync { model.closeYamlImport() }
            file.delete()
            MmkvManager.encodeRoutingRulesets(original)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for YAML import state", condition())
    }
}
