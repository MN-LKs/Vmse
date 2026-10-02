package com.vmse.manager;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * How lyrics are rendered. Defaults come from config.yml, per-song overrides come
 * from the JSON "display" node.
 */
public record LyricsDisplay(String mode, String defaultColor, String highlightColor, boolean perWordHighlight,
                            int fadeInMs, int fadeOutMs, boolean bossbarProgress, String chatPrefix) {

    public static final String[] MODES = {"actionbar", "title", "bossbar", "chat"};

    public static LyricsDisplay fromConfig(FileConfiguration cfg, String prefix) {
        return new LyricsDisplay(
                normalizeMode(cfg.getString(prefix + ".mode", "actionbar")),
                normalizeColor(cfg.getString(prefix + ".default-color", "#FFFFFF"), "#FFFFFF"),
                normalizeColor(cfg.getString(prefix + ".highlight-color", "#FFD700"), "#FFD700"),
                cfg.getBoolean(prefix + ".per-word-highlight", false),
                Math.max(0, cfg.getInt(prefix + ".fade-in-ms", 200)),
                Math.max(0, cfg.getInt(prefix + ".fade-out-ms", 200)),
                cfg.getBoolean(prefix + ".bossbar-progress", true),
                cfg.getString(prefix + ".chat-prefix", "") == null ? "" : cfg.getString(prefix + ".chat-prefix", ""));
    }

    public LyricsDisplay withJsonOverride(com.google.gson.JsonObject display) {
        if (display == null) {
            return this;
        }
        return new LyricsDisplay(
                display.has("mode") ? normalizeMode(display.get("mode").getAsString()) : mode,
                display.has("default_color") ? normalizeColor(display.get("default_color").getAsString(), defaultColor) : defaultColor,
                display.has("highlight_color") ? normalizeColor(display.get("highlight_color").getAsString(), highlightColor) : highlightColor,
                display.has("per_word_highlight") ? display.get("per_word_highlight").getAsBoolean() : perWordHighlight,
                display.has("fade_in_ms") ? Math.max(0, display.get("fade_in_ms").getAsInt()) : fadeInMs,
                display.has("fade_out_ms") ? Math.max(0, display.get("fade_out_ms").getAsInt()) : fadeOutMs,
                bossbarProgress,
                chatPrefix);
    }

    public static String normalizeMode(String mode) {
        if (mode == null) {
            return "actionbar";
        }
        String m = mode.toLowerCase().trim();
        for (String valid : MODES) {
            if (valid.equals(m)) {
                return m;
            }
        }
        return "actionbar";
    }

    public static String normalizeColor(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String c = raw.trim();
        if (!c.startsWith("#")) {
            c = "#" + c;
        }
        return c.matches("^#[0-9a-fA-F]{6}$") ? c.toUpperCase() : fallback;
    }
}
