package com.winlator.renderer;

import com.winlator.xserver.Drawable;

class RenderableWindow {
    final Drawable content;
    short rootX;
    short rootY;
    final boolean transparent;
    final FullscreenTransformation fullscreenTransformation;
    final boolean gameCandidate;
    boolean occluded;

    public RenderableWindow(Drawable content, int rootX, int rootY, boolean transparent, FullscreenTransformation fullscreenTransformation) {
        this(content, rootX, rootY, transparent, fullscreenTransformation, false);
    }

    public RenderableWindow(Drawable content, int rootX, int rootY, boolean transparent, FullscreenTransformation fullscreenTransformation, boolean gameCandidate) {
        this.content = content;
        this.rootX = (short)rootX;
        this.rootY = (short)rootY;
        this.transparent = transparent;
        this.fullscreenTransformation = fullscreenTransformation;
        this.gameCandidate = gameCandidate;
    }

    boolean covers(RenderableWindow back) {
        FullscreenTransformation frontTransform = fullscreenTransformation;
        FullscreenTransformation backTransform = back.fullscreenTransformation;
        return WindowOcclusion.covers(
            backTransform == null ? back.rootX : backTransform.x, backTransform == null ? back.rootY : backTransform.y,
            backTransform == null ? back.content.width : backTransform.width, backTransform == null ? back.content.height : backTransform.height,
            frontTransform == null ? rootX : frontTransform.x, frontTransform == null ? rootY : frontTransform.y,
            frontTransform == null ? content.width : frontTransform.width, frontTransform == null ? content.height : frontTransform.height,
            transparent);
    }
}
