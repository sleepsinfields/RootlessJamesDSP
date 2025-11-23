#define STEREOEQ_DEBUG 1   // turn this off when done debugging

#if STEREOEQ_DEBUG
#define TAG "ArbEqConv"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#else
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#define LOGW(...) ((void)0)
#endif


#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"

// ============================================================================
// [RJDSP-LR-EQ] Arbitrary Response EQ – multi-curve subsystem (Master / L / R)
// ============================================================================

// For now this is just a flag layer. Later we can attach separate
// impulse responses / convolvers per curve for real L/R independence.
typedef struct {
    char enabled;    // audio on/off for this curve
} ArbEqCurveFlags;

typedef struct {
    char enabled;           // global subsystem on/off
    ArbEqCurveFlags master; // stereo-linked base curve (L+R)
    ArbEqCurveFlags left;   // extra left-only curve
    ArbEqCurveFlags right;  // extra right-only curve
} ArbEqSubsystemFlags;

// Single instance with defaults: subsystem on, master on, L/R extra off.
static ArbEqSubsystemFlags g_arbEq = {
    .enabled = 1,
    .master = { .enabled = 1 },
    .left   = { .enabled = 0 },
    .right  = { .enabled = 0 },
};


void ArbitraryResponseEqualizerConstructor(JamesDSPLib *jdsp)
{
    jdsp->arbMag.instance.filterLen =
        InitArbitraryEq(&jdsp->arbMag.instance.coeffGen, 0);

    FFTConvolver2x2Init(&jdsp->arbMag.instance.convState);

    // New: three convolvers
    FFTConvolver2x2Init(&jdsp->arbMag.masterConv);
    FFTConvolver2x2Init(&jdsp->arbMag.leftConv);
    FFTConvolver2x2Init(&jdsp->arbMag.rightConv);

    float *kDelta = (float*)malloc(
        jdsp->arbMag.instance.filterLen * sizeof(float));
    memset(kDelta, 0,
           jdsp->arbMag.instance.filterLen * sizeof(float));
    kDelta[0] = 1.0f;

    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.instance.convState,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );

    // Identity IR into all three for now
    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.masterConv,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );

    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.leftConv,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );

    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.rightConv,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );

    free(kDelta);
}

void ArbitraryResponseEqualizerDestructor(JamesDSPLib *jdsp)
{
    EqNodesFree(&jdsp->arbMag.instance.coeffGen);
    FFTConvolver2x2Free(&jdsp->arbMag.instance.convState);

    FFTConvolver2x2Free(&jdsp->arbMag.masterConv);
    FFTConvolver2x2Free(&jdsp->arbMag.leftConv);
    FFTConvolver2x2Free(&jdsp->arbMag.rightConv);
}

void ArbitraryResponseEqualizerStringParser(JamesDSPLib *jdsp, char *stringEq)
{
    // Parse and design the main arbitrary EQ FIR
    ArbitraryEqString2SortedNodes(&jdsp->arbMag.instance.coeffGen, stringEq);
    float *eqFil =
        jdsp->arbMag.instance.coeffGen.GetFilter(
            &jdsp->arbMag.instance.coeffGen,
            (float)jdsp->fs
        );

    // Build a delta (identity) impulse for the "unaffected" channel
    float *kDelta = (float*)malloc(
        jdsp->arbMag.instance.filterLen * sizeof(float));
    memset(kDelta, 0,
           jdsp->arbMag.instance.filterLen * sizeof(float));
    kDelta[0] = 1.0f;

    // Master: same EQ on both channels
    FFTConvolver2x2RefreshImpulseResponse(
        &jdsp->arbMag.instance.convState,
        &jdsp->arbMag.masterConv,
        eqFil, eqFil,
        jdsp->arbMag.instance.filterLen
    );

    // Left-only: EQ on L, identity on R
    FFTConvolver2x2RefreshImpulseResponse(
        &jdsp->arbMag.instance.convState,
        &jdsp->arbMag.leftConv,
        eqFil,      /* left IR */
        kDelta,     /* right stays identity */
        jdsp->arbMag.instance.filterLen
    );

    // Right-only: identity on L, EQ on R
    FFTConvolver2x2RefreshImpulseResponse(
        &jdsp->arbMag.instance.convState,
        &jdsp->arbMag.rightConv,
        kDelta,     /* left stays identity */
        eqFil,      /* right IR */
        jdsp->arbMag.instance.filterLen
    );

    free(kDelta);
}

// ----------------------------------------------------------------------------
// [RJDSP-LR-EQ] Flag control helpers for Master / Left / Right
// ----------------------------------------------------------------------------

// Global subsystem on/off (all three curves)
void ArbitraryResponseEqualizerSetGlobalEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp; // not used yet, but kept for symmetry / future
    g_arbEq.enabled = enable ? 1 : 0;
}

void ArbitraryResponseEqualizerSetMasterEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp;
    g_arbEq.master.enabled = enable ? 1 : 0;
    LOGE("StereoEQ: Master flag set to %d", g_arbEq.master.enabled);
}

// Left-only curve on/off
void ArbitraryResponseEqualizerSetLeftEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp;
    g_arbEq.left.enabled = enable ? 1 : 0;
    LOGE("StereoEQ: Left flag set to %d", g_arbEq.left.enabled);
}

// Right-only curve on/off
void ArbitraryResponseEqualizerSetRightEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp;
    g_arbEq.right.enabled = enable ? 1 : 0;
    LOGE("StereoEQ: Right flag set to %d", g_arbEq.right.enabled);
}

void ArbitraryResponseEqualizerEnable(JamesDSPLib *jdsp, char enable)
{
    if (jdsp->arbMagForceRefresh)
    {
        float *eqFil =
            jdsp->arbMag.instance.coeffGen.GetFilter(
                &jdsp->arbMag.instance.coeffGen,
                (float)jdsp->fs
            );

        float *kDelta = (float*)malloc(
            jdsp->arbMag.instance.filterLen * sizeof(float));
        memset(kDelta, 0,
               jdsp->arbMag.instance.filterLen * sizeof(float));
        kDelta[0] = 1.0f;

        jdsp_lock(jdsp);

        FFTConvolver2x2Free(&jdsp->arbMag.instance.convState);
        FFTConvolver2x2Free(&jdsp->arbMag.masterConv);
        FFTConvolver2x2Free(&jdsp->arbMag.leftConv);
        FFTConvolver2x2Free(&jdsp->arbMag.rightConv);

        FFTConvolver2x2LoadImpulseResponse(
            &jdsp->arbMag.instance.convState,
            (unsigned int)jdsp->blockSize,
            eqFil, eqFil,
            jdsp->arbMag.instance.filterLen
        );

        // Master: EQ on both channels
        FFTConvolver2x2LoadImpulseResponse(
            &jdsp->arbMag.masterConv,
            (unsigned int)jdsp->blockSize,
            eqFil, eqFil,
            jdsp->arbMag.instance.filterLen
        );

        // Left-only: EQ on L, identity on R
        FFTConvolver2x2LoadImpulseResponse(
            &jdsp->arbMag.leftConv,
            (unsigned int)jdsp->blockSize,
            eqFil,
            kDelta,
            jdsp->arbMag.instance.filterLen
        );

        // Right-only: identity on L, EQ on R
        FFTConvolver2x2LoadImpulseResponse(
            &jdsp->arbMag.rightConv,
            (unsigned int)jdsp->blockSize,
            kDelta,
            eqFil,
            jdsp->arbMag.instance.filterLen
        );

        jdsp_unlock(jdsp);
        free(kDelta);

        jdsp->arbMagForceRefresh = 0;
    }

    if (enable)
        jdsp->arbitraryMagEnabled = 1;
}

void ArbitraryResponseEqualizerDisable(JamesDSPLib *jdsp)
{
    jdsp->arbitraryMagEnabled = 0;
}

void ArbitraryResponseEqualizerProcess(JamesDSPLib *jdsp, size_t n)
{
    // If main arbitrary EQ is disabled at jdspController level, this
    // function shouldn't be called, but we keep the check just in case.
    if (!jdsp->arbitraryMagEnabled)
        return;

    if (!g_arbEq.enabled)
        return;

    float *left  = jdsp->tmpBuffer[0];
    float *right = jdsp->tmpBuffer[1];

    // 1) Master curve (stereo)
    if (g_arbEq.master.enabled)
    {
        FFTConvolver2x2Process(
            &jdsp->arbMag.masterConv,
            left, right,
            left, right,
            (unsigned int)n
        );
    }

    // 2) Left-only curve
    if (g_arbEq.left.enabled)
    {
        FFTConvolver2x2Process(
            &jdsp->arbMag.leftConv,
            left, right,
            left, right,
            (unsigned int)n
        );
    }

    // 3) Right-only curve
    if (g_arbEq.right.enabled)
    {
        FFTConvolver2x2Process(
            &jdsp->arbMag.rightConv,
            left, right,
            left, right,
            (unsigned int)n
        );
    }
}