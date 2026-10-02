package com.vmse.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * Vmse audio decoder. Preserves up to 2 source channels (stereo),
 * resamples to 48 kHz, then applies hi-fi post-processing:
 * <ul>
 *   <li>dynamic range compression (prevents volume jumps on stutter)</li>
 *   <li>gentle low-shelf boost (warmth)</li>
 *   <li>gentle high-shelf boost (clarity)</li>
 *   <li>soft-clip limiter (ceiling 0.95)</li>
 *   <li>short fade-in/out (avoids pops)</li>
 * </ul>
 * When {@code original=true} all DSP is skipped – raw decoded PCM.
 */
public final class AudioLoader {

    public static final int TARGET_RATE = 48000;

    private AudioLoader() {}

    public static AudioData load(Path file, int maxSeconds) throws Exception {
        return load(file, maxSeconds, true, false);
    }

    public static AudioData load(Path file, int maxSeconds, boolean keepStereo) throws Exception {
        return load(file, maxSeconds, keepStereo, false);
    }

    public static AudioData load(Path file, int maxSeconds, boolean keepStereo, boolean original) throws Exception {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        AudioData raw;
        if (name.endsWith(".mp3")) {
            raw = loadMp3(file, maxSeconds, original);
        } else {
            raw = loadWithJavaSound(file, maxSeconds, original);
        }
        if (raw.channelCount > 1 && !keepStereo) {
            return new AudioData(raw.sampleRate1, raw.mono());
        }
        return raw;
    }

    private static AudioData loadMp3(Path file, int maxSeconds, boolean original) throws Exception {
        int sourceRate = -1;
        int channels = -1;
        ShortCollector collector = new ShortCollector();
        try (InputStream input = Files.newInputStream(file)) {
            Bitstream bitstream = new Bitstream(input);
            Decoder decoder = new Decoder();
            try {
                Header header;
                while ((header = bitstream.readFrame()) != null) {
                    SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    if (sourceRate < 0) {
                        sourceRate = buffer.getSampleFrequency();
                        channels = Math.max(1, buffer.getChannelCount());
                    }
                    if (buffer.getSampleFrequency() != sourceRate || buffer.getChannelCount() != channels) {
                        throw new IOException("MP3 contains changing audio format");
                    }
                    int length = buffer.getBufferLength();
                    long maxSamples = (long) maxSeconds * sourceRate * Math.max(1, channels);
                    if ((long) collector.size() + length > maxSamples) {
                        throw new IOException("Audio longer than " + maxSeconds + "s limit");
                    }
                    collector.add(buffer.getBuffer(), length);
                    bitstream.closeFrame();
                }
            } finally {
                try { bitstream.close(); } catch (Exception ignored) {}
            }
        }
        if (sourceRate <= 0 || channels <= 0 || collector.size() == 0) {
            throw new IOException("MP3 contains no decodable audio");
        }
        short[] decoded = collector.toArray();
        return finalizeChannels(decoded, channels, sourceRate, maxSeconds, original);
    }

    private static AudioData loadWithJavaSound(Path file, int maxSeconds, boolean original) throws Exception {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat sourceFormat = source.getFormat();
            int sourceChannels = Math.max(1, sourceFormat.getChannels());
            float sourceRateFloat = sourceFormat.getSampleRate();
            if (sourceRateFloat <= 0.0f) {
                throw new IOException("Unknown audio sample rate");
            }
            int sourceRate = Math.round(sourceRateFloat);
            AudioFormat pcmFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sourceRateFloat, 16,
                    sourceChannels, sourceChannels * 2, sourceRateFloat, false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, source)) {
                byte[] bytes = readAll(pcm, maxSeconds, sourceRate, sourceChannels);
                short[] decoded = bytesToShorts(bytes, pcmFormat.isBigEndian());
                return finalizeChannels(decoded, sourceChannels, sourceRate, maxSeconds, original);
            }
        }
    }

    private static AudioData finalizeChannels(short[] interleaved, int channels, int sourceRate, int maxSeconds, boolean original) throws IOException {
        int keep = Math.min(2, Math.max(1, channels));
        int frames = interleaved.length / Math.max(1, channels);
        short[][] chans = new short[keep][];
        for (int c = 0; c < keep; c++) {
            short[] one = new short[frames];
            for (int f = 0; f < frames; f++) {
                one[f] = interleaved[f * Math.max(1, channels) + c];
            }
            chans[c] = resample(one, sourceRate, TARGET_RATE);
            if (chans[c].length > 48000L * maxSeconds) {
                throw new IOException("Audio longer than " + maxSeconds + "s limit");
            }
        }
        int minLen = Integer.MAX_VALUE;
        for (short[] c : chans) minLen = Math.min(minLen, c.length);
        for (int c = 0; c < keep; c++) {
            if (chans[c].length != minLen) {
                short[] t = new short[minLen];
                System.arraycopy(chans[c], 0, t, 0, minLen);
                chans[c] = t;
            }
        }
        if (!original) {
            enhance(chans, minLen, TARGET_RATE);
        }
        return new AudioData(TARGET_RATE, chans);
    }

    /** Lightweight hi-fi pipeline: compression → shelves → fade → limiter. */
    private static void enhance(short[][] chans, int len, int rate) {
        float[] left = new float[len];
        float[] right = chans.length > 1 ? new float[len] : left;
        for (int i = 0; i < len; i++) {
            left[i] = chans[0][i];
            if (chans.length > 1) right[i] = chans[1][i];
        }

        // 1) dynamic range compression (gentle, ~1.5:1 above -20 dBFS)
        float peak = 0f;
        for (int i = 0; i < len; i++) {
            peak = Math.max(peak, Math.abs(left[i]));
            if (chans.length > 1) peak = Math.max(peak, Math.abs(right[i]));
        }
        float thresh = 0.25f * 32767f;
        float ratio = 1f;
        if (peak > thresh) {
            ratio = 1f - 0.35f * Math.min(1f, (peak - thresh) / (32767f - thresh));
        }
        for (int i = 0; i < len; i++) {
            left[i] *= ratio;
            if (chans.length > 1) right[i] *= ratio;
        }

        // 2) 3-band shelf via one-pole filters (bass < 250 Hz, treble > 5 kHz)
        final float bassK = 0.055f;
        final float trebleK = 0.93f;
        float bassGain = 1.12f;
        float midGain = 1.0f;
        float trebleGain = 1.10f;
        float lpL = 0f, hpL = 0f, lpR = 0f, hpR = 0f;
        for (int i = 0; i < len; i++) {
            float l = left[i], r = chans.length > 1 ? right[i] : l;
            float bL = lpL += (l - lpL) * bassK;
            float hL = l - bL;
            float tL = hpL += (hL - hpL) * trebleK;
            float mL = hL - tL;
            float bR = chans.length > 1 ? (lpR += (r - lpR) * bassK) : bL;
            float hR_ = chans.length > 1 ? (r - bR) : hL;
            float tR = chans.length > 1 ? (hpR += (hR_ - hpR) * trebleK) : tL;
            float mR = chans.length > 1 ? (hR_ - tR) : 0f;
            left[i] = bL * bassGain + mL * midGain + tL * trebleGain;
            if (chans.length > 1) right[i] = bR * bassGain + mR * midGain + tR * trebleGain;
        }

        // 3) short fade-in/out to avoid pops
        int fi = Math.max(1, rate / 25);
        int fo = Math.max(1, rate / 12);
        for (int i = 0; i < fi && i < len; i++) {
            left[i] *= (float) i / fi;
            if (chans.length > 1) right[i] *= (float) i / fi;
        }
        for (int i = 0; i < fo && i < len; i++) {
            int idx = len - 1 - i;
            left[idx] *= (float) (fo - i) / fo;
            if (chans.length > 1) right[idx] *= (float) (fo - i) / fo;
        }

        // 4) soft-clip limiter at 0.95 ceiling
        float maxAmp = 0f;
        for (int i = 0; i < len; i++) {
            maxAmp = Math.max(maxAmp, Math.abs(left[i]));
            if (chans.length > 1) maxAmp = Math.max(maxAmp, Math.abs(right[i]));
        }
        float ceiling = 0.95f * 32767f;
        if (maxAmp > ceiling) {
            float gain = ceiling / maxAmp;
            for (int i = 0; i < len; i++) {
                left[i] *= gain;
                if (chans.length > 1) right[i] *= gain;
            }
        }
        for (int i = 0; i < len; i++) {
            chans[0][i] = clip(left[i]);
            if (chans.length > 1) chans[1][i] = clip(right[i]);
        }
    }

    private static short clip(float v) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
    }

    private static void applyFade(short[][] chans, int rate) {
        int fi = Math.max(1, rate / 25);
        int fo = Math.max(1, rate / 12);
        for (short[] c : chans) {
            int len = c.length;
            int f = Math.min(fi, len);
            for (int i = 0; i < f; i++) c[i] = (short) Math.round(c[i] * (float) i / f);
            int o = Math.min(fo, len);
            for (int i = 0; i < o; i++) {
                int idx = len - 1 - i;
                c[idx] = (short) Math.round(c[idx] * (float) (o - i) / o);
            }
        }
    }

    private static void applyLimiter(short[][] chans, float threshold) {
        float maxAmp = 0f;
        for (short[] c : chans) for (short s : c) { float a = Math.abs(s); if (a > maxAmp) maxAmp = a; }
        float ceiling = threshold * 32767f;
        if (maxAmp <= ceiling) return;
        float gain = ceiling / maxAmp;
        for (short[] c : chans) for (int i = 0; i < c.length; i++) c[i] = (short) Math.max(-32768, Math.min(32767, Math.round(c[i] * gain)));
    }

    private static byte[] readAll(AudioInputStream in, int maxSeconds, int sampleRate, int channels) throws IOException {
        long maxBytes = (long) maxSeconds * Math.max(1, sampleRate) * Math.max(1, channels) * 2L + 4096L;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int r;
        while ((r = in.read(buf)) != -1) { total += r; if (total > maxBytes) throw new IOException("Audio exceeds duration limit"); out.write(buf, 0, r); }
        return out.toByteArray();
    }

    private static short[] bytesToShorts(byte[] bytes, boolean bigEndian) {
        short[] s = new short[bytes.length / 2];
        for (int i = 0, j = 0; i < s.length; i++, j += 2) {
            int a = bytes[j] & 0xFF, b = bytes[j + 1] & 0xFF;
            s[i] = (short) (bigEndian ? a << 8 | b : b << 8 | a);
        }
        return s;
    }

    private static short[] resample(short[] input, int fromRate, int toRate) {
        if (input.length == 0 || fromRate == toRate) return input;
        int outLen = (int) Math.max(1L, Math.round((double) input.length * toRate / fromRate));
        short[] out = new short[outLen];
        double ratio = (double) fromRate / toRate;
        for (int i = 0; i < outLen; i++) {
            int left = (int) (i * ratio);
            int right = Math.min(left + 1, input.length - 1);
            double frac = i * ratio - left;
            out[i] = left >= input.length ? input[input.length - 1]
                    : (short) Math.round(input[left] + (input[right] - input[left]) * frac);
        }
        return out;
    }

    private static final class ShortCollector {
        private short[] data = new short[8192];
        private int size;
        void add(short[] src, int n) { ensure(size + n); System.arraycopy(src, 0, data, size, n); size += n; }
        int size() { return size; }
        short[] toArray() { short[] r = new short[size]; System.arraycopy(data, 0, r, 0, size); return r; }
        private void ensure(int need) { if (need <= data.length) return; int n = data.length; while (n < need) n = Math.max(need, n * 2); data = Arrays.copyOf(data, n); }
    }
}