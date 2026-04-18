#include <jni.h>
#include <android/bitmap.h>
#include <ass/ass.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "LibassJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

typedef struct {
    ASS_Library  *library;
    ASS_Renderer *renderer;
    ASS_Track    *track;
    int           frame_w;
    int           frame_h;
    uint32_t     *pixel_buffer;  // Android ARGB_8888: stored as 0xAARRGGBB (uint32_t), i.e. [B,G,R,A] in memory
    int           has_cached_frame;
} LibassContext;

JNIEXPORT jlong JNICALL
Java_dev_jdtech_jellyfin_player_xr_LibassRenderer_nativeInit(JNIEnv *env, jobject thiz, jint width, jint height) {
    LibassContext *ctx = (LibassContext *)calloc(1, sizeof(LibassContext));
    if (!ctx) return 0;

    ctx->library = ass_library_init();
    if (!ctx->library) {
        free(ctx);
        return 0;
    }

    ctx->renderer = ass_renderer_init(ctx->library);
    if (!ctx->renderer) {
        ass_library_done(ctx->library);
        free(ctx);
        return 0;
    }

    // Set high-fidelity rendering options
    ass_set_frame_size(ctx->renderer, width, height);
    // Default storage size = frame size. Overridden once the video resolution is known
    // via nativeResize; without an initial value libass uses frame_size and applies a
    // neutral pixel aspect ratio, which shifts \pos coordinates before the first resize.
    ass_set_storage_size(ctx->renderer, width, height);
    // Use LIGHT hinting as NATIVE can sometimes result in strange glyph spacing on
    // Android system fonts. SHAPING_COMPLEX enables HarfBuzz for correct BiDi.
    ass_set_hinting(ctx->renderer, ASS_HINTING_LIGHT);
    ass_set_shaper(ctx->renderer, ASS_SHAPING_COMPLEX);

    // Fallback font for glyphs not covered by any registered font, and the default
    // family name libass resolves when the ASS style's Fontname is unknown.
    // Kotlin registers /system/fonts/* and embedded attachments via nativeAddFont;
    // provider NONE = resolve purely from that in-memory registry (Android has no
    // fontconfig/CoreText/DirectWrite — AUTODETECT resolves to NONE anyway).
    //
    // Most anime ASS scripts specify Fontname: Arial. The Galaxy XR has no Arial, so we
    // steer the fallback to Roboto — it has Regular/Bold/Italic variants on the
    // device. Passing "Roboto" as default_family means any unknown Fontname in the
    // style resolves to Roboto too, and libass picks the correct weight automatically
    // from the registered Roboto-Bold.ttf / Roboto-Medium.ttf.
    const char *font_noto_cjk = "/system/fonts/NotoSansCJK-Regular.ttc";
    const char *font_roboto = "/system/fonts/Roboto-Regular.ttf";
    const char *font_droid = "/system/fonts/DroidSans.ttf";
    const char *fallback = NULL;
    // Prefer Roboto for Latin characters to avoid rendering English in Noto CJK.
    if (access(font_roboto, R_OK) == 0) fallback = font_roboto;
    else if (access(font_noto_cjk, R_OK) == 0) fallback = font_noto_cjk;
    else if (access(font_droid, R_OK) == 0) fallback = font_droid;
    LOGI("nativeInit: fallback font %s (default family: Roboto)", fallback ? fallback : "(none)");
    ass_set_fonts(ctx->renderer, fallback, "Roboto", ASS_FONTPROVIDER_NONE, NULL, 1);

    ctx->frame_w = width;
    ctx->frame_h = height;
    LOGI("nativeInit: frame %dx%d", width, height);
    ctx->pixel_buffer = (uint32_t *)malloc(width * height * 4);
    if (!ctx->pixel_buffer) {
        ass_renderer_done(ctx->renderer);
        ass_library_done(ctx->library);
        free(ctx);
        return 0;
    }

    return (jlong)(uintptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_dev_jdtech_jellyfin_player_xr_LibassRenderer_nativeAddFont(JNIEnv *env, jobject thiz, jlong ctx_ptr, jstring name, jbyteArray data) {
    LibassContext *ctx = (LibassContext *)(uintptr_t)ctx_ptr;
    if (!ctx || !ctx->library) return;

    const char *font_name = (*env)->GetStringUTFChars(env, name, NULL);
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *font_data = (*env)->GetByteArrayElements(env, data, NULL);

    ass_add_font(ctx->library, (char *)font_name, (char *)font_data, len);

    // If the font name contains "Roboto", also register it as "Roboto" to satisfy the default family
    if (strstr(font_name, "Roboto") != NULL) {
        ass_add_font(ctx->library, "Roboto", (char *)font_data, len);
    }
    // If it's the Noto Arabic font, also register it as "Noto Naskh Arabic"
    if (strstr(font_name, "NotoNaskhArabic") != NULL) {
        ass_add_font(ctx->library, "Noto Naskh Arabic", (char *)font_data, len);
    }

    (*env)->ReleaseByteArrayElements(env, data, font_data, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, name, font_name);
}

JNIEXPORT void JNICALL
Java_dev_jdtech_jellyfin_player_xr_LibassRenderer_nativeSetDefaultFamily(JNIEnv *env, jobject thiz, jlong ctx_ptr, jstring family) {
    LibassContext *ctx = (LibassContext *)(uintptr_t)ctx_ptr;
    if (!ctx || !ctx->renderer) return;

    const char *family_name = (*env)->GetStringUTFChars(env, family, NULL);
    
    const char *font_noto_cjk = "/system/fonts/NotoSansCJK-Regular.ttc";
    const char *font_roboto = "/system/fonts/Roboto-Regular.ttf";
    const char *font_droid = "/system/fonts/DroidSans.ttf";
    const char *fallback = NULL;
    if (access(font_noto_cjk, R_OK) == 0) fallback = font_noto_cjk;
    else if (access(font_roboto, R_OK) == 0) fallback = font_roboto;
    else if (access(font_droid, R_OK) == 0) fallback = font_droid;

    LOGI("nativeSetDefaultFamily: family=%s fallback=%s", family_name, fallback ? fallback : "(none)");
    ass_set_fonts(ctx->renderer, fallback, family_name, ASS_FONTPROVIDER_NONE, NULL, 1);

    (*env)->ReleaseStringUTFChars(env, family, family_name);
}

JNIEXPORT void JNICALL
Java_dev_jdtech_jellyfin_player_xr_LibassRenderer_nativeSetTrackData(JNIEnv *env, jobject thiz, jlong ctx_ptr, jbyteArray codecPrivate) {
    LibassContext *ctx = (LibassContext *)(uintptr_t)ctx_ptr;
    if (!ctx || !ctx->library) return;

    if (ctx->track) {
        ass_free_track(ctx->track);
    }
    ctx->track = ass_new_track(ctx->library);
    if (!ctx->track) return;

    if (codecPrivate) {
        jsize len = (*env)->GetArrayLength(env, codecPrivate);
        jbyte *data = (*env)->GetByteArrayElements(env, codecPrivate, NULL);
        LOGI("nativeSetTrackData: codec_private %d bytes", (int)len);
        // Split into 900-char chunks so logcat doesn't truncate the style/format rows.
        int offset = 0;
        while (offset < len) {
            int chunk = len - offset > 900 ? 900 : (int)(len - offset);
            LOGD("nativeSetTrackData: header[%d..%d]=%.*s", offset, offset + chunk, chunk, (char *)data + offset);
            offset += chunk;
        }
        ass_process_codec_private(ctx->track, (char *)data, len);
        (*env)->ReleaseByteArrayElements(env, codecPrivate, data, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_dev_jdtech_jellyfin_player_xr_LibassRenderer_nativeProcessChunk(JNIEnv *env, jobject thiz, jlong ctx_ptr, jbyteArray data, jlong startMs, jlong durationMs) {
    LibassContext *ctx = (LibassContext *)(uintptr_t)ctx_ptr;
    if (!ctx || !ctx->track) return;

    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *chunk_data = (*env)->GetByteArrayElements(env, data, NULL);

    // Log a null-safe preview of the chunk text (first 120 chars)
    char preview[121];
    int preview_len = len < 120 ? (int)len : 120;
    memcpy(preview, chunk_data, preview_len);
    preview[preview_len] = '\0';
    LOGD("processChunk: start=%lldms dur=%lldms text=%.120s", (long long)startMs, (long long)durationMs, preview);

    ass_process_chunk(ctx->track, (char *)chunk_data, len, startMs, durationMs);

    (*env)->ReleaseByteArrayElements(env, data, chunk_data, JNI_ABORT);
}

JNIEXPORT jintArray JNICALL
Java_dev_jdtech_jellyfin_player_xr_LibassRenderer_nativeRenderFrame(JNIEnv *env, jobject thiz, jlong ctx_ptr, jlong timeMs) {
    LibassContext *ctx = (LibassContext *)(uintptr_t)ctx_ptr;
    if (!ctx || !ctx->renderer || !ctx->track) return NULL;

    int changed = 0;
    ASS_Image *img = ass_render_frame(ctx->renderer, ctx->track, timeMs, &changed);

    // Early out: nothing changed since last call
    if (changed == 0 && ctx->has_cached_frame) {
        return NULL; // Kotlin side reuses last bitmap
    }

    // Count images and log frame info
    int img_count = 0;
    ASS_Image *tmp = img;
    while (tmp) { img_count++; tmp = tmp->next; }
    LOGD("renderFrame: t=%lldms changed=%d images=%d", (long long)timeMs, changed, img_count);

    // No content
    if (img == NULL) {
        ctx->has_cached_frame = 0;
        jintArray result = (*env)->NewIntArray(env, 5);
        jint res[5] = {0, 0, 0, 0, 0};
        (*env)->SetIntArrayRegion(env, result, 0, 5, res);
        return result;
    }

    // Track dirty rect across all images
    int dirty_x1 = ctx->frame_w, dirty_y1 = ctx->frame_h;
    int dirty_x2 = 0, dirty_y2 = 0;
    int has_content = 0;

    // Clear buffer to transparent
    memset(ctx->pixel_buffer, 0, ctx->frame_w * ctx->frame_h * 4);

    ASS_Image *cur = img;
    while (cur) {
        if (cur->w == 0 || cur->h == 0) {
            cur = cur->next;
            continue;
        }
        has_content = 1;

        // Expand dirty rect
        if (cur->dst_x < dirty_x1) dirty_x1 = cur->dst_x;
        if (cur->dst_y < dirty_y1) dirty_y1 = cur->dst_y;
        if (cur->dst_x + (int)cur->w > dirty_x2) dirty_x2 = cur->dst_x + cur->w;
        if (cur->dst_y + (int)cur->h > dirty_y2) dirty_y2 = cur->dst_y + cur->h;

        // img->color is 0xRRGGBBAA; alpha is inverted (0=opaque, 255=transparent)
        uint8_t r = (cur->color >> 24) & 0xFF;
        uint8_t g = (cur->color >> 16) & 0xFF;
        uint8_t b = (cur->color >> 8)  & 0xFF;
        uint8_t a = 255 - (cur->color & 0xFF);

        LOGI("renderFrame: color r=%d g=%d b=%d a=%d raw=0x%08X pos=(%d,%d) size=%dx%d",
             r, g, b, a, cur->color, cur->dst_x, cur->dst_y, cur->w, cur->h);

        for (int y = 0; y < cur->h; y++) {
            int dst_y = cur->dst_y + y;
            if (dst_y < 0 || dst_y >= ctx->frame_h) continue;

            for (int x = 0; x < cur->w; x++) {
                int dst_x = cur->dst_x + x;
                if (dst_x < 0 || dst_x >= ctx->frame_w) continue;

                uint8_t glyph_alpha = cur->bitmap[y * cur->stride + x];
                uint32_t src_a = (glyph_alpha * a + 127) / 255;
                if (src_a == 0) continue;

                uint32_t *dst = &ctx->pixel_buffer[dst_y * ctx->frame_w + dst_x];
                uint32_t dst_pixel = *dst;

                // Unpack destination — buffer is Android ARGB_8888: 0xAARRGGBB as uint32_t
                uint8_t dst_a = (dst_pixel >> 24) & 0xFF;
                uint8_t dst_r = (dst_pixel >> 16) & 0xFF;
                uint8_t dst_g = (dst_pixel >> 8)  & 0xFF;
                uint8_t dst_b =  dst_pixel        & 0xFF;

                // Src-over compositing
                uint32_t inv_src_a = 255 - src_a;
                uint8_t out_a = src_a + (dst_a * inv_src_a + 127) / 255;
                uint8_t out_r = (r * src_a + dst_r * inv_src_a + 127) / 255;
                uint8_t out_g = (g * src_a + dst_g * inv_src_a + 127) / 255;
                uint8_t out_b = (b * src_a + dst_b * inv_src_a + 127) / 255;

                // Write back as 0xAARRGGBB — matches Android Bitmap.Config.ARGB_8888
                *dst = (out_a << 24) | (out_r << 16) | (out_g << 8) | out_b;
            }
        }
        cur = cur->next;
    }

    if (has_content) {
        LOGD("renderFrame: dirty (%d,%d) %dx%d in frame %dx%d (bottom=%d)",
             dirty_x1, dirty_y1,
             dirty_x2 > dirty_x1 ? dirty_x2 - dirty_x1 : 0,
             dirty_y2 > dirty_y1 ? dirty_y2 - dirty_y1 : 0,
             ctx->frame_w, ctx->frame_h,
             ctx->frame_h - dirty_y2);
    }

    ctx->has_cached_frame = has_content;
    
    jintArray result = (*env)->NewIntArray(env, 5);
    if (!result) return NULL;
    
    jint res[5];
    res[0] = has_content ? 1 : 0;
    res[1] = dirty_x1;
    res[2] = dirty_y1;
    res[3] = dirty_x2 > dirty_x1 ? dirty_x2 - dirty_x1 : 0;
    res[4] = dirty_y2 > dirty_y1 ? dirty_y2 - dirty_y1 : 0;
    
    (*env)->SetIntArrayRegion(env, result, 0, 5, res);
    return result;
}

JNIEXPORT jobject JNICALL
Java_dev_jdtech_jellyfin_player_xr_LibassRenderer_nativeGetBuffer(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    LibassContext *ctx = (LibassContext *)(uintptr_t)ctx_ptr;
    if (!ctx || !ctx->pixel_buffer) return NULL;

    return (*env)->NewDirectByteBuffer(env, ctx->pixel_buffer, ctx->frame_w * ctx->frame_h * 4);
}

JNIEXPORT void JNICALL
Java_dev_jdtech_jellyfin_player_xr_LibassRenderer_nativeResize(JNIEnv *env, jobject thiz, jlong ctx_ptr, jint width, jint height, jint storageW, jint storageH) {
    LibassContext *ctx = (LibassContext *)(uintptr_t)ctx_ptr;
    if (!ctx || !ctx->renderer) return;

    ctx->frame_w = width;
    ctx->frame_h = height;
    
    uint32_t *new_buffer = (uint32_t *)realloc(ctx->pixel_buffer, width * height * 4);
    if (new_buffer) {
        ctx->pixel_buffer = new_buffer;
        memset(ctx->pixel_buffer, 0, width * height * 4);
    }

    ass_set_frame_size(ctx->renderer, width, height);
    if (storageW > 0 && storageH > 0) {
        ass_set_storage_size(ctx->renderer, storageW, storageH);
        LOGI("nativeResize: frame=%dx%d storage=%dx%d", width, height, storageW, storageH);
    } else {
        LOGI("nativeResize: frame=%dx%d (no storage size)", width, height);
    }
    ctx->has_cached_frame = 0;
}

JNIEXPORT void JNICALL
Java_dev_jdtech_jellyfin_player_xr_LibassRenderer_nativeClearCache(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    LibassContext *ctx = (LibassContext *)(uintptr_t)ctx_ptr;
    if (!ctx) return;

    if (ctx->track) {
        ass_flush_events(ctx->track);
    }
    ctx->has_cached_frame = 0;
}

JNIEXPORT void JNICALL
Java_dev_jdtech_jellyfin_player_xr_LibassRenderer_nativeDestroy(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    LibassContext *ctx = (LibassContext *)(uintptr_t)ctx_ptr;
    if (!ctx) return;

    if (ctx->track) ass_free_track(ctx->track);
    if (ctx->renderer) ass_renderer_done(ctx->renderer);
    if (ctx->library) ass_library_done(ctx->library);
    if (ctx->pixel_buffer) free(ctx->pixel_buffer);
    
    free(ctx);
}
