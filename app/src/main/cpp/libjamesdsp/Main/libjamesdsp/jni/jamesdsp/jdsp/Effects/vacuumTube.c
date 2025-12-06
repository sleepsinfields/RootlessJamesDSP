#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"

#define VT_EVEN_GAIN 1.0   // global gain for even harmonics (2,4)
#define VT_ODD_GAIN  1.0   // global gain for odd harmonics  (3,5)

// 
// ------------------------------------------------------------
// Triode-like soft clipper using tube params
// ------------------------------------------------------------
static inline double vt_triodeshape(double x, const VacuumTube *tb)
{
    // these come from VTInit defaults and can be changed at runtime
    const double drive = (double)tb->triodeDrive;
    const double bias  = (double)tb->triodeBias;
    const double scale = (double)tb->triodeScale;

    double v = (x + bias) * drive;
    double y = tanh(v);
    return y * scale;
}

// ------------------------------------------------------------
// Init
// ------------------------------------------------------------

void VTInit(VacuumTube *tb, double fs)
{
    tb->pregain = 1.0f;
    tb->postgain = 1.0f;
    tb->needOversample = 0;
    tb->shapeMix = 0.3f;   // your triode mix

    // New: start with neutral even/odd balance
    tb->evenGain = (float)VT_EVEN_GAIN;
    tb->oddGain  = (float)VT_ODD_GAIN;

    // Triode shape parameters (your previous constants)
    tb->triodeDrive = 2.5f;
    tb->triodeBias  = 0.0f;
    tb->triodeScale = 0.5f;

    // Shared harmonic scale factor (replaces separate 0.20 / 0.25)
    tb->harmScale = 0.20f;


    // Oversampling setup (same logic you had, plus 2x at very high fs)
    if (fs >= 65000.0)
    {
        oversample_makeSmp(&tb->smp[0], 2);
        oversample_makeSmp(&tb->smp[1], 2);
        tb->needOversample = 1;
    }
    else if (fs >= 30000.0 && fs < 65000.0)
    {
        oversample_makeSmp(&tb->smp[0], 2);
        oversample_makeSmp(&tb->smp[1], 2);
        tb->needOversample = 1;
    }
    else if (fs >= 20000.0 && fs < 30000.0)
    {
        oversample_makeSmp(&tb->smp[0], 3);
        oversample_makeSmp(&tb->smp[1], 3);
        tb->needOversample = 1;
    }
    else if (fs >= 14000.0 && fs < 20000.0)
    {
        oversample_makeSmp(&tb->smp[0], 4);
        oversample_makeSmp(&tb->smp[1], 4);
        tb->needOversample = 1;
    }
    else // fs < 14000
    {
        oversample_makeSmp(&tb->smp[0], 5);
        oversample_makeSmp(&tb->smp[1], 5);
        tb->needOversample = 1;
    }

    memset(tb->subband, 0, sizeof(tb->subband));
    init6BandsCrossover(&tb->subband[0], fs, 300.0, 950.0, 2200.0, 4000.0, 6000.0);
    init6BandsCrossover(&tb->subband[1], fs, 300.0, 950.0, 2200.0, 4000.0, 6000.0);
}

// ------------------------------------------------------------
// Process
// ------------------------------------------------------------
void VTProcess(VacuumTube *tb, float *x1, float *x2, float *out1, float *out2, size_t n)
{
    float  upsample[2][5];  // up to 5x oversample
    double bandCh1[6], bandCh2[6];

    if (tb->needOversample)
    {
        // -------- Oversampled branch --------
        for (size_t i = 0; i < n; i++)
        {
            char state1[6] = { 0 };
            char state2[6] = { 0 };

            oversample_stepupSmp(&tb->smp[0], x1[i] * tb->pregain, upsample[0]);
            oversample_stepupSmp(&tb->smp[1], x2[i] * tb->pregain, upsample[1]);

            for (int j = 0; j < tb->smp[0].factor; j++)
            {
                process6BandsCrossover(
                    &tb->subband[0],
                    upsample[0][j],
                    &bandCh1[0], &bandCh1[1], &bandCh1[2],
                    &bandCh1[3], &bandCh1[4], &bandCh1[5]
                );
                process6BandsCrossover(
                    &tb->subband[1],
                    upsample[1][j],
                    &bandCh2[0], &bandCh2[1], &bandCh2[2],
                    &bandCh2[3], &bandCh2[4], &bandCh2[5]
                );

                // original sign flips
                bandCh1[1] = -bandCh1[1];
                bandCh1[3] = -bandCh1[3];
                bandCh1[5] = -bandCh1[5];
                bandCh2[1] = -bandCh2[1];
                bandCh2[3] = -bandCh2[3];
                bandCh2[5] = -bandCh2[5];

// Per-band tube shaping on ALL bands using shapeMix
if (tb->shapeMix > 0.0f)
{
    double mix = 1 * (double)tb->shapeMix;
    for (int b = 0; b < 6; ++b)
    {
        double dry1 = bandCh1[b];
        double dry2 = bandCh2[b];

        double tri1 = vt_triodeshape(dry1, tb);
        double tri2 = vt_triodeshape(dry2, tb);

        bandCh1[b] = (1.0 - mix) * dry1 + mix * tri1;
        bandCh2[b] = (1.0 - mix) * dry2 + mix * tri2;
    }
}
//
                // sum of middle bands
                double allpassCh1 = bandCh1[1] + bandCh1[2] + bandCh1[3] + bandCh1[4];
                double allpassCh2 = bandCh2[1] + bandCh2[2] + bandCh2[3] + bandCh2[4];

                // original harmonic “exciter” part
                double harmonic2Ch1 = bandCh1[1] * bandCh1[1];
double harmonic3Ch1 = bandCh1[2] * bandCh1[2];
double harmonic4Ch1 = bandCh1[3] * bandCh1[3];
double harmonic5Ch1 = bandCh1[4] * bandCh1[4];

double harmonic2Ch2 = bandCh2[1] * bandCh2[1];
double harmonic3Ch2 = bandCh2[2] * bandCh2[2];
double harmonic4Ch2 = bandCh2[3] * bandCh2[3];
double harmonic5Ch2 = bandCh2[4] * bandCh2[4];

// split even / odd for oversampled core too
double harmEvenCh1 = (harmonic2Ch1 + harmonic4Ch1) * tb->evenGain;
double harmOddCh1  = (harmonic3Ch1 + harmonic5Ch1) * tb->oddGain;

double harmEvenCh2 = (harmonic2Ch2 + harmonic4Ch2) * tb->evenGain;
double harmOddCh2  = (harmonic3Ch2 + harmonic5Ch2) * tb->oddGain;

double harmCh1 = (harmEvenCh1 + harmOddCh1) * (double)tb->harmScale;
double harmCh2 = (harmEvenCh2 + harmOddCh2) * (double)tb->harmScale;

// --- triode core on allpass (second core) ---
    double coreInCh1 = allpassCh1;
    double coreInCh2 = allpassCh2;

    double triodeCh1 = vt_triodeshape(coreInCh1, tb);
    double triodeCh2 = vt_triodeshape(coreInCh2, tb);

    double coreOutCh1 =
        (1.0 - tb->shapeMix) * coreInCh1 +
        tb->shapeMix * triodeCh1;

    double coreOutCh2 =
        (1.0 - tb->shapeMix) * coreInCh2 +
        tb->shapeMix * triodeCh2;

    // final oversampled sample before downsampling
    double wetCh1 = bandCh1[0] + coreOutCh1 + bandCh1[5] + harmCh1;
    double wetCh2 = bandCh2[0] + coreOutCh2 + bandCh2[5] + harmCh2;

    upsample[0][j] = (float)wetCh1;
    upsample[1][j] = (float)wetCh2;
            }

            out1[i] = oversample_stepdownSmpFloat(&tb->smp[0], upsample[0]) * tb->postgain;
            out2[i] = oversample_stepdownSmpFloat(&tb->smp[1], upsample[1]) * tb->postgain;
        }
    }
    else
    {
        // -------- Non-oversampled branch (single rate) --------
        for (size_t j = 0; j < n; j++)
        {
            process6BandsCrossover(
                &tb->subband[0],
                x1[j] * tb->pregain,
                &bandCh1[0], &bandCh1[1], &bandCh1[2],
                &bandCh1[3], &bandCh1[4], &bandCh1[5]
            );
            process6BandsCrossover(
                &tb->subband[1],
                x2[j] * tb->pregain,
                &bandCh2[0], &bandCh2[1], &bandCh2[2],
                &bandCh2[3], &bandCh2[4], &bandCh2[5]
            );

            bandCh1[1] = -bandCh1[1];
            bandCh1[3] = -bandCh1[3];
            bandCh1[5] = -bandCh1[5];
            bandCh2[1] = -bandCh2[1];
            bandCh2[3] = -bandCh2[3];
            bandCh2[5] = -bandCh2[5];
// Per-band tube shaping on ALL bands using shapeMix
if (tb->shapeMix > 0.0f)
{
    double mix = 1 * (double)tb->shapeMix;
    for (int b = 0; b < 6; ++b)
    {
        double dry1 = bandCh1[b];
        double dry2 = bandCh2[b];

        double tri1 = vt_triodeshape(dry1, tb);
        double tri2 = vt_triodeshape(dry2, tb);

        bandCh1[b] = (1.0 - mix) * dry1 + mix * tri1;
        bandCh2[b] = (1.0 - mix) * dry2 + mix * tri2;
    }
}
//
            double allpassCh1 = bandCh1[1] + bandCh1[2] + bandCh1[3] + bandCh1[4];
            double allpassCh2 = bandCh2[1] + bandCh2[2] + bandCh2[3] + bandCh2[4];

            // --- harmonic energy per band ---
double harmonic2Ch1 = bandCh1[1] * bandCh1[1];
double harmonic3Ch1 = bandCh1[2] * bandCh1[2];
double harmonic4Ch1 = bandCh1[3] * bandCh1[3];
double harmonic5Ch1 = bandCh1[4] * bandCh1[4];

double harmonic2Ch2 = bandCh2[1] * bandCh2[1];
double harmonic3Ch2 = bandCh2[2] * bandCh2[2];
double harmonic4Ch2 = bandCh2[3] * bandCh2[3];
double harmonic5Ch2 = bandCh2[4] * bandCh2[4];

// --- EVEN / ODD SPLIT ---
// even: 2nd + 4th, odd: 3rd + 5th
double harmEvenCh1 = (harmonic2Ch1 + harmonic4Ch1) * tb->evenGain;
double harmOddCh1  = (harmonic3Ch1 + harmonic5Ch1) * tb->oddGain;

double harmEvenCh2 = (harmonic2Ch2 + harmonic4Ch2) * tb->evenGain;
double harmOddCh2  = (harmonic3Ch2 + harmonic5Ch2) * tb->oddGain;

// base scaling for harmonic contribution

double harmCh1 = (harmEvenCh1 + harmOddCh1) * (double)tb->harmScale;
double harmCh2 = (harmEvenCh2 + harmOddCh2) * (double)tb->harmScale;

// --- triode core mix (unchanged) ---
double coreInCh1 = allpassCh1;
double coreInCh2 = allpassCh2;

double triodeCh1 = vt_triodeshape(coreInCh1, tb);
double triodeCh2 = vt_triodeshape(coreInCh2, tb);

double coreOutCh1 =
    (1.0 - tb->shapeMix) * coreInCh1 +
    tb->shapeMix * triodeCh1;

double coreOutCh2 =
    (1.0 - tb->shapeMix) * coreInCh2 +
    tb->shapeMix * triodeCh2;

// final wet sample
double wetCh1 = bandCh1[0] + coreOutCh1 + bandCh1[5] + harmCh1;
double wetCh2 = bandCh2[0] + coreOutCh2 + bandCh2[5] + harmCh2;

out1[j] = (float)wetCh1 * tb->postgain;
out2[j] = (float)wetCh2 * tb->postgain;
        }
    }
}

// ------------------------------------------------------------
// Public API
// ------------------------------------------------------------
void VacuumTubeEnable(JamesDSPLib *jdsp)
{
    VTInit(&jdsp->tube, jdsp->fs);
    jdsp->tubeEnabled = 1;
}

void VacuumTubeDisable(JamesDSPLib *jdsp)
{
    jdsp->tubeEnabled = 0;
}

void VacuumTubeSetGain(JamesDSPLib *jdsp, double dbGain)
{
    if (dbGain > 12.0)
        dbGain = 12.0;
    if (dbGain < -3.0)
        dbGain = -3.0;

    jdsp->tube.pregain  = db2magf(dbGain);
    jdsp->tube.postgain = 1.0f / jdsp->tube.pregain;
}

void VacuumTubeSetShape(JamesDSPLib *jdsp, double mix)
{
    if (mix < 0.0)
        mix = 0.0;
    if (mix > 1.0)
        mix = 1.0;

    jdsp->tube.shapeMix = (float)mix;
}

void VacuumTubeProcess(JamesDSPLib *jdsp, size_t n)
{
    VTProcess(&jdsp->tube,
              jdsp->tmpBuffer[0], jdsp->tmpBuffer[1],
              jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n);
}

void VacuumTubeSetHarmonics(JamesDSPLib *jdsp, double even, double odd)
{
    if (even < 0.0) even = 0.0;
    if (odd  < 0.0) odd  = 0.0;

    jdsp->tube.evenGain = (float)even;
    jdsp->tube.oddGain  = (float)odd;
}

void VacuumTubeSetTriodeParams(JamesDSPLib *jdsp,
                               double drive,
                               double bias,
                               double scale)
{
    // Simple sanity guards, you can tune these ranges later
    if (drive < 0.0) drive = 0.0;
    if (scale < 0.0) scale = 0.0;

    jdsp->tube.triodeDrive = (float)drive;
    jdsp->tube.triodeBias  = (float)bias;
    jdsp->tube.triodeScale = (float)scale;
}

void VacuumTubeSetHarmScale(JamesDSPLib *jdsp, double harmScale)
{
    if (harmScale < 0.0) harmScale = 0.0;
    jdsp->tube.harmScale = (float)harmScale;
}