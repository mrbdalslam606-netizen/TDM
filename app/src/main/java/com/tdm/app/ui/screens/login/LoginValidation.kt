package com.tdm.app.ui.screens.login

/** Pure validation rules used by the login screen and unit tests. */
object LoginValidation {
    fun isValidApiId(value: String): Boolean = value.trim().toIntOrNull()?.let { it > 0 } == true

    fun isValidApiHash(value: String): Boolean = value.trim().length >= 16

    /** Accepts Telegram international numbers with optional spaces, dashes, and parentheses. */
    fun isValidPhone(value: String): Boolean {
        val normalized = value.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
        if (!normalized.startsWith("+")) return false
        val digits = normalized.drop(1)
        return digits.length in 8..15 && digits.all(Char::isDigit)
    }

    fun isValidCode(value: String): Boolean = value.trim().length in 3..10 && value.trim().all(Char::isDigit)
}
