package com.vmse.manager;

import java.util.List;

/**
 * Parsed lyrics for one song: independent timeline lines plus global offset
 * and display settings.
 */
public record LyricsData(List<LyricLine> lines, long offsetMs, LyricsDisplay display, boolean fromJson,
                         String title, String artist) {

    public static LyricsData empty(LyricsDisplay display) {
        return new LyricsData(List.of(), 0L, display, false, null, null);
    }

    /**
     * mc_lyrics rule: the current line is exactly the line whose
     * [start_ms, end_ms) contains t. No previous/next fallback, no waiting dots.
     * During gaps this returns null and the plugin hides lyrics.
     */
    public LyricLine lineAt(long ms) {
        int lo = 0;
        int hi = lines.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LyricLine line = lines.get(mid);
            if (ms < line.startMs()) {
                hi = mid - 1;
            } else if (ms >= line.endMs()) {
                lo = mid + 1;
            } else {
                return line;
            }
        }
        return null;
    }
}
