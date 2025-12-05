#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"

// Normalization so square and cube shapes have roughly similar RMS
#define VT_NORM_SQ 2.0                         // ~1 / 0.5
#define VT_NORM_CU 1.3333333333333333          // ~1 / 0.75

// x in [-1,1], mix in [0,1]
// mix = 0 -> mostly squared (more even harmonics)
// mix = 1 -> more cubed (more odd harmonics)
static inline double vt_nonlinear(double x, double mix)
{
    // x^2 and x^3
    double sq = x * x;
    double cu = sq * x;

    // normalize RMS
    double sq_norm = sq * VT_NORM_SQ;
    double cu_norm = cu * VT_NORM_CU;

    // crossfade
    return (1.0 - mix) * sq_norm + mix * cu_norm;
}

void VTInit(VacuumTube *tb, double fs)
{
    tb->pregain = 1.0f;
    tb->postgain = 1.0f;
    tb->needOversample = 0;
    tb->shapeMix = 0.0f;  // default: square-only, matches original sound

    // Original oversampling scheme (kept logic, just slightly clarified)
    if (fs >= 30000.0 && fs < 65000.0)
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
    else if (fs < 14000.0)
    {
        oversample_makeSmp(&tb->smp[0], 5);
        oversample_makeSmp(&tb->smp[1], 5);
        tb->needOversample = 1;
    }

    memset(tb->subband, 0, sizeof(tb->subband));
    init6BandsCrossover(&tb->subband[0], fs, 300.0, 950.0, 2200.0, 4000.0, 6000.0);
    init6BandsCrossover(&tb->subband[1], fs, 300.0, 950.0, 2200.0, 4000.0, 6000.0);
}

void VTProcess(VacuumTube *tb, float *x1, float *x2, float *out1, float *out2, size_t n)
{
    float upsample[2][5];
    double bandCh1[6], bandCh2[6];

    // ---------- Oversampled path ----------
    if (tb->needOversample)
    {
        for (size_t i = 0; i < n; i++)
        {
            char state1[6] = { 0 };
            char state2[6] = { 0 };
            (void)state1;
            (void)state2;

            oversample_stepupSmp(&tb->smp[0], x1[i] * tb->pregain, upsample[0]);
            oversample_stepupSmp(&tb->smp[1], x2[i] * tb->pregain, upsample[1]);

            for (int j = 0; j < tb->smp[0].factor; j++)
            {
                process6BandsCrossover(&tb->subband[0], upsample[0][j],
                                       &bandCh1[0], &bandCh1[1], &bandCh1[2],
                                       &bandCh1[3], &bandCh1[4], &bandCh1[5]);
                process6BandsCrossover(&tb->subband[1], upsample[1][j],
                                       &bandCh2[0], &bandCh2[1], &bandCh2[2],
                                       &bandCh2[3], &bandCh2[4], &bandCh2[5]);

                // sign flips like original code
                bandCh1[1] = -bandCh1[1];
                bandCh1[3] = -bandCh1[3];
                bandCh1[5] = -bandCh1[5];
                bandCh2[1] = -bandCh2[1];
                bandCh2[3] = -bandCh2[3];
                bandCh2[5] = -bandCh2[5];

                double allpassCh1 = bandCh1[1] + bandCh1[2] + bandCh1[3] + bandCh1[4];
                double allpassCh2 = bandCh2[1] + bandCh2[2] + bandCh2[3] + bandCh2[4];

                // Instead of pure squares, run the bands through the blended nonlinearity
                double harmonic2Ch1 = vt_nonlinear(bandCh1[1], tb->shapeMix);
                double harmonic3Ch1 = vt_nonlinear(bandCh1[2], tb->shapeMix);
                double harmonic4Ch1 = vt_nonlinear(bandCh1[3], tb->shapeMix);
                double harmonic5Ch1 = vt_nonlinear(bandCh1[4], tb->shapeMix);

                double harmonic2Ch2 = vt_nonlinear(bandCh2[1], tb->shapeMix);
                double harmonic3Ch2 = vt_nonlinear(bandCh2[2], tb->shapeMix);
                double harmonic4Ch2 = vt_nonlinear(bandCh2[3], tb->shapeMix);
                double harmonic5Ch2 = vt_nonlinear(bandCh2[4], tb->shapeMix);

                // Same structure as original: dry band[0] + boosted harmonics + allpass + band[5]
                double baseCh1 = bandCh1[0] + allpassCh1 + bandCh1[5];
                double harmCh1 = (harmonic2Ch1 + harmonic3Ch1 +
                                  harmonic4Ch1 + harmonic5Ch1) * 0.2;

                double baseCh2 = bandCh2[0] + allpassCh2 + bandCh2[5];
                double harmCh2 = (harmonic2Ch2 + harmonic3Ch2 +
                                  harmonic4Ch2 + harmonic5Ch2) * 0.2;

                upsample[0][j] = (float)(baseCh1 + harmCh1);
                upsample[1][j] = (float)(baseCh2 + harmCh2);
            }

            out1[i] = oversample_stepdownSmpFloat(&tb->smp[0], upsample[0]) * tb->postgain;
            out2[i] = oversample_stepdownSmpFloat(&tb->smp[1], upsample[1]) * tb->postgain;
        }
    }
    // ---------- Non-oversampled path ----------
    else
    {
        for (size_t j = 0; j < n; j++)
        {
            process6BandsCrossover(&tb->subband[0], x1[j] * tb->pregain,
                                   &bandCh1[0], &bandCh1[1], &bandCh1[2],
                                   &bandCh1[3], &bandCh1[4], &bandCh1[5]);
            process6BandsCrossover(&tb->subband[1], x2[j] * tb->pregain,
                                   &bandCh2[0], &bandCh2[1], &bandCh2[2],
                                   &bandCh2[3], &bandCh2[4], &bandCh2[5]);

            bandCh1[1] = -bandCh1[1];
            bandCh1[3] = -bandCh1[3];
            bandCh1[5] = -bandCh1[5];
            bandCh2[1] = -bandCh2[1];
            bandCh2[3] = -bandCh2[3];
            bandCh2[5] = -bandCh2[5];

            double allpassCh1 = bandCh1[1] + bandCh1[2] + bandCh1[3] + bandCh1[4];
            double allpassCh2 = bandCh2[1] + bandCh2[2] + bandCh2[3] + bandCh2[4];

            double harmonic2Ch1 = vt_nonlinear(bandCh1[1], tb->shapeMix);
            double harmonic3Ch1 = vt_nonlinear(bandCh1[2], tb->shapeMix);
            double harmonic4Ch1 = vt_nonlinear(bandCh1[3], tb->shapeMix);
            double harmonic5Ch1 = vt_nonlinear(bandCh1[4], tb->shapeMix);

            double harmonic2Ch2 = vt_nonlinear(bandCh2[1], tb->shapeMix);
            double harmonic3Ch2 = vt_nonlinear(bandCh2[2], tb->shapeMix);
            double harmonic4Ch2 = vt_nonlinear(bandCh2[3], tb->shapeMix);
            double harmonic5Ch2 = vt_nonlinear(bandCh2[4], tb->shapeMix);

            double baseCh1 = bandCh1[0] + allpassCh1 + bandCh1[5];
            double harmCh1 = (harmonic2Ch1 + harmonic3Ch1 +
                              harmonic4Ch1 + harmonic5Ch1) * 0.25;

            double baseCh2 = bandCh2[0] + allpassCh2 + bandCh2[5];
            double harmCh2 = (harmonic2Ch2 + harmonic3Ch2 +
                              harmonic4Ch2 + harmonic5Ch2) * 0.25;

            out1[j] = (float)(baseCh1 + harmCh1) * tb->postgain;
            out2[j] = (float)(baseCh2 + harmCh2) * tb->postgain;
        }
    }
}

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

    jdsp->tube.pregain = db2magf(dbGain);
    jdsp->tube.postgain = 1.0f / jdsp->tube.pregain;
}

// Shape mix setter: 0 -> square-like, 1 -> cube-like
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
              jdsp->tmpBuffer[0], jdsp->tmpBuffer[1],
              n);
}