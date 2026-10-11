package fr.astragames.app.settings

import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class BatteryOptimizationTest {
    @Test fun batteryStatusComesFromAndroidAndRequestsOnlyThisPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        assertEquals(expected, BatteryOptimization.isExempt(context))
        val intents = BatteryOptimization.settingsIntents(context)
        assertEquals(if (expected) Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            else Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intents.first().action)
        assertTrue(intents.filter { it.data != null }.all { it.data.toString() == "package:${context.packageName}" })
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.last().action)
    }
}
