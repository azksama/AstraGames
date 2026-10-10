plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Official research samples remain local and are never packaged in the app.
    systemProperty("wolf.fixtureRoot", rootProject.layout.projectDirectory.dir("build/wolf-research").asFile.absolutePath)
}
