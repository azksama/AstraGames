-keepclasseswithmembernames class com.winlator.** { native <methods>; }

# zstd-jni 1.5.7-6 initDStream resolves these long fields by their literal names.
# Native-method retention alone does not keep them: R8 removes srcPos and renames
# dstPos, causing GetFieldID/NoSuchFieldError followed by an ART SIGABRT at extraction.
-keepclassmembers class com.github.luben.zstd.ZstdInputStreamNoFinalizer {
    long srcPos;
    long dstPos;
}
