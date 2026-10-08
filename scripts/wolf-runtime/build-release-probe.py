"""Build an SDK-only instrumentation APK for an obfuscated Astra release.

Uses the ordinary Android debug key. Does not install or modify the application.
"""
import os
from pathlib import Path
import subprocess
import zipfile

repo = Path(__file__).resolve().parents[2]
out = repo / "build/wolf-research/release-probe"
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk")))
jdk = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Android/Android Studio/jbr"))
bt = sdk / "build-tools/36.1.0"
android = sdk / "platforms/android-36/android.jar"
classes = out / "classes"
classes.mkdir(parents=True, exist_ok=True)
(out / "dex").mkdir(exist_ok=True)
manifest = out / "AndroidManifest.xml"
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
 package="fr.astragames.releaseprobe"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
 <instrumentation android:name="fr.astragames.releaseprobe.ReleaseRuntimeProbe" android:targetPackage="fr.astragames.app"/>
 <application android:label="Astra release probe"/></manifest>''', encoding="utf8")
env = dict(os.environ, JAVA_HOME=str(jdk))
def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, env=env)
run(jdk / "bin/javac.exe", "-source", "8", "-target", "8", "-classpath", android,
    "-d", classes, repo / "scripts/wolf-runtime/ReleaseRuntimeProbe.java")
run(jdk / "bin/jar.exe", "cf", out / "classes.jar", "-C", classes, ".")
run(bt / "d8.bat", "--min-api", "26", "--lib", android, "--output", out / "dex", out / "classes.jar")
run(bt / "aapt2.exe", "link", "-I", android, "--manifest", manifest, "-o", out / "probe.apk")
with zipfile.ZipFile(out / "probe.apk", "a") as archive:
    archive.write(out / "dex/classes.dex", "classes.dex")
run(bt / "apksigner.bat", "sign", "--ks", Path.home() / ".android/debug.keystore",
    "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android", out / "probe.apk")
print(out / "probe.apk")
