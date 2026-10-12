package com.winlator.renderer;

import java.util.Arrays;
import java.util.Locale;

/** Bounded telemetry. Submission time includes driver waits, not asynchronous GPU execution. */
public final class RenderStatistics {
    private final long[] gaps = new long[600];
    private final long[] submissions = new long[600], locks = new long[600];
    private long epoch = System.nanoTime(), lastContent, frames, updates, submitNanos, maxSubmit;
    private long uploadBytes, fullUploads, partialUploads;
    private long drawableLockNanos, maxDrawableLock;
    private int gapCount;
    private int sampleCount;

    public synchronized void updated() { updates++; }
    public synchronized void frame(boolean content, long now, long submission, long bytes, long full, long partial, long drawableLock) {
        submitNanos += submission; maxSubmit = Math.max(maxSubmit, submission);
        drawableLockNanos += drawableLock; maxDrawableLock = Math.max(maxDrawableLock, drawableLock);
        uploadBytes += bytes; fullUploads += full; partialUploads += partial;
        frames++;
        submissions[sampleCount % submissions.length] = submission;
        locks[sampleCount++ % locks.length] = drawableLock;
        if (content) {
            if (lastContent != 0) gaps[gapCount++ % gaps.length] = now - lastContent;
            lastContent = now;
        }
    }

    public synchronized String snapshotAndReset() {
        long now = System.nanoTime();
        double seconds = Math.max(0.001, (now - epoch) / 1e9);
        double p95 = percentile(gaps, gapCount, 95);
        double p99 = percentile(gaps, gapCount, 99);
        double submit95 = percentile(submissions, sampleCount, 95), submit99 = percentile(submissions, sampleCount, 99);
        double lock99 = percentile(locks, sampleCount, 99);
        String result = String.format(Locale.ROOT,
            "Rendu Android : intervalle=%.1fs, notifications=%d, dessins=%d, soumission moyenne=%.2fms/max=%.2fms, " +
            "verrou images moyenne=%.2fms/max=%.2fms/p99=%.2fms, écart images p95=%.2fms/p99=%.2fms, " +
            "soumission p95=%.2fms/p99=%.2fms, transferts=%.2fMio/s, complets=%d, partiels=%d. " +
            "Soumission CPU/pilote, hors exécution GPU asynchrone ; un écart peut aussi être une image statique.",
            seconds, updates, frames, frames == 0 ? 0 : submitNanos / 1e6 / frames, maxSubmit / 1e6,
            frames == 0 ? 0 : drawableLockNanos / 1e6 / frames, maxDrawableLock / 1e6, lock99, p95, p99, submit95, submit99,
            uploadBytes / seconds / (1024 * 1024), fullUploads, partialUploads);
        epoch = now; frames = updates = submitNanos = maxSubmit = uploadBytes = fullUploads = partialUploads = 0;
        drawableLockNanos = maxDrawableLock = 0;
        gapCount = sampleCount = 0;
        return result;
    }
    private static double percentile(long[] source, int count, int percentile) {
        if (count == 0) return 0;
        long[] sorted = Arrays.copyOf(source, Math.min(count, source.length));
        Arrays.sort(sorted);
        return sorted[Math.max(0, (int)Math.ceil(sorted.length * percentile / 100.0) - 1)] / 1e6;
    }
}
