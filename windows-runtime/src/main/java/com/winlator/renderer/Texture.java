package com.winlator.renderer;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES32;

import com.winlator.xserver.Drawable;

import java.nio.ByteBuffer;

public class Texture {
    protected int textureId = 0;
    protected int wrapS = GLES20.GL_CLAMP_TO_EDGE;
    protected int wrapT = GLES20.GL_CLAMP_TO_EDGE;
    protected int magFilter = GLES20.GL_LINEAR;
    protected int minFilter = GLES20.GL_LINEAR;
    protected int format = GLES11Ext.GL_BGRA;
    protected boolean needsUpdate = true;
    private boolean flipY = false;
    protected Drawable owner;
    private boolean fullUpdate = true;
    private final TextureDamage damage = new TextureDamage();
    private long uploadedBytes, fullUploads, partialUploads;
    private final ByteBuffer[] capturedPixels = new ByteBuffer[TextureDamage.MAX_REGIONS];
    private final int[] capturedRectangles = new int[TextureDamage.MAX_REGIONS * 4];
    private int capturedCount;
    private boolean captured;
    private int capturedImageWidth, capturedImageHeight;

    public long getUploadedBytes() { return uploadedBytes; }
    public long getFullUploads() { return fullUploads; }
    public long getPartialUploads() { return partialUploads; }

    public Texture(Drawable owner) {
        this.owner = owner;
    }

    protected void generateTextureId() {
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        textureId = textureIds[0];
    }

    protected void setTextureParameters() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrapS);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrapT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter);
    }

    public void allocateTexture(short width, short height, ByteBuffer data) {
        generateTextureId();

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        if (data != null) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, data);
        }

        setTextureParameters();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    public Drawable getOwner() {
        return owner;
    }

    public void setOwner(Drawable owner) {
        this.owner = owner;
    }

    public boolean isFlipY() {
        return flipY;
    }

    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
    }

    public int getWrapS() {
        return wrapS;
    }

    public void setWrapS(int wrapS) {
        this.wrapS = wrapS;
    }

    public int getWrapT() {
        return wrapT;
    }

    public void setWrapT(int wrapT) {
        this.wrapT = wrapT;
    }

    public int getMagFilter() {
        return magFilter;
    }

    public void setMagFilter(int magFilter) {
        this.magFilter = magFilter;
    }

    public int getMinFilter() {
        return minFilter;
    }

    public void setMinFilter(int minFilter) {
        this.minFilter = minFilter;
    }

    public int getFormat() {
        return format;
    }

    public void setFormat(int format) {
        this.format = format;
    }

    public boolean isNeedsUpdate() {
        return needsUpdate;
    }

    public void setNeedsUpdate(boolean needsUpdate) {
        this.needsUpdate = needsUpdate;
        fullUpdate = needsUpdate;
        damage.clear();
    }

    /** Keep separate damage for distant sprites; first and unknown/shared-buffer updates stay complete. */
    public void markDirty(int x, int y, int width, int height) {
        if (owner == null) { setNeedsUpdate(true); return; }
        int left = Math.max(0, x), top = Math.max(0, y);
        int right = Math.min(owner.width, x + width), bottom = Math.min(owner.height, y + height);
        if (right <= left || bottom <= top) return;
        if (needsUpdate && fullUpdate) return;
        damage.add(left, top, right - left, bottom - top);
        needsUpdate = true;
    }

    public void updateFromDrawable() {
        if (owner == null || owner.getData() == null) return;

        ByteBuffer data = owner.getData();
        if (!isAllocated()) {
            allocateTexture(owner.width, owner.height, data);
            uploadedBytes += (long)owner.width * owner.height * 4;
            fullUploads++;
            setNeedsUpdate(false);
        }
        else if (needsUpdate) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            if (fullUpdate) {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, owner.width, owner.height, format, GLES20.GL_UNSIGNED_BYTE, data);
                uploadedBytes += (long)owner.width * owner.height * 4;
                fullUploads++;
            }
            else {
                // GLES 3 reads the rectangle directly from the existing image buffer: no staging allocation/copy.
                GLES20.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, owner.width);
                for (int i = 0; i < damage.size(); i++) {
                    int x = damage.get(i, 0), y = damage.get(i, 1), width = damage.get(i, 2) - x, height = damage.get(i, 3) - y;
                    GLES20.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, x);
                    GLES20.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, y);
                    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x, y, width, height, format, GLES20.GL_UNSIGNED_BYTE, data);
                    uploadedBytes += (long)width * height * 4;
                    partialUploads++;
                }
                GLES20.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
                GLES20.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0);
                GLES20.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            setNeedsUpdate(false);
        }
    }

    /** Snapshot bounded damage under the X11 lock; upload later without blocking Wine. */
    public void captureUpdate() {
        if (captured || owner == null || owner.getData() == null || (isAllocated() && !needsUpdate)) return;
        boolean complete = !isAllocated() || fullUpdate;
        capturedImageWidth = owner.width; capturedImageHeight = owner.height;
        capturedCount = complete ? 1 : damage.size();
        for (int i = 0; i < capturedCount; i++) {
            int x = complete ? 0 : damage.get(i, 0), y = complete ? 0 : damage.get(i, 1);
            int width = complete ? owner.width : damage.get(i, 2) - x, height = complete ? owner.height : damage.get(i, 3) - y;
            int p = i * 4;
            capturedRectangles[p] = x; capturedRectangles[p + 1] = y; capturedRectangles[p + 2] = width; capturedRectangles[p + 3] = height;
            int bytes = width * height * 4;
            if (capturedPixels[i] == null || capturedPixels[i].capacity() < bytes) capturedPixels[i] = ByteBuffer.allocateDirect(bytes);
            owner.copyPixelsTo(capturedPixels[i], x, y, width, height);
        }
        // Keep small sprite buffers across a scrolling burst, but do not retain a
        // growing collection of obsolete large snapshots after a map transition.
        if (complete) {
            long retained = 0;
            for (ByteBuffer pixels : capturedPixels) if (pixels != null) retained += pixels.capacity();
            if (retained > (long)capturedImageWidth * capturedImageHeight * 8)
                for (int i = 1; i < capturedPixels.length; i++) capturedPixels[i] = null;
        }
        captured = true;
        setNeedsUpdate(false);
    }

    /** GL thread only. Incoming X11 updates after capture remain dirty for the next frame. */
    public void uploadCapturedUpdate() {
        if (!captured) return;
        boolean allocated = isAllocated();
        if (!allocated) allocateTexture((short)capturedImageWidth, (short)capturedImageHeight, capturedPixels[0]);
        if (allocated) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            for (int i = 0; i < capturedCount; i++) {
                int p = i * 4;
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, capturedRectangles[p], capturedRectangles[p + 1], capturedRectangles[p + 2], capturedRectangles[p + 3],
                    format, GLES20.GL_UNSIGNED_BYTE, capturedPixels[i]);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        for (int i = 0; i < capturedCount; i++) {
            int width = capturedRectangles[i * 4 + 2], height = capturedRectangles[i * 4 + 3];
            uploadedBytes += (long)width * height * 4;
            if (width == capturedImageWidth && height == capturedImageHeight) fullUploads++;
            else partialUploads++;
        }
        captured = false;
    }

    public boolean isAllocated() {
        return textureId > 0;
    }

    public int getTextureId() {
        return textureId;
    }

    public void copyFromSource(Texture source) {
        if (!source.isAllocated()) source.allocateTexture(source.owner.width, source.owner.height, null);
        if (!this.isAllocated()) this.allocateTexture(source.owner.width, source.owner.height, null);
        GLES32.glCopyImageSubData(
            source.textureId, GLES20.GL_TEXTURE_2D, 0, 0, 0, 0,
            this.textureId, GLES20.GL_TEXTURE_2D, 0, 0, 0, 0,
            source.owner.width, source.owner.height, 1
        );
        GLES20.glFlush();
    }

    public void copyFromReadBuffer(short width, short height) {
        if (!isAllocated()) allocateTexture(width, height, null);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glCopyTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 0, 0, width, height, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glFlush();
    }

    public void destroy() {
        java.util.Arrays.fill(capturedPixels, null); captured = false;
        if (textureId > 0) {
            int[] textureIds = new int[]{textureId};
            GLES20.glDeleteTextures(textureIds.length, textureIds, 0);
            textureId = 0;
        }
    }
}
