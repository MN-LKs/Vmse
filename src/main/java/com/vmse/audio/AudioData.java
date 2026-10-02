package com.vmse.audio;

import java.util.Arrays;

/**
 * Decoded PCM audio, preserving up to 2 source channels so that stereo /
 * surround playback can route distinct material to distinct sound sources.
 * <p>
 * Vmse 2.0 keeps mono + original stereo instead of blindly downmixing to
 * mono. This is the foundation for surround (stereo / 5.1) output and for
 * the "-original" hi-fi path.
 */
public final class AudioData {
    /** Number of source channels kept (1 = mono, 2 = stereo). */
    public final int channelCount;
    /** 48000 Hz PCM, one array per channel, same length. */
    public final short[][] channels;
    public final int sampleRate1;
    /** true when the audio was decoded from an original stereo source. */
    public final boolean originalStereoBeyondThresHold;

    private final int durationSeconds;

    /**
     * @param sampleRate  sample rate in Hz (kept at 48000 by loader)
     * @param channels    array of PCM channels; length &gt;= 1
     */
    public AudioData(int sampleRate, short[]... channels) {
        this.sampleRate1 = sampleRate;
        if (channels == null || channels.length == 0) {
            throw new IllegalArgumentException("AudioData needs at least one channel");
        }
        // make defensive copies, all equal length
        int len = channels[0].length;
        short[][] copy = new short[channels.length][];
        for (int c = 0; c < channels.length; c++) {
            short[] src = channels[c];
            if (src == null || src.length != len) {
                throw new IllegalArgumentException("All channels must have equal length");
            }
            copy[c] = src.clone();
        }
        this.channels = copy;
        this.channelCount = copy.length;
        // flag stereo when we actually have >= 2 channels and they differ meaningfully
        boolean differ = false;
        if (copy.length >= 2) {
            long diffSum = 0;
            for (int i = 0; i < len; i++) {
                diffSum += Math.abs((int) copy[0][i] - (int) copy[1][i]);
                if (i > 0 && i % 256 == 0) {
                    if (diffSum / 256 > 40) {
                        differ = true;
                        break;
                    }
                    diffSum = 0;
                }
            }
        }
        this.originalStereoBeyondThresHold = differ;
        this.durationSeconds = (int) Math.ceil((double) len / sampleRate1);
    }

    public int durationSeconds() {
        return durationSeconds;
    }

    public int sampleCount() {
        return channels[0].length;
    }

    /** Mono downmix (average of all channels) - used for static / single-source playback. */
    public short[] mono() {
        if (channelCount == 1) {
            return channels[0];
        }
        int len = channels[0].length;
        short[] m = new short[len];
        for (int i = 0; i < len; i++) {
            long sum = 0;
            for (int c = 0; c < channelCount; c++) {
                sum += channels[c][i];
            }
            m[i] = (short) Math.max(-32768, Math.min(32767, sum / channelCount));
        }
        return m;
    }

    /**
     * Extract a single channel (clamped to available count). For mono data
     * always returns channel 0. Used by surround to feed distinct sources.
     */
    public short[] channel(int index) {
        if (channelCount == 1) {
            return channels[0];
        }
        return channels[Math.max(0, Math.min(channelCount - 1, index))];
    }

    /** Alias kept for legacy callers (returns mono). */
    public short[] samples() {
        return mono();
    }

    public int sampleRate() {
        return sampleRate1;
    }

    public int channelsCount() {
        return channelCount;
    }

    public int totalPcmBytes() {
        return sampleCount() * 2 * channelCount;
    }

    /** Deep copy — safe to mutate channels without touching the source. */
    public AudioData clone() {
        return new AudioData(sampleRate1, channels);
    }

    @Override
    public String toString() {
        return "AudioData{" + channelCount + "ch, " + sampleCount() + " samples @ " + sampleRate1 + "Hz, "
                + durationSeconds + "s, stereo=" + originalStereoBeyondThresHold + '}';
    }
}