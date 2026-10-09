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
            Intent intent = new Intent().setClassName(getTargetContext(), "fr.astragames.app.windows.WolfRuntimeActivity")
                .putExtra("id", gameId).putExtra("source", sample.toURI().toString())
                .putExtra("executable", "Game.exe").putExtra("title", "Wolf release test")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            long launchStarted = android.os.SystemClock.elapsedRealtime();
            activity = startActivitySync(intent);
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
            if (arguments != null && "true".equals(arguments.getString("wolfRequireDirect"))) {
                org.json.JSONObject mode = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(gameRoot, "working-mode.json").toPath()), "UTF-8"));
                if (!mode.optString("mode").equals("DIRECT")) throw new Exception("Original directory was not used: " + mode);
                if (!migrated && new File(gameRoot, "game").exists()) throw new Exception("Unexpected game import");
                if (originalIni == null ? ini.exists() : !java.util.Arrays.equals(originalIni, java.nio.file.Files.readAllBytes(ini.toPath())))
                    throw new Exception("Original configuration was not restored");
            }
            result.putBoolean("debuggable", (getTargetContext().getApplicationInfo().flags & 2) != 0);
            result.putString("stream", "PASS: Release game rendered, accepted input and closed with supervised wineserver. readyMs=" + readyMs + " renderedMs=" + renderedMs + "\n" + state);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
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
