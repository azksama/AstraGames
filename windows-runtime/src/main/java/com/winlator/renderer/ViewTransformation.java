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

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        update(outerWidth, outerHeight, innerWidth, innerHeight, innerWidth, innerHeight, 0);
    }

    /** Fit/fill the game image itself, independently of the Windows desktop's aspect ratio. */
    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight, int imageWidth, int imageHeight, int mode) {
        if (outerWidth <= 0 || outerHeight <= 0 || innerWidth <= 0 || innerHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return;
        aspect = mode == 1 ? Math.max((float)outerWidth / imageWidth, (float)outerHeight / imageHeight)
            : Math.min((float)outerWidth / imageWidth, (float)outerHeight / imageHeight);
        viewWidth = mode == 2 ? outerWidth : Math.round(imageWidth * aspect);
        viewHeight = mode == 2 ? outerHeight : Math.round(imageHeight * aspect);
        viewOffsetX = (outerWidth - viewWidth) / 2;
        viewOffsetY = (outerHeight - viewHeight) / 2;

        sceneScaleX = (float)viewWidth / outerWidth;
        sceneScaleY = (float)viewHeight / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
        sceneOffsetY = (innerHeight - innerHeight * sceneScaleY) * 0.5f;
    }
}
