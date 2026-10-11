package fr.astragames.releaseprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

/** SDK-only probe: no dependencies on app classes that R8 can rename or remove. */
public final class NativeReleaseProbe extends Instrumentation {
    private Bundle arguments;
    private final JSONObject evidence = new JSONObject();
    private Context target;
    private Activity activity;

    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            target = getTargetContext();
            check((target.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0, "APK is debuggable");
            evidence.put("version", target.getPackageManager().getPackageInfo(target.getPackageName(), 0).versionName);
            evidence.put("abi", android.os.Build.SUPPORTED_ABIS[0]);
            testJni();
            String root = arguments.getString("nativeOfficialRoot");
            String nameOnlyRoot = arguments.getString("nativeNameOnlyRoot");
            check(root != null || nameOnlyRoot != null, "A native fixture root is required; no skipped corpus tests");
            JSONArray journeys = new JSONArray(); evidence.put("journeys", journeys);
            if (root != null) {
                journeys.put(journey(new File(root, "22961"), "22961"));
                journeys.put(journey(new File(root, "3729"), "3729"));
            }
            if (nameOnlyRoot != null) {
                File fixture = new File(nameOnlyRoot);
                check(new String(bytes(new FileInputStream(new File(fixture, ".astra-test-fixture"))), "UTF-8").equals("AUTHORED_DB701"),
                    "The name-only registry probe requires the authored 701-row fixture");
                File project = new File(fixture, "Data/BasicData/SysDatabase.project");
                File data = new File(fixture, "Data/BasicData/SysDatabase.dat");
                String projectBefore = digest(bytes(new FileInputStream(project)));
                String dataBefore = digest(bytes(new FileInputStream(data)));
                JSONObject regression = journey(fixture, "authored-db701", 2);
                check(projectBefore.equals(digest(bytes(new FileInputStream(project)))) &&
                    dataBefore.equals(digest(bytes(new FileInputStream(data)))), "Authored database changed");
                regression.put("nameOnlyRows", 701).put("systemProjectSha256", projectBefore).put("systemDataSha256", dataBefore);
                journeys.put(regression);
            }
            evidence.put("result", "PASS");
            result.putString("stream", "Native R8 release: JNI frames/PCM and " + journeys.length() + " requested game journey(s) PASS\n");
        } catch (Throwable failure) {
            try { evidence.put("result", "FAIL").put("error", failure.toString()); } catch (Exception ignored) { }
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            result.putString("stream", trace.toString());
        } finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
            try { write(new File(target.getExternalFilesDir(null), "native-release-probe.json"), evidence.toString(2).getBytes("UTF-8")); }
            catch (Exception failure) { result.putString("writeError", failure.toString()); }
            finish("PASS".equals(evidence.optString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }

    private void testJni() throws Exception {
        // This class is deliberately kept for the native JNI ABI. No VM or renderer reflection.
        Class<?> type = Class.forName("fr.astragames.app.wolfnative.WolfNativeTheora", true, target.getClassLoader());
        Object instance = type.getField("INSTANCE").get(null);
        type.getMethod("ensureAvailable").invoke(instance);
        byte[] clip = bytes(getContext().getAssets().open("reference.ogv"));
        check(digest(clip).equals("fc0e1d432f456a77460a8dc0a3dd2179d9c806dfd58ebd20194c50bb5ea2393e"), "Unexpected OGV fixture");
        long handle = (Long) type.getMethod("open", byte[].class).invoke(instance, (Object) clip);
        try {
            long[] info = (long[]) type.getMethod("info", long.class).invoke(instance, handle);
            check(info[0] == 64 && info[1] == 48 && info[2] >= 2900 && info[2] <= 3100, "Wrong video dimensions/duration");
            check(info[3] == 48000 && info[4] == 1, "Wrong Vorbis format");
            int[] pixels = new int[64 * 48];
            Method frame = type.getMethod("frameAt", long.class, long.class, int[].class, long.class);
            long first = (Long) frame.invoke(instance, handle, 0L, pixels, Long.MIN_VALUE);
            int red = pixels[24 * 64 + 32];
            check(((red >> 16) & 255) > 220 && ((red >> 8) & 255) < 30, "First frame is not red");
            long second = (Long) frame.invoke(instance, handle, 1500L, pixels, first);
            int green = pixels[24 * 64 + 32];
            check(second != first && ((green >> 8) & 255) > 90 && ((green >> 16) & 255) < 30, "Seek did not decode green");
            byte[] pcm = new byte[16384];
            int length = (Integer) type.getMethod("readAudio", long.class, byte[].class).invoke(instance, handle, pcm);
            check(length > 0 && length <= pcm.length && length % 2 == 0, "No decoded PCM");
            int peak = 0;
            for (int i = 0; i < length; i += 2) peak = Math.max(peak, Math.abs((short)((pcm[i] & 255) | (pcm[i + 1] << 8))));
            check(peak > 100, "Decoded PCM is silent");
            evidence.put("jni", new JSONObject().put("width", info[0]).put("height", info[1])
                .put("durationMs", info[2]).put("pcmBytes", length).put("pcmPeak", peak).put("result", "PASS"));
        } finally { type.getMethod("close", long.class).invoke(instance, handle); }
    }

    private JSONObject journey(File root, String version) throws Exception {
        return journey(root, version, 28);
    }

    private JSONObject journey(File root, String version, int acknowledgements) throws Exception {
        File original = new File(root, "Data/BasicData/Game.dat");
        check(original.isFile(), "Missing corpus " + version);
        String before = digest(bytes(new FileInputStream(original)));
        String id = "r8-native-" + version + "-" + UUID.randomUUID();
        String hash = digest(id.getBytes("UTF-8"));
        File owned = new File(target.getFilesDir(), "wolf-native/" + hash);
        File saved = new File(owned, "saves/slot-0.astrawolf");
        String label = "R8 native " + version;
        target.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE).edit()
            .putString(hash, "{\"runtime\":\"NATIVE\",\"showFps\":false}").commit();
        JSONObject record = new JSONObject().put("corpus", version).put("metadataSha256", before);
        long started = SystemClock.elapsedRealtime();
        try {
            Intent intent = new Intent().setClassName(target, "fr.astragames.app.wolfnative.WolfNativeActivity")
                .putExtra("id", id).putExtra("source", Uri.fromFile(root).toString())
                .putExtra("executable", "Game.exe").putExtra("title", label).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // A running native game keeps publishing frames. Observe its launch
            // directly rather than waiting for global UI idleness.
            ActivityMonitor monitor = addMonitor("fr.astragames.app.wolfnative.WolfNativeActivity", null, false);
            try {
                target.startActivity(intent);
                activity = waitForMonitorWithTimeout(monitor, 10000);
                check(activity != null, "Native release activity did not start");
            } finally { removeMonitor(monitor); }
            await("first native image", 30000, () -> events(label).contains("Première image native affichée"));
            record.put("firstImageMs", SystemClock.elapsedRealtime() - started);
            SystemClock.sleep(2000);
            save(saved); Snapshot title = snapshot(saved);
            screenshot("release-" + version + "-title");
            button("Valider", false);
            // Real game acknowledgements, through the application's controls.
            for (int i = 0; i < acknowledgements; i++) { SystemClock.sleep(650); button("Valider", false); checkSession(label); }
            save(saved); Snapshot game = snapshot(saved);
            check(game.map != title.map, "New game did not leave the title map");
            screenshot("release-" + version + "-map");
            button("Droite", true); SystemClock.sleep(700); save(saved); Snapshot moved = snapshot(saved);
            check(moved.x != game.x || moved.y != game.y, "Original hero did not move");
            byte[] checkpoint = bytes(new FileInputStream(saved));
            // Retrace the path just crossed and observe the different live position through a real save.
            button("Gauche", true); SystemClock.sleep(700); save(saved); Snapshot displaced = snapshot(saved);
            check(displaced.x != moved.x || displaced.y != moved.y, "Hero did not leave the saved position");
            // Restore only the app-created private checkpoint, byte for byte, as a save backup.
            // No VM state, original game file, event or variable is injected.
            write(saved, checkpoint);
            menu(); dialog("Charger une sauvegarde native"); dialog("Emplacement 0");
            SystemClock.sleep(300);
            save(saved); Snapshot restored = snapshot(saved);
            check(restored.map == moved.map && restored.x == moved.x && restored.y == moved.y, "Private save reload did not restore the saved position");
            check(events(label).contains("aucun transfert général"), "No direct-source diagnostic");
            check(!new File(target.getFilesDir(), "wolf-games/" + hash + "/game").exists(), "Unexpected imported game folder");
            record.put("result", "PASS").put("titleMap", title.map).put("gameMap", game.map)
                .put("saveVersion", moved.version).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("movedX", moved.x).put("movedY", moved.y)
                .put("displacedX", displaced.x).put("displacedY", displaced.y)
                .put("saveReloadRestoredPosition", true).put("events", events(label));
        } finally {
            if (activity != null) { runOnMainSync(() -> activity.finish()); activity = null; SystemClock.sleep(200); }
            target.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE).edit().remove(hash).commit();
            target.getSharedPreferences("wolf_native_launch", Context.MODE_PRIVATE).edit().remove(hash).commit();
            if (owned.exists()) { check(owned.getCanonicalFile().getParentFile().equals(new File(target.getFilesDir(), "wolf-native").getCanonicalFile()), "Cleanup outside owned game"); remove(owned); }
            check(before.equals(digest(bytes(new FileInputStream(original)))), "Original game metadata changed");
        }
        return record;
    }

    private void save(File file) throws Exception {
        long previous = file.isFile() ? file.lastModified() : -1;
        menu(); dialog("Sauvegarder · emplacement 0");
        await("private save", 5000, () -> file.isFile() && file.lastModified() != previous);
        dialog("Fermer"); SystemClock.sleep(100);
    }
    private void menu() throws Exception { button("Menu", false); dialog("Réglages du jeu"); }
    private static final class Snapshot { int version, map; float x, y; }
    /** Independently validates the envelope and reads only the stable public AWNS prefix. */
    private Snapshot snapshot(File file) throws Exception {
        check(file.length() <= 16 * 1024 * 1024 + 4096, "Oversized saved state");
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            byte[] magic = new byte[20]; input.readFully(magic);
            check(new String(magic, "UTF-8").equals("ASTRA-WOLF-NATIVE-1\n"), "Unexpected private save envelope");
            input.readUTF(); String checksum = input.readUTF(); int count = input.readInt();
            check(count > 8 && count <= 16 * 1024 * 1024, "Bad state size");
            byte[] payload = new byte[count]; input.readFully(payload);
            check(input.read() == -1 && digest(payload).equals(checksum), "Private save digest mismatch");
            ByteBuffer buffer = ByteBuffer.wrap(payload); int size = buffer.getInt();
            check(size == payload.length - 8, "AWNS size mismatch");
            CRC32 crc = new CRC32(); crc.update(payload, 4, size);
            check((int)crc.getValue() == buffer.getInt(payload.length - 4), "AWNS CRC mismatch");
            check(buffer.getInt() == 0x41574e53, "Wrong native snapshot magic");
            Snapshot state = new Snapshot(); state.version = buffer.getInt();
            int titleLength = buffer.getInt(); check(titleLength >= 0 && titleLength <= size - 32, "Bad title length");
            buffer.position(buffer.position() + titleLength); buffer.getLong();
            state.map = buffer.getInt(); state.x = buffer.getFloat(); state.y = buffer.getFloat();
            return state;
        }
    }
    private void button(String label, boolean description) {
        final boolean[] done = {false}; runOnMainSync(() -> {
            for (View view : descendants(activity.getWindow().getDecorView())) if (view instanceof Button && view.isShown()) {
                CharSequence text = description ? view.getContentDescription() : ((Button)view).getText();
                if (label.contentEquals(text == null ? "" : text)) { done[0] = view.performClick(); break; }
            }
        }); check(done[0], "Missing native control " + label);
    }
    private void dialog(String text) throws Exception {
        await("dialog " + text, 5000, () -> {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                if (!text.equalsIgnoreCase(node.getText() == null ? "" : node.getText().toString())) continue;
                for (AccessibilityNodeInfo click = node; click != null; click = click.getParent())
                    if (click.isClickable()) return click.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            return false;
        }); SystemClock.sleep(100);
    }
    private interface Condition { boolean get() throws Exception; }
    private void await(String label, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) { if (condition.get()) return; SystemClock.sleep(80); }
        throw new AssertionError("Timeout: " + label);
    }
    private void checkSession(String label) throws Exception {
        String value = events(label);
        check(!value.contains("Moteur natif arrêté") && !value.contains("Secours Winlator"), "Native session failed:\n" + value);
    }
    private String events(String label) throws Exception {
        File[] directories = new File(target.getNoBackupFilesDir(), "wolf-diagnostics").listFiles();
        if (directories == null) return "";
        Arrays.sort(directories, Comparator.comparing(File::getName).reversed());
        for (File directory : directories) {
            File log = new File(directory, "events.log");
            if (!log.isFile()) continue;
            check(log.length() <= 2 * 1024 * 1024, "Diagnostic exceeds bounded probe size");
            String text = new String(bytes(new FileInputStream(log)), "UTF-8");
            if (text.contains(label)) return text;
        }
        return "";
    }
    private void screenshot(String name) throws Exception {
        runOnMainSync(() -> activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE));
        SystemClock.sleep(150); Bitmap image = getUiAutomation().takeScreenshot();
        check(image != null, "No SDK screenshot");
        try (OutputStream output = new FileOutputStream(new File(target.getExternalFilesDir(null), name + ".png"))) {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output), "Screenshot encoding failed");
        } finally { image.recycle(); }
    }
    private static List<View> descendants(View view) {
        List<View> result = new ArrayList<>(); result.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) result.addAll(descendants(((ViewGroup)view).getChildAt(i)));
        return result;
    }
    private static byte[] bytes(InputStream stream) throws IOException {
        try (InputStream source = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = source.read(buffer)) >= 0) { if (count > 0) out.write(buffer, 0, count); check(out.size() <= 16 * 1024 * 1024, "Probe input too large"); }
            return out.toByteArray();
        }
    }
    private static String digest(byte[] data) throws Exception {
        StringBuilder text = new StringBuilder(); for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) text.append(String.format(Locale.ROOT, "%02x", value & 255)); return text.toString();
    }
    private static void write(File file, byte[] bytes) throws IOException { try (OutputStream output = new FileOutputStream(file)) { output.write(bytes); } }
    private static void remove(File file) throws IOException {
        if (file.isDirectory()) for (File child : Objects.requireNonNull(file.listFiles())) {
            check(child.getCanonicalFile().toPath().startsWith(file.getCanonicalFile().toPath()), "Owned probe directory contains an escaping symlink"); remove(child);
        }
        check(file.delete(), "Failed to remove owned probe file");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
