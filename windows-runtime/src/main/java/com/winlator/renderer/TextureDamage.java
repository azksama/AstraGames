package com.winlator.renderer;

/** Bounded damage regions: distant animated sprites must not upload the empty space between them. */
public final class TextureDamage {
    public static final int MAX_REGIONS = 32;
    private final int[] rectangles = new int[(MAX_REGIONS + 1) * 4];
    private int count;

    public int size() { return count; }
    public int get(int index, int component) { return rectangles[index * 4 + component]; }
    public void clear() { count = 0; }

    public void add(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        int left = x, top = y, right = x + width, bottom = y + height;
        // Merge overlapping/touching rectangles only when the extra transfer is negligible.
        for (int i = 0; i < count;) {
            int p = i * 4, l = rectangles[p], t = rectangles[p + 1], r = rectangles[p + 2], b = rectangles[p + 3];
            long combined = area(Math.min(left, l), Math.min(top, t), Math.max(right, r), Math.max(bottom, b));
            if (combined <= area(left, top, right, bottom) + area(l, t, r, b) + 128) {
                left = Math.min(left, l); top = Math.min(top, t); right = Math.max(right, r); bottom = Math.max(bottom, b);
                remove(i); i = 0;
            } else i++;
        }
        int p = count++ * 4;
        rectangles[p] = left; rectangles[p + 1] = top; rectangles[p + 2] = right; rectangles[p + 3] = bottom;
        if (count > MAX_REGIONS) {
            // Bound GL calls and snapshot buffers without ever discarding a changed pixel.
            int first = 0, second = 1; long leastWaste = Long.MAX_VALUE;
            for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++) {
                int a = i * 4, b = j * 4;
                long waste = area(Math.min(rectangles[a], rectangles[b]), Math.min(rectangles[a + 1], rectangles[b + 1]),
                    Math.max(rectangles[a + 2], rectangles[b + 2]), Math.max(rectangles[a + 3], rectangles[b + 3])) -
                    area(rectangles[a], rectangles[a + 1], rectangles[a + 2], rectangles[a + 3]) -
                    area(rectangles[b], rectangles[b + 1], rectangles[b + 2], rectangles[b + 3]);
                if (waste < leastWaste) { leastWaste = waste; first = i; second = j; }
            }
            int a = first * 4, b = second * 4;
            rectangles[a] = Math.min(rectangles[a], rectangles[b]); rectangles[a + 1] = Math.min(rectangles[a + 1], rectangles[b + 1]);
            rectangles[a + 2] = Math.max(rectangles[a + 2], rectangles[b + 2]); rectangles[a + 3] = Math.max(rectangles[a + 3], rectangles[b + 3]);
            remove(second);
        }
    }

    private static long area(int l, int t, int r, int b) { return (long)(r - l) * (b - t); }
    private void remove(int index) {
        count--;
        if (index < count) System.arraycopy(rectangles, (index + 1) * 4, rectangles, index * 4, (count - index) * 4);
    }
}
