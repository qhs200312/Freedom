package com.v2ray.ang

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.receiver.NotificationStopReceiver
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.shortcut.ScStartActivity
import com.v2ray.ang.util.RuntimeDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun notificationStopActionStopsTheLiveService() = withCore {
        val notification = awaitNotification()
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertNotNull(notification.deleteIntent)
        assertFalse(context.packageManager.getReceiverInfo(
            ComponentName(context, NotificationStopReceiver::class.java), 0,
        ).exported)
        notification.actions.first().actionIntent.send()
        awaitStopped()
    }

    @Test
    fun detachedNotificationCanBeDismissedAfterTheCoreProcessDies() = withCore {
        val notification = awaitNotification()
        killDaemon()
        // Some OEM cleaners retain a detached copy instead of removing the notification.
        notification.flags = notification.flags and Notification.FLAG_FOREGROUND_SERVICE.inv()
        manager.notify(1, notification)
        val detached = awaitNotification()
        assertEquals(0, detached.flags and Notification.FLAG_ONGOING_EVENT)
        assertNotNull(detached.deleteIntent)
        detached.deleteIntent.send()
        awaitStopped()
    }

    @Test
    fun removingTheTaskAfterVpnProcessDeathClearsItsNotification() =
        removeTaskWhileRunning(vpnMode = true, killBeforeRemoval = true)

    @Test
    fun goingHomeKeepsTheVpnRunning() {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            withCore(vpnMode = true) {
                shell("input keyevent KEYCODE_HOME")
                Thread.sleep(2_000)
                assertTrue(manager.activeNotifications.any { it.id == 1 })
                assertTrue(shell("dumpsys activity services ${context.packageName}")
                    .contains("CoreVpnService"))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun finishingStartShortcutDoesNotStopTheExistingVpn() = withCore(vpnMode = true) {
        context.startActivity(Intent(context, ScStartActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
        ))
        Thread.sleep(3_000)
        assertTrue("Finishing a transient shortcut must preserve the VPN",
            shell("dumpsys activity services ${context.packageName}").contains("CoreVpnService"))
        assertTrue(manager.activeNotifications.any { it.id == 1 })
    }

    @Test
    fun notificationStopActionStopsTheLiveVpn() = withCore(vpnMode = true) {
        awaitNotification().actions.first().actionIntent.send()
        awaitStopped()
    }

    @Test
    fun removingTheAppTaskStopsTheServiceAndNotification() = removeTaskWhileRunning(vpnMode = false)

    @Test
    fun removingTheAppTaskStopsTheVpnAndNotification() = removeTaskWhileRunning(vpnMode = true)

    private fun removeTaskWhileRunning(vpnMode: Boolean, killBeforeRemoval: Boolean = false) {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            withCore(vpnMode) {
                awaitNotification()
                if (killBeforeRemoval) killDaemon()
                instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
                awaitStopped()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun withCore(vpnMode: Boolean = false, test: () -> Unit) {
        val selected = MmkvManager.getSelectServer()
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE)
        val root = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_MODE_ENABLE)
        val id = UUID.randomUUID().toString()
        try {
            if (vpnMode) assertNull("Grant the isolated test app VPN permission", VpnService.prepare(context))
            LauncherManager.stopService(context)
            awaitStopped()
            MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, if (vpnMode) AppConfig.VPN else "PROXY")
            MmkvManager.encodeServerConfig(id, ProfileItem.create(EConfigType.HTTP).copy(
                remarks = "Notification lifecycle fixture",
                server = "127.0.0.1",
                serverPort = "9",
                preferredCore = "XRAY",
            ))
            MmkvManager.setSelectServer(id)
            RuntimeDiagnostics.clear(context)
            assertTrue(LauncherManager.startService(context))
            await("Core started") {
                RuntimeDiagnostics.read(context).any {
                    it.contains(if (vpnMode) "vpn_ready core=XRAY" else "running core=XRAY")
                }
            }
            test()
        } finally {
            LauncherManager.stopService(context)
            awaitStopped()
            MmkvManager.removeServer(id)
            selected?.let(MmkvManager::setSelectServer)
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, mode)
            MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, root)
        }
    }

    private fun awaitNotification(): Notification {
        await("Foreground notification posted") {
            manager.activeNotifications.any { it.id == 1 && it.notification.actions?.isNotEmpty() == true }
        }
        return manager.activeNotifications.single { it.id == 1 }.notification
    }

    private fun killDaemon() {
        val processes = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val daemon = processes.runningAppProcesses.single {
            it.uid == Process.myUid() &&
                it.processName == "${context.packageName}:RunSoLibV2RayDaemon"
        }
        Process.killProcess(daemon.pid)
        await("Core process exited") {
            processes.runningAppProcesses.none { it.pid == daemon.pid }
        }
    }

    private fun awaitStopped() {
        await("Core service and notification stopped") {
            val services = shell("dumpsys activity services ${context.packageName}")
            manager.activeNotifications.none { it.id == 1 } &&
                listOf("CoreProxyOnlyService", "CoreVpnService", "VpnTaskService").none(services::contains)
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun await(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        val services = shell("dumpsys activity services ${context.packageName}")
            .lineSequence().filter {
                it.contains("ServiceRecord") || it.contains("app=") ||
                    it.contains("isForeground=") || it.contains("startRequested=") ||
                    it.contains("ConnectionRecord")
            }.joinToString("\n")
        val notifications = manager.activeNotifications.filter { it.id == 1 }
            .map { "id=${it.id} flags=${it.notification.flags}" }
        fail("$message; services: $services; notifications: $notifications; " +
            "diagnostics: ${RuntimeDiagnostics.read(context).take(6)}")
    }
}
