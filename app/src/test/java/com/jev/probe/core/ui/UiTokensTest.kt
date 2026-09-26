package com.jev.probe.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTokensTest {

    private val hexColor = Regex("^#[0-9A-Fa-f]{6}$")

    private fun assertPaletteAllHex(p: UiTokens.Palette) {
        listOf(
            "ink" to p.ink,
            "sub" to p.sub,
            "faint" to p.faint,
            "surface" to p.surface,
            "canvas" to p.canvas,
            "card" to p.card,
            "accent" to p.accent,
            "accentDeep" to p.accentDeep,
            "accentLight" to p.accentLight,
            "accentSoft" to p.accentSoft,
            "surfaceElev" to p.surfaceElev,
            "danger" to p.danger,
            "warn" to p.warn,
            "ok" to p.ok,
        ).forEach { (name, value) ->
            assertTrue("LIGHT/DARK palette field '$name' is not a valid #RRGGBB: $value", hexColor.matches(value))
        }
        assertTrue(
            "hairline must be #AARRGGBB: ${p.hairline}",
            Regex("^#[0-9A-Fa-f]{8}$").matches(p.hairline))
    }

    @Test
    fun `light palette fields are all valid RRGGBB`() {
        assertPaletteAllHex(UiTokens.LIGHT)
    }

    @Test
    fun `dark palette fields are all valid RRGGBB`() {
        assertPaletteAllHex(UiTokens.DARK)
    }

    @Test
    fun `palette(true) returns DARK and palette(false) returns LIGHT`() {
        assertEquals(UiTokens.DARK, UiTokens.palette(true))
        assertEquals(UiTokens.LIGHT, UiTokens.palette(false))
    }

    @Test
    fun `light and dark palettes differ`() {
        assertTrue(UiTokens.LIGHT != UiTokens.DARK)
    }
}
