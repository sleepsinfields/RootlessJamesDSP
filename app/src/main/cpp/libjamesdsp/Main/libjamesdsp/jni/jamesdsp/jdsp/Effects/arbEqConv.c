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

    // Refresh main conv state and runtime convolver
    FFTConvolver2x2RefreshImpulseResponse(
    &jdsp->arbMag.instance.convState,
    &jdsp->arbMag.masterConv,
    eqFil, eqFil,
    jdsp->arbMag.instance.filterLen
);

// NOTE:
// For now, the extra L/R convolvers keep their identity IRs.
// Later, we will add separate parsers / strings for L/R curves
// and load their own IRs into jdsp->arbMag.leftConv / rightConv.
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

// Master (L+R) curve on/off
void ArbitraryResponseEqualizerSetMasterEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp;
    g_arbEq.master.enabled = enable ? 1 : 0;
}

// Left-only curve on/off
void ArbitraryResponseEqualizerSetLeftEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp;
    g_arbEq.left.enabled = enable ? 1 : 0;
}

// Right-only curve on/off
void ArbitraryResponseEqualizerSetRightEnabled(JamesDSPLib *jdsp, char enable)
{
    (void)jdsp;
    g_arbEq.right.enabled = enable ? 1 : 0;
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

    jdsp_lock(jdsp);

    FFTConvolver2x2Free(&jdsp->arbMag.instance.convState);
    FFTConvolver2x2Free(&jdsp->arbMag.masterConv);

    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.instance.convState,
        (unsigned int)jdsp->blockSize,
        eqFil, eqFil,
        jdsp->arbMag.instance.filterLen
    );

    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.masterConv,
        (unsigned int)jdsp->blockSize,
        eqFil, eqFil,
        jdsp->arbMag.instance.filterLen
    );

    // L/R convolvers remain as identity for now.

    jdsp_unlock(jdsp);
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

    // 1) Master curve (current behavior)
    if (g_arbEq.enabled && g_arbEq.master.enabled)
{
    FFTConvolver2x2Process(
        &jdsp->arbMag.masterConv,
        jdsp->tmpBuffer[0], jdsp->tmpBuffer[1],
        jdsp->tmpBuffer[0], jdsp->tmpBuffer[1],
        (unsigned int)n
    );
}
}