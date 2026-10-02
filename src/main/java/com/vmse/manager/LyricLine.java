package com.vmse.manager;

import java.util.List;

/**
 * One lyric line of the mc_lyrics v1 timeline.
 * Every line owns its own [startMs, endMs) window; lines are fully independent.
 */
public record LyricLine(long startMs, long endMs, String text, List<LyricWord> words, LineStyle style) {

    public boolean active(long ms) {
        return ms >= startMs && ms < endMs;
    }

    /** Per-character timing for word-by-word highlighting (optional). */
    public record LyricWord(String text, long startMs, long endMs) {
    }

    /** Optional per-line style overrides. */
    public record LineStyle(String color, Boolean bold, Boolean italic, Boolean underlined) {
    }
}
