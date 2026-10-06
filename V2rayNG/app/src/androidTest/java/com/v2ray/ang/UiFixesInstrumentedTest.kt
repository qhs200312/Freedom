package com.v2ray.ang

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.SingBoxConfigGenerator
import com.v2ray.ang.dto.NodeProfile
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.main.MainViewModel
import com.v2ray.ang.util.AppMemoryReader
import com.v2ray.ang.util.JsonUtil
import corebundle.Corebundle
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiFixesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aStrictMapsRulesKeepOtherEndpointsWithoutBlockingSearch() {
        val expected = listOf("full:clients4.google.com", "full:csi.gstatic.com")
        val xrayRule = CoreConfigManager.googleMapsSharedSdkRule(true, true)
        assertEquals(expected, xrayRule?.domain)
        assertEquals(AppConfig.TAG_BLOCKED, xrayRule?.outboundTag)
        val config = SingBoxConfigGenerator.generate(
            profile = NodeProfile(
                remarks = "ui-regression",
                protocol = EConfigType.VLESS,
                server = "example.com",
                port = 443,
                password = "11111111-1111-1111-1111-111111111111",
                method = "none",
                tlsEnabled = false,
                insecure = false,
            ),
            socksPort = 21808,
            httpPort = 21809,
            logLevel = "warn",
            blockGoogleMaps = true,
            strictBlockGoogleMapsSdk = true,
        )
        Corebundle.checkSingBoxConfig(config)
        val rules = JsonUtil.parseString(config)!!.getAsJsonObject("route").getAsJsonArray("rules")
        val blocked = rules.filter { it.asJsonObject.get("action")?.asString == "reject" }
        assertTrue(blocked.any {
            it.asJsonObject.getAsJsonArray("domain")?.map { domain -> domain.asString } ==
                listOf("clients4.google.com", "csi.gstatic.com")
        })
        assertFalse(blocked.any {
            it.asJsonObject.getAsJsonArray("domain")?.any { domain ->
                domain.asString == "www.google.com" || domain.asString == "google.com"
            } == true
        })
        Log.i(TAG, "Strict Maps rules PASS: both cores exclude search and retain the other endpoints")
    }

    @Test
    fun bMemoryIncludesTheUiAndCoreDaemon() {
        launchMain()
        val manager = context.getSystemService(ActivityManager::class.java)
        await {
            manager.runningAppProcesses.orEmpty().any {
                it.processName == "${context.packageName}:RunSoLibV2RayDaemon"
            }
        }
        val processes = manager.runningAppProcesses.orEmpty().filter {
            it.uid == Process.myUid() &&
                (it.processName == context.packageName || it.processName.startsWith("${context.packageName}:"))
        }
        assertTrue(processes.any { it.pid == Process.myPid() })
        assertTrue(processes.any { it.processName.endsWith(":RunSoLibV2RayDaemon") })
        val pids = processes.map { it.pid }.distinct().toIntArray()
        val samples = manager.getProcessMemoryInfo(pids)
        val expectedKb = samples.sumOf { it.totalPss.toLong() }
        val actualKb = AppMemoryReader.readPssKb(context)
        assertNotNull(actualKb)
        assertTrue(actualKb!! > 0L)
        assertTrue(kotlin.math.abs(actualKb - expectedKb) < expectedKb / 4L)
        processes.zip(samples.toList()).forEach { (process, sample) ->
            Log.i(TAG, "PSS ${process.processName}: pid=${process.pid}, kb=${sample.totalPss}")
        }
        Log.i(TAG, "Memory PASS: expectedKb=$expectedKb, readerKb=$actualKb")
    }

    @Test
    fun cDeletingTheLastLocalNodeSwitchesToTheRemainingSubscription() {
        val localId = AppConfig.DEFAULT_SUBSCRIPTION_ID
        val originalLocal = MmkvManager.decodeSubscription(localId)?.copy()
        val originalLocalNodes = MmkvManager.decodeServerList(localId).toMutableList()
        val originalOrder = MmkvManager.decodeSubsList().toMutableList()
        val originalSelected = MmkvManager.getSelectServer()
        val originalGroup = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID).orEmpty()
        val originalAllGroups = MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)
        val remoteId = "__codex_ui_regression_remote__"
        val remoteGuid = "__codex_ui_regression_remote_node__"
        val localGuid = "__codex_ui_regression_local_node__"
        assertTrue(MmkvManager.decodeSubscription(remoteId) == null)
        var viewModel: MainViewModel? = null
        try {
            MmkvManager.encodeSettings(AppConfig.PREF_GROUP_ALL_DISPLAY, false)
            MmkvManager.encodeServerList(mutableListOf(), localId)
            SettingsManager.ensureDefaultSubscription(context.getString(R.string.subscription_group_local))
            MmkvManager.encodeSubscription(remoteId, SubscriptionItem(remarks = "CODEX Remote Test"))
            MmkvManager.encodeServerConfig(remoteGuid, testProfile("CODEX_REMOTE_NODE", remoteId))
            MmkvManager.encodeServerConfig(localGuid, testProfile("CODEX_LOCAL_NODE", localId))
            MmkvManager.encodeSubsList(
                (listOf(localId, remoteId) + originalOrder.filter { it != localId }).toMutableList()
            )
            MmkvManager.setSelectServer(remoteGuid)
            val activity = launchMain()
            instrumentation.runOnMainSync {
                viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
                viewModel!!.refreshUiSettings()
                viewModel!!.refreshSelectedGuid()
            }
            refreshGroups(viewModel!!)
            instrumentation.runOnMainSync { viewModel!!.subscriptionIdChanged(localId) }
            await { viewModel!!.serversForGroup(localId).value.any { it.guid == localGuid } }
            instrumentation.runOnMainSync {
                viewModel!!.updateSelectedGuid(localGuid)
                viewModel!!.triggerLocateSelectedServer()
            }
            screenshot("navigation-state.png")
            val root = instrumentation.uiAutomation.rootInActiveWindow
            Log.i(TAG, "Active accessibility window: package=${root?.packageName}, class=${root?.className}")
            await { hasVisibleText("CODEX_LOCAL_NODE") }
            instrumentation.runOnMainSync { viewModel!!.updateSelectedGuid(remoteGuid) }
            screenshot("local-before-delete.png")

            instrumentation.runOnMainSync { viewModel!!.removeServerAndRefresh(localGuid) }
            await {
                viewModel!!.uiState.value.groups.none { it.id == localId } &&
                    viewModel!!.uiState.value.selectedGroupId == remoteId
            }
            await { hasVisibleText("CODEX_REMOTE_NODE") }
            assertTrue(MmkvManager.decodeSubscription(localId) == null)
            assertTrue(MmkvManager.decodeSubscription(remoteId) != null)
            assertFalse(hasVisibleText("CODEX_LOCAL_NODE"))
            screenshot("local-after-delete.png")
            Log.i(TAG, "Local deletion PASS: remote node is visible without opening the subscription menu")

            MmkvManager.encodeServerConfig(localGuid, testProfile("CODEX_LOCAL_NODE", localId))
            refreshGroups(viewModel!!)
            assertTrue(viewModel!!.uiState.value.groups.any { it.id == localId })
            assertTrue(MmkvManager.decodeSubscription(localId) != null)
            Log.i(TAG, "Local reimport PASS: group recreated")
        } finally {
            MmkvManager.removeServer(localGuid)
            MmkvManager.removeServer(remoteGuid)
            MmkvManager.removeSubscription(remoteId)
            MmkvManager.encodeServerList(originalLocalNodes, localId)
            if (originalLocal != null) {
                MmkvManager.encodeSubscription(localId, originalLocal)
            } else if (originalLocalNodes.isEmpty()) {
                MmkvManager.removeSubscription(localId)
            }
            MmkvManager.encodeSubsList(originalOrder)
            MmkvManager.setSelectServer(originalSelected.orEmpty())
            MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, originalGroup)
            MmkvManager.encodeSettings(AppConfig.PREF_GROUP_ALL_DISPLAY, originalAllGroups)
            viewModel?.let {
                instrumentation.runOnMainSync {
                    it.refreshUiSettings()
                    it.refreshSelectedGuid()
                    it.subscriptionIdChanged(originalGroup)
                }
                refreshGroups(it)
            }
        }
    }

    private fun testProfile(name: String, groupId: String) = ProfileItem(
        configType = EConfigType.SOCKS,
        remarks = name,
        server = "127.0.0.1",
        serverPort = "1",
        subscriptionId = groupId,
    )

    private fun launchMain(): MainActivity {
        mainActivity?.takeIf { !it.isDestroyed && !it.isFinishing }?.let { return it }
        return (instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity).also { mainActivity = it }
    }

    private fun refreshGroups(viewModel: MainViewModel) {
        var job: Job? = null
        instrumentation.runOnMainSync { job = viewModel.setupGroupTab(forceRefresh = true) }
        runBlocking { job!!.join() }
        instrumentation.waitForIdleSync()
    }

    private fun hasVisibleText(text: String): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return containsVisibleText(root, text)
    }

    private fun containsVisibleText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString() == text && node.isVisibleToUser) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (containsVisibleText(child, text)) return true
        }
        return false
    }

    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(context.filesDir, "ui-regression-results").apply { mkdirs() }
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (!condition()) {
            assertTrue("Timed out waiting for app state", System.currentTimeMillis() < deadline)
            Thread.sleep(100L)
        }
    }

    companion object {
        private const val TAG = "FreedomUiRegression"
        private var mainActivity: MainActivity? = null
    }
}
