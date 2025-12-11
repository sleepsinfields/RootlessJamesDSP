#include <android/log.h>

#define TAG "JamesDspWrapper_JNI"
#include <Log.h>

#include <string>
#include <jni.h>

#include "JamesDspWrapper.h"
#include "JArrayList.h"
#include "EelVmVariable.h"

extern "C" {
#include "../EELStdOutExtension.h"
#include <jdsp_header.h>
}

// C interop
inline JamesDSPLib* cast(void* raw){
    if(raw == nullptr)
    {
        LOGE("JamesDspWrapper::cast: JamesDSPLib pointer is NULL")
    }
    return static_cast<JamesDSPLib*>(raw);
}

inline JamesDspWrapper* castWrapper(jlong raw){
    if(raw == 0)
    {
        LOGE("JamesDspWrapper::castWrapper: JamesDspWrapper pointer is NULL")
    }
    return reinterpret_cast<JamesDspWrapper*>(raw);
}

#define RETURN_IF_NULL(name, retval) \
    if(name == nullptr)      \
        return retval;

#define DECLARE_WRAPPER(retval) \
     if(self == 0L) \
        return retval; \
     auto* wrapper = castWrapper(self); \
     RETURN_IF_NULL(wrapper, retval)

#define DECLARE_DSP(retval) \
    DECLARE_WRAPPER(retval) \
    auto* dsp = cast(wrapper->dsp); \
    RETURN_IF_NULL(dsp, retval)

#define DECLARE_WRAPPER_V DECLARE_WRAPPER()
#define DECLARE_DSP_V DECLARE_DSP()
#define DECLARE_WRAPPER_B DECLARE_WRAPPER(false)
#define DECLARE_DSP_B DECLARE_DSP(false)

inline int32_t arySearch(int32_t *array, int32_t N, int32_t x)
{
    for (int32_t i = 0; i < N; i++)
    {
        if (array[i] == x)
            return i;
    }
    return -1;
}

#define FLOIDX 20000
/*inline void* GetStringForIndex(eel_string_context_state *st, float val, int32_t write)
{
    auto castedValue = (int32_t)(val + 0.5f);
    if (castedValue < FLOIDX)
        return nullptr;
    int32_t idx = arySearch(st->map, st->slot, castedValue);
    if (idx < 0)
        return nullptr;
    if (!write)
    {
        s_str *tmp = &st->m_literal_strings[idx];
        const char *s = s_str_c_str(tmp);
        return (void*)s;
    }
    else
        return (void*)&st->m_literal_strings[idx];
}*/

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_alloc(JNIEnv *env, jobject obj, jobject callback)
{
    auto* self = new JamesDspWrapper();
    self->callbackInterface = env->NewGlobalRef(callback);
    self->env = env;

    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr)
    {
        LOGE("JamesDspWrapper::ctor: Cannot find callback class");
        delete self;
        return 0;
    }
    else
    {
        self->callbackOnLiveprogOutput = env->GetMethodID(callbackClass, "onLiveprogOutput",
                                                      "(Ljava/lang/String;)V");
        self->callbackOnLiveprogExec = env->GetMethodID(callbackClass, "onLiveprogExec",
                                                    "(Ljava/lang/String;)V");
        self->callbackOnLiveprogResult = env->GetMethodID(callbackClass, "onLiveprogResult",
                                                          "(ILjava/lang/String;Ljava/lang/String;)V");
        self->callbackOnVdcParseError = env->GetMethodID(callbackClass, "onVdcParseError",
                                                          "()V");
        if (self->callbackOnLiveprogOutput == nullptr || self->callbackOnLiveprogExec == nullptr ||
            self->callbackOnLiveprogResult == nullptr || self->callbackOnVdcParseError == nullptr)
        {
            LOGE("JamesDspWrapper::ctor: Cannot find callback method");
            delete self;
            return 0;
        }
    }


    auto* _dsp = (JamesDSPLib*)malloc(sizeof(JamesDSPLib));
    memset(_dsp, 0, sizeof(JamesDSPLib));

    if(!_dsp)
    {
        LOGE("JamesDspWrapper::ctor: Failed to allocate memory for libjamesdsp class object");
        delete self;
        return 1;
    }

    JamesDSPGlobalMemoryAllocation();
    JamesDSPInit(_dsp, 128, 48000);

    if(!JamesDSPGetMutexStatus(_dsp))
    {
        LOGE("JamesDspWrapper::ctor: JamesDSPGetMutexStatus returned false. "
                    "Cannot run safely in multi-threaded environment.");
        JamesDSPFree(_dsp);
        JamesDSPGlobalMemoryDeallocation();
        delete self;
        return 2;
    }

    self->dsp = _dsp;

    LOGD("JamesDspWrapper::ctor: memory allocated at %lx", (long)self);
    return (long)self;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_free(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_DSP_V

    LOGD("JamesDspWrapper::dtor: freeing memory allocated at %lx", (long)self);

    setStdOutHandler(nullptr, nullptr);

    JamesDSPFree(dsp);
    free(dsp);
    wrapper->dsp = nullptr;

    JamesDSPGlobalMemoryDeallocation();

    env->DeleteGlobalRef(wrapper->callbackInterface);
    delete wrapper;

    LOGD("JamesDspWrapper::dtor: memory freed");
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_getBenchmarkSize(JNIEnv *env, jobject obj) {
    return MAX_BENCHMARK;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_runBenchmark(JNIEnv *env, jobject obj, jdoubleArray jc0, jdoubleArray jc1)
{
    LOGD("JamesDspWrapper::runBenchmark: started");

    auto c0 = env->GetDoubleArrayElements(jc0, nullptr);
    auto c1 = env->GetDoubleArrayElements(jc1, nullptr);

    JamesDSP_Start_benchmark();
    JamesDSP_Save_benchmark(c0, c1);

    env->ReleaseDoubleArrayElements(jc0, c0, 0);
    env->ReleaseDoubleArrayElements(jc1, c1, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_loadBenchmark(JNIEnv *env, jobject obj, jdoubleArray jc0, jdoubleArray jc1)
{
    LOGD("JamesDspWrapper::loadBenchmark: loading data");

    auto c0 = env->GetDoubleArrayElements(jc0, nullptr);
    auto c1 = env->GetDoubleArrayElements(jc1, nullptr);

    JamesDSP_Load_benchmark(c0, c1);

    env->ReleaseDoubleArrayElements(jc0, c0, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jc1, c1, JNI_ABORT);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setSamplingRate(JNIEnv *env,
                                                                                 jobject obj,
                                                                                 jlong self,
                                                                                 jfloat sample_rate,
                                                                                 jboolean force_refresh)
{
    DECLARE_DSP_V
    JamesDSPSetSampleRate(dsp, sample_rate, force_refresh);
}


extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_isHandleValid(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_DSP_B // This macro returns false if the DSP object can't be accessed
    return true;
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt16(JNIEnv *env, jobject obj, jlong self, jshortArray inputObj, jshortArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    jsize inputLength;
    if(size < 0)
        inputLength = env->GetArrayLength(inputObj);
    else
        inputLength = size;
    if(offset < 0)
        offset = 0;

    auto input = env->GetShortArrayElements(inputObj, nullptr);
    auto output = env->GetShortArrayElements(outputObj, nullptr);
    dsp->processInt16Multiplexd(dsp, input + offset, output, inputLength / 2);
    env->ReleaseShortArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseShortArrayElements(outputObj, output, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt32(JNIEnv *env, jobject obj, jlong self, jintArray inputObj, jintArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    jsize inputLength;
    if(size < 0)
        inputLength = env->GetArrayLength(inputObj);
    else
        inputLength = size;
    if(offset < 0)
        offset = 0;

    auto input = env->GetIntArrayElements(inputObj, nullptr);
    auto output = env->GetIntArrayElements(outputObj, nullptr);
    dsp->processInt32Multiplexd(dsp, input + offset, output, inputLength / 2);
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
}

extern "C"
JNIEXPORT jbooleanArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt24Packed(JNIEnv *env, jobject obj, jlong self, jbooleanArray inputObj)
{
    /* We need to use jbooleanArray (= unsigned 8-bit) instead of jbyteArray (= signed 8-bit) here! */

    // Return inputObj if DECLARE failed
    DECLARE_DSP(inputObj)

    auto inputLength = env->GetArrayLength(inputObj);
    auto outputObj = env->NewBooleanArray(inputLength);

    auto input = env->GetBooleanArrayElements(inputObj, nullptr);
    auto output = env->GetBooleanArrayElements(outputObj, nullptr);
    dsp->processInt24PackedMultiplexd(dsp, input, output, inputLength / 2);
    env->ReleaseBooleanArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseBooleanArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt8U24(JNIEnv *env, jobject obj, jlong self, jintArray inputObj)
{
    // Return inputObj if DECLARE failed
    DECLARE_DSP(inputObj)

    auto inputLength = env->GetArrayLength(inputObj);
    auto outputObj = env->NewIntArray(inputLength);

    auto input = env->GetIntArrayElements(inputObj, nullptr);
    auto output = env->GetIntArrayElements(outputObj, nullptr);
    dsp->processInt8_24Multiplexd(dsp, input, output, inputLength / 2);
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processFloat(JNIEnv *env, jobject obj, jlong self, jfloatArray inputObj, jfloatArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    jsize inputLength;
    if(size < 0)
        inputLength = env->GetArrayLength(inputObj);
    else
        inputLength = size;
    if(offset < 0)
        offset = 0;

    auto input = env->GetFloatArrayElements(inputObj, nullptr);
    auto output = env->GetFloatArrayElements(outputObj, nullptr);

    dsp->processFloatMultiplexd(dsp, input + offset, output, inputLength / 2);

    env->ReleaseFloatArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseFloatArrayElements(outputObj, output, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLimiter(JNIEnv *env, jobject obj, jlong self, jfloat threshold, jfloat release)
{
    DECLARE_DSP_B
    JLimiterSetCoefficients(dsp, threshold, release);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setPostGain(JNIEnv *env, jobject obj, jlong self, jfloat gain)
{
    DECLARE_DSP_B
    JamesDSPSetPostGain(dsp, gain);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setMultiEqualizer(JNIEnv *env, jobject obj, jlong self,
                                                                                   jboolean enable, jint filterType, jint interpolationMode,
                                                                                   jdoubleArray bands)
{
    DECLARE_DSP_B

    if(env->GetArrayLength(bands) != 30)
    {
        LOGE("JamesDspWrapper::setMultiEqualizer: Invalid EQ data. 30 semicolon-separated fields expected, "
                      "found %d fields instead.", env->GetArrayLength(bands));
        return false;
    }

    if(bands == nullptr)
    {
        LOGW("JamesDspWrapper::setMultiEqualizer: EQ band pointer is NULL. Disabling EQ");
        MultimodalEqualizerDisable(dsp);
        return true;
    }

    if(enable)
    {
        auto* nativeBands = (env->GetDoubleArrayElements(bands, nullptr));
        MultimodalEqualizerAxisInterpolation(dsp, interpolationMode, filterType, nativeBands, nativeBands + 15);
        env->ReleaseDoubleArrayElements(bands, nativeBands, JNI_ABORT);
        MultimodalEqualizerEnable(dsp, 1);
    }
    else
    {
        MultimodalEqualizerDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVdc(JNIEnv *env, jobject obj, jlong self,
                                                                       jboolean enable, jstring vdcContents)
{
    DECLARE_DSP_B
    if(enable)
    {
        const char *nativeString = env->GetStringUTFChars(vdcContents, nullptr);
        DDCStringParser(dsp, (char*)nativeString);
        env->ReleaseStringUTFChars(vdcContents, nativeString);

        int ret = DDCEnable(dsp, 1);
        if (ret <= 0)
        {
            LOGE("JamesDspWrapper::setVdc: Call to DDCEnable(wrapper->dsp) failed. Invalid DDC parameter?");
            LOGE("JamesDspWrapper::setVdc: Disabling DDC engine");
            env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnVdcParseError);

            DDCDisable(dsp);
            return false;
        }
    }
    else
    {
        DDCDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCompander(JNIEnv *env, jobject obj, jlong self,
                                                                              jboolean enable, jfloat timeConstant, jint granularity, jint tfresolution, jdoubleArray bands)
{
    DECLARE_DSP_B

    if(env->GetArrayLength(bands) != 14)
    {
        LOGE("JamesDspWrapper::setCompander: Invalid compander data. 14 semicolon-separated fields expected, "
             "found %d fields instead.", env->GetArrayLength(bands));
        return false;
    }

    if(bands == nullptr)
    {
        LOGW("JamesDspWrapper::setCompander: Compander band pointer is NULL. Disabling compander");
        MultimodalEqualizerDisable(dsp);
        return true;
    }

    if(enable)
    {
        CompressorSetParam(dsp, timeConstant, granularity, tfresolution, 0);
        auto* nativeBands = (env->GetDoubleArrayElements(bands, nullptr));
        CompressorSetGain(dsp, nativeBands, nativeBands + 7, 1);
        env->ReleaseDoubleArrayElements(bands, nativeBands, JNI_ABORT);
        CompressorEnable(dsp, 1);
    }
    else
    {
        CompressorDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setReverb(JNIEnv *env, jobject obj, jlong self,
                                                                          jboolean enable, jint preset)
{
    DECLARE_DSP_B
    if(enable)
    {
        Reverb_SetParam(dsp, preset);
        ReverbEnable(dsp);
    }
    else
    {
        ReverbDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setConvolver(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jfloatArray impulseResponse,
                                                                             jint irChannels, jint irFrames)
{
    DECLARE_DSP_B

    int success = 1;
    if(env->GetArrayLength(impulseResponse) <= 0)
    {
        LOGW("JamesDspWrapper::setConvolver: Impulse response array is empty. Disabling convolver");
        enable = false;
    }

    if(enable)
    {
        if(irFrames <= 0)
        {
            LOGW("JamesDspWrapper::setConvolver: Impulse response has zero frames");
        }

        LOGD("JamesDspWrapper::setConvolver: Impulse response loaded: channels=%d, frames=%d", irChannels, irFrames);

        Convolver1DDisable(dsp);

        auto* nativeImpulse = (env->GetFloatArrayElements(impulseResponse, nullptr));
        success = Convolver1DLoadImpulseResponse(dsp, nativeImpulse, irChannels, irFrames, 1);
        env->ReleaseFloatArrayElements(impulseResponse, nativeImpulse, JNI_ABORT);
    }

    if(enable)
        Convolver1DEnable(dsp);
    else
        Convolver1DDisable(dsp);

    if(success <= 0)
    {
        LOGD("JamesDspWrapper::setConvolver: Failed to update convolver. Convolver1DLoadImpulseResponse returned an error.");
        return false;
    }

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setGraphicEq(
        JNIEnv *env, jobject obj, jlong self,
        jboolean enable, jstring graphicEq)
{
    DECLARE_DSP_B

    if (graphicEq == nullptr || env->GetStringUTFLength(graphicEq) <= 0)
    {
        LOGE("JamesDspWrapper::setGraphicEq: graphicEq is empty or NULL. Disabling graphic eq.");
        enable = false;
    }

    if (enable)
    {
        // Legacy single-curve path (used by old UI / non-stereo mode)
        LOGE("🔥 setGraphicEq triggered (legacy single-curve path)");

        const char *nativeString = env->GetStringUTFChars(graphicEq, nullptr);
        ArbitraryResponseEqualizerStringParser(dsp, (char*)nativeString);
        env->ReleaseStringUTFChars(graphicEq, nativeString);

        ArbitraryResponseEqualizerEnable(dsp, 1);
    }
    else
    {
        ArbitraryResponseEqualizerDisable(dsp);
    }

    return JNI_TRUE;  // or `true`, both are fine for jboolean
}

// new
extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setStereoGraphicEq(
        JNIEnv *env,
        jobject /*obj*/,
        jlong self,
        jboolean enable,
        jstring masterEq,
        jstring leftEq,
        jstring rightEq)
{
    DECLARE_DSP_B

    // If explicitly disabled, just turn off the EQ and return.
    if (!enable)
    {
        LOGE("JamesDspWrapper::setStereoGraphicEq: enable == false, disabling EQ.");
        ArbitraryResponseEqualizerDisable(dsp);
        return JNI_TRUE;
    }

    // Require a master curve string
    if (masterEq == nullptr || env->GetStringUTFLength(masterEq) <= 0)
    {
        LOGE("JamesDspWrapper::setStereoGraphicEq: masterEq is empty or null. Disabling EQ.");
        ArbitraryResponseEqualizerDisable(dsp);
        return JNI_TRUE;
    }

    // ✅ This is the correct place for your “path hit” log:
    LOGE("🔥 setStereoGraphicEq triggered (correct stereo path)");

    // Get master chars
    const char *masterStr = env->GetStringUTFChars(masterEq, nullptr);

    // Decide what to use for left/right:
    //  - if leftEq/rightEq are non-null and non-empty, use them
    //  - otherwise, fall back to masterStr
    const char *leftStr  = nullptr;
    const char *rightStr = nullptr;

    bool leftIsMaster  = false;
    bool rightIsMaster = false;

    if (leftEq != nullptr && env->GetStringUTFLength(leftEq) > 0)
    {
        leftStr = env->GetStringUTFChars(leftEq, nullptr);
    }
    else
    {
        leftStr = masterStr;
        leftIsMaster = true;
    }

    if (rightEq != nullptr && env->GetStringUTFLength(rightEq) > 0)
    {
        rightStr = env->GetStringUTFChars(rightEq, nullptr);
    }
    else
    {
        rightStr = masterStr;
        rightIsMaster = true;
    }

    // 🔥 Feed all three curves into your stereo-aware parser
    ArbitraryResponseEqualizerStringParserStereo(
        dsp,
        masterStr,
        leftStr,
        rightStr
    );

    // Clean up JNI strings:
    if (!rightIsMaster && rightEq != nullptr)
        env->ReleaseStringUTFChars(rightEq, rightStr);

    if (!leftIsMaster && leftEq != nullptr)
        env->ReleaseStringUTFChars(leftEq, leftStr);

    env->ReleaseStringUTFChars(masterEq, masterStr);

    // Make sure EQ is active
    ArbitraryResponseEqualizerEnable(dsp, 1);

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setArbEqStereoFlags(
        JNIEnv *env,
        jobject obj,
        jlong self,
        jboolean globalEnable,
        jboolean masterEnable,
        jboolean leftEnable,
        jboolean rightEnable)
{
    (void)env;
    (void)obj;

    // Use the standard handle → wrapper → dsp path
    DECLARE_DSP_V  // sets `wrapper` and `dsp`, returns early if null

    const char global = (globalEnable == JNI_TRUE) ? 1 : 0;
    const char master = (masterEnable == JNI_TRUE) ? 1 : 0;
    const char left   = (leftEnable   == JNI_TRUE) ? 1 : 0;
    const char right  = (rightEnable  == JNI_TRUE) ? 1 : 0;

    ArbitraryResponseEqualizerSetGlobalEnabled(dsp, global);
    ArbitraryResponseEqualizerSetMasterEnabled(dsp, master);
    ArbitraryResponseEqualizerSetLeftEnabled(dsp, left);
    ArbitraryResponseEqualizerSetRightEnabled(dsp, right);

    LOGE("JamesDspWrapper_setArbEqStereoFlags: global=%d master=%d left=%d right=%d",
         global, master, left, right);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCrossfeed(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jint mode, jint customFcut, jint customFeed)
{
    DECLARE_DSP_B
    if(mode == 99)
    {
        memset(&dsp->advXF.bs2b, 0, sizeof(dsp->advXF.bs2b));
        BS2BInit(&dsp->advXF.bs2b[1], (unsigned int)dsp->fs, ((unsigned int)customFcut | ((unsigned int)customFeed << 16)));
        dsp->advXF.mode = 1;
    }
    else
    {
       CrossfeedChangeMode(dsp, mode);
    }

    if(enable)
        CrossfeedEnable(dsp, 1);
    else
        CrossfeedDisable(dsp);

    return true;
}

//
extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setBassBoost(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jfloat maxGain)
{
    DECLARE_DSP_B
    if(enable)
    {
        BassBoostSetParam(dsp, maxGain);
        BassBoostEnable(dsp);
    }
    else
    {
        BassBoostDisable(dsp);
    }
    return true;
}
//
/* ???
extern "C" JNIEXPORT jboolean JNICALL Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_00024Companion_setBassBoostAdvanced(
*/

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setBassBoostAdvanced(
    JNIEnv *env, jobject obj,
    jlong self,
    jboolean enable,
    jfloat maxGain,
    jfloat widthNorm,
    jint   typeInt,
    jfloat speedNorm,
    jfloat stabilityNorm
)
{
    DECLARE_DSP_B

    // If DBB is off, just disable and bail
    if (!enable) {
        BassBoostDisable(dsp);
        return JNI_TRUE;
    }

    // --- Simple clamps (no <algorithm> needed) ---
    auto clamp01 = [](float v) -> float {
        if (v < 0.0f) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    };
    auto clampInt = [](int v, int lo, int hi) -> int {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    };

    float w  = clamp01(widthNorm);       // 0..1
    float sp = clamp01(speedNorm);       // 0..1
    float st = clamp01(stabilityNorm);   // 0..1
    int mode = clampInt(typeInt, 0, 2);  // 0,1,2

    // ----------------------------------------------------------------
    // 1) Map "Bass type" into big behavioral differences
    // ----------------------------------------------------------------
    //   mode 0 = Sub-bass focus: slower, heavier, deeper, narrower
    //   mode 1 = Balanced: close to legacy
    //   mode 2 = Punchy: faster dynamics, higher center, wider band
    double baseTargetFs;   // analysis fs → indirectly controls how bins are spaced
    double baseDetectMs;   // detector smoothing
    double baseGainMs;     // gain smoothing
    float  baseResonance;  // resonance → Q

    switch (mode) {
        case 0: // Sub-bass
            baseTargetFs  = 350.0;
            baseDetectMs  = 8.0;
            baseGainMs    = 120.0;
            baseResonance = 0.90f;
            break;

        default:
        case 1: // Balanced (near original)
            baseTargetFs  = 500.0;
            baseDetectMs  = 3.0;
            baseGainMs    = 60.0;
            baseResonance = 0.75f;
            break;

        case 2: // Punchy
            baseTargetFs  = 900.0;
            baseDetectMs  = 1.0;
            baseGainMs    = 20.0;
            baseResonance = 0.60f;
            break;
    }

    // ----------------------------------------------------------------
    // 2) Width slider → resonance + targetFs swing
    //    w=0: really focused & narrow
    //    w=1: much wider, more upper-bass
    // ----------------------------------------------------------------
    // resonance: 0.40 .. 0.98 (very audible)
    const float RES_MIN = 0.40f;
    const float RES_MAX = 0.98f;

    // For sub-bass and balanced, higher width = lower resonance (wider)
    // For punchy, invert a bit so high width can go a bit peaky if desired
    float res;
    if (mode == 2) {
        // punchy: width=0 → medium-wide, width=1 → fairly narrow
        res = RES_MIN + (RES_MAX - RES_MIN) * (0.3f + 0.7f * w);
    } else {
        // sub / balanced: width=0 → very narrow, width=1 → wider
        res = RES_MAX - (RES_MAX - RES_MIN) * w;
    }

    // TargetFs: swing ±50% with width
    // (this changes how dense / where the analysis bins sit)
    double targetFs =
        baseTargetFs * (0.5 + w);   // w=0 → 0.5x, w=1 → 1.5x

    // ----------------------------------------------------------------
    // 3) Speed slider → huge swing in detector & gain time constants
    //    sp=0: VERY slow / lazy
    //    sp=1: VERY fast / snappy
    // ----------------------------------------------------------------
    // We’ll allow a 10x range around the base values.
    const double DET_MULT_MIN = 0.1;
    const double DET_MULT_MAX = 10.0;
    const double GAIN_MULT_MIN = 0.1;
    const double GAIN_MULT_MAX = 10.0;

    // invert sp because UI "faster" is high value
    double invSp = 1.0 - sp;

    double detMult  = DET_MULT_MIN  + (DET_MULT_MAX  - DET_MULT_MIN)  * invSp;
    double gainMult = GAIN_MULT_MIN + (GAIN_MULT_MAX - GAIN_MULT_MIN) * invSp;

    double detectMs = baseDetectMs * detMult;
    double gainMs   = baseGainMs   * gainMult;

    // ----------------------------------------------------------------
    // 4) Stability slider → extra damping on detector only
    //    st=0: twitchy, oversensitive
    //    st=1: extra stable, longer averaging
    // ----------------------------------------------------------------
    double stabFactor = 0.5 + 1.5 * st;    // 0.5x .. 2.0x
    detectMs *= stabFactor;

    // ----------------------------------------------------------------
    // 5) Push these into the C core (DBBParam will use them)
    // ----------------------------------------------------------------
    BassBoostSetTargetFs(dsp, targetFs);
    BassBoostSetSmoothing(dsp, detectMs, gainMs);
    BassBoostSetResonance(dsp, res);

    // For now keep using mode 0 / 1 (legacy / log-spaced)
    // You’ll hear type differences mainly via timing + resonance.
    int freqMode = (mode == 1) ? 0 : 1;
    BassBoostSetFreqMode(dsp, freqMode, nullptr);

    // Finally apply gain & enable
    BassBoostSetParam(dsp, maxGain);
    BassBoostEnable(dsp);

    return JNI_TRUE;
}
//
extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setStereoEnhancement(JNIEnv *env, jobject obj, jlong self,
                                                                                     jboolean enable, jfloat level)
{
    DECLARE_DSP_B
    StereoEnhancementDisable(dsp);
    StereoEnhancementSetParam(dsp, level / 100.0f);
    if(enable)
    {
        StereoEnhancementEnable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTube(
    JNIEnv* env,
    jobject obj,
    jlong self,
    jboolean enable,
    jdouble level   // dB from Kotlin
) {
    // Use the same macro as before – this is important
    // because it safely resolves `self` → JamesDSPLib* dsp
    DECLARE_DSP_B   // gives you `dsp` (JamesDSPLib*) or early-return

    // Clamp to your UI/desired range (matches slider: -3 to 12 dB)
    if (level > 12.0) level = 12.0;
    if (level < -3.0) level = -3.0;

    // Pass dB directly to your DSP (VacuumTubeSetGain converts dB → linear internally)
    VacuumTubeSetGain(dsp, level);

    if (enable) {
        VacuumTubeEnable(dsp);
    } else {
        VacuumTubeDisable(dsp);
    }

    return JNI_TRUE;
}

// --- Advanced Vacuum Tube controls ----------------------------------
// NOTE: keep the simple setVacuumTube() above exactly as-is.

// Shape mix: 0.0 – 1.0
extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTubeShape(
    JNIEnv *env,
    jobject obj,
    jlong self,
    jdouble mix
) {
    DECLARE_DSP_B
    VacuumTubeSetShape(dsp, (double)mix);
    return JNI_TRUE;
}

// Triode parameters: drive / bias / scale
extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTubeTriodeParams(
    JNIEnv *env,
    jobject obj,
    jlong self,
    jdouble drive,
    jdouble bias,
    jdouble scale
) {
    DECLARE_DSP_B
    VacuumTubeSetTriodeParams(dsp,
                              (double)drive,
                              (double)bias,
                              (double)scale);
    return JNI_TRUE;
}

// Global harmonic scale
extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTubeHarmScale(
    JNIEnv *env,
    jobject obj,
    jlong self,
    jdouble harmScale
) {
    DECLARE_DSP_B
    VacuumTubeSetHarmScale(dsp, (double)harmScale);
    return JNI_TRUE;
}

// Even / odd harmonic gains
extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTubeHarmonics(
    JNIEnv *env,
    jobject obj,
    jlong self,
    jdouble even,
    jdouble odd
) {
    DECLARE_DSP_B
    VacuumTubeSetHarmonics(dsp,
                           (double)even,
                           (double)odd);
    return JNI_TRUE;
}

// Core triode on the mid allpass band
extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTubeCoreTriode(
    JNIEnv *env,
    jobject obj,
    jlong self,
    jboolean enabled
) {
    DECLARE_DSP_B
    VacuumTubeSetCoreTriode(dsp,
                            (enabled == JNI_TRUE) ? 1 : 0);
    return JNI_TRUE;
}
//
extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLiveprog(JNIEnv *env, jobject obj, jlong self,
                                                                            jboolean enable, jstring id, jstring liveprogContent)
{
    DECLARE_DSP_B

    // Attach log listener
    setStdOutHandler(receiveLiveprogStdOut, wrapper);

    LiveProgDisable(dsp);

    const char *nativeString = env->GetStringUTFChars(liveprogContent, nullptr);
    if(strlen(nativeString) < 1) {
        LOGD("JamesDspWrapper::setLiveprog: empty file")
        env->ReleaseStringUTFChars(liveprogContent, nativeString);
        return true;
    }

    env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnLiveprogExec, id);

    int ret = LiveProgStringParser(dsp, (char*)nativeString); // Ignore constness, libjamesdsp does not modify it
    env->ReleaseStringUTFChars(liveprogContent, nativeString);

    // Workaround due to library bug
    jdsp_unlock(dsp);

    const char* errorString = NSEEL_code_getcodeerror(dsp->eel.vm);
    if(errorString != nullptr)
    {
        LOGW("JamesDspWrapper::setLiveprog: NSEEL_code_getcodeerror: Syntax error in script file, cannot load. Reason: %s", errorString);
    }
    if(ret <= 0)
    {
        LOGW("JamesDspWrapper::setLiveprog: %s", checkErrorCode(ret));
    }

    jstring errorStringJni = env->NewStringUTF(errorString);
    env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnLiveprogResult, ret, id, errorStringJni);
    env->DeleteLocalRef(errorStringJni);

    if(enable)
        LiveProgEnable(dsp);
    else
        LiveProgDisable(dsp);
    return true;
}


extern "C" JNIEXPORT jobject JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_enumerateEelVariables(JNIEnv *env, jobject obj, jlong self)
{
    auto array = JArrayList(env);

    // Return empty array if DECLARE failed
    DECLARE_DSP(array.getJavaReference())

    auto *ctx = (compileContext*)dsp->eel.vm;
    for (int i = 0; i < ctx->varTable_numBlocks; i++)
    {
        for (int j = 0; j < NSEEL_VARS_PER_BLOCK; j++)
        {
            // TODO fix string handling (broke after last libjamesdsp update)
            const char *valid = nullptr;//(char*)GetStringForIndex(ctx->region_context, ctx->varTable_Values[i][j], 1);
            bool isString = valid;

            if (ctx->varTable_Names[i][j])
            {
                const char* name = ctx->varTable_Names[i][j];
                const char* value;

                if(isString)
                    value = valid;
                else
                    value = std::to_string(ctx->varTable_Values[i][j]).c_str();

                auto var = EelVmVariable(env, name, value, isString);
                array.add(var.getJavaReference());
            }
        }
    }

    return array.getJavaReference();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_manipulateEelVariable(JNIEnv *env, jobject obj, jlong self,
                                                                                      jstring name, jfloat value)
{
    DECLARE_DSP_B
    auto* ctx = (compileContext*)dsp->eel.vm;
    for (int i = 0; i < ctx->varTable_numBlocks; i++)
    {
        for (int j = 0; j < NSEEL_VARS_PER_BLOCK; j++)
        {
            const char *nativeName = env->GetStringUTFChars(name, nullptr);
            if(!ctx->varTable_Names[i][j] || std::strcmp(ctx->varTable_Names[i][j], nativeName) != 0)
            {
                env->ReleaseStringUTFChars(name, nativeName);
                continue;
            }

            const char *valid = nullptr;//(char*)GetStringForIndex(ctx->region_context, ctx->varTable_Values[i][j], 1);
            if(valid)
            {
                LOGE("JamesDspWrapper::manipulateEelVariable: variable '%s' is a string; currently only numerical variables can be manipulated", nativeName);
                env->ReleaseStringUTFChars(name, nativeName);
                return false;
            }

            ctx->varTable_Values[i][j] = value;

            env->ReleaseStringUTFChars(name, nativeName);
            return true;
        }
    }

    const char *nativeName = env->GetStringUTFChars(name, nullptr);
    LOGE("JamesDspWrapper::manipulateEelVariable: variable '%s' not found", nativeName);
    env->ReleaseStringUTFChars(name, nativeName);
    return false;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_freezeLiveprogExecution(JNIEnv *env, jobject obj, jlong self,
                                                                                        jboolean freeze)
{
    DECLARE_DSP_V
    dsp->eel.active = !freeze;
    LOGD("JamesDspWrapper::freezeLiveprogExecution: Liveprog execution has been %s", (freeze ? "frozen" : "resumed"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_eelErrorCodeToString(JNIEnv *env,
                                                                                     jobject obj,
                                                                                     jint error_code)
{
    return env->NewStringUTF(checkErrorCode(error_code));
}

void receiveLiveprogStdOut(const char *buffer, void* userData)
{
    auto* self = static_cast<JamesDspWrapper*>(userData);
    if(self == nullptr)
    {
        LOGE("JamesDspWrapper::receiveLiveprogStdOut: Self reference is NULL");
        LOGE("JamesDspWrapper::receiveLiveprogStdOut: Unhandled output: %s", buffer);
        return;
    }

    self->env->CallVoidMethod(self->callbackInterface, self->callbackOnLiveprogOutput, self->env->NewStringUTF(buffer));
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *)
{
#ifndef NO_CRASHLYTICS
    firebase::crashlytics::Initialize();
#endif
    LOGD("JNI_OnLoad called")
    return JNI_VERSION_1_6;
}
