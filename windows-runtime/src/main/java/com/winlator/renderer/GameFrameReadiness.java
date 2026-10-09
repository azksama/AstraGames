package com.winlator.renderer;

import java.nio.ByteBuffer;
import java.util.Locale;

/** Ignore Windows setup/console windows and empty client buffers during game startup. */
public final class GameFrameReadiness {
    public static boolean isGameWindow(String className, String expectedExecutable) {
        String value = className.toLowerCase(Locale.ROOT);
        for (String excluded : new String[]{"explorer.exe", "wineboot", "wineconsole", "conhost", "cmd.exe", "winedbg"})
            if (value.contains(excluded)) return false;
        return !value.contains(".exe") || value.contains(expectedExecutable.toLowerCase(Locale.ROOT));
    }

    public static boolean hasVisiblePixels(ByteBuffer pixels, int width, int height, int stride) {
        if (pixels == null || width <= 0 || height <= 0 || stride < width) return false;
        int visible = 0;
        int rows = Math.min(height, 24), columns = Math.min(width, 32);
        for (int row = 0; row < rows; row++) for (int col = 0; col < columns; col++) {
            int x = (2 * col + 1) * width / (2 * columns), y = (2 * row + 1) * height / (2 * rows);
            long offset = ((long)y * stride + x) * 4;
            if (offset + 2 >= pixels.limit()) continue;
            int i = (int)offset;
            if (((pixels.get(i) & 255) > 20 || (pixels.get(i + 1) & 255) > 20 || (pixels.get(i + 2) & 255) > 20) && ++visible >= 8)
                return true;
        }
        return false;
    }
}
