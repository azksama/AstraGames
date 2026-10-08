package com.winlator.core;

import android.system.Os;
import com.github.luben.zstd.ZstdInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;
import java.io.*;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/** Extracts a verified runtime archive into a fresh application-owned directory. */
public final class RuntimeArchive {
    public static void extract(File archive, File destination, boolean zstd, boolean librariesOnly) throws Exception {
        destination.mkdirs();
        String root = destination.getCanonicalPath() + File.separator;
        List<String[]> links = new ArrayList<>();
        long total = 0;
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive));
             InputStream decoder = zstd ? new ZstdInputStream(file) : new XZInputStream(file);
             TarArchiveInputStream tar = new TarArchiveInputStream(decoder)) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = tar.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                String name = entry.getName();
                while (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty() || name.equals(".")) continue;
                if (librariesOnly && !name.startsWith("usr/lib/") && !name.startsWith("usr/etc/")
                        && !name.startsWith("usr/share/alsa/")) continue;
                File out = new File(destination, name);
                if (name.startsWith("/") || !out.getCanonicalPath().startsWith(root)) throw new IOException("Unsafe runtime entry");
                if (entry.isSymbolicLink() || entry.isLink()) {
                    File target = entry.isLink() ? new File(destination, entry.getLinkName()) : new File(out.getParentFile(), entry.getLinkName());
                    if (!entry.getLinkName().startsWith("/") && target.getCanonicalPath().startsWith(root)) {
                        links.add(new String[]{out.getPath(), target.getCanonicalPath()});
                    }
                } else if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create runtime directory");
                } else if (entry.isFile()) {
                    if ((total += entry.getSize()) > 2_000_000_000L) throw new IOException("Runtime archive too large");
                    out.getParentFile().mkdirs();
                    try (OutputStream sink = new FileOutputStream(out)) {
                        int read;
                        while ((read = tar.read(buffer)) != -1) sink.write(buffer, 0, read);
                    }
                    Os.chmod(out.getPath(), (entry.getMode() & 0111) != 0 ? 0700 : 0600);
                }
            }
        }
        // Links are materialized last, so they can never redirect subsequent writes.
        for (String[] link : links) {
            File out = new File(link[0]);
            // File.exists follows symlinks and returns false for a dangling link.
            // Such links are valid in a filtered runtime and may remain after an
            // interrupted install; creating them again would fail with EEXIST.
            if (Files.exists(out.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            out.getParentFile().mkdirs();
            Os.symlink(link[1], link[0]);
        }
    }
}
