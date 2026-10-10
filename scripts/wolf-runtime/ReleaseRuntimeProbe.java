package fr.astragames.releaseprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import java.io.File;
import java.io.FileOutputStream;

/** Framework-only instrumentation: also works with the fully obfuscated release APK. */
public class ReleaseRuntimeProbe extends Instrumentation {
    private Activity activity;
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { this.arguments = arguments; super.onCreate(arguments); start(); }
    @Override public void onStart() {
        if (arguments != null && "true".equals(arguments.getString("wolfInspectConfiguration"))) { inspectConfiguration(); return; }
        if (arguments != null && "true".equals(arguments.getString("wolfNative"))) { nativeProbe(); return; }
        Bundle result = new Bundle();
        try {
            File sample = arguments != null && arguments.containsKey("wolfSource") ? new File(arguments.getString("wolfSource"))
                : new File(getTargetContext().getFilesDir(), "wolf-probe/game3729");
            String gameId = arguments == null ? "official-wolf-test-game3729-beta5"
                : arguments.getString("wolfGameId", "official-wolf-test-game3729-beta5");
            File ini = new File(sample, "Game.ini");
            byte[] originalIni = ini.isFile() ? java.nio.file.Files.readAllBytes(ini.toPath()) : null;
            if (arguments != null && "true".equals(arguments.getString("wolfRequireRecoveredSave"))) {
                File original = new File(getTargetContext().getExternalFilesDir(null), "forced-original-Game.ini.bin");
                originalIni = original.isFile() ? java.nio.file.Files.readAllBytes(original.toPath()) : null;
            }
            if (!new File(sample, "Game.exe").isFile()) throw new Exception("Missing official fixture");
            File gameRoot = gameDirectory(gameId);
            if (arguments != null && "true".equals(arguments.getString("wolfSeedLegacyCopy"))) seedLegacyCopy(sample, gameRoot);
            boolean automatic = arguments != null && "true".equals(arguments.getString("wolfAutomatic"));
            Intent intent = new Intent().setClassName(getTargetContext(), automatic ? "fr.astragames.app.wolfnative.WolfNativeActivity" : "fr.astragames.app.windows.WolfRuntimeActivity")
                .putExtra("id", gameId).putExtra("source", sample.toURI().toString())
                .putExtra("executable", "Game.exe").putExtra("title", "Wolf release test")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            long launchStarted = android.os.SystemClock.elapsedRealtime();
            long launchWall = System.currentTimeMillis();
            if (automatic) {
                ActivityMonitor monitor = addMonitor("fr.astragames.app.windows.WolfRuntimeActivity", null, false);
                try { startActivitySync(intent); activity = monitor.waitForActivityWithTimeout(20000); }
                finally { removeMonitor(monitor); }
                if (activity == null) throw new Exception("Automatic mode did not launch the Winlator fallback");
            } else activity = startActivitySync(intent);
            runOnMainSync(() -> activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
            Thread.sleep(100);
            screenshot("release-loading.png");
            boolean[] ready = {false};
            for (int i = 0; i < 240 && !ready[0]; i++) {
                Thread.sleep(1000);
                runOnMainSync(() -> { Button button = find(activity.getWindow().getDecorView(), "Valider"); ready[0] = button != null && button.isShown(); });
            }
            if (!ready[0]) throw new Exception("Runtime did not become ready");
            long readyMs = android.os.SystemClock.elapsedRealtime() - launchStarted;
            Thread.sleep(10000);
            runOnMainSync(() -> activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
            waitForIdleSync();
            Thread.sleep(500);
            screenshot("release-title.png");
            runOnMainSync(() -> find(activity.getWindow().getDecorView(), "Valider").performClick());
            Thread.sleep(4000);
            screenshot("release-game.png");
            long renderedMs = android.os.SystemClock.elapsedRealtime() - launchStarted;
            boolean coldPrefix = arguments != null && "true".equals(arguments.getString("wolfColdPrefix"));
            if (!coldPrefix && renderedMs >= 60000) throw new Exception("Prepared official sample exceeded 60 seconds: " + renderedMs);
            boolean migrated = arguments != null && "true".equals(arguments.getString("wolfRequireMigration"));
            if (migrated) verifyMigration(sample, gameRoot);
            if (arguments != null && "true".equals(arguments.getString("wolfKillAfterRendered"))) {
                File sentinel = new File(sample, "SaveData/astra-interruption-probe.sav");
                sentinel.getParentFile().mkdirs();
                java.nio.file.Files.write(sentinel.toPath(), "ASTRA_INTERRUPTION_TEST".getBytes("UTF-8"));
                File evidence = new File(getTargetContext().getExternalFilesDir(null), "forced-interruption.txt");
                java.nio.file.Files.write(evidence.toPath(), ("Rendered; force-killing Android process pid=" + android.os.Process.myPid() + "; gameId=" + gameId).getBytes("UTF-8"));
                File original = new File(getTargetContext().getExternalFilesDir(null), "forced-original-Game.ini.bin");
                if (originalIni != null) java.nio.file.Files.write(original.toPath(), originalIni); else original.delete();
                android.os.Process.killProcess(android.os.Process.myPid());
                throw new Exception("Force-kill did not terminate the process");
            }
            if (arguments != null && "true".equals(arguments.getString("wolfRequireRecoveredSave"))) {
                String sentinel = new String(java.nio.file.Files.readAllBytes(new File(sample, "SaveData/astra-interruption-probe.sav").toPath()), "UTF-8");
                if (!sentinel.equals("ASTRA_INTERRUPTION_TEST")) throw new Exception("Interrupted save fixture was lost");
            }
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            File[] reports = new File(getTargetContext().getNoBackupFilesDir(), "wolf-diagnostics").listFiles(File::isDirectory);
            if (reports == null || reports.length == 0) throw new Exception("No diagnostic report");
            java.util.Arrays.sort(reports, java.util.Comparator.comparing(File::getName).reversed());
            org.json.JSONObject state = null;
            for (int attempt=0; attempt<30; attempt++) {
                Thread.sleep(1000);
                state = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(reports[0], "session.json").toPath()), "UTF-8"));
                if (state.optBoolean("finished")) break;
            }
            if (state == null || !state.optBoolean("finished") || !state.optString("result").equals("Fermeture demandée"))
                throw new Exception("Session did not close cleanly: " + state);
            String events = new String(java.nio.file.Files.readAllBytes(new File(reports[0], "events.log").toPath()), "UTF-8");
            if (!events.contains("Serveur Windows supervisé") || !events.contains("Fin wineserver : code de sortie 0"))
                throw new Exception("Missing supervised server lifecycle evidence");
            if (automatic) {
                boolean observed = false;
                for (File report : reports) {
                    org.json.JSONObject nativeState = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(report, "session.json").toPath()), "UTF-8"));
                    if (nativeState.optLong("started") >= launchWall && nativeState.optBoolean("finished") && nativeState.optString("result").startsWith("Secours Winlator")) observed = true;
                }
                if (!observed) throw new Exception("No completed native incompatibility report before fallback");
                result.putBoolean("automaticFallback", true);
            }
            if (arguments != null && "true".equals(arguments.getString("wolfRequireDirect"))) {
                org.json.JSONObject mode = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(gameRoot, "working-mode.json").toPath()), "UTF-8"));
                if (!mode.optString("mode").equals("DIRECT")) throw new Exception("Original directory was not used: " + mode);
                if (!migrated && new File(gameRoot, "game").exists()) throw new Exception("Unexpected game import");
                result.putString("configuration", verifyRestoredConfiguration(ini, originalIni, gameRoot));
            }
            result.putBoolean("debuggable", (getTargetContext().getApplicationInfo().flags & 2) != 0);
            result.putString("stream", "PASS: Release game rendered, accepted input and closed with supervised wineserver. readyMs=" + readyMs + " renderedMs=" + renderedMs + "\n" + state);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    /** Wolf may save its own settings while running; only Astra's two temporary keys must revert. */
    private String verifyRestoredConfiguration(File ini, byte[] original, File profile) throws Exception {
        if (new File(profile, "source-configuration.json").exists()) throw new Exception("Configuration restoration journal was not completed");
        byte[] restored = ini.isFile() ? java.nio.file.Files.readAllBytes(ini.toPath()) : null;
        if (java.util.Arrays.equals(original, restored)) return "Original bytes preserved";
        String before = original == null ? "" : new String(original, "ISO-8859-1");
        String after = restored == null ? "" : new String(restored, "ISO-8859-1");
        for (String key : new String[] {"SoftModeFlag", "WindowModeFlag"}) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?mi)^[ \\t]*" + key + "[ \\t]*=[ \\t]*([^\\r\\n]*)");
            java.util.ArrayList<String> previous = new java.util.ArrayList<>(), current = new java.util.ArrayList<>();
            java.util.regex.Matcher matcher = pattern.matcher(before);
            while (matcher.find()) previous.add(matcher.group(1).trim());
            matcher = pattern.matcher(after);
            while (matcher.find()) current.add(matcher.group(1).trim());
            if (!previous.equals(current)) throw new Exception("Temporary configuration not restored: " + key + " " + previous + " -> " + current);
        }
        return "Temporary keys restored; settings written by the game preserved";
    }
    private void inspectConfiguration() {
        Bundle result = new Bundle();
        try {
            File source = new File(arguments.getString("wolfSource"));
            File profile = gameDirectory(arguments.getString("wolfGameId"));
            File export = getTargetContext().getExternalFilesDir(null);
            for (File input : new File[] {new File(source, "Game.ini"), new File(profile, "source-configuration.json")}) {
                if (input.isFile()) {
                    File output = new File(export, "inspect-" + input.getName());
                    try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(java.nio.file.Files.readAllBytes(input.toPath())); }
                    output.setReadable(true, false);
                }
            }
            result.putString("stream", "Configuration journal present=" + new File(profile, "source-configuration.json").exists());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    /** Exercises the real R8 APK using SDK framework APIs, without app classes or reflection. */
    private void nativeProbe() {
        Bundle result = new Bundle();
        String gameId = "native-release-beta14";
        android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences("wolf_game_options", 0);
        String preferenceKey = null, previous = null;
        try {
            if ((getTargetContext().getApplicationInfo().flags & 2) != 0) throw new Exception("Expected non-debuggable release APK");
            File source = new File(getTargetContext().getFilesDir(), "wolf-probe/native-authored-beta14");
            File marker = new File(source, ".astra-test-fixture");
            if (!marker.isFile() || !new String(java.nio.file.Files.readAllBytes(marker.toPath()), "UTF-8").equals("AUTHORED_BETA14"))
                throw new Exception("Missing independently authored native fixture");
            byte[] original = java.nio.file.Files.readAllBytes(new File(source, "Save/original.sav").toPath());
            preferenceKey = hex(java.security.MessageDigest.getInstance("SHA-256").digest(gameId.getBytes("UTF-8")));
            previous = prefs.getString(preferenceKey, null);
            if (!prefs.edit().putString(preferenceKey, new org.json.JSONObject().put("runtime", "NATIVE").put("showFps", false)
                .put("smooth", false).put("pinchZoom", true).toString()).commit()) throw new Exception("Could not select native runtime");
            long started = android.os.SystemClock.elapsedRealtime();
            activity = startActivitySync(new Intent().setClassName(getTargetContext(), "fr.astragames.app.wolfnative.WolfNativeActivity")
                .putExtra("id", gameId).putExtra("source", source.toURI().toString()).putExtra("executable", "Game.exe")
                .putExtra("title", "Native authored release fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
            long deadline = started + 15000;
            boolean[] ready = {false};
            while (android.os.SystemClock.elapsedRealtime() < deadline && !ready[0]) {
                runOnMainSync(() -> { Button button = find(activity.getWindow().getDecorView(), "Valider"); ready[0] = button != null && button.isShown(); });
                Thread.sleep(50);
            }
            if (!ready[0]) throw new Exception("Native release did not show its first image");
            long readyMs = android.os.SystemClock.elapsedRealtime() - started;
            Thread.sleep(400); nativeImage("release-native-title.png", 0xff6020a0);
            runOnMainSync(() -> find(activity.getWindow().getDecorView(), "Valider").performClick());
            Thread.sleep(300);
            runOnMainSync(() -> find(activity.getWindow().getDecorView(), "Valider").performClick());
            Thread.sleep(300); nativeImage("release-native-game.png", 0xff208040);
            File slot = new File(getTargetContext().getFilesDir(), "wolf-native/" + preferenceKey + "/saves/slot-0.astrawolf");
            long previousWrite = slot.lastModified();
            nativeMenu(); clickText("Sauvegarder · emplacement 0");
            deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while ((!slot.isFile() || slot.lastModified() <= previousWrite) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            if (!slot.isFile() || slot.length() == 0 || slot.lastModified() <= previousWrite) throw new Exception("Native release did not write a new save");
            clickText("Fermer"); nativeMenu(); clickText("Charger une sauvegarde native"); clickText("Emplacement 0");
            Thread.sleep(300); nativeImage("release-native-loaded.png", 0xff208040);
            nativeMenu(); clickText("Réglages du jeu"); screenshot("release-native-options.png"); clickText("Annuler");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            if (!java.util.Arrays.equals(original, java.nio.file.Files.readAllBytes(new File(source, "Save/original.sav").toPath())))
                throw new Exception("Original Windows save changed");
            File[] reports = new File(getTargetContext().getNoBackupFilesDir(), "wolf-diagnostics").listFiles(File::isDirectory);
            if (reports == null) throw new Exception("Missing native diagnostics");
            java.util.Arrays.sort(reports, java.util.Comparator.comparing(File::getName).reversed());
            File report = null;
            for (File candidate : reports) {
                String events = new String(java.nio.file.Files.readAllBytes(new File(candidate, "events.log").toPath()), "UTF-8");
                if (events.contains("Native authored release fixture") && events.contains("wolf-native-kotlin-1")) { report = candidate; break; }
            }
            if (report == null) throw new Exception("No report from the real native release");
            org.json.JSONObject state = null;
            deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                state = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(report, "session.json").toPath()), "UTF-8"));
                if (state.optBoolean("finished")) break;
                Thread.sleep(50);
            }
            if (state == null || !state.optBoolean("finished") || !state.optString("result").contains("fermée")) throw new Exception("Native release did not close cleanly: " + state);
            result.putBoolean("debuggable", false);
            result.putString("stream", "PASS: R8 native release displayed title and map, accepted controls, saved, loaded and closed. readyMs=" + readyMs
                + "; original Windows save preserved; authored fixture only.\n" + state);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null && !activity.isDestroyed()) runOnMainSync(() -> activity.finish());
            if (preferenceKey != null) { android.content.SharedPreferences.Editor edit = prefs.edit(); if (previous == null) edit.remove(preferenceKey); else edit.putString(preferenceKey, previous); edit.commit(); }
        }
    }
    private void nativeMenu() throws Exception {
        runOnMainSync(() -> find(activity.getWindow().getDecorView(), "Menu").performClick());
        clickText("Réglages du jeu");
    }
    private void clickText(String text) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                if (!text.equalsIgnoreCase(node.getText() == null ? "" : node.getText().toString())) continue;
                android.view.accessibility.AccessibilityNodeInfo target = node;
                while (target != null && !target.isClickable()) target = target.getParent();
                if (target != null && target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(200); return; }
            }
            Thread.sleep(50);
        }
        throw new Exception("Native menu action unavailable: " + text);
    }
    private void nativeImage(String name, int expected) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new Exception("Native screenshot unavailable");
        int matching = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 8) for (int x = 0; x < bitmap.getWidth(); x += 8)
            if (bitmap.getPixel(x, y) == expected) matching++;
        bitmap.recycle();
        if (matching < 50) throw new Exception("Authored native content was not rendered: " + name + " matches=" + matching);
        screenshot(name);
    }
    private File gameDirectory(String gameId) throws Exception {
        byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(gameId.getBytes("UTF-8"));
        return new File(getTargetContext().getFilesDir(), "wolf-games/" + hex(bytes));
    }
    /** Only used on an owned official sample: simulate a completed old import with pending progress. */
    private void seedLegacyCopy(File sample, File gameRoot) throws Exception {
        File copy = new File(gameRoot, "game");
        if (copy.exists()) throw new Exception("Refusing to overwrite an existing private game fixture");
        File sourceSave = new File(sample, "SaveData/astra-migration-probe.sav");
        if (sourceSave.exists()) throw new Exception("Refusing to overwrite an existing source save fixture");
        sourceSave.getParentFile().mkdirs();
        java.nio.file.Files.write(sourceSave.toPath(), "ASTRA_MIGRATION_ORIGINAL".getBytes("UTF-8"));
        org.json.JSONObject hashes = new org.json.JSONObject(), stamps = new org.json.JSONObject();
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(sample.toPath())) {
            java.util.Iterator<java.nio.file.Path> files = paths.filter(path -> java.nio.file.Files.isRegularFile(path)
                && !java.nio.file.Files.isSymbolicLink(path)).iterator();
            while (files.hasNext()) {
                java.nio.file.Path path = files.next();
                String relative = sample.toPath().relativize(path).toString().replace('\\', '/');
                if (relative.contains(".astra-")) continue;
                File target = new File(copy, relative);
                target.getParentFile().mkdirs();
                java.nio.file.Files.copy(path, target.toPath());
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path));
                hashes.put(relative, hex(digest));
                stamps.put(relative, target.length() + ":" + target.lastModified());
            }
        }
        java.nio.file.Files.write(new File(copy, "SaveData/astra-migration-probe.sav").toPath(), "ASTRA_MIGRATION_PENDING".getBytes("UTF-8"));
        java.nio.file.Files.write(new File(gameRoot, "source-hashes.json").toPath(), hashes.toString().getBytes("UTF-8"));
        java.nio.file.Files.write(new File(gameRoot, "import-state.json").toPath(), new org.json.JSONObject()
            .put("source", sample.toURI().toString()).put("complete", true).put("stamps", stamps).toString().getBytes("UTF-8"));
        java.nio.file.Files.write(new File(gameRoot, "working-mode.json").toPath(), new org.json.JSONObject()
            .put("source", sample.toURI().toString()).put("mode", "COPY").toString().getBytes("UTF-8"));
    }
    private void verifyMigration(File sample, File gameRoot) throws Exception {
        org.json.JSONObject mode = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(gameRoot, "working-mode.json").toPath()), "UTF-8"));
        if (!mode.optString("mode").equals("DIRECT")) throw new Exception("Legacy game did not migrate to its original directory: " + mode);
        for (File root : new File[] {sample, new File(gameRoot, "game")}) {
            String saved = new String(java.nio.file.Files.readAllBytes(new File(root, "SaveData/astra-migration-probe.sav").toPath()), "UTF-8");
            if (!saved.equals("ASTRA_MIGRATION_PENDING")) throw new Exception("Pending migration fixture was lost: " + root);
        }
        File[] backups = new File(sample, "SaveData").listFiles(file -> file.getName().startsWith(".astra-wolf-backup-"));
        boolean preserved = false;
        if (backups != null) for (File backup : backups) {
            if (new String(java.nio.file.Files.readAllBytes(backup.toPath()), "UTF-8").equals("ASTRA_MIGRATION_ORIGINAL")) preserved = true;
        }
        if (!preserved) throw new Exception("Original source version was not backed up during migration");
    }
    private static String hex(byte[] bytes) {
        StringBuilder hash = new StringBuilder();
        for (byte b : bytes) hash.append(String.format("%02x", b));
        return hash.toString();
    }
    private static Button find(View view, String label) {
        if (view instanceof Button && ((Button)view).getText().toString().equals(label)) return (Button)view;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            Button found = find(((ViewGroup)view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new Exception("Screenshot unavailable");
        if (name.equals("release-game.png")) {
            java.util.HashSet<Integer> colors = new java.util.HashSet<>();
            for (int y = bitmap.getHeight()/4; y < bitmap.getHeight()*3/4; y += 4)
                for (int x = bitmap.getWidth()/5; x < bitmap.getWidth()*4/5; x += 4) colors.add(bitmap.getPixel(x, y));
            if (colors.size() < 128) throw new Exception("Official sample rendered a blank/incomplete game frame: " + colors.size() + " colors");
        }
        try (FileOutputStream output = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
