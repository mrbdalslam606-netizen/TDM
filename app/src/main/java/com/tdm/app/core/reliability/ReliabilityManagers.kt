package com.tdm.app.core.reliability

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteException
import android.provider.Settings
import com.tdm.app.shizuku.IShellService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku Capability Layer (spec §34): optional, never mandatory (spec §83).
 * Before any privileged operation: availability → permission → version → execute → verify → log.
 * Commands run through a Shizuku USER SERVICE (shell uid) — Shizuku is NOT treated as root
 * and only whitelisted commands are executed (spec §34: لا تنفذ shell commands عشوائية).
 */
class ShizukuCapabilityManager(private val context: Context) {

    fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun canUseShell(): Boolean = isShizukuAvailable() && isPermissionGranted()
    fun canPerformBackgroundOperation(): Boolean = canUseShell()
    fun canPerformRecoveryOperation(): Boolean = canPerformBackgroundOperation()

    fun requestPermission(requestCode: Int) {
        runCatching {
            if (isShizukuAvailable() && !isPermissionGranted()) {
                Shizuku.requestPermission(requestCode)
            }
        }
    }

    /* ------------------------- user service binding ------------------------- */

    @Volatile private var shellService: IShellService? = null
    private val serviceLatch = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shellService = IShellService.Stub.asInterface(binder)
            serviceLatch.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
        }
    }

    private fun ensureService(): IShellService? {
        shellService?.let { return it }
        if (!canUseShell()) return null
        return runCatching {
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity"))
            val cn = ComponentName(context.packageName, ShellServiceImpl::class.java.name)
            val args = Shizuku.UserServiceArgs(cn)
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(1)
            Shizuku.bindUserService(args, connection)
            serviceLatch.await(5, TimeUnit.SECONDS)
            shellService
        }.getOrNull()
    }

    /**
     * Restart the engine service via Shizuku shell when normal FGS start is blocked (spec §35).
     * ONLY this exact command is ever executed.
     */
    fun restartEngineService(): Boolean {
        if (!canPerformRecoveryOperation()) return false
        val svc = ensureService() ?: return false
        return runCatching {
            val result = svc.runCommand(
                arrayOf(
                    "am", "start-foreground-service",
                    "-n", "${context.packageName}/com.tdm.app.service.DownloadForegroundService"
                ),
            )
            result.lineSequence().firstOrNull()?.toIntOrNull() == 0
        }.getOrDefault(false)
    }

    /** Read-only device state query via `settings get` (used by the Samsung profile). */
    fun readSystemSetting(namespace: String, key: String): String? {
        if (!canUseShell()) return null
        val svc = ensureService() ?: return null
        return runCatching {
            val result = svc.runCommand(arrayOf("settings", "get", namespace, key))
            val lines = result.lineSequence().toList()
            val exit = lines.firstOrNull()?.toIntOrNull() ?: -1
            val text = lines.drop(1).joinToString("\n").trim()
            if (exit == 0 && text.isNotBlank() && text != "null") text else null
        }.getOrNull()
    }

    fun dispose() {
        runCatching { shellService?.destroy() }
    }
}

/**
 * Runs inside the Shizuku server process as the shell user.
 * Executes exactly what TDM sends — TDM only ever sends whitelisted commands.
 */
class ShellServiceImpl : IShellService.Stub() {

    override fun runCommand(cmd: Array<out String>?): String {
        if (cmd == null || cmd.isEmpty()) return "-1\nno command"
        return try {
            val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
            val p = pb.start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            "$code\n$text"
        } catch (e: Exception) {
            "-1\n${e.message}"
        }
    }

    override fun destroy() { /* process cleanup handled by Shizuku server */ }
}

/**
 * Device Compatibility Layer (spec §36): Samsung profile vs generic Android.
 * Detects battery optimization, background restriction, notification state, FGS state.
 * Never breaks non-Samsung devices (spec §36).
 */
class DeviceCompatibility(private val context: Context) {

    interface DeviceProfile {
        val name: String
        fun isIgnoringBatteryOptimizations(): Boolean
        fun batteryOptimizationIntent(): android.content.Intent
        fun backgroundRestricted(): Boolean?
        fun autostartGuidance(): String
    }

    class SamsungProfile(private val ctx: Context) : DeviceProfile {
        override val name = "Samsung"
        override fun isIgnoringBatteryOptimizations(): Boolean = ignoring(ctx)
        override fun batteryOptimizationIntent(): android.content.Intent =
            android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}"))
        override fun backgroundRestricted(): Boolean? = restricted(ctx)
        override fun autostartGuidance() =
            "Samsung: Settings → Battery → Background usage limits → remove TDM from 'Sleeping apps' and 'Deep sleeping apps'."
    }

    class GenericAndroidProfile(private val ctx: Context) : DeviceProfile {
        override val name = "Generic"
        override fun isIgnoringBatteryOptimizations(): Boolean = ignoring(ctx)
        override fun batteryOptimizationIntent(): android.content.Intent =
            android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}"))
        override fun backgroundRestricted(): Boolean? = restricted(ctx)
        override fun autostartGuidance() =
            "Disable battery optimization for TDM to allow reliable background downloads."
    }

    val profile: DeviceProfile = if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true))
        SamsungProfile(context) else GenericAndroidProfile(context)

    fun notificationEnabled(): Boolean = runCatching {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm?.areNotificationsEnabled() ?: true
    }.getOrDefault(true)

    /** Full check for the Reliability Assistant (spec §37). */
    data class Check(
        val shizukuRunning: Boolean,
        val shizukuPermission: Boolean,
        val batteryOptimized: Boolean,   // true = optimizations IGNORED (good)
        val backgroundRestricted: Boolean?,
        val notificationEnabled: Boolean,
        val deviceProfile: String,
        val guidance: String,
    )

    fun runCheck(shizuku: ShizukuCapabilityManager): Check = Check(
        shizukuRunning = shizuku.isShizukuAvailable(),
        shizukuPermission = shizuku.isPermissionGranted(),
        batteryOptimized = profile.isIgnoringBatteryOptimizations(),
        backgroundRestricted = profile.backgroundRestricted(),
        notificationEnabled = notificationEnabled(),
        deviceProfile = profile.name,
        guidance = profile.autostartGuidance(),
    )
}

/* file-level helpers shared by both profiles (nested classes cannot see outer privates) */

private fun ignoring(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun restricted(ctx: Context): Boolean? = runCatching {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return null
    usm.appStandbyBucket == android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED
}.getOrNull()
