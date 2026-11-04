# Room and Coil publish their own consumer rules. Keep only serialized database enums.
-keepclassmembers enum fr.astragames.app.core.model.** { *; }
-keep class fr.astragames.app.mkxpz.MkxpzActivity { *; }
