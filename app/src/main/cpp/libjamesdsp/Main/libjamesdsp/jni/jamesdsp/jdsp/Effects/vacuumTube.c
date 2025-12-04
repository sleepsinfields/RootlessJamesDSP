#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"

void VTInit(VacuumTube *tb, double fs)
{
    tb->pregain = 1.0f;
    tb->postgain = 1.0f;
    tb->needOversample = 0;

    // NEW: blend default
    tb->shapeMix = 0.5; // 0 = square, 1 = cube

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
	if (tb->needOversample)
	{
		for (size_t i = 0; i < n; i++)
		{
			char state1[6] = { 0 };
			char state2[6] = { 0 };
			oversample_stepupSmp(&tb->smp[0], x1[i] * tb->pregain, upsample[0]);
			oversample_stepupSmp(&tb->smp[1], x2[i] * tb->pregain, upsample[1]);
			for (int j = 0; j < tb->smp[0].factor; j++)
			{
				process6BandsCrossover(&tb->subband[0], upsample[0][j], &bandCh1[0], &bandCh1[1], &bandCh1[2], &bandCh1[3], &bandCh1[4], &bandCh1[5]);
				process6BandsCrossover(&tb->subband[1], upsample[1][j], &bandCh2[0], &bandCh2[1], &bandCh2[2], &bandCh2[3], &bandCh2[4], &bandCh2[5]);
				bandCh1[1] = -bandCh1[1];
				bandCh1[3] = -bandCh1[3];
				bandCh1[5] = -bandCh1[5];
				bandCh2[1] = -bandCh2[1];
				bandCh2[3] = -bandCh2[3];
				bandCh2[5] = -bandCh2[5];
//
				double allpassCh1 = bandCh1[1] + bandCh1[2] + bandCh1[3] + bandCh1[4];
double allpassCh2 = bandCh2[1] + bandCh2[2] + bandCh2[3] + bandCh2[4];

// --- squared (original tube flavor) ---
double h2_2_sq = bandCh1[1] * bandCh1[1];
double h3_2_sq = bandCh1[2] * bandCh1[2];
double h4_2_sq = bandCh1[3] * bandCh1[3];
double h5_2_sq = bandCh1[4] * bandCh1[4];
double evenSumCh1 = h2_2_sq + h3_2_sq + h4_2_sq + h5_2_sq;

double h2_2_sq_R = bandCh2[1] * bandCh2[1];
double h3_2_sq_R = bandCh2[2] * bandCh2[2];
double h4_2_sq_R = bandCh2[3] * bandCh2[3];
double h5_2_sq_R = bandCh2[4] * bandCh2[4];
double evenSumCh2 = h2_2_sq_R + h3_2_sq_R + h4_2_sq_R + h5_2_sq_R;

// --- cubic (more aggressive / “electric”) ---
double h2_3_cu = h2_2_sq * bandCh1[1];
double h3_3_cu = h3_2_sq * bandCh1[2];
double h4_3_cu = h4_2_sq * bandCh1[3];
double h5_3_cu = h5_2_sq * bandCh1[4];
double oddSumCh1  = h2_3_cu + h3_3_cu + h4_3_cu + h5_3_cu;

double h2_3_cu_R = h2_2_sq_R * bandCh2[1];
double h3_3_cu_R = h3_2_sq_R * bandCh2[2];
double h4_3_cu_R = h4_2_sq_R * bandCh2[3];
double h5_3_cu_R = h5_2_sq_R * bandCh2[4];
double oddSumCh2  = h2_3_cu_R + h3_3_cu_R + h4_3_cu_R + h5_3_cu_R;

// --- blend square vs cube ----
double mix = tb->shapeMix;           // 0 = square, 1 = cube
double harmCh1 = (1.0 - mix) * evenSumCh1 + mix * oddSumCh1;
double harmCh2 = (1.0 - mix) * evenSumCh2 + mix * oddSumCh2;

upsample[0][j] = (float)(
    bandCh1[0]
    + harmCh1 * 0.2
    + allpassCh1
    + bandCh1[5]
);
upsample[1][j] = (float)(
    bandCh2[0]
    + harmCh2 * 0.2
    + allpassCh2
    + bandCh2[5]
);
//
			}
			out1[i] = oversample_stepdownSmpFloat(&tb->smp[0], upsample[0]) * tb->postgain;
			out2[i] = oversample_stepdownSmpFloat(&tb->smp[1], upsample[1]) * tb->postgain;
		}
	}
	else
	{
		for (size_t j = 0; j < n; j++)
		{
			process6BandsCrossover(&tb->subband[0], x1[j] * tb->pregain, &bandCh1[0], &bandCh1[1], &bandCh1[2], &bandCh1[3], &bandCh1[4], &bandCh1[5]);
			process6BandsCrossover(&tb->subband[1], x2[j] * tb->pregain, &bandCh2[0], &bandCh2[1], &bandCh2[2], &bandCh2[3], &bandCh2[4], &bandCh2[5]);
			bandCh1[1] = -bandCh1[1];
			bandCh1[3] = -bandCh1[3];
			bandCh1[5] = -bandCh1[5];
			bandCh2[1] = -bandCh2[1];
			bandCh2[3] = -bandCh2[3];
			bandCh2[5] = -bandCh2[5];
//
			double allpassCh1 = bandCh1[1] + bandCh1[2] + bandCh1[3] + bandCh1[4];
double allpassCh2 = bandCh2[1] + bandCh2[2] + bandCh2[3] + bandCh2[4];

// squared
double h2_2_sq = bandCh1[1] * bandCh1[1];
double h3_2_sq = bandCh1[2] * bandCh1[2];
double h4_2_sq = bandCh1[3] * bandCh1[3];
double h5_2_sq = bandCh1[4] * bandCh1[4];
double evenSumCh1 = h2_2_sq + h3_2_sq + h4_2_sq + h5_2_sq;

double h2_2_sq_R = bandCh2[1] * bandCh2[1];
double h3_2_sq_R = bandCh2[2] * bandCh2[2];
double h4_2_sq_R = bandCh2[3] * bandCh2[3];
double h5_2_sq_R = bandCh2[4] * bandCh2[4];
double evenSumCh2 = h2_2_sq_R + h3_2_sq_R + h4_2_sq_R + h5_2_sq_R;

// cubic
double h2_3_cu = h2_2_sq * bandCh1[1];
double h3_3_cu = h3_2_sq * bandCh1[2];
double h4_3_cu = h4_2_sq * bandCh1[3];
double h5_3_cu = h5_2_sq * bandCh1[4];
double oddSumCh1  = h2_3_cu + h3_3_cu + h4_3_cu + h5_3_cu;

double h2_3_cu_R = h2_2_sq_R * bandCh2[1];
double h3_3_cu_R = h3_2_sq_R * bandCh2[2];
double h4_3_cu_R = h4_2_sq_R * bandCh2[3];
double h5_3_cu_R = h5_2_sq_R * bandCh2[4];
double oddSumCh2  = h2_3_cu_R + h3_3_cu_R + h4_3_cu_R + h5_3_cu_R;

// blend
double mix = tb->shapeMix;
double harmCh1 = (1.0 - mix) * evenSumCh1 + mix * oddSumCh1;
double harmCh2 = (1.0 - mix) * evenSumCh2 + mix * oddSumCh2;

out1[j] = (float)(
    bandCh1[0]
    + harmCh1 * 0.25f
    + allpassCh1
    + bandCh1[5]
) * tb->postgain;

out2[j] = (float)(
    bandCh2[0]
    + harmCh2 * 0.25f
    + allpassCh2
    + bandCh2[5]
) * tb->postgain;
//
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
void VacuumTubeProcess(JamesDSPLib *jdsp, size_t n)
{
	VTProcess(&jdsp->tube, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n);
}