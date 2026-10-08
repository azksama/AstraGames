package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private final GLRenderer renderer;
    private final android.os.Handler frames = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean frameScheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile int maxFps = 60;
    private volatile long lastFrameRequest;
    private final Runnable contentFrame = () -> {
        lastFrameRequest = android.os.SystemClock.uptimeMillis();
        frameScheduled.set(false);
        requestRender();
    };

    public void setMaxFps(int fps) { maxFps = fps == 30 ? 30 : 60; }
    /** Coalesce X11 damage notifications; don't upload the same frame for each small update. */
    public void requestContentRender() {
        if (frameScheduled.compareAndSet(false, true)) {
            long delay = Math.max(0, (1000 + maxFps - 1) / maxFps - (android.os.SystemClock.uptimeMillis() - lastFrameRequest));
            frames.postDelayed(contentFrame, delay);
        }
    }
    @Override public void onPause() { frames.removeCallbacks(contentFrame); frameScheduled.set(false); super.onPause(); }
    @Override protected void onDetachedFromWindow() { frames.removeCallbacks(contentFrame); frameScheduled.set(false); super.onDetachedFromWindow(); }

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public GLRenderer getRenderer() {
        return renderer;
    }
}
