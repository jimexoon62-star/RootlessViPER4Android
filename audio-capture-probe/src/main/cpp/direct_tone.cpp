#include <aaudio/AAudio.h>
#include <jni.h>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>

namespace {
struct Tone {
    AAudioStream* stream = nullptr;
    int frequency;
    int rate = 48000;
    int channels = 2;
    uint64_t frame = 0;
};
std::mutex lifecycle;
std::unique_ptr<Tone> tone;
aaudio_data_callback_result_t render(AAudioStream*, void* data, void* audio, int32_t frames) {
    auto& t = *static_cast<Tone*>(data);
    auto* pcm = static_cast<float*>(audio);
    for (int i = 0; i < frames; ++i) {
        const float value = static_cast<float>(.2 * std::sin(2 * 3.141592653589793 * t.frequency * t.frame++ / t.rate));
        for (int c = 0; c < t.channels; ++c) *pcm++ = value;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
void closeTone() {
    if (!tone) return;
    AAudioStream_requestStop(tone->stream);
    // close waits for callbacks before the user-data object is destroyed.
    AAudioStream_close(tone->stream);
    tone.reset();
}
}

extern "C" JNIEXPORT jint JNICALL
Java_org_rv4a_captureprobe_DirectToneActivity_startNative(JNIEnv*, jclass, jint frequency) {
    std::lock_guard<std::mutex> guard(lifecycle);
    if (tone || frequency < 100 || frequency > 10000) return AAUDIO_ERROR_INVALID_STATE;
    AAudioStreamBuilder* builder = nullptr;
    auto result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return result;
    auto next = std::make_unique<Tone>();
    next->frequency = frequency;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setAllowedCapturePolicy(builder, AAUDIO_ALLOW_CAPTURE_BY_SYSTEM);
    // Match the Spotify source rate observed in the HONOR capture; the other
    // fixture remains at 48 kHz. The callback uses the negotiated stream rate.
    AAudioStreamBuilder_setSampleRate(builder, 44100);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSessionId(builder, AAUDIO_SESSION_ID_ALLOCATE);
    AAudioStreamBuilder_setDataCallback(builder, render, next.get());
    result = AAudioStreamBuilder_openStream(builder, &next->stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) return result;
    next->rate = AAudioStream_getSampleRate(next->stream);
    next->channels = AAudioStream_getChannelCount(next->stream);
    if (AAudioStream_getFormat(next->stream) != AAUDIO_FORMAT_PCM_FLOAT || next->channels != 2 || next->rate <= 0) {
        AAudioStream_close(next->stream);
        return AAUDIO_ERROR_INVALID_FORMAT;
    }
    tone = std::move(next);
    result = AAudioStream_requestStart(tone->stream);
    if (result != AAUDIO_OK) closeTone();
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_rv4a_captureprobe_DirectToneActivity_stopNative(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(lifecycle);
    closeTone();
}
