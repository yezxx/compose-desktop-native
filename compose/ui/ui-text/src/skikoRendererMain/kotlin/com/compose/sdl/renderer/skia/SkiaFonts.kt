package com.compose.sdl.renderer.skia

import androidx.compose.ui.text.font.FontVariation as ComposeFontVariation
import com.compose.sdl.icons.IconFont
import com.compose.sdl.res.composeResourceReader
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontWeight
import org.jetbrains.skia.FontWidth
import org.jetbrains.skia.FontStyle as SkiaFontStyle
import org.jetbrains.skia.FontVariation as SkiaFontVariation
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import kotlin.math.roundToInt

// ==================
// MARK: SkiaFonts - font-resolution bridge for the skiko paragraph engine
// ==================

/**
 * Turns the port's `(family-name, variable-axis)` font model into skiko
 * [Typeface]s for the paragraph engine. Families come from
 * [com.compose.sdl.text.NamedFont.projectFontName] (a bundled default, an
 * [IconFont]-registered text/icon family, or `generic:*`); variable axes
 * (Material Symbols `FILL`/`wght`/… and paragraph `FontWeight`→`wght`) are
 * applied via [Typeface.makeClone].
 *
 * Every resolved typeface is registered in a [TypefaceFontProvider] under a
 * UNIQUE alias, and [resolve] hands that alias back so the caller sets both
 * `TextStyle.typeface` AND `TextStyle.fontFamilies = [alias]`. That matters:
 * skiko's shaper maps codepoints→glyphs through `fontFamilies` (the
 * FontCollection), so an icon font MUST be reachable by its alias - otherwise a
 * bare `typeface` is ignored and private-use icon codepoints render as tofu.
 * (Mirrors upstream FontCache: register alias + set fontFamilies + typeface.)
 */
internal object SkiaFonts {
	private const val DEFAULT_ALIAS = "Noto Sans"
	private const val GENERIC_PREFIX = "generic:"

	private val fontMgr = FontMgr.default
	private val provider = TypefaceFontProvider()

	/** font/NotoSans.ttf from data.kres (the bridge plugin bundles it by default), or null. */
	private val bundledDefault: Typeface? = bundledFont("font/NotoSans.ttf")

	/** The default typeface - the fallback for every unresolved family. ALWAYS NotoSans when
	   data.kres carries font/NotoSans.ttf. Without it, the OS sans-serif, then the platform's
	   default face, so the default is never EMPTY: a null typeface measures as all-zero font
	   metrics, which turned the baseline-shift math into NaN under a height override
	   (issue #5). Registered under [DEFAULT_ALIAS] either way so the paragraph's alias
	   lookup lands on the same face. */
	val defaultTypeface: Typeface? =
		(bundledDefault
			?: resolveGeneric("sans-serif", SkiaFontStyle.NORMAL)
			?: runCatching { fontMgr.legacyMakeTypeface("", SkiaFontStyle.NORMAL) }.getOrNull())
			?.also { provider.registerTypeface(it, DEFAULT_ALIAS) }

	/** The real italic companion of [defaultTypeface], from the SAME family: font/NotoSans-Italic.ttf
	   when the default is the bundled NotoSans, else the OS italic of the system default. Null
	   when there is none - FontStyle.Italic then falls back to Skia's synthetic slant of the
	   upright face. Also registered under [DEFAULT_ALIAS], so the alias's style set holds both
	   faces and skparagraph's style match picks the right one. */
	val defaultItalicTypeface: Typeface? =
		(if (bundledDefault != null) bundledFont("font/NotoSans-Italic.ttf")
		else defaultTypeface?.let { matchItalic(it.familyName) })
			?.also { provider.registerTypeface(it, DEFAULT_ALIAS) }

	/** Shared collection: the alias provider first (bundled + icon + varied fonts),
	   then the system FontMgr for glyph fallback (CJK/emoji where present). */
	val fontCollection: FontCollection = FontCollection().apply {
		setDefaultFontManager(fontMgr)
		setAssetFontManager(provider)
	}

	// (family name (null = default), weight, italic) -> base typeface
	private val baseCache = mutableMapOf<Triple<String?, Int, Boolean>, Typeface?>()
	// resolve key -> (typeface, registered alias)
	private val resolveCache = mutableMapOf<String, Pair<Typeface?, String>>()
	private val registered = mutableSetOf(DEFAULT_ALIAS)

	private fun bundledFont(path: String): Typeface? =
		composeResourceReader?.invoke(path)?.let { fontMgr.makeFromData(Data.makeFromBytes(it), 0) }

	/** The OS italic face of [familyName], or null when the family has no real italic. */
	private fun matchItalic(familyName: String): Typeface? =
		runCatching { fontMgr.matchFamilyStyle(familyName, SkiaFontStyle.ITALIC) }.getOrNull()?.takeIf { it.isItalic }

	private fun skiaStyle(weight: Int, italic: Boolean): SkiaFontStyle =
		SkiaFontStyle(weight, FontWidth.NORMAL, if (italic) FontSlant.ITALIC else FontSlant.UPRIGHT)

	/** The default face for [weight] + [italic]. The bundled NotoSans is variable, so its
	   weight is applied later as the wght axis. A SYSTEM default is usually static (no wght
	   axis), so its bold / italic faces come from the OS family match instead - otherwise
	   FontWeight.Bold would render at regular weight on the fallback. */
	private fun defaultFor(weight: Int, italic: Boolean): Typeface? {
		val vDefault = defaultTypeface
		val vStyled = if (italic) defaultItalicTypeface ?: vDefault else vDefault
		if (bundledDefault != null || vDefault == null || (weight == FontWeight.NORMAL && !italic)) return vStyled
		return runCatching { fontMgr.matchFamilyStyle(vDefault.familyName, skiaStyle(weight, italic)) }.getOrNull() ?: vStyled
	}

	private fun baseTypeface(family: String?, weight: Int, italic: Boolean): Typeface? =
		baseCache.getOrPut(Triple(family, weight, italic)) {
			val bytes = family?.let { IconFont.bytesFor(it) }
			val style = skiaStyle(weight, italic)
			when {
				// Registered bytes ARE the face (an italic resource font is already italic);
				// an upright-only family keeps Skia's synthetic slant.
				bytes != null -> fontMgr.makeFromData(Data.makeFromBytes(bytes), 0)
				// Generic family (serif / cursive / …) not bundled: resolve through the
				// per-OS concrete-name alias list (upstream GenericFontFamiliesMapping),
				// so `FontFamily.Serif` picks Times/Noto Serif instead of silently
				// becoming the sans-serif default. (Monospace is caught by IconFont above
				// when NotoSansMono is bundled; sans-serif maps to null → default Noto Sans.)
				family != null && family.startsWith(GENERIC_PREFIX) ->
					resolveGeneric(family.removePrefix(GENERIC_PREFIX), style) ?: defaultFor(weight, italic)
				// A concrete family name: resolve against the OS font set (e.g. "Arial",
				// "Times New Roman"), so a requested system font isn't silently Noto Sans.
				family != null ->
					runCatching { fontMgr.matchFamilyStyle(family, style) }.getOrNull()
						?: defaultFor(weight, italic)
				else -> defaultFor(weight, italic)
			}
		}

	/** Resolve a generic family name (serif / sans-serif / monospace / cursive) to a
	   concrete OS typeface in [style] by trying the per-OS candidate names in order. */
	private fun resolveGeneric(genericName: String, style: SkiaFontStyle): Typeface? {
		for (name in genericFamilyAliases(genericName)) {
			runCatching { fontMgr.matchFamilyStyle(name, style) }.getOrNull()?.let { return it }
		}
		return null
	}

	private fun variationsKey(variations: List<ComposeFontVariation.Setting>): String =
		variations.sortedBy { it.axisName }.joinToString(",") { "${it.axisName}=${it.toVariationValue(null)}" }

	/** Resolve a family + optional variable-axis settings + [italic] to a concrete typeface
	   AND a provider alias (register-on-first-use). Set `fontFamilies = [alias]` and
	   `typeface = first` on the skiko TextStyle. [italic] selects the family's REAL italic
	   face when one exists; otherwise the upright comes back and Skia slants it. */
	fun resolve(
		family: String?,
		variations: List<ComposeFontVariation.Setting>?,
		italic: Boolean = false,
	): Pair<Typeface?, String> {
		if (family == null && variations.isNullOrEmpty()) return defaultFor(FontWeight.NORMAL, italic) to DEFAULT_ALIAS
		val varKey = variations?.takeUnless { it.isEmpty() }?.let { variationsKey(it) } ?: ""
		val key = "${family ?: ""}#$varKey" + if (italic) "#italic" else ""
		return resolveCache.getOrPut(key) {
			// wght also picks the face for static (system) families; variable ones get it
			// again as an axis below.
			val weight = variations?.firstOrNull { it.axisName == "wght" }?.toVariationValue(null)?.roundToInt()
				?: FontWeight.NORMAL
			val base = baseTypeface(family, weight, italic) ?: defaultFor(weight, italic)
			val typeface = if (varKey.isEmpty() || base == null) base
			else runCatching {
				base.makeClone(variations!!.map { SkiaFontVariation(it.axisName, it.toVariationValue(null)) }.toTypedArray())
			}.getOrDefault(base)
			val alias = if (family == null && varKey.isEmpty()) DEFAULT_ALIAS else "cdn-font:$key"
			if (typeface != null && alias !in registered) {
				provider.registerTypeface(typeface, alias)
				registered += alias
			}
			typeface to alias
		}
	}
}

// ==================
// MARK: Generic font family aliases (per-OS)
// ==================

/** Concrete OS family-name candidates for a generic family, tried in order.
   Mirrors upstream `GenericFontFamiliesMapping` (PlatformFont.skiko.kt); unknown
   generics get no candidates so they fall back to the bundled default. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun genericFamilyAliases(genericName: String): List<String> = when (kotlin.native.Platform.osFamily) {
	kotlin.native.OsFamily.MACOSX -> when (genericName) {
		"sans-serif" -> listOf(".AppleSystemUIFont", "Helvetica Neue", "Helvetica")
		"serif" -> listOf(".AppleSystemUIFontSerif", "Times", "Times New Roman")
		"monospace" -> listOf(".AppleSystemUIFontMonospaced", "Menlo", "Courier")
		"cursive" -> listOf("Apple Chancery", "Snell Roundhand")
		else -> emptyList()
	}
	kotlin.native.OsFamily.WINDOWS -> when (genericName) {
		"sans-serif" -> listOf("Segoe UI", "Arial")
		"serif" -> listOf("Times New Roman")
		"monospace" -> listOf("Consolas")
		"cursive" -> listOf("Comic Sans MS")
		else -> emptyList()
	}
	else -> when (genericName) { // Linux + everything else
		"sans-serif" -> listOf("Noto Sans", "DejaVu Sans", "Arial")
		"serif" -> listOf("Noto Serif", "DejaVu Serif", "Times New Roman")
		"monospace" -> listOf("Noto Sans Mono", "DejaVu Sans Mono", "Consolas")
		"cursive" -> listOf("Comic Sans MS")
		else -> emptyList()
	}
}
