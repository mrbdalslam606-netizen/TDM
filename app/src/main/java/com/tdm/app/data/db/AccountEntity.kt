package com.tdm.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Telegram account metadata; TDLib session data is stored in a separate private directory. */
@Entity(tableName = "telegram_accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val displayName: String = "",
    val phoneNumber: String = "",
    val apiId: Int,
    val apiHash: String,
    val loggedIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
)
