package com.vmse.manager;

import com.vmse.manager.LyricLine;
import com.vmse.manager.LyricLine.LyricWord;
import com.vmse.manager.LyricLine.LineStyle;
import com.vmse.manager.LyricsDisplay;
import java.awt.Color;
import net.md_5.bungee.api.ChatColor;

/**
 * Turns a LyricLine + timestamp into a legacy-coloured Minecraft string,
 * supporting optional word-by-word highlighting.
 */
public final class LyricsRenderer {
    private LyricsRenderer() {
    }

    /**
     * Render the line at time t (already offset-corrected, in ms).
     *
     * @param perWord force word-by-word colouring (display.per_word_highlight)
     */
    public static String render(LyricLine line, LyricsDisplay display, long t, boolean perWord) {
        boolean useWords = perWord && line.words() != null && !line.words().isEmpty();
        String baseColor = colorOf(line, display);
        String formatCodes = formatCodes(line);
        if (!useWords) {
            return baseColor + formatCodes + line.text();
        }

        int sung = sungCharCount(line, t);
        String highlight = ChatColor.of(parseColor(display.highlightColor())).toString();
        StringBuilder sb = new StringBuilder();
        String currentColor = null;
        for (int i = 0; i < line.text().length(); ++i) {
            String c = sung > i ? highlight : baseColor;
            if (!c.equals(currentColor)) {
                sb.append(c).append(formatCodes);
                currentColor = c;
            }
            sb.append(line.text().charAt(i));
        }
        return sb.toString();
    }

    /** Colour of the whole line when word highlight is off. */
    public static String colorOf(LyricLine line, LyricsDisplay display) {
        if (line.style() != null && line.style().color() != null) {
            Color c = parseColor(line.style().color());
            if (c != null) {
                return ChatColor.of(c).toString();
            }
        }
        return ChatColor.of(parseColor(display.defaultColor())).toString();
    }

    private static String formatCodes(LyricLine line) {
        LineStyle s = line.style();
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (s.bold() != null && s.bold()) {
            sb.append(ChatColor.BOLD);
        }
        if (s.italic() != null && s.italic()) {
            sb.append(ChatColor.ITALIC);
        }
        if (s.underlined() != null && s.underlined()) {
            sb.append(ChatColor.UNDERLINE);
        }
        return sb.toString();
    }

    /**
     * How many characters of the line have been sung at time t.
     * Uses words timing when the words map cleanly onto the text,
     * otherwise falls back to proportional progress.
     */
    private static int sungCharCount(LyricLine line, long t) {
        if (line.words() != null && !line.words().isEmpty()) {
            int[] map = wordCharMap(line);
            if (map != null) {
                int sung = 0;
                for (int i = 0; i < map.length; ++i) {
                    if (map[i] >= 0 && t >= line.words().get(map[i]).startMs()) {
                        sung = i + 1;
                    }
                }
                return sung;
            }
        }
        long dur = line.endMs() - line.startMs();
        if (dur <= 0) {
            return 0;
        }
        double progress = (double) (t - line.startMs()) / dur;
        progress = Math.max(0.0, Math.min(1.0, progress));
        return (int) Math.round(progress * line.text().length());
    }

    /**
     * Maps text char index -> index into words, by consuming each word's text
     * sequentially. Returns null if words do not cover the text cleanly.
     */
    private static int[] wordCharMap(LyricLine line) {
        String text = line.text();
        int[] map = new int[text.length()];
        int textPos = 0;
        for (int w = 0; w < line.words().size(); ++w) {
            String wordText = line.words().get(w).text();
            if (wordText == null || wordText.isEmpty()) {
                continue;
            }
            int start = text.indexOf(wordText, textPos);
            if (start < 0 || start > textPos) {
                return null; // gap or reorder: not a clean mapping
            }
            for (int i = 0; i < wordText.length(); ++i) {
                if (textPos >= text.length()) {
                    return null;
                }
                map[textPos++] = w;
            }
        }
        if (textPos != text.length()) {
            return null;
        }
        return map;
    }

    /** Parses "#RRGGBB" (mc_lyrics colour format) into a Color, null on failure. */
    public static Color parseColor(String hex) {
        if (hex == null) {
            return Color.WHITE;
        }
        String c = hex.trim();
        if (c.startsWith("#")) {
            c = c.substring(1);
        }
        if (c.length() == 3) {
            c = "" + c.charAt(0) + c.charAt(0) + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2);
        }
        try {
            return new Color(Integer.parseInt(c, 16));
        } catch (NumberFormatException e) {
            return Color.WHITE;
        }
    }
}
