package com.compose.sdl.text

import com.compose.sdl.icons.IconFont
import com.compose.sdl.loadComposeResourceBytes

// ==================
// MARK: Generic font families (FontFamily.Monospace / Serif / Cursive)
// ==================

/**
 * Registers the bundled fonts that back the generic FontFamily.* values, under the
 * "generic:<name>" keys that FontFamily.projectFontName() returns. Today only
 * Monospace has a bundled font (font/NotoSansMono.ttf, put in data.kres by the bridge
 * plugin's default-font bundling). When a generic isn't bundled the lookup misses and the
 * renderer resolves it to the OS font for that generic (SkiaFonts.resolveGeneric).
 *
 * Idempotent; called from ComposeWindow.installGlobals (data.kres is loadable by then).
 */
private var mRegistered = false

fun registerGenericFonts() {
	if (mRegistered) return
	mRegistered = true
	loadComposeResourceBytes("font/NotoSansMono.ttf")?.let { IconFont.register("generic:monospace", it) }
}
