package ru.hl100.pult

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun migratesOldThemes() {
        assertEquals(THEME_DARK, normalizeTheme("signal"))
        assertEquals(THEME_DARK, normalizeTheme("orbit"))
        assertEquals(THEME_LIGHT, normalizeTheme("quiet"))
        assertEquals(THEME_LIGHT, normalizeTheme(THEME_LIGHT))
        assertEquals(THEME_DARK, normalizeTheme(THEME_DARK))
    }

    @Test fun newInstallFollowsSystem() {
        assertEquals(THEME_SYSTEM, normalizeTheme(null))
        assertEquals(THEME_SYSTEM, normalizeTheme(THEME_SYSTEM))
        assertEquals(THEME_SYSTEM, normalizeTheme("unknown"))
    }

    @Test fun resolvesSystemTheme() {
        assertEquals(THEME_DARK, resolveTheme(THEME_SYSTEM, systemDark = true))
        assertEquals(THEME_LIGHT, resolveTheme(THEME_SYSTEM, systemDark = false))
        assertEquals(THEME_DARK, resolveTheme(THEME_DARK, systemDark = false))
        assertEquals(THEME_LIGHT, resolveTheme(THEME_LIGHT, systemDark = true))
    }

    @Test fun namesThemes() {
        assertEquals("Как в системе", themeName(THEME_SYSTEM))
        assertEquals("Тёмная", themeName(THEME_DARK))
        assertEquals("Светлая", themeName(THEME_LIGHT))
    }
}
