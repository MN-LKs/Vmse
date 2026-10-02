package com.vmse.audio;

/**
 * Lightweight DSP engine for the Vmse effect presets.
 * <p>
 * Performs, on 48k PCM pairs (or mono):
 * <ul>
 *   <li>3-band shelf (bass / mid / treble) using one-pole filters</li>
 *   <li>mid-side virtual widening ("space") for stereo material</li>
 *   <li>soft-knee limiter so heavy presets never clip</li>
 * </ul>
 * Everything is designed to be cheap (a few multiplies per sample) so it can
 * run inside the audio-supplier path without causing frame drops.
 */
public final class DspEngine {

    private DspEngine() {
    }

    // one-pole filter state, reset per buffer
    private static final class State {
        float lp = 0f; // low-pass (bass)
        float hp = 0f; // high-pass residue (treble)
    }

    /**
     * Apply a preset to a stereo pair (or mono by mirroring). In-place.
     *
     * @param left   left channel PCM (len == right.length when stereo)
     * @param right  right channel PCM, or null for mono (mirrored internally)
     * @param fx     preset to apply (EffectPreset.NONE is a no-op)
     */
    public static void apply(short[] left, short[] right, EffectPreset fx) {
        if (fx == null || fx == EffectPreset.NONE) {
            return;
        }
        boolean stereo = right != null && right.length == left.length;
        int n = left.length;
        float bassGain = fx.bass;
        float midGain = fx.mid;
        float trebleGain = fx.treble;
        float space = fx.space;   // 0..0.5
        State sL = new State();
        State sR = new State();
        // one-pole coefficients for 48k (approx crossovers: bass<250Hz, treble>5k)
        final float bassK = 0.06f;
        final float trebleK = 0.92f;

        for (int i = 0; i < n; i++) {
            float l = left[i];
            float r = stereo ? right[i] : l;

            // ---- 3-band split ----
            float bassL = sL.lp += (l - sL.lp) * bassK;
            float highL = l - bassL;
            float trebL = sL.hp += (highL - sL.hp) * trebleK;
            float midL = highL - trebL;

            float bassR, highR = 0f, trebR = 0f, midR = 0f;
            if (stereo) {
                bassR = sR.lp += (r - sR.lp) * bassK;
                highR = r - bassR;
                trebR = sR.hp += (highR - sR.hp) * trebleK;
                midR = highR - trebR;
            } else {
                bassR = bassL;
            }

            // ---- re-mix with gains ----
            float outL = bassL * bassGain + midL * midGain + trebL * trebleGain;
            float outR = stereo ? bassR * bassGain + midR * midGain + trebR * trebleGain : outL;

            // ---- mid-side widening ----
            if (stereo && space > 0f) {
                float m = (outL + outR) * 0.5f;
                float s_ = (outL - outR) * 0.5f * (1f + space * 2f);
                outL = m + s_;
                outR = m - s_;
            }

            left[i] = softClip(outL);
            if (stereo) {
                right[i] = softClip(outR);
            }
        }
    }

    /** Soft-knee limiter: tanh-style, keeps peaks under ~0.98 full scale. */
    private static short softClip(float v) {
        // gentle saturation above 0.82, absolute ceiling at 0.98
        float x = v / 32767f;
        if (x > 0.82f) {
            x = 0.82f + (float) Math.tanh((x - 0.82f) * 1.4f) * 0.16f;
        } else if (x < -0.82f) {
            x = -0.82f - (float) Math.tanh((x + 0.82f) * 1.4f) * 0.16f;
        }
        return (short) Math.max(-32767, Math.min(32767, Math.round(x * 32767f)));
    }
}