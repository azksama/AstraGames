package fr.astragames.app.launcher

import android.content.Context
import java.util.Locale
import android.content.Intent
import android.content.pm.PackageManager
import fr.astragames.app.core.model.GameEngine

data class JoiPlayRuntimeInfo(
    val key: String,
    val name: String,
    val packageNames: List<String>,
    val engines: Set<GameEngine>,
    val installedPackage: String?,
    val versionName: String?,
    val required: Boolean,
    val downloadUrl: String,
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    val description: String? = null
) {
    val installed: Boolean get() = installedPackage != null
}

class JoiPlayRuntimeManager {
    @Suppress("DEPRECATION")
    fun inspect(
        context: Context,
        libraryEngines: Set<GameEngine>,
        catalog: List<JoiPlayCatalogEntry> = emptyList()
    ): List<JoiPlayRuntimeInfo> {
        val packageManager = context.packageManager
        val installed = linkedMapOf<String, InstalledComponent>()

        fun addPackage(packageName: String, actions: Set<String> = emptySet()) {
            val packageInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull() ?: return
            val applicationInfo = packageInfo.applicationInfo
            val label = applicationInfo?.let { packageManager.getApplicationLabel(it).toString() }.orEmpty().ifBlank { packageName }
            val previous = installed[packageName]
            installed[packageName] = InstalledComponent(
                packageName = packageName,
                label = label,
                version = packageInfo.versionName,
                actions = previous?.actions.orEmpty() + actions
            )
        }

        addPackage(JoiPlayLauncher.PACKAGE)
        KNOWN_PACKAGES.forEach(::addPackage)
        DISCOVERY_ACTIONS.forEach { action ->
            val intent = Intent(action).addCategory(Intent.CATEGORY_DEFAULT)
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).forEach { resolved ->
                addPackage(resolved.activityInfo.packageName, setOf(action))
            }
        }
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY).forEach { resolved ->
            val packageName = resolved.activityInfo.packageName
            val label = resolved.loadLabel(packageManager).toString()
            if (packageName.startsWith("cyou.joiplay", ignoreCase = true) || "joiplay" in label.lowercase(Locale.ROOT)) {
                addPackage(packageName)
            }
        }

        return reconcile(libraryEngines, catalog, installed.values.toList())
    }

    /** Separate discovery from catalogue reconciliation so version matching can be tested without Android. */
    internal fun reconcile(
        libraryEngines: Set<GameEngine>,
        catalog: List<JoiPlayCatalogEntry>,
        installed: List<InstalledComponent>
    ): List<JoiPlayRuntimeInfo> {
        val entries = if (catalog.isEmpty()) FALLBACK_CATALOG else catalog
        val unmatched = installed.toMutableList()
        val result = entries.map { entry ->
            val component = bestMatch(entry, unmatched)
            if (component != null) unmatched.remove(component)
            val engines = enginesFor(entry.title)
            JoiPlayRuntimeInfo(
                key = "catalog-${entry.id}",
                name = entry.title,
                packageNames = component?.let { listOf(it.packageName) }.orEmpty(),
                engines = engines,
                installedPackage = component?.packageName,
                versionName = component?.version,
                required = isRequired(entry.title, engines, libraryEngines),
                downloadUrl = entry.downloadUrl,
                latestVersion = entry.version.ifBlank { null },
                updateAvailable = component?.version?.let { installedVersion -> entry.version.isNotBlank() && isNewer(entry.version, installedVersion) } == true,
                description = entry.description
            )
        }.toMutableList()

        unmatched.forEach { component ->
            result += JoiPlayRuntimeInfo(
                key = "installed-${component.packageName}",
                name = component.label,
                packageNames = listOf(component.packageName),
                engines = enginesFor("${component.label} ${component.packageName}"),
                installedPackage = component.packageName,
                versionName = component.version,
                required = false,
                downloadUrl = JOIPLAY_URL,
                description = "Composant JoiPlay détecté localement"
            )
        }
        return result
    }

    private fun bestMatch(entry: JoiPlayCatalogEntry, candidates: List<InstalledComponent>): InstalledComponent? {
        if (entry.title.equals("JoiPlay", ignoreCase = true)) {
            return candidates.firstOrNull { it.packageName == JoiPlayLauncher.PACKAGE }
        }
        val family = familyOf(entry.title)
        val sameFamily = candidates.filter { familyOf("${it.label} ${it.packageName} ${it.actions.joinToString()}") == family }
        if (sameFamily.isEmpty()) return null
        // Godot's packages are stable across engine updates; catalogue titles include a minor version,
        // while their Android labels contain only the generation ("Godot 3/4 Plugin for JoiPlay").
        if (family == "godot") {
            val generation = godotGeneration(entry.title) ?: return null
            return sameFamily.firstOrNull { godotGeneration("${it.packageName} ${it.label}") == generation }
        }
        val wantedEngineVersion = engineVersion(entry.title)
        if (wantedEngineVersion != null) {
            return sameFamily.firstOrNull { candidate ->
                val actual = engineVersion("${candidate.label} ${candidate.packageName}") ?: return@firstOrNull false
                val commonSize = minOf(wantedEngineVersion.size, actual.size)
                wantedEngineVersion.take(commonSize) == actual.take(commonSize)
            }
        }
        return if (sameFamily.size == 1) sameFamily.single() else null
    }

    private fun familyOf(value: String): String {
        val normalized = value.lowercase(Locale.ROOT).replace("’", "'")
        return when {
            "ren'py" in normalized || "renpy" in normalized -> "renpy"
            "rpg" in normalized -> "rpgmaker"
            "godot" in normalized -> "godot"
            "ruffle" in normalized || "flash" in normalized -> "ruffle"
            "joiplay" in normalized -> "joiplay"
            else -> normalized
        }
    }

    private fun engineVersion(value: String): List<Int>? {
        val dotted = ENGINE_VERSION.find(value)?.groupValues?.get(1)
        if (dotted != null) return dotted.split('.').map(String::toInt)
        return PACKAGE_ENGINE_VERSION.find(value)?.groupValues?.drop(1)?.map(String::toInt)
    }

    private fun godotGeneration(value: String): Int? = GODOT_GENERATION.find(value)?.groupValues?.get(1)?.toInt()

    private fun enginesFor(value: String): Set<GameEngine> = when (familyOf(value)) {
        "renpy" -> setOf(GameEngine.RENPY)
        "rpgmaker" -> RPG_ENGINES
        "godot" -> when (godotGeneration(value)) {
            3 -> setOf(GameEngine.GODOT_3)
            4 -> setOf(GameEngine.GODOT_4)
            else -> setOf(GameEngine.GODOT_3, GameEngine.GODOT_4)
        }
        else -> emptySet()
    }

    private fun isRequired(title: String, engines: Set<GameEngine>, libraryEngines: Set<GameEngine>): Boolean =
        title.equals("JoiPlay", ignoreCase = true) || engines.any(libraryEngines::contains)

    internal fun isNewer(latest: String, installed: String): Boolean {
        val expected = VERSION_PARTS.findAll(latest).map { it.value.toIntOrNull() ?: 0 }.toList()
        val actual = VERSION_PARTS.findAll(installed.substringBefore('-')).map { it.value.toIntOrNull() ?: 0 }.toList()
        val size = maxOf(expected.size, actual.size)
        repeat(size) { index ->
            val comparison = (expected.getOrNull(index) ?: 0).compareTo(actual.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    internal data class InstalledComponent(
        val packageName: String,
        val label: String,
        val version: String?,
        val actions: Set<String> = emptySet()
    )

    private companion object {
        const val JOIPLAY_URL = "https://joiplay.net/"
        val VERSION_PARTS = Regex("\\d+")
        val ENGINE_VERSION = Regex("(?<!\\d)([3478](?:\\.\\d+){1,2})(?![\\d.])")
        val PACKAGE_ENGINE_VERSION = Regex("\\.v([3478])d([0-9]{1,3})d([0-9]{1,3})(?:$|\\s)")
        val GODOT_GENERATION = Regex("godot\\s*([34])(?=[.\\s]|$)", RegexOption.IGNORE_CASE)
        val RPG_ENGINES = setOf(
            GameEngine.RPG_MAKER_MV, GameEngine.RPG_MAKER_MZ, GameEngine.RPG_MAKER_VX_ACE,
            GameEngine.RPG_MAKER_VX, GameEngine.RPG_MAKER_XP, GameEngine.RPG_MAKER_2000, GameEngine.RPG_MAKER_2003
        )
        val KNOWN_PACKAGES = listOf(
            "cyou.joiplay.runtime.rpgmaker", "cyou.joiplay.runtime.renpy", "cyou.joiplay.runtime.renpy8",
            "cyou.joiplay.runtime.godot", "cyou.joiplay.runtime.godot3", "cyou.joiplay.runtime.godot4",
            "cyou.joiplay.runtime.ruffle"
        )
        val DISCOVERY_ACTIONS = listOf(
            "cyou.joiplay.runtime.renpy.run", "cyou.joiplay.runtime.renpy8.run",
            "cyou.joiplay.runtime.rpgmmv.run", "cyou.joiplay.runtime.rpgmmz.run",
            "cyou.joiplay.runtime.rpgmvxace.run", "cyou.joiplay.runtime.rpgmvx.run", "cyou.joiplay.runtime.rpgmxp.run",
            "cyou.joiplay.runtime.godot.run", "cyou.joiplay.runtime.godot3.run", "cyou.joiplay.runtime.godot4.run",
            "cyou.joiplay.runtime.ruffle.run"
        )
        val FALLBACK_CATALOG = listOf(
            JoiPlayCatalogEntry(1, "JoiPlay", "", "Application principale", JOIPLAY_URL, "", null),
            JoiPlayCatalogEntry(2, "RPG Maker Plugin", "", "Plugin RPG Maker", JOIPLAY_URL, "", null),
            JoiPlayCatalogEntry(3, "Ren'Py Plugin", "", "Plugin Ren'Py", JOIPLAY_URL, "", null),
            JoiPlayCatalogEntry(6, "Godot 4 Plugin", "", "Plugin Godot 4", JOIPLAY_URL, "", null),
            JoiPlayCatalogEntry(7, "Godot 3 Plugin", "", "Plugin Godot 3", JOIPLAY_URL, "", null)
        )
    }
}
