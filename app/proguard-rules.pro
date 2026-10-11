# Room and Coil publish their own consumer rules. Keep only serialized database enums.
-keepclassmembers enum fr.astragames.app.core.model.** { *; }
# Theora/Vorbis decode calls and callback members are resolved by JNI.
-keep class fr.astragames.app.wolfnative.WolfNativeTheora { *; }
