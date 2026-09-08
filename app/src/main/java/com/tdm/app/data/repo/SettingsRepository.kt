package com.tdm.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tdm.app.data.db.NetworkPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "tdm_settings")

/** Global application settings (spec §50). Per-source config lives in DB. */
data class AppSettings(
    val apiId: Int = 0,
    val apiHash: String = "",
    val phoneNumberHint: String = "",
    val loggedIn: Boolean = false,
    val globalSpeedLimitBps: Long = 0,          // 0 = unlimited (spec §11)
    val concurrencyMode: String = "AUTO",        // FIXED | AUTO (spec §18)
    val fixedConcurrency: Int = 2,
    val maxConcurrency: Int = 5,                 // hard cap for auto (spec §19)
    val networkPolicy: NetworkPolicy = NetworkPolicy.ANY,
    val downloadNowOverridesNetwork: Boolean = true,
    val endBehaviorDefault: String = "STOP_IMMEDIATELY",
    val notificationsOnComplete: Boolean = true,
    val notificationsPerFile: Boolean = false,
    val inboxChatId: Long = 0,                   // 0 = Saved Messages (spec §4B)
    val smartQueueGlobal: Boolean = false,
    val storageTreeUri: String = "",
    val storageTemplate: String = "{root}/{channel}/{date}/{filename}",
    val reliabilityWatchdogEnabled: Boolean = true,
    val reliabilityShizukuEnabled: Boolean = true,
    val autoStartAfterBoot: Boolean = true,
    val statsKeepDays: Int = 180,
)

class SettingsRepository(private val context: Context) {

    private object K {
        val apiId = intPreferencesKey("api_id")
        val apiHash = stringPreferencesKey("api_hash")
        val phone = stringPreferencesKey("phone_hint")
        val loggedIn = booleanPreferencesKey("logged_in")
        val speed = longPreferencesKey("speed_limit_bps")
        val concMode = stringPreferencesKey("concurrency_mode")
        val fixedConc = intPreferencesKey("fixed_concurrency")
        val maxConc = intPreferencesKey("max_concurrency")
        val netPolicy = stringPreferencesKey("network_policy")
        val dnOverrides = booleanPreferencesKey("download_now_overrides_network")
        val endBehavior = stringPreferencesKey("end_behavior_default")
        val notifComplete = booleanPreferencesKey("notif_on_complete")
        val notifPerFile = booleanPreferencesKey("notif_per_file")
        val inbox = longPreferencesKey("inbox_chat_id")
        val smartQueue = booleanPreferencesKey("smart_queue_global")
        val storageUri = stringPreferencesKey("storage_tree_uri")
        val storageTemplate = stringPreferencesKey("storage_template")
        val watchdog = booleanPreferencesKey("watchdog_enabled")
        val shizuku = booleanPreferencesKey("shizuku_enabled")
        val autoBoot = booleanPreferencesKey("auto_start_after_boot")
        val statsKeepDays = intPreferencesKey("stats_keep_days")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            apiId = p[K.apiId] ?: 0,
            apiHash = p[K.apiHash] ?: "",
            phoneNumberHint = p[K.phone] ?: "",
            loggedIn = p[K.loggedIn] ?: false,
            globalSpeedLimitBps = p[K.speed] ?: 0L,
            concurrencyMode = p[K.concMode] ?: "AUTO",
            fixedConcurrency = p[K.fixedConc] ?: 2,
            maxConcurrency = p[K.maxConc] ?: 5,
            networkPolicy = runCatching { NetworkPolicy.valueOf(p[K.netPolicy] ?: "ANY") }
                .getOrDefault(NetworkPolicy.ANY),
            downloadNowOverridesNetwork = p[K.dnOverrides] ?: true,
            endBehaviorDefault = p[K.endBehavior] ?: "STOP_IMMEDIATELY",
            notificationsOnComplete = p[K.notifComplete] ?: true,
            notificationsPerFile = p[K.notifPerFile] ?: false,
            inboxChatId = p[K.inbox] ?: 0L,
            smartQueueGlobal = p[K.smartQueue] ?: false,
            storageTreeUri = p[K.storageUri] ?: "",
            storageTemplate = p[K.storageTemplate] ?: "{root}/{channel}/{date}/{filename}",
            reliabilityWatchdogEnabled = p[K.watchdog] ?: true,
            reliabilityShizukuEnabled = p[K.shizuku] ?: true,
            autoStartAfterBoot = p[K.autoBoot] ?: true,
            statsKeepDays = p[K.statsKeepDays] ?: 180,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val cur = AppSettings(
                apiId = p[K.apiId] ?: 0, apiHash = p[K.apiHash] ?: "",
                phoneNumberHint = p[K.phone] ?: "", loggedIn = p[K.loggedIn] ?: false,
                globalSpeedLimitBps = p[K.speed] ?: 0L, concurrencyMode = p[K.concMode] ?: "AUTO",
                fixedConcurrency = p[K.fixedConc] ?: 2, maxConcurrency = p[K.maxConc] ?: 5,
                networkPolicy = runCatching { NetworkPolicy.valueOf(p[K.netPolicy] ?: "ANY") }
                    .getOrDefault(NetworkPolicy.ANY),
                downloadNowOverridesNetwork = p[K.dnOverrides] ?: true,
                endBehaviorDefault = p[K.endBehavior] ?: "STOP_IMMEDIATELY",
                notificationsOnComplete = p[K.notifComplete] ?: true,
                notificationsPerFile = p[K.notifPerFile] ?: false,
                inboxChatId = p[K.inbox] ?: 0L,
                smartQueueGlobal = p[K.smartQueue] ?: false,
                storageTreeUri = p[K.storageUri] ?: "",
                storageTemplate = p[K.storageTemplate] ?: "{root}/{channel}/{date}/{filename}",
                reliabilityWatchdogEnabled = p[K.watchdog] ?: true,
                reliabilityShizukuEnabled = p[K.shizuku] ?: true,
                autoStartAfterBoot = p[K.autoBoot] ?: true,
                statsKeepDays = p[K.statsKeepDays] ?: 180,
            )
            val n = transform(cur)
            p[K.apiId] = n.apiId; p[K.apiHash] = n.apiHash; p[K.phone] = n.phoneNumberHint
            p[K.loggedIn] = n.loggedIn; p[K.speed] = n.globalSpeedLimitBps
            p[K.concMode] = n.concurrencyMode; p[K.fixedConc] = n.fixedConcurrency
            p[K.maxConc] = n.maxConcurrency; p[K.netPolicy] = n.networkPolicy.name
            p[K.dnOverrides] = n.downloadNowOverridesNetwork
            p[K.endBehavior] = n.endBehaviorDefault
            p[K.notifComplete] = n.notificationsOnComplete; p[K.notifPerFile] = n.notificationsPerFile
            p[K.inbox] = n.inboxChatId; p[K.smartQueue] = n.smartQueueGlobal
            p[K.storageUri] = n.storageTreeUri; p[K.storageTemplate] = n.storageTemplate
            p[K.watchdog] = n.reliabilityWatchdogEnabled; p[K.shizuku] = n.reliabilityShizukuEnabled
            p[K.autoBoot] = n.autoStartAfterBoot; p[K.statsKeepDays] = n.statsKeepDays
        }
    }

    fun dbPolicy(p: NetworkPolicy): NetworkPolicy = p
}
