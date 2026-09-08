package com.tdm.app.di

import android.content.Context
import androidx.lifecycle.lifecycleScope
import com.tdm.app.core.engine.DownloadEngine
import com.tdm.app.core.engine.EngineStateHolder
import com.tdm.app.core.engine.Heartbeat
import com.tdm.app.core.engine.MonitorEngine
import com.tdm.app.core.network.NetworkMonitor
import com.tdm.app.core.recovery.RecoveryManager
import com.tdm.app.core.reliability.DeviceCompatibility
import com.tdm.app.core.reliability.ShizukuCapabilityManager
import com.tdm.app.core.stats.StatisticsEngine
import com.tdm.app.core.storage.StorageAdapter
import com.tdm.app.data.db.TdmDatabase
import com.tdm.app.data.repo.SettingsRepository
import com.tdm.app.service.DownloadForegroundService
import com.tdm.app.service.WatchdogService
import com.tdm.app.telegram.TelegramAccountManager
import com.tdm.app.telegram.TelegramClientPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual DI container (spec §52): explicit components with clear responsibilities.
 * NOT a god-singleton — every component is small, focused, replaceable.
 */
class AppContainer private constructor(private val appContext: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: TdmDatabase by lazy { TdmDatabase.get(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val accountManager: TelegramAccountManager by lazy {
        TelegramAccountManager(appContext, database, settingsRepository)
    }
    val telegram: TelegramClientPort by lazy { accountManager }
    val storageAdapter: StorageAdapter by lazy { StorageAdapter(appContext) }
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext).also { it.refresh() } }
    val heartbeat: Heartbeat by lazy { Heartbeat(appContext) }
    val shizuku: ShizukuCapabilityManager by lazy { ShizukuCapabilityManager(appContext) }
    val deviceCompatibility: DeviceCompatibility by lazy { DeviceCompatibility(appContext) }
    val recovery: RecoveryManager by lazy {
        RecoveryManager(appContext, database, telegram, storageAdapter, heartbeat)
    }

    private val engineStateHolder = EngineStateHolder()
    var engineState: EngineStateHolder = engineStateHolder
        internal set

    private var engineInstance: DownloadEngine? = null

    /** Access for UI control actions once the service has created the engine. */
    fun engineInstanceOrNull(): DownloadEngine? = engineInstance

    /** MonitorEngine is stateless — UI can create it freely (Scan Now, manual pipeline). */
    fun monitorInstance(): com.tdm.app.core.engine.MonitorEngine? =
        runCatching { monitor(appContext) }.getOrNull()

    /** Engine is bound to the service lifecycle scope (dies with the service). */
    fun engine(context: Context): DownloadEngine {
        engineInstance?.let { return it }
        synchronized(this) {
            engineInstance?.let { return it }
            val svc = context as? androidx.lifecycle.LifecycleService
                ?: throw IllegalStateException("engine must be created from the service")
            val stats = StatisticsEngine(dbStats(), dbSessions())
            val e = DownloadEngine(
                context = appContext,
                db = database,
                settings = settingsRepository,
                tg = telegram,
                storage = storageAdapter,
                networkMonitor = networkMonitor,
                stateHolder = engineStateHolder,
                heartbeat = heartbeat,
                stats = stats,
                workerScope = svc.lifecycleScope,
            )
            engineInstance = e
            return e
        }
    }

    fun monitor(context: Context): MonitorEngine =
        MonitorEngine(database, settingsRepository, telegram)

    fun startWatchdog() {
        appScope.launch {
            if (settingsRepository.current().reliabilityWatchdogEnabled) {
                WatchdogService.start(appContext)
                com.tdm.app.service.WatchdogAlarmReceiver.schedule(appContext)
            }
        }
    }

    fun stopWatchdog() {
        WatchdogService.stop(appContext)
    }

    private fun dbStats() = database.statisticsDao()
    private fun dbSessions() = database.sessionDao()

    companion object {
        @Volatile private var instance: AppContainer? = null
        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
