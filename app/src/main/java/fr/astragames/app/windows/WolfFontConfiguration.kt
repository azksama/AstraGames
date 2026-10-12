package fr.astragames.app.windows

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Stable fontconfig files and a runtime-scoped cache reused by all game prefixes. */
internal object WolfFontConfiguration {
    fun prepare(context: Context, runtime: File, gameDirectory: File, policy: WolfFontPolicy): File {
        val cache = File(context.noBackupFilesDir, "wolf-font-cache/v1/${WolfRuntimeInstaller.REVISION}/${policy.name}")
        check(cache.isDirectory || cache.mkdirs()) { "Cache des polices inaccessible." }
        val bytes = xml(File(runtime, "fonts").path, cache.path, policy).toByteArray(Charsets.UTF_8)
        val file = File(gameDirectory, "fonts.conf")
        // Preserving mtime also avoids triggering repeated fontconfig invalidations.
        if (file.isFile && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)) return file
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        return file
    }
    internal fun xml(fonts: String, cache: String, policy: WolfFontPolicy): String = buildString {
        append("<?xml version=\"1.0\"?><!DOCTYPE fontconfig SYSTEM \"fonts.dtd\"><fontconfig>")
        append("<dir>").append(escape(fonts)).append("</dir>")
        if (policy == WolfFontPolicy.SYSTEM) append("<dir>/system/fonts</dir>")
        append("<cachedir>").append(escape(cache)).append("</cachedir>")
        listOf("MS Gothic", "MS PGothic", "MS UI Gothic", "ＭＳ ゴシック", "ＭＳ Ｐゴシック").forEach { family ->
            append("<alias binding=\"strong\"><family>").append(family)
                .append("</family><prefer><family>IPAexGothic</family></prefer></alias>")
        }
        append("</fontconfig>")
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
