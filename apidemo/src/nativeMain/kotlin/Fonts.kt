package apidemo

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import com.compose.sdl.icons.IconFont
import com.compose.sdl.loadComposeResourceBytes
import com.compose.sdl.text.NamedFont
import com.compose.sdl.text.namedFontFamily

// ==================
// MARK: Monospace body font - native actuals (data.kres + IconFont)
// ==================

actual val monoFontFamilyName: String? by lazy {
    val vBytes = loadComposeResourceBytes("font/NotoSansMono.ttf")
    if (vBytes == null) {
        println("apidemo: NotoSansMono.ttf not bundled - body uses the default font")
        null
    } else {
        IconFont.register(kMonoFamily, vBytes)
        kMonoFamily
    }
}

actual val monoFontFamily: FontFamily? by lazy {
    monoFontFamilyName?.let { namedFontFamily(it) }
}

/** Native already resolves FontFamily.Default to the bundled NotoSans, but the
family is registered by name so both stacks go through the same lookup. The italic
member (font/NotoSans-Italic.ttf) keeps FontStyle.Italic a real italic, not a slant. */
actual val defaultFontFamily: FontFamily? by lazy {
    val vBytes = loadComposeResourceBytes("font/NotoSans.ttf")
    if (vBytes == null) {
        println("apidemo: NotoSans.ttf not bundled - using the platform default font")
        null
    } else {
        IconFont.register(kDefaultFamily, vBytes)
        val vItalicBytes = loadComposeResourceBytes("font/NotoSans-Italic.ttf")
        vItalicBytes?.let { IconFont.register("$kDefaultFamily-italic", it) }
        FontFamily(listOfNotNull(
            NamedFont(kDefaultFamily),
            vItalicBytes?.let { NamedFont("$kDefaultFamily-italic", style = FontStyle.Italic) },
        ))
    }
}
