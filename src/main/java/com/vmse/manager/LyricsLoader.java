package com.vmse.manager;

import com.vmse.VmsePlugin;
import com.vmse.manager.LyricLine;
import com.vmse.manager.LyricLine.LyricWord;
import com.vmse.manager.LyricLine.LineStyle;
import com.vmse.manager.LyricsData;
import com.vmse.manager.LyricsDisplay;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lyrics loader.
 * Priority: lyrics/&lt;base&gt;.json (mc_lyrics v1) &gt; word/&lt;base&gt;.yml &gt; word/&lt;base&gt;.txt
 */
public final class LyricsLoader {
    private LyricsLoader() {
    }

    public static LyricsData load(VmsePlugin plugin, Path lyricsFolder, Path wordFolder,
                                  String musicFileName, LyricsDisplay defaults) {
        String base = baseName(musicFileName);
        Path json = lyricsFolder.resolve(base + ".json").normalize();
        Path yml = wordFolder.resolve(base + ".yml").normalize();
        Path txt = wordFolder.resolve(base + ".txt").normalize();
        try {
            if (Files.isRegularFile(json, new LinkOption[0])) {
                LyricsData data = loadJson(json, defaults);
                if (data != null) {
                    return data;
                }
                return LyricsData.empty(defaults);
            }
            if (Files.isRegularFile(yml, new LinkOption[0])) {
                return loadYaml(yml, defaults);
            }
            if (Files.isRegularFile(txt, new LinkOption[0])) {
                return loadTxt(txt, defaults);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to load lyrics for '" + musicFileName + "': " + e.getMessage());
        }
        return LyricsData.empty(defaults);
    }

    // ------------------------------------------------------------------
    // mc_lyrics v1 JSON
    // ------------------------------------------------------------------

    private static LyricsData loadJson(Path file, LyricsDisplay defaults) throws IOException {
        JsonObject root;
        try (InputStream in = Files.newInputStream(file)) {
            root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Invalid JSON: " + e.getMessage());
        }
        String format = str(root, "format", "");
        int version = root.has("version") ? root.get("version").getAsInt() : -1;
        if (!"mc_lyrics".equals(format) || version != 1) {
            throw new IOException("Not a mc_lyrics v1 file (format=" + format + ", version=" + version + ")");
        }

        long offsetMs = 0L;
        String title = null;
        String artist = null;
        if (root.has("song") && root.get("song").isJsonObject()) {
            JsonObject song = root.getAsJsonObject("song");
            if (song.has("offset_ms")) {
                offsetMs = song.get("offset_ms").getAsLong();
            }
            if (song.has("title")) {
                title = str(song, "title", null);
            }
            if (song.has("artist")) {
                artist = str(song, "artist", null);
            }
        }

        LyricsDisplay display = defaults;
        if (root.has("display") && root.get("display").isJsonObject()) {
            display = display.withJsonOverride(root.getAsJsonObject("display"));
        }

        List<LyricLine> lines = new ArrayList<>();
        if (root.has("timeline") && root.get("timeline").isJsonArray()) {
            JsonArray timeline = root.getAsJsonArray("timeline");
            for (JsonElement el : timeline) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject node = el.getAsJsonObject();
                String type = str(node, "type", "lyric");
                if (!"lyric".equals(type)) {
                    continue; // interlude/blank/waiting nodes are forbidden by the spec, skip defensively
                }
                long start = node.has("start_ms") ? node.get("start_ms").getAsLong() : Long.MIN_VALUE;
                long end = node.has("end_ms") ? node.get("end_ms").getAsLong() : Long.MIN_VALUE;
                String text = str(node, "text", "");
                if (start == Long.MIN_VALUE || end == Long.MIN_VALUE || end <= start || text.isEmpty()) {
                    continue;
                }
                List<LyricWord> words = parseWords(node);
                LineStyle style = parseStyle(node);
                lines.add(new LyricLine(start, end, text, words, style));
            }
        }
        if (lines.isEmpty()) {
            throw new IOException("timeline contains no valid lyric lines");
        }
        lines.sort(Comparator.comparingLong(LyricLine::startMs));
        return new LyricsData(List.copyOf(lines), offsetMs, display, true, title, artist);
    }

    private static List<LyricWord> parseWords(JsonObject node) {
        if (!node.has("words") || !node.get("words").isJsonArray()) {
            return List.of();
        }
        List<LyricWord> words = new ArrayList<>();
        for (JsonElement el : node.getAsJsonArray("words")) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject w = el.getAsJsonObject();
            String text = str(w, "text", "");
            long s = w.has("start_ms") ? w.get("start_ms").getAsLong() : Long.MIN_VALUE;
            long e = w.has("end_ms") ? w.get("end_ms").getAsLong() : Long.MIN_VALUE;
            if (text.isEmpty() || s == Long.MIN_VALUE || e == Long.MIN_VALUE || e <= s) {
                continue;
            }
            words.add(new LyricWord(text, s, e));
        }
        return List.copyOf(words);
    }

    private static LineStyle parseStyle(JsonObject node) {
        if (!node.has("style") || !node.get("style").isJsonObject()) {
            return null;
        }
        JsonObject s = node.getAsJsonObject("style");
        String color = s.has("color") ? LyricsDisplay.normalizeColor(s.get("color").getAsString(), null) : null;
        Boolean bold = s.has("bold") ? s.get("bold").getAsBoolean() : null;
        Boolean italic = s.has("italic") ? s.get("italic").getAsBoolean() : null;
        Boolean underlined = s.has("underlined") ? s.get("underlined").getAsBoolean() : null;
        if (color == null && bold == null && italic == null && underlined == null) {
            return null;
        }
        return new LineStyle(color, bold, italic, underlined);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    // ------------------------------------------------------------------
    // Legacy word/*.yml and word/*.txt support
    // ------------------------------------------------------------------

    private static LyricsData loadYaml(Path file, LyricsDisplay defaults) {
        org.bukkit.configuration.file.YamlConfiguration yml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        org.bukkit.configuration.ConfigurationSection section = yml.getConfigurationSection("lyrics");
        if (section == null) {
            return LyricsData.empty(defaults);
        }
        List<LyricLine> lines = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection line = section.getConfigurationSection(key);
            if (line == null) {
                continue;
            }
            double start = line.getDouble("start", -1.0);
            double end = line.getDouble("end", -1.0);
            String text = line.getString("text", "");
            if (start < 0.0 || end <= start || text == null || text.isEmpty()) {
                continue;
            }
            lines.add(new LyricLine(toMs(start), toMs(end), text, List.of(), null));
        }
        lines.sort(Comparator.comparingLong(LyricLine::startMs));
        return new LyricsData(List.copyOf(lines), 0L, defaults, false, null, null);
    }

    private static LyricsData loadTxt(Path file, LyricsDisplay defaults) throws IOException {
        List<LyricLine> lines = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }
            try {
                double start = Double.parseDouble(parts[0].trim());
                double end = Double.parseDouble(parts[1].trim());
                if (end <= start) {
                    continue;
                }
                lines.add(new LyricLine(toMs(start), toMs(end), parts[2].trim(), List.of(), null));
            } catch (NumberFormatException ignored) {
            }
        }
        lines.sort(Comparator.comparingLong(LyricLine::startMs));
        return new LyricsData(List.copyOf(lines), 0L, defaults, false, null, null);
    }

    private static long toMs(double seconds) {
        return Math.round(seconds * 1000.0);
    }

    private static String baseName(String name) {
        String simple = Path.of(name.replace('\\', '/')).getFileName().toString();
        int dot = simple.lastIndexOf('.');
        return dot > 0 ? simple.substring(0, dot) : simple;
    }
}
