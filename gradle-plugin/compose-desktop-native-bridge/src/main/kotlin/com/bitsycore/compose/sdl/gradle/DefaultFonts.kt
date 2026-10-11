package com.bitsycore.compose.sdl.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// ==================
// MARK: Default fonts - Noto Sans (+ Italic) + Noto Sans Mono into data.kres
// ==================
//
// The runtime resolves FontFamily.Default to `font/NotoSans.ttf` (its
// FontStyle.Italic to `font/NotoSans-Italic.ttf`) and FontFamily.Monospace to
// `font/NotoSansMono.ttf`, read from data.kres by fixed path (SkiaFonts) - no
// manifest needed. Without them text still renders - the paragraph's FontCollection
// falls through to the system FontMgr - but the default typeface is EMPTY: text
// looks different on every host (fontconfig / DirectWrite / CoreText pick), and
// anything reading the style's own font metrics sees zeros (issue #5).
//
// The fonts are pinned to an immutable google/fonts commit and SHA-256 verified,
// downloaded once into the Gradle user home and shared by every project/clean.

/**
 * One bundled font: the data.kres entry name, its pinned source, and the hash
 * the download must match.
 */
private data class PinnedFont(val fileName: String, val url: String, val sha256: String)

private val kNotoSans = PinnedFont(
	"NotoSans.ttf",
	"https://raw.githubusercontent.com/google/fonts/8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf",
	"bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d",
)

private val kNotoSansItalic = PinnedFont(
	"NotoSans-Italic.ttf",
	"https://raw.githubusercontent.com/google/fonts/8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5/ofl/notosans/NotoSans-Italic%5Bwdth%2Cwght%5D.ttf",
	"58e6e0ebd1931b29a365aa2d3e2ee9a9e831a3af7cf3ad1462d4e72154f0b291",
)

private val kNotoSansMono = PinnedFont(
	"NotoSansMono.ttf",
	"https://raw.githubusercontent.com/google/fonts/097bc1b8c04c3224087c2ae95f7a923859b778cf/ofl/notosansmono/NotoSansMono%5Bwdth%2Cwght%5D.ttf",
	"2cb2adb378a8f574213e23df697050b83c54c27df465a2015552740b2769a081",
)

private val kPinnedFonts = listOf(kNotoSans, kNotoSansItalic, kNotoSansMono).associateBy { it.fileName }

/** Task staging the selected default fonts; its output dir holds exactly the bundled set. */
internal const val BUNDLE_FONTS_TASK_NAME = "bundleComposeNativeFonts"

/**
 * The default fonts bundled into data.kres - all ON by default.
 *
 * ```kotlin
 * compose.desktop.native {
 *     fonts {
 *         bundleNotoSans.set(false)        // FontFamily.Default → system font fallback
 *         bundleNotoSansItalic.set(false)  // FontStyle.Italic → synthetic slant of NotoSans
 *         bundleNotoSansMono.set(false)    // FontFamily.Monospace → system font fallback
 *     }
 * }
 * ```
 *
 * `-PbundleDefaultFont=false` turns all of them off for a single build.
 */
abstract class NativeFontsSpec {
	/** Bundle `font/NotoSans.ttf`, the font behind FontFamily.Default. Default `true`. */
	abstract val bundleNotoSans: Property<Boolean>

	/** Bundle `font/NotoSans-Italic.ttf`, the real italic of FontFamily.Default (only used
	    alongside [bundleNotoSans] - the runtime never mixes it with a system upright).
	    Without it, FontStyle.Italic is Skia's synthetic slant of the upright. Default `true`. */
	abstract val bundleNotoSansItalic: Property<Boolean>

	/** Bundle `font/NotoSansMono.ttf`, the font behind FontFamily.Monospace (google/fonts ships
	    no Mono italic - its FontStyle.Italic is always synthetic). Default `true`. */
	abstract val bundleNotoSansMono: Property<Boolean>
}

/**
 * Registers [BUNDLE_FONTS_TASK_NAME] and adds its output under `font/` to every
 * data.kres zip (ResourcePackaging's package<Variant>ComposeResources<Target>).
 * Registered at apply time - not in afterEvaluate - so build logic can wire the
 * same fonts elsewhere (the repo's JVM parity legs stage them on the classpath);
 * the font selection itself is read lazily from [inSpec].
 */
internal fun installDefaultFonts(inProject: Project, inSpec: NativeFontsSpec) {
	inSpec.bundleNotoSans.convention(true)
	inSpec.bundleNotoSansItalic.convention(true)
	inSpec.bundleNotoSansMono.convention(true)
	val vEnabled = inProject.providers.gradleProperty("bundleDefaultFont").map(String::toBoolean).orElse(true)

	val vTask = inProject.tasks.register(BUNDLE_FONTS_TASK_NAME, BundleDefaultFontsTask::class.java) { task ->
		task.description = "Stage the default Noto fonts (pinned + SHA-256 verified) for data.kres."
		task.fontFileNames.set(inProject.provider {
			if (!vEnabled.get()) emptyList() else buildList {
				if (inSpec.bundleNotoSans.get()) add(kNotoSans.fileName)
				if (inSpec.bundleNotoSans.get() && inSpec.bundleNotoSansItalic.get()) add(kNotoSansItalic.fileName)
				if (inSpec.bundleNotoSansMono.get()) add(kNotoSansMono.fileName)
			}
		})
		task.cacheDir.set(File(inProject.gradle.gradleUserHomeDir, "caches/compose-desktop-native/fonts"))
		task.outputDir.set(inProject.layout.buildDirectory.dir("composeNativeFonts"))
	}

	inProject.tasks.matching { it.name.startsWith("package") && it.name.contains("ComposeResources") }
		.configureEach { zip ->
			(zip as Zip).from(vTask) { spec -> spec.into("font") }
		}
}

// ==================
// MARK: Task
// ==================

@DisableCachingByDefault(because = "copies from the shared Gradle-user-home font cache")
abstract class BundleDefaultFontsTask : DefaultTask() {
	/** data.kres font file names to stage (keys of the pinned font table). */
	@get:Input
	abstract val fontFileNames: ListProperty<String>

	/** Shared download cache, content-addressed by SHA-256. */
	@get:Internal
	abstract val cacheDir: DirectoryProperty

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun run() {
		val vOutDir = outputDir.get().asFile
		// Purge so a font switched off leaves no stale entry in data.kres.
		vOutDir.deleteRecursively()
		vOutDir.mkdirs()
		for (vName in fontFileNames.get()) {
			val vFont = kPinnedFonts.getValue(vName)
			cachedFont(vFont).copyTo(File(vOutDir, vFont.fileName))
		}
	}

	/** The verified cached copy of [inFont], downloading it on first use. */
	private fun cachedFont(inFont: PinnedFont): File {
		val vCached = File(cacheDir.get().asFile, "${inFont.sha256}/${inFont.fileName}")
		if (vCached.isFile && sha256(vCached) == inFont.sha256) return vCached
		vCached.parentFile.mkdirs()
		// Unique temp name + atomic move: parallel projects (or builds) share this cache
		// and may download the same font at once.
		val vTmp = File.createTempFile(inFont.fileName, ".part", vCached.parentFile)
		logger.lifecycle("compose-desktop-native: downloading ${inFont.fileName}")
		try {
			URI(inFont.url).toURL().openStream().use { vIn -> vTmp.outputStream().use { vIn.copyTo(it) } }
		} catch (t: Exception) {
			vTmp.delete()
			throw GradleException(
				"Couldn't download ${inFont.fileName} from ${inFont.url} (${t.message}).\n" +
					"Place the file at ${vCached.absolutePath} manually, or skip the default fonts " +
					"with compose.desktop.native { fonts { bundleNotoSans.set(false); bundleNotoSansItalic.set(false); " +
					"bundleNotoSansMono.set(false) } } " +
					"or -PbundleDefaultFont=false.", t)
		}
		val vActual = sha256(vTmp)
		if (vActual != inFont.sha256) {
			vTmp.delete()
			throw GradleException(
				"${inFont.fileName} checksum mismatch: expected ${inFont.sha256}, got $vActual (${inFont.url}).")
		}
		try {
			Files.move(vTmp.toPath(), vCached.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} catch (e: java.io.IOException) {
			// A concurrent download won the race (Windows can't replace a file another
			// process has open) - its copy is just as good once verified.
			vTmp.delete()
			if (!vCached.isFile || sha256(vCached) != inFont.sha256) throw e
		}
		return vCached
	}

	private fun sha256(inFile: File): String {
		val vDigest = MessageDigest.getInstance("SHA-256")
		inFile.inputStream().use { vIn ->
			val vBuf = ByteArray(64 * 1024)
			while (true) {
				val vN = vIn.read(vBuf)
				if (vN < 0) break
				vDigest.update(vBuf, 0, vN)
			}
		}
		return vDigest.digest().joinToString("") { "%02x".format(it) }
	}
}
