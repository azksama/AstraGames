package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.ViewGroup;
import android.view.Choreographer;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private final GLRenderer renderer;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final java.util.concurrent.atomic.AtomicBoolean frameScheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile int maxFps = 60;
    private long lastFrameRequest;
    private volatile boolean paused;
    private final Choreographer.FrameCallback contentFrame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (paused) { frameScheduled.set(false); return; }
            long interval = 1_000_000_000L / maxFps;
            // Small tolerance absorbs vsync timestamp rounding, including 59.94 Hz displays.
            if (frameTimeNanos - lastFrameRequest + 500_000L < interval) {
                choreographer.postFrameCallback(this);
                return;
            }
            lastFrameRequest = frameTimeNanos;
            frameScheduled.set(false);
            requestRender();
        }
    };

    public void setMaxFps(int fps) { maxFps = fps == 30 ? 30 : 60; }
    public int getMaxFps() { return maxFps; }
    /** Coalesce X11 damage notifications; don't upload the same frame for each small update. */
    public void requestContentRender() {
        if (!paused && frameScheduled.compareAndSet(false, true)) choreographer.postFrameCallback(contentFrame);
    }
    private void stopFrames() {
        paused = true;
        choreographer.removeFrameCallback(contentFrame); frameScheduled.set(false);
    }
    @Override public void onPause() { stopFrames(); super.onPause(); }
    @Override public void onResume() { super.onResume(); paused = false; lastFrameRequest = 0; requestContentRender(); }
    @Override protected void onDetachedFromWindow() { stopFrames(); super.onDetachedFromWindow(); }

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
