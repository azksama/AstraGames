plugins { id("com.android.library") }

android {
    namespace = "com.winlator"
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}

dependencies {
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    implementation("org.tukaani:xz:1.10")
    implementation("org.apache.commons:commons-compress:1.28.0")
}
