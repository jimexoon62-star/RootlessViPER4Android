#include <aaudio/AAudio.h>
#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <atomic>
#include <condition_variable>
#include <chrono>

namespace {
struct Tone {
    AAudioStream* stream = nullptr;
    int frequency;
    int rate = 44100;
    int channels = 2;
    uint64_t frame = 0;
    bool recover = true;
    int recoveryDelayMs = 0;
    std::atomic<bool> stopping{false}, disconnected{false};
    std::mutex wakeMutex;
    std::condition_variable wake;
    std::thread recovery;
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
void onError(AAudioStream*, void* data, aaudio_result_t error) {
    auto& t = *static_cast<Tone*>(data);
    __android_log_print(ANDROID_LOG_WARN,"DirectToneAAudio","AAUDIO_ERROR code=%d recover=%d",error,t.recover);
    if (t.recover && !t.stopping.load() && error == AAUDIO_ERROR_DISCONNECTED) {
        t.disconnected.store(true); t.wake.notify_one();
    }
}
void closeStream(Tone& t) {
    if (!t.stream) return;
    AAudioStream_requestStop(t.stream);
    AAudioStream_close(t.stream); // Waits for callbacks before user data can be freed.
    t.stream = nullptr;
}
aaudio_result_t openStream(Tone& t) {
    AAudioStreamBuilder* builder = nullptr;
    auto result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return result;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setAllowedCapturePolicy(builder, AAUDIO_ALLOW_CAPTURE_BY_SYSTEM);
    AAudioStreamBuilder_setSampleRate(builder, 44100);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSessionId(builder, AAUDIO_SESSION_ID_ALLOCATE);
    AAudioStreamBuilder_setDataCallback(builder, render, &t);
    AAudioStreamBuilder_setErrorCallback(builder, onError, &t);
    result = AAudioStreamBuilder_openStream(builder, &t.stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) return result;
    t.rate = AAudioStream_getSampleRate(t.stream);
    t.channels = AAudioStream_getChannelCount(t.stream);
    if (AAudioStream_getFormat(t.stream) != AAUDIO_FORMAT_PCM_FLOAT || t.channels != 2 || t.rate <= 0) {
        closeStream(t); return AAUDIO_ERROR_INVALID_FORMAT;
    }
    result = AAudioStream_requestStart(t.stream);
    if (result != AAUDIO_OK) closeStream(t);
    return result;
}
void recoverStream(Tone* t) {
    while (!t->stopping.load()) {
        std::unique_lock<std::mutex> lock(t->wakeMutex);
        t->wake.wait(lock,[&]{return t->stopping.load() || t->disconnected.load();});
        if (t->stopping.load()) break;
        t->disconnected.store(false);
        if (t->wake.wait_for(lock,std::chrono::milliseconds(t->recoveryDelayMs),[&]{return t->stopping.load();})) break;
        lock.unlock();
        // AAudio requires close/reopen on a DIFFERENT thread, never in its error callback.
        closeStream(*t);
        for (int attempt=0; attempt<3 && !t->stopping.load(); ++attempt) {
            auto result=openStream(*t);
            if (result==AAUDIO_OK) { __android_log_print(ANDROID_LOG_INFO,"DirectToneAAudio","AAUDIO_REOPENED rate=%d",t->rate); break; }
            std::unique_lock<std::mutex> wait(t->wakeMutex);
            t->wake.wait_for(wait,std::chrono::milliseconds(100*(attempt+1)),[&]{return t->stopping.load();});
        }
    }
}
void stopTone(std::unique_ptr<Tone> old) {
    if (!old) return;
    old->stopping.store(true); old->wake.notify_all();
    if (old->recovery.joinable()) old->recovery.join();
    closeStream(*old);
}
}
extern "C" JNIEXPORT jint JNICALL
Java_org_rv4a_captureprobe_DirectToneActivity_startNative(JNIEnv*, jclass, jint frequency, jboolean recover, jint delay) {
    std::lock_guard<std::mutex> guard(lifecycle);
    if (tone || frequency < 100 || frequency > 10000 || delay<0 || delay>3000) return AAUDIO_ERROR_INVALID_STATE;
    auto next=std::make_unique<Tone>(); next->frequency=frequency; next->recover=recover; next->recoveryDelayMs=delay;
    auto result=openStream(*next);
    if (result!=AAUDIO_OK) return result;
    tone=std::move(next);
    if (tone->recover) tone->recovery=std::thread(recoverStream,tone.get());
    return AAUDIO_OK;
}
extern "C" JNIEXPORT void JNICALL
Java_org_rv4a_captureprobe_DirectToneActivity_stopNative(JNIEnv*, jclass) {
    std::unique_ptr<Tone> old;
    { std::lock_guard<std::mutex> guard(lifecycle); old=std::move(tone); }
    stopTone(std::move(old));
}
