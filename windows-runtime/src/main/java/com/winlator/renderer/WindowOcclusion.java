package com.winlator.renderer;

/** Conservative occlusion: only one fully covering opaque rectangle can hide a window. */
public final class WindowOcclusion {
    private WindowOcclusion() {}

    public static boolean covers(int backX, int backY, int backWidth, int backHeight,
                                 int frontX, int frontY, int frontWidth, int frontHeight,
                                 boolean frontTransparent) {
        return !frontTransparent && backWidth > 0 && backHeight > 0 && frontWidth > 0 && frontHeight > 0 &&
            frontX <= backX && frontY <= backY &&
            (long)frontX + frontWidth >= (long)backX + backWidth &&
            (long)frontY + frontHeight >= (long)backY + backHeight;
    }
}
