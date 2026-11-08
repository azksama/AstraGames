package fr.astragames.upgradeprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Platform-only instrumentation: no dependency on the app's R8 names or Kotlin runtime. */
public final class UpgradeProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            verify();
            result.putString("stream", "\nOK: catalogue, settings and backup hashes verified\n");
            finish(-1, result);
        } catch (Throwable error) {
            StringWriter output = new StringWriter();
            error.printStackTrace(new PrintWriter(output));
            result.putString("stream", "\nFAILED: " + output);
            finish(0, result);
        }
    }

    private void verify() throws Exception {
        String mode = arguments.getString("mode", "");
        require(mode.equals("before") || mode.equals("after") || mode.equals("after-launch"), "Specify before, after or after-launch");
        Context context = getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        JSONObject tables = new JSONObject();
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath("astra_games.db").getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            require(db.getVersion() == 9, "Unexpected database schema");
            List<String> names = new ArrayList<>();
            try (Cursor rows = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
                while (rows.moveToNext()) names.add(rows.getString(0));
            }
            for (String name : names) {
                String quoted = "\"" + name.replace("\"", "\"\"") + "\"";
                int count;
                try (Cursor columns = db.rawQuery("SELECT * FROM " + quoted + " LIMIT 0", null)) { count = columns.getColumnCount(); }
                List<String> positions = new ArrayList<>();
                for (int i = 1; i <= count; i++) positions.add(Integer.toString(i));
                try (Cursor rows = db.rawQuery("SELECT * FROM " + quoted + " ORDER BY " + String.join(",", positions), null)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    while (rows.moveToNext()) for (int i = 0; i < count; i++) {
                        int type = rows.getType(i);
                        byte[] bytes = type == Cursor.FIELD_TYPE_NULL ? new byte[0] : type == Cursor.FIELD_TYPE_BLOB ? rows.getBlob(i) : rows.getString(i).getBytes(StandardCharsets.UTF_8);
                        digest.update((byte) type);
                        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
                    }
                    tables.put(name, new JSONObject().put("rows", rows.getCount()).put("sha256", hex(digest.digest())));
                }
            }
        }
        require(tables.getJSONObject("games").getInt("rows") > 0, "A populated catalogue is required");
        File settings = new File(context.getFilesDir(), "datastore/astra_settings.preferences_pb");
        require(settings.isFile(), "Missing settings");
        List<File> retained = new ArrayList<>(); retained.add(settings);
        collect(new File(context.getFilesDir(), "save-backups"), retained);
        Collections.sort(retained);
        JSONObject files = new JSONObject();
        for (File file : retained) {
            String path = context.getFilesDir().toPath().relativize(file.toPath()).toString();
            files.put(path, hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))));
        }
        JSONObject snapshot = new JSONObject().put("version", info.versionName).put("firstInstallTime", info.firstInstallTime)
            .put("tables", tables).put("files", files);
        File proof = new File(context.getExternalFilesDir(null), "release-upgrade");
        require(proof.isDirectory() || proof.mkdirs(), "Cannot create evidence directory");
        Files.write(new File(proof, mode + ".json").toPath(), snapshot.toString(2).getBytes(StandardCharsets.UTF_8));
        if (mode.equals("before")) require(info.versionName.equals("1.12.0-beta.24"), "Expected the published beta.24 baseline");
        else {
            require(info.versionName.equals("1.12.0-beta.25"), "Expected beta.25");
            JSONObject before = new JSONObject(new String(Files.readAllBytes(new File(proof, "before.json").toPath()), StandardCharsets.UTF_8));
            require(before.getLong("firstInstallTime") == info.firstInstallTime, "Installation identity changed");
            require(before.getJSONObject("tables").toString().equals(tables.toString()), "Catalogue table contents changed");
            require(before.getJSONObject("files").toString().equals(files.toString()), "Settings or backup contents changed");
        }
    }

    private static void collect(File directory, List<File> files) throws Exception {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File file : children) {
            // Never follow a link outside the installation's backup tree.
            if (Files.isSymbolicLink(file.toPath())) continue;
            if (file.isDirectory()) collect(file, files); else if (file.isFile()) files.add(file);
        }
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
