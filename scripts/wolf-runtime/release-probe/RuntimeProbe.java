package fr.astragames.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;

/** Framework-only instrumentation, also compatible with the actual non-debuggable R8 APK. */
public final class RuntimeProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        File root = new File(context.getNoBackupFilesDir(), "wolf-runtime/wine9-astra-2");
        File backup = new File(context.getFilesDir(), "release-install-probe-backup");
        String[] markers = {"ready", "audio-ready", ".component-1"};
        Activity activity = null;
        try {
            if (!Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("x86_64"))
                throw new IllegalStateException("Use the dedicated x86_64 emulator with its prepared runtime");
            if (!new File(root, "proton/bin/wine").isFile())
                throw new IllegalStateException("Prepare the dedicated emulator runtime first");
            backup.mkdirs();
            for (String name : markers) {
                File marker = new File(root, name), saved = new File(backup, name);
                if (marker.isFile() && !saved.exists()) Files.copy(marker.toPath(), saved.toPath());
                Files.deleteIfExists(marker.toPath());
            }
            // This x86_64 archive uses exactly the Zstd JNI decoder used by Box64's ARM64 archive.
            Intent launch = new Intent().setClassName(context, "fr.astragames.app.windows.WolfRuntimeActivity")
                .putExtra("id", "release-install-probe")
                .putExtra("source", "file:///astra-nonexistent-probe-game")
                .putExtra("executable", "Game.exe").putExtra("title", "RELEASE_ZSTD_INSTALLATION_PROBE")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(launch);
            boolean finished = false;
            File reports = new File(context.getNoBackupFilesDir(), "wolf-diagnostics");
            for (int attempt = 0; attempt < 180; attempt++) {
                Thread.sleep(1000);
                File[] directories = reports.listFiles(File::isDirectory);
                if (directories == null || directories.length == 0) continue;
                Arrays.sort(directories, Comparator.comparing(File::getName).reversed());
                File metadata = new File(directories[0], "session.json");
                File events = new File(directories[0], "events.log");
                if (!metadata.isFile() || !events.isFile()) continue;
                String log = new String(Files.readAllBytes(events.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (!log.contains("RELEASE_ZSTD_INSTALLATION_PROBE")) continue;
                JSONObject state = new JSONObject(new String(Files.readAllBytes(metadata.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                if (state.optBoolean("finished")) { finished = true; result.putString("session", state.toString()); break; }
            }
            if (!finished || !new File(root, "ready").isFile())
                throw new AssertionError("Zstd installation did not complete before the expected missing-game error");
            result.putString("result", "PASS: production Zstd extraction completed in the installed APK");
            result.putBoolean("debuggable", (context.getApplicationInfo().flags & 2) != 0);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error));
        } finally {
            for (String name : markers) {
                File saved = new File(backup, name);
                if (saved.isFile()) try { Files.copy(saved.toPath(), new File(root, name).toPath(), StandardCopyOption.REPLACE_EXISTING); }
                catch (Exception error) { result.putString("restoreFailure", error.toString()); }
            }
            if (activity != null) { final Activity target = activity; runOnMainSync(target::finish); }
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }
}
