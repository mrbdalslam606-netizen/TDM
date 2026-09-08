package com.tdm.app

import com.tdm.app.ui.screens.login.LoginValidation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginValidationTest {
    @Test
    fun validApiCredentialsEnableContinue() {
        assertTrue(LoginValidation.isValidApiId("123456"))
        assertTrue(LoginValidation.isValidApiHash("1234567890abcdef"))
        assertFalse(LoginValidation.isValidApiId("0"))
        assertFalse(LoginValidation.isValidApiHash("short"))
    }

    @Test
    fun internationalPhoneFormattingIsAccepted() {
        assertTrue(LoginValidation.isValidPhone("+201001234567"))
        assertTrue(LoginValidation.isValidPhone("+20 (100) 123-4567"))
        assertFalse(LoginValidation.isValidPhone("01001234567"))
        assertFalse(LoginValidation.isValidPhone("+20abc1234567"))
        assertFalse(LoginValidation.isValidPhone("+123"))
    }

    @Test
    fun numericLoginCodeIsRequired() {
        assertTrue(LoginValidation.isValidCode("12345"))
        assertFalse(LoginValidation.isValidCode(""))
        assertFalse(LoginValidation.isValidCode("12a45"))
    }
}
