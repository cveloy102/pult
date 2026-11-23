package ru.hl100.pult

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun migratesOldThemes() {
        assertEquals(THEME_DARK, normalizeTheme(null))
        assertEquals(THEME_DARK, normalizeTheme("signal"))
        assertEquals(THEME_DARK, normalizeTheme("orbit"))
        assertEquals(THEME_LIGHT, normalizeTheme("quiet"))
        assertEquals(THEME_LIGHT, normalizeTheme(THEME_LIGHT))
        assertEquals(THEME_DARK, normalizeTheme("unknown"))
    }

    @Test fun namesThemes() {
        assertEquals("Тёмная", themeName(THEME_DARK))
        assertEquals("Светлая", themeName(THEME_LIGHT))
    }
}
