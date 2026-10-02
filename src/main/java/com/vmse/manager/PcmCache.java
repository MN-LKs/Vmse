package com.vmse.manager;

import com.vmse.audio.AudioData;
import com.vmse.audio.AudioLoader;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small bounded cache of decoded PCM so repeating the same song does not
 * re-decode from disk every time (saves CPU and cuts the gap between request
 * and playback). Sized by count, evicts least-recently-used.
 */
public final class PcmCache {

    private final int maxEntries;
    private final Map<String, AudioData> map;
    private final boolean keepStereo;

    public PcmCache(int maxEntries, boolean keepStereo) {
        this.maxEntries = Math.max(1, maxEntries);
        this.keepStereo = keepStereo;
        this.map = new LinkedHashMap<>(maxEntries, 0.75f, true);
    }

    public synchronized AudioData get(Path file, String key, int maxSeconds) {
        String k = key == null ? file.toString() : key;
        AudioData hit = map.get(k);
        if (hit != null) {
            return hit;
        }
        try {
            AudioData decoded = AudioLoader.load(file, maxSeconds, keepStereo);
            if (decoded == null || decoded.sampleCount() == 0) {
                return null;
            }
            if (map.size() >= maxEntries) {
                var it = map.entrySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            map.put(k, decoded);
            return decoded;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }
}