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

// Extra convolvers reserved for left/right curves.
// Right now they are loaded with an identity (delta) IR,
// so they are effectively no-op until we give them real filters.
static FFTConvolver2x2 g_arbLeftConv;
static FFTConvolver2x2 g_arbRightConv;

void ArbitraryResponseEqualizerConstructor(JamesDSPLib *jdsp)
{
    jdsp->arbMag.instance.filterLen =
        InitArbitraryEq(&jdsp->arbMag.instance.coeffGen, 0);

    FFTConvolver2x2Init(&jdsp->arbMag.instance.convState);
    FFTConvolver2x2Init(&jdsp->arbMag.conv);

    // Init extra L/R convolvers
    FFTConvolver2x2Init(&g_arbLeftConv);
    FFTConvolver2x2Init(&g_arbRightConv);

    float *kDelta = (float*)malloc(jdsp->arbMag.instance.filterLen * sizeof(float));
    if (!kDelta)
        return; // allocation failure: safest to bail, rest of engine will likely handle

    memset(kDelta, 0, jdsp->arbMag.instance.filterLen * sizeof(float));
    kDelta[0] = 1.0f;

    // Identity IR for main arbitrary EQ
    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.instance.convState,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );
    FFTConvolver2x2LoadImpulseResponse(
        &jdsp->arbMag.conv,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );

    // Identity IRs for L/R extra curves as well
    FFTConvolver2x2LoadImpulseResponse(
        &g_arbLeftConv,
        (unsigned int)jdsp->blockSize,
        kDelta, kDelta,
        jdsp->arbMag.instance.filterLen
    );
    FFTConvolver2x2LoadImpulseResponse(
        &g_arbRightConv,
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
    FFTConvolver2x2Free(&jdsp->arbMag.conv);

    // Free extra convolvers
    FFTConvolver2x2Free(&g_arbLeftConv);
    FFTConvolver2x2Free(&g_arbRightConv);
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
        &jdsp->arbMag.conv,
        eqFil, eqFil,
        jdsp->arbMag.instance.filterLen
    );

    // NOTE:
    // For now, the extra L/R convolvers keep their identity IRs.
    // Later, we will add separate parsers / strings for L/R curves
    // and load their own IRs into g_arbLeftConv / g_arbRightConv.
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
        FFTConvolver2x2Free(&jdsp->arbMag.conv);

        FFTConvolver2x2LoadImpulseResponse(
            &jdsp->arbMag.instance.convState,
            (unsigned int)jdsp->blockSize,
            eqFil, eqFil,
            jdsp->arbMag.instance.filterLen
        );
        FFTConvolver2x2LoadImpulseResponse(
            &jdsp->arbMag.conv,
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
            &jdsp->arbMag.conv,
            jdsp->tmpBuffer[0], jdsp->tmpBuffer[1],
            jdsp->tmpBuffer[0], jdsp->tmpBuffer[1],
            (unsigned int)n
        );
    }

    // 2) Left-only extra curve
    //    For now, g_arbLeftConv has an identity IR; processing it changes nothing.
    if (g_arbEq.enabled && g_arbEq.left.enabled)
    {
        FFTConvolver2x2Process(
            &g_arbLeftConv,
            jdsp->tmpBuffer[0], jdsp->tmpBuffer[1],
            jdsp->tmpBuffer[0], jdsp->tmpBuffer[1],
            (unsigned int)n
        );
    }

    // 3) Right-only extra curve
    //    Same: identity IR, so currently a no-op.
    if (g_arbEq.enabled && g_arbEq.right.enabled)
    {
        FFTConvolver2x2Process(
            &g_arbRightConv,
            jdsp->tmpBuffer[0], jdsp->tmpBuffer[1],
            jdsp->tmpBuffer[0], jdsp->tmpBuffer[1],
            (unsigned int)n
        );
    }
}