package fr.astragames.app.windows

import android.content.Context
import android.os.Build
import com.winlator.core.RuntimeArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Pinned, verified components; completion marker is written only after extraction. */
class WolfRuntimeInstaller(private val context: Context) {
    companion object {
        private val lock = Mutex()
        const val REVISION = "wine9-astra-2"
        fun supportedAbi() = Build.SUPPORTED_ABIS.firstOrNull() in setOf("arm64-v8a", "x86_64")
        fun hash(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    data class Component(val name: String, val url: String, val sha: String, val destination: String, val librariesOnly: Boolean = false, val raw: Boolean = false)
    val root = File(context.noBackupFilesDir, "wolf-runtime/$REVISION")
    val arm = Build.SUPPORTED_ABIS.first() == "arm64-v8a"

    suspend fun install(progress: (String) -> Unit): File = withContext(Dispatchers.IO) { lock.withLock {
        check(supportedAbi()) { "Le moteur Wolf nécessite Android 64 bits (ARM64 ou x86_64)." }
        if (File(root, "ready").readTextOrNull() == REVISION && File(root, "audio-ready").isFile) return@withLock root
        root.mkdirs()
        val components = mutableListOf(Component("Wine", "https://downloads.gamenative.app/proton-9.0-x86_64.txz",
            "922155d4a096730390029f72f074d7074f0a5edc7abb13b55a7b10092b0f3a2a", "proton", false))
        if (arm) {
            components += Component("Bibliothèques Android", "https://downloads.gamenative.app/imagefs_bionic.txz",
                "368db62bfc58b72c97e5169bda9aa64d4246f07964c447e27c79a065e7e9c48b", "libs", true)
            components += Component("Box64", "https://raw.githubusercontent.com/utkarshdalal/GameNative/26be8bc16d36fe98cedfd56ee01f9d6878cd9e00/app/src/main/assets/box86_64/box64-0.4.2-bionic.tzst",
                "fcd10730ff899bad829a5e91780d8aae6350a7e152069ff2c90e93922ecad192", "box64")
        } else {
            components += Component("Bibliothèques Android", "https://raw.githubusercontent.com/Bliss-Bass/GameNative-x64/66981c0675b754dd69eec24877267849f50f982b/app/src/modernX64/assets/bionic-libs-x86_64-20260828.tzst",
                "5f65b788bbe2c3e783f7ac300086036049eb7cf47d8d88060e546bd7233c7839", "libs", true)
        }
        val audioBase = "https://raw.githubusercontent.com/Bliss-Bass/GameNative-x64/66981c0675b754dd69eec24877267849f50f982b/app/src/modernX64"
        components += if (arm) Component("Audio", "https://raw.githubusercontent.com/utkarshdalal/GameNative/26be8bc16d36fe98cedfd56ee01f9d6878cd9e00/app/src/main/assets/pulseaudio-gamenative-20260919.tzst",
            "e64090772d8def3347f307a1b308713664ce027532137e6afb774931f2160259", "pulse")
        else Component("Audio", "$audioBase/assets/pulseaudio-gamenative-x86_64-20260827.tzst", "3b97f2f0e9133bd52309e051606f4a7cadfc10cca29b7ac413f94f067186e6f5", "pulse")
        if (!arm) mapOf(
            "libltdl.so" to "6da04426466369872ec14c52f059cf61d92c250af89ef1613a99a3832edde99b",
            "libpulse.so" to "1cb9e3fece3f9acd6762b7b5b5075d87b30c9a1c3e8bad874fb0b4596133327d",
            "libpulseaudio.so" to "de1119ffd132ecfe82183aa0e5aeaabe2be4fada0d2849cc1bd3ef581e4a82dc",
            "libpulsecommon-13.0.so" to "d4958d4aa203aa4f90c1f90027a94b81a94d4db6da8efa3123f19ef42ce6b860",
            "libpulsecore-13.0.so" to "24bf9aad2bc06f8ca2361869a1392c3697bc2b117af3a393383b1dfbbc8a32a6",
            "libsndfile.so" to "a862bdac788f566b2a6ca73dc083bd88787b564f6eb3419fbe4b4e2a83e32bdc"
        ).forEach { (name, sha) -> components += Component("Audio", "$audioBase/jniLibs/x86_64/$name", sha, "libs/usr/lib/$name", raw = true) }
        for ((index, component) in components.withIndex()) {
            coroutineContext.ensureActive()
            val marker = File(root, ".component-$index")
            if (marker.readTextOrNull() == component.sha) continue
            val archive = File(context.cacheDir, "wolf-${component.sha}.archive")
            if (!archive.exists() || hash(archive) != component.sha) {
                progress("Téléchargement : ${component.name}")
                download(component, archive, progress)
            }
            check(hash(archive) == component.sha) { "Le téléchargement de ${component.name} est incomplet ou a changé. Réessayez plus tard." }
            progress("Installation : ${component.name}")
            if (component.raw) {
                val destination = File(root, component.destination).apply { parentFile!!.mkdirs() }
                archive.copyTo(destination, overwrite = true)
            } else RuntimeArchive.extract(archive, File(root, component.destination), component.url.endsWith(".tzst"), component.librariesOnly)
            marker.writeText(component.sha)
            archive.delete()
        }
        if (!arm) patchNativeX64()
        val font = File(root, "fonts/ipaexg.ttf").apply { parentFile!!.mkdirs() }
        context.assets.open("windows/ipaexg.ttf").use { input -> font.outputStream().use { input.copyTo(it) } }
        check(File(root, "proton/bin/wine").isFile) { "Installation Wine incomplète" }
        File(root, "ready").writeText(REVISION)
        File(root, "audio-ready").writeText("1")
        root
    } }

    private suspend fun download(component: Component, destination: File, progress: (String) -> Unit) {
        val connection = URI(component.url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000; connection.readTimeout = 30_000
        val temporary = File(destination.path + ".part")
        try {
            check(connection.responseCode == 200) { "Téléchargement indisponible : ${component.name} (${connection.responseCode})" }
            val expected = connection.contentLengthLong
            var copied = 0L
            var last = -1L
            connection.inputStream.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer); if (read < 0) break
                    copied += read
                    check(copied <= 300_000_000) { "Téléchargement trop volumineux" }
                    output.write(buffer, 0, read)
                    val mb = copied / 1_000_000
                    if (mb != last) { last = mb; progress("${component.name} : $mb Mo" + if (expected > 0) " / ${expected / 1_000_000} Mo" else "") }
                }
            } }
            check(hash(temporary) == component.sha) { "Échec de la vérification de ${component.name}" }
            check(temporary.renameTo(destination)) { "Impossible de finaliser le téléchargement" }
        } finally { connection.disconnect(); temporary.delete() }
    }

    private fun patchNativeX64() {
        val file = File(root, "proton/lib/wine/x86_64-unix/ntdll.so")
        if (File(root, ".native-x64").isFile) return
        if (hash(file) == "d927858a6db35029e13f943067098fafe247e2f955de074719d69b48ce1a0d22") {
            File(root, ".native-x64").writeText("4"); return
        }
        check(hash(file) == "4811c95f4af90bc4886133fd223fd8a397bb932d0be794b3d15c89900a004247") { "Version Wine inattendue" }
        val bytes = file.readBytes()
        val before = byteArrayOf(0, 0, -1, -1, 0x7f, 0, 0, 0)
        val after = byteArrayOf(0, 0, -1, -1, -1, 0x7f, 0, 0)
        // This build targets a 39-bit Box64 address space. Native x64 needs the
        // matching 47-bit upper bound (0x7fffffff0000), avoiding reserve recursion.
        var count = 0
        for (i in 0..bytes.size - 8) if (before.indices.all { bytes[i + it] == before[it] }) {
            after.copyInto(bytes, i); count++
        }
        check(count == 4) { "Disposition Wine inattendue ($count)" }
        val patched = File(file.parentFile, "ntdll.so.new").apply { writeBytes(bytes) }
        check(patched.renameTo(file))
        File(root, ".native-x64").writeText("4")
    }
}

private fun File.readTextOrNull() = if (isFile) readText() else null
