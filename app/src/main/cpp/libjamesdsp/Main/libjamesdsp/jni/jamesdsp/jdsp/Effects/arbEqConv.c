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
// convolution/filter state per curve if we want full independent IRs.
typedef struct {
    char enabled;    // audio on/off for this curve
} ArbEqCurveFlags;

typedef struct {
    char enabled;        // global subsystem on/off
    ArbEqCurveFlags master;  // stereo-linked base curve (L+R)
    ArbEqCurveFlags left;    // extra left-only curve
    ArbEqCurveFlags right;   // extra right-only curve
} ArbEqSubsystemFlags;

// Single instance with defaults: subsystem on, master on, L/R extra off.
static ArbEqSubsystemFlags g_arbEq = {
    .enabled = 1,
    .master = { .enabled = 1 },
    .left   = { .enabled = 0 },
    .right  = { .enabled = 0 },
};
// end new section

void ArbitraryResponseEqualizerConstructor(JamesDSPLib *jdsp)
{
	jdsp->arbMag.instance.filterLen = InitArbitraryEq(&jdsp->arbMag.instance.coeffGen, 0);
	FFTConvolver2x2Init(&jdsp->arbMag.instance.convState);
	FFTConvolver2x2Init(&jdsp->arbMag.conv);
	float *kDelta = (float*)malloc(jdsp->arbMag.instance.filterLen * sizeof(float));
	memset(kDelta, 0, jdsp->arbMag.instance.filterLen * sizeof(float));
	kDelta[0] = 1.0f;
	FFTConvolver2x2LoadImpulseResponse(&jdsp->arbMag.instance.convState, (unsigned int)jdsp->blockSize, kDelta, kDelta, jdsp->arbMag.instance.filterLen);
	FFTConvolver2x2LoadImpulseResponse(&jdsp->arbMag.conv, (unsigned int)jdsp->blockSize, kDelta, kDelta, jdsp->arbMag.instance.filterLen);
	free(kDelta);
}
void ArbitraryResponseEqualizerDestructor(JamesDSPLib *jdsp)
{
	EqNodesFree(&jdsp->arbMag.instance.coeffGen);
	FFTConvolver2x2Free(&jdsp->arbMag.instance.convState);
	FFTConvolver2x2Free(&jdsp->arbMag.conv);
}
void ArbitraryResponseEqualizerStringParser(JamesDSPLib *jdsp, char *stringEq)
{
	ArbitraryEqString2SortedNodes(&jdsp->arbMag.instance.coeffGen, stringEq);
	float *eqFil = jdsp->arbMag.instance.coeffGen.GetFilter(&jdsp->arbMag.instance.coeffGen, (float)jdsp->fs);
	FFTConvolver2x2RefreshImpulseResponse(&jdsp->arbMag.instance.convState, &jdsp->arbMag.conv, eqFil, eqFil, jdsp->arbMag.instance.filterLen);
}
void ArbitraryResponseEqualizerEnable(JamesDSPLib *jdsp, char enable)
{
	if (jdsp->arbMagForceRefresh)
	{
		float *eqFil = jdsp->arbMag.instance.coeffGen.GetFilter(&jdsp->arbMag.instance.coeffGen, (float)jdsp->fs);
		jdsp_lock(jdsp);
		FFTConvolver2x2Free(&jdsp->arbMag.instance.convState);
		FFTConvolver2x2Free(&jdsp->arbMag.conv);
		FFTConvolver2x2LoadImpulseResponse(&jdsp->arbMag.instance.convState, (unsigned int)jdsp->blockSize, eqFil, eqFil, jdsp->arbMag.instance.filterLen);
		FFTConvolver2x2LoadImpulseResponse(&jdsp->arbMag.conv, (unsigned int)jdsp->blockSize, eqFil, eqFil, jdsp->arbMag.instance.filterLen);
		jdsp_unlock(jdsp);
		jdsp->arbMagForceRefresh = 0;
	}
	if (enable)
		jdsp->arbitraryMagEnabled = 1;
}
// 
void ArbitraryResponseEqualizerDisable(JamesDSPLib *jdsp)
{
    jdsp->arbitraryMagEnabled = 0;
}
 ----------------------------------------------------------------------------
// [RJDSP-LR-EQ] Multi-curve processing hook (placeholder)
// ----------------------------------------------------------------------------
static void ArbEqProcessSubsystem(JamesDSPLib *jdsp, size_t n)
{
    // This is intentionally a no-op right now. In later steps we will:
    //  - use g_arbEq.master/left/right.enabled to decide which curves run
    //  - apply extra left-only / right-only convolvers after the master.
    (void)jdsp;
    (void)n;
}

void ArbitraryResponseEqualizerProcess(JamesDSPLib *jdsp, size_t n)
{
    // Existing master arbitrary response EQ (unchanged behavior).
    FFTConvolver2x2Process(&jdsp->arbMag.conv,
                           jdsp->tmpBuffer[0],
                           jdsp->tmpBuffer[1],
                           jdsp->tmpBuffer[0],
                           jdsp->tmpBuffer[1],
                           (unsigned int)n);

    // Hook for extra Left/Right-only curves (currently a no-op).
    ArbEqProcessSubsystem(jdsp, n);
}