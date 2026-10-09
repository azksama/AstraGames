package com.winlator.renderer;

public class ViewTransformation {
    public int viewOffsetX;
    public int viewOffsetY;
    public int viewWidth;
    public int viewHeight;
    public float aspect;
    public float sceneScaleX;
    public float sceneScaleY;
    public float sceneOffsetX;
    public float sceneOffsetY;
    private volatile float zoom = 1f;
    private float panX, panY;
    private int outerWidth, outerHeight, baseWidth, baseHeight;
    public float getZoom() { return zoom; }
    public void resetZoom() { zoom = 1f; panX = panY = 0; }

    /** Anchor the image under the fingers; all pointer mapping uses this same viewport. */
    public void zoomBy(float factor, float focusX, float focusY, float deltaX, float deltaY) {
        if (!Float.isFinite(factor) || factor <= 0 || !Float.isFinite(focusX) || !Float.isFinite(focusY) ||
            !Float.isFinite(deltaX) || !Float.isFinite(deltaY) || baseWidth <= 0 || baseHeight <= 0) return;
        float next = Math.max(1f, Math.min(4f, zoom * factor));
        float ratio = next / zoom;
        int nextWidth = Math.round(baseWidth * next), nextHeight = Math.round(baseHeight * next);
        panX = focusX - (focusX - deltaX - viewOffsetX) * ratio - (outerWidth - nextWidth) * .5f;
        panY = focusY - (focusY - deltaY - viewOffsetY) * ratio - (outerHeight - nextHeight) * .5f;
        zoom = next;
        if (zoom == 1f) panX = panY = 0;
    }

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        update(outerWidth, outerHeight, innerWidth, innerHeight, innerWidth, innerHeight, 0);
    }

    /** Fit/fill the game image itself, independently of the Windows desktop's aspect ratio. */
    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight, int imageWidth, int imageHeight, int mode) {
        if (outerWidth <= 0 || outerHeight <= 0 || innerWidth <= 0 || innerHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return;
        aspect = mode == 1 ? Math.max((float)outerWidth / imageWidth, (float)outerHeight / imageHeight)
            : Math.min((float)outerWidth / imageWidth, (float)outerHeight / imageHeight);
        this.outerWidth = outerWidth; this.outerHeight = outerHeight;
        baseWidth = mode == 2 ? outerWidth : Math.round(imageWidth * aspect);
        baseHeight = mode == 2 ? outerHeight : Math.round(imageHeight * aspect);
        viewWidth = Math.round(baseWidth * zoom);
        viewHeight = Math.round(baseHeight * zoom);
        panX = Math.max(-Math.max(0, viewWidth - outerWidth) * .5f, Math.min(Math.max(0, viewWidth - outerWidth) * .5f, panX));
        panY = Math.max(-Math.max(0, viewHeight - outerHeight) * .5f, Math.min(Math.max(0, viewHeight - outerHeight) * .5f, panY));
        viewOffsetX = Math.round((outerWidth - viewWidth) * .5f + panX);
        viewOffsetY = Math.round((outerHeight - viewHeight) * .5f + panY);

        sceneScaleX = (float)viewWidth / outerWidth;
        sceneScaleY = (float)viewHeight / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
        sceneOffsetY = (innerHeight - innerHeight * sceneScaleY) * 0.5f;
    }
}
