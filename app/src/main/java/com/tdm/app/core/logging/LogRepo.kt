package com.tdm.app.core.logging

import com.tdm.app.data.db.SystemLogEntity
import com.tdm.app.data.db.SystemLogDao
import kotlinx.coroutines.flow.Flow

/**
 * Diagnostic logging (spec §64): categories + severity + timestamps.
 * NEVER logs OTP / 2FA password / session secrets (spec §64 hard rule).
 */
object LogRepo {

    // Secrets that must never appear in logs even by accident
    private val sensitive = Regex(
        "(?i)(otp|code|password|2fa|session|auth_key|token|secret)\\s*[:=]\\s*\\S+"
    )

    fun sanitize(msg: String): String = sensitive.replace(msg, "$1=***")

    suspend fun log(dao: SystemLogDao, category: String, severity: String, message: String) {
        dao.insert(
            SystemLogEntity(
                at = System.currentTimeMillis(),
                category = category,
                severity = severity,
                message = sanitize(message),
            )
        )
    }

    suspend fun log(db: com.tdm.app.data.db.TdmDatabase, category: String, severity: String, message: String) {
        log(db.systemLogDao(), category, severity, message)
    }

    fun observe(dao: SystemLogDao, limit: Int = 300): Flow<List<SystemLogEntity>> =
        dao.observeRecent(limit)

    suspend fun prune(dao: SystemLogDao, keepDays: Int = 14) {
        dao.prune(System.currentTimeMillis() - keepDays * 86_400_000L)
    }
}
