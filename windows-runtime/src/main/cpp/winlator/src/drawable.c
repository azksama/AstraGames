#include <jni.h>
#include <string.h>
#include <malloc.h>
#include <stdbool.h>
#include <stdlib.h>
#include <math.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdint.h>

#define WHITE 0xffffff
#define BLACK 0x000000

enum GCFunction {GCF_CLEAR, GCF_AND, GCF_AND_REVERSE, GCF_COPY, GCF_AND_INVERTED, GCF_NO_OP, GCF_XOR, GCF_OR, GCF_NOR, GCF_EQUIV, GCF_INVERT, GCF_OR_REVERSE, GCF_COPY_INVERTED, GCF_OR_INVERTED, GCF_NAND, GCF_SET};

static int packColor(int8_t r, int8_t g, int8_t b) {
    return ((r & 0xff00) << 8) | (g & 0xff00) | (b >> 8);
}

static void unpackColor(int color, uint8_t *rgba) {
    rgba[2] = (color >> 16) & 255;
    rgba[1] = (color >> 8) & 255;
    rgba[0] = color & 255;
    rgba[3] = 255;
}

static int8_t getBit(uint8_t *line, int x) {
    uint8_t mask = (1 << (x & 7));
    line += (x >> 3);
    return (*line & mask) ? 1 : 0;
}

static int getBitmapBytePad(int width) {
    return ((width + 32 - 1) >> 5) << 2;
}

static int setPixelOp(int srcColor, int dstColor, enum GCFunction gcFunction) {
    switch (gcFunction) {
        case GCF_CLEAR :
            return BLACK;
        case GCF_AND :
            return srcColor & dstColor;
        case GCF_AND_REVERSE :
            return srcColor & ~dstColor;
        case GCF_COPY :
            return srcColor;
        case GCF_AND_INVERTED :
            return ~srcColor & dstColor;
        case GCF_XOR :
            return srcColor ^ dstColor;
        case GCF_OR :
            return srcColor | dstColor;
        case GCF_NOR :
            return ~srcColor & ~dstColor;
        case GCF_EQUIV :
            return ~srcColor ^ dstColor;
        case GCF_INVERT :
            return ~dstColor;
        case GCF_OR_REVERSE :
            return srcColor | ~dstColor;
        case GCF_COPY_INVERTED :
            return ~srcColor;
        case GCF_OR_INVERTED :
            return ~srcColor | dstColor;
        case GCF_NAND :
            return ~srcColor | ~dstColor;
        case GCF_SET :
            return WHITE;
        case GCF_NO_OP :
        default:
            return dstColor;
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_drawBitmap(JNIEnv *env, jclass obj,
                                              jshort width, jshort height, jobject srcData,
                                              jobject dstData) {
    uint8_t *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);
    int *dstDataAddr = (*env)->GetDirectBufferAddress(env, dstData);

    int stride = getBitmapBytePad(width);
    for (int16_t y = 0, x; y < height; y++) {
        for (x = 0; x < width; x++) *dstDataAddr++ = getBit(srcDataAddr, x) ? WHITE : BLACK;
        srcDataAddr += stride;
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_copyArea(JNIEnv *env, jclass obj, jshort srcX,
                                            jshort srcY, jshort dstX, jshort dstY,
                                            jshort width, jshort height, jshort srcStride,
                                            jshort dstStride, jobject srcData,
                                            jobject dstData) {
    uint8_t *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);
    uint8_t *dstDataAddr = (*env)->GetDirectBufferAddress(env, dstData);

    srcDataAddr += (srcX + srcY * srcStride) * 4;
    dstDataAddr += (dstX + dstY * dstStride) * 4;

    if (width == srcStride && width == dstStride) {
        memcpy(dstDataAddr, srcDataAddr, width * height * 4);
    }
    else {
        width *= 4;
        srcStride *= 4;
        dstStride *= 4;

        for (int16_t y = 0; y < height; y++) {
            memcpy(dstDataAddr, srcDataAddr, width);
            srcDataAddr += srcStride;
            dstDataAddr += dstStride;
        }
    }
}

/* A software frame can cover the whole window while only a few pixels changed.
 * Bionic's vectorized memcmp skips identical rows; copy and upload only the changed span. */
JNIEXPORT jlong JNICALL
Java_com_winlator_xserver_Drawable_copyAreaChanged(JNIEnv *env, jclass obj,
        jshort srcX, jshort srcY, jshort dstX, jshort dstY, jshort width, jshort height,
        jshort srcStride, jshort dstStride, jobject srcData, jobject dstData) {
    if (width <= 0 || height <= 0 || srcX < 0 || srcY < 0 || dstX < 0 || dstY < 0 ||
        srcX + width > srcStride || dstX + width > dstStride) return 0;
    const uint8_t *srcBase = (*env)->GetDirectBufferAddress(env, srcData);
    uint8_t *dstBase = (*env)->GetDirectBufferAddress(env, dstData);
    int64_t srcOffset = ((int64_t)srcY * srcStride + srcX) * 4;
    int64_t dstOffset = ((int64_t)dstY * dstStride + dstX) * 4;
    int64_t srcSize = ((int64_t)(height - 1) * srcStride + width) * 4;
    int64_t dstSize = ((int64_t)(height - 1) * dstStride + width) * 4;
    if (!srcBase || !dstBase || srcOffset + srcSize > (*env)->GetDirectBufferCapacity(env, srcData) ||
        dstOffset + dstSize > (*env)->GetDirectBufferCapacity(env, dstData)) return 0;
    const uint8_t *src = srcBase + srcOffset;
    uint8_t *dst = dstBase + dstOffset;
    /* Aliased buffers keep the previous copy path; the diff path is for independent frames. */
    if ((uintptr_t)dst < (uintptr_t)src + srcSize && (uintptr_t)src < (uintptr_t)dst + dstSize) {
        Java_com_winlator_xserver_Drawable_copyArea(env, obj, srcX, srcY, dstX, dstY,
            width, height, srcStride, dstStride, srcData, dstData);
        return ((uint64_t)(uint16_t)dstX << 48) | ((uint64_t)(uint16_t)dstY << 32) |
            ((uint64_t)(uint16_t)width << 16) | (uint16_t)height;
    }
    int left = width, right = 0, top = height, bottom = 0;
    for (int y = 0; y < height; y++, src += srcStride * 4, dst += dstStride * 4) {
        if (memcmp(src, dst, width * 4) == 0) continue;
        int first = 0, last = width;
        while (first + 16 <= last && memcmp(src + first * 4, dst + first * 4, 64) == 0) first += 16;
        while (first < last && memcmp(src + first * 4, dst + first * 4, 4) == 0) first++;
        while (last - 16 >= first && memcmp(src + (last - 16) * 4, dst + (last - 16) * 4, 64) == 0) last -= 16;
        while (last > first && memcmp(src + (last - 1) * 4, dst + (last - 1) * 4, 4) == 0) last--;
        memcpy(dst + first * 4, src + first * 4, (last - first) * 4);
        if (first < left) left = first;
        if (last > right) right = last;
        if (y < top) top = y;
        bottom = y + 1;
    }
    if (right <= left) return 0;
    return ((uint64_t)(uint16_t)(dstX + left) << 48) | ((uint64_t)(uint16_t)(dstY + top) << 32) |
        ((uint64_t)(uint16_t)(right - left) << 16) | (uint16_t)(bottom - top);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_copyAreaOp(JNIEnv *env, jclass obj, jshort srcX,
                                              jshort srcY, jshort dstX, jshort dstY,
                                              jshort width, jshort height, jshort srcStride,
                                              jshort dstStride, jobject srcData,
                                              jobject dstData, int gcFunction) {
    int i, j, srcColor, dstColor;
    uint8_t *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);
    uint8_t *dstDataAddr = (*env)->GetDirectBufferAddress(env, dstData);

    for (int16_t x, y = 0; y < height; y++) {
        for (x = 0; x < width; x++) {
            i = (x + srcX + (y + srcY) * srcStride) * 4;
            j = (x + dstX + (y + dstY) * dstStride) * 4;
            srcColor = (srcDataAddr[i+0] << 16) | (srcDataAddr[i+1] << 8) | srcDataAddr[i+2];
            dstColor = (dstDataAddr[j+0] << 16) | (dstDataAddr[j+1] << 8) | dstDataAddr[j+2];

            dstColor = setPixelOp(srcColor, dstColor, gcFunction);

            dstDataAddr[j+0] = (dstColor >> 16) & 0xff;
            dstDataAddr[j+1] = (dstColor >> 8) & 0xff;
            dstDataAddr[j+2] = dstColor & 0xff;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_fillRect(JNIEnv *env, jclass obj, jshort x, jshort y,
                                            jshort width, jshort height, jint color, jshort stride,
                                            jobject data) {
    uint8_t *dataAddr = (*env)->GetDirectBufferAddress(env, data);

    uint8_t rgba[4];
    unpackColor(color, rgba);

    int rowSize = width * 4;
    uint8_t *row = malloc(rowSize);

    for (int i = 0; i < rowSize; i += 4) memcpy(row + i, rgba, 4);
    for (int16_t i = 0; i < height; i++) {
        memcpy(dataAddr + (x + (i + y) * stride) * 4, row, rowSize);
    }

    free(row);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_drawLine(JNIEnv *env, jclass obj, jshort x0, jshort y0,
                                            jshort x1, jshort y1, jint color, jshort lineWidth,
                                            jshort stride, jobject data) {
    uint8_t *dataAddr = (*env)->GetDirectBufferAddress(env, data);
    int dx =  abs(x1-x0);
    int dy = -abs(y1-y0);
    int8_t sx = x0 < x1 ? 1 : -1;
    int8_t sy = y0 < y1 ? 1 : -1;
    int e1 = dx + dy, e2;

    uint8_t rgba[4];
    unpackColor(color, rgba);

    int rowSize = lineWidth * 4;
    uint8_t *row = malloc(lineWidth * 4);

    int16_t i;
    for (i = 0; i < rowSize; i += 4) memcpy(row + i, rgba, 4);

    while (true) {
        for (i = 0; i < lineWidth; i++) memcpy(dataAddr + (x0 + (i + y0) * stride) * 4, row, rowSize);
        if (x0 == x1 && y0 == y1) break;

        e2 = e1 * 2;
        if (e2 >= dy) {
            e1 += dy;
            x0 += sx;
        }
        if (e2 <= dx) {
            e1 += dx;
            y0 += sy;
        }
    }

    free(row);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(JNIEnv *env, jclass obj,
                                                         jbyte foreRed, jbyte foreGreen,
                                                         jbyte foreBlue, jbyte backRed,
                                                         jbyte backGreen, jbyte backBlue,
                                                         jobject srcData, jobject maskData,
                                                         jobject dstData) {
    int *srcDataAddr = (*env)->GetDirectBufferAddress(env, srcData);
    int *maskDataAddr = (*env)->GetDirectBufferAddress(env, maskData);
    int *dstDataAddr = (*env)->GetDirectBufferAddress(env, dstData);

    int foreColor = packColor(foreRed, foreGreen, foreBlue);
    int backColor = packColor(backRed, backGreen, backBlue);

    jlong dstLength = (*env)->GetDirectBufferCapacity(env, dstData) / 4;
    for (int i = 0; i < dstLength; i++) {
        dstDataAddr[i] = maskDataAddr[i] == WHITE ? (srcDataAddr[i] == WHITE ? foreColor : backColor) | 0xff000000 : 0x00000000;
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_fromBitmap(JNIEnv *env, jclass obj, jobject bitmap,
                                              jobject data) {
    char *dataAddr = (*env)->GetDirectBufferAddress(env, data);

    AndroidBitmapInfo info;
    uint8_t *pixels;

    AndroidBitmap_getInfo(env, bitmap, &info);
    AndroidBitmap_lockPixels(env, bitmap, (void**)&pixels);

    for (int i = 0, size = info.width * info.height * 4; i < size; i++) {
        memcpy(dataAddr + i, pixels + i, 4);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Pixmap_toBitmap(JNIEnv *env, jclass obj, jobject colorData,
                                          jobject maskData, jobject bitmap) {
    char *colorDataAddr = (*env)->GetDirectBufferAddress(env, colorData);
    char *maskDataAddr = maskData ? (*env)->GetDirectBufferAddress(env, maskData) : NULL;

    AndroidBitmapInfo info;
    uint8_t *pixels;

    AndroidBitmap_getInfo(env, bitmap, &info);
    AndroidBitmap_lockPixels(env, bitmap, (void**)&pixels);

    for (int i = 0, size = info.width * info.height * 4; i < size; i += 4) {
        pixels[i+2] = colorDataAddr[i+0];
        pixels[i+1] = colorDataAddr[i+1];
        pixels[i+0] = colorDataAddr[i+2];
        pixels[i+3] = maskDataAddr ? maskDataAddr[i+0] : colorDataAddr[i+3];
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}
