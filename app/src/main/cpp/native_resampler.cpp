#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <vector>

extern "C" {
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

struct Resampler {
    SwrContext* context = nullptr;
    int channels = 0;
    int sample_rate = 0;
};

static void throwIllegalState(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, message);
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_dm1sh_rechka_transcriber_NativeResampler_00024Companion_nativeCreate(
        JNIEnv* env, jclass, jint sample_rate, jint channels) {
    if (sample_rate <= 0 || channels <= 0) {
        throwIllegalState(env, "Invalid input audio format");
        return 0;
    }
    auto* r = new Resampler();
    r->channels = channels;
    r->sample_rate = sample_rate;
    AVChannelLayout in_layout;
    av_channel_layout_default(&in_layout, channels);
    AVChannelLayout out_layout = AV_CHANNEL_LAYOUT_MONO;
    const int allocResult = swr_alloc_set_opts2(
        &r->context,
        &out_layout, AV_SAMPLE_FMT_S16, 16000,
        &in_layout, AV_SAMPLE_FMT_S16, sample_rate,
        0, nullptr);
    av_channel_layout_uninit(&in_layout);
    if (allocResult < 0 || r->context == nullptr || swr_init(r->context) < 0) {
        if (r->context) swr_free(&r->context);
        delete r;
        throwIllegalState(env, "Unable to initialize native resampler");
        return 0;
    }
    return reinterpret_cast<jlong>(r);
}

static jshortArray convert(JNIEnv* env, Resampler* r, const uint8_t* input,
                           int input_bytes, bool flush) {
    const int input_samples = flush ? 0 : input_bytes / (2 * r->channels);
    const int max_output = swr_get_out_samples(r->context, input_samples);
    if (max_output <= 0) return env->NewShortArray(0);
    std::vector<int16_t> output(static_cast<size_t>(max_output));
    const uint8_t* in[] = { input };
    uint8_t* out[] = { reinterpret_cast<uint8_t*>(output.data()) };
    // A null input pointer (not an array containing a null pointer) tells
    // libswresample to flush its delayed samples.
    const int produced = swr_convert(r->context, out, max_output,
                                     flush ? nullptr : in, input_samples);
    if (produced < 0) {
        throwIllegalState(env, "Native audio conversion failed");
        return nullptr;
    }
    jshortArray result = env->NewShortArray(produced);
    env->SetShortArrayRegion(result, 0, produced,
                             reinterpret_cast<const jshort*>(output.data()));
    return result;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_ru_dm1sh_rechka_transcriber_NativeResampler_00024Companion_nativeProcess(
        JNIEnv* env, jclass, jlong handle, jobject buffer, jint byte_count) {
    auto* r = reinterpret_cast<Resampler*>(handle);
    auto* input = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!r || !input || byte_count < 0) {
        throwIllegalState(env, "Invalid native audio buffer");
        return nullptr;
    }
    return convert(env, r, input, byte_count, false);
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_ru_dm1sh_rechka_transcriber_NativeResampler_00024Companion_nativeFlush(
        JNIEnv* env, jclass, jlong handle) {
    auto* r = reinterpret_cast<Resampler*>(handle);
    if (!r) return env->NewShortArray(0);
    return convert(env, r, nullptr, 0, true);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_dm1sh_rechka_transcriber_NativeResampler_00024Companion_nativeRelease(
        JNIEnv*, jclass, jlong handle) {
    auto* r = reinterpret_cast<Resampler*>(handle);
    if (!r) return;
    swr_free(&r->context);
    delete r;
}
