package com.vmse.manager;

import com.vmse.VmsePlugin;
import com.vmse.audio.AudioData;
import com.vmse.audio.AudioLoader;
import com.vmse.audio.DspEngine;
import com.vmse.audio.EffectPreset;
import com.vmse.integration.VoiceMessagesIntegration;
import com.vmse.manager.LyricLine;
import com.vmse.manager.LyricsData;
import com.vmse.manager.LyricsDisplay;
import com.vmse.manager.LyricsLoader;
import com.vmse.manager.LyricsRenderer;
import com.vmse.spatial.SpatialPlayer;
import com.vmse.spatial.SurroundLayout;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import java.awt.Color;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.dimaskama.voicemessages.api.VoiceMessagesApi;

public final class MusicManager {
    private final VmsePlugin plugin;
    private final Map<UUID, PlaybackSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, com.vmse.spatial.SurroundLayout> surroundLayouts = new ConcurrentHashMap<>();
    private final Map<UUID, com.vmse.audio.EffectPreset> effectPresets = new ConcurrentHashMap<>();
    private final Object apiLock = new Object();
    private volatile VoicechatServerApi voicechatApi;
    private volatile FileConfiguration lang;
    private volatile VoiceMessagesIntegration voiceMessagesIntegration;
    private volatile PcmCache pcmCache;
    private volatile com.vmse.playlist.PlaylistManager playlistManager;
    // player UUID -> playlist currently auto-advancing (for playlist连播)
    private final Map<UUID, com.vmse.playlist.Playlist> activePlaylists = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> skipGuard = new ConcurrentHashMap<>();

    /** Provide the playlist manager (used for region auto-play). */
    public void setPlaylistManager(com.vmse.playlist.PlaylistManager pm) {
        this.playlistManager = pm;
    }

    /** Mark a player as playing a playlist so the next track auto-advances. */
    public void startPlaylistPlayback(UUID uuid, com.vmse.playlist.Playlist pl) {
        activePlaylists.put(uuid, pl);
    }

    /** Clear playlist auto-advance context (e.g. on manual stop). */
    public void clearPlaylistContext(UUID uuid) {
        activePlaylists.remove(uuid);
    }

    public com.vmse.playlist.Playlist activePlaylistOf(UUID uuid) {
        return activePlaylists.get(uuid);
    }

    public MusicManager(VmsePlugin plugin) {
        this.plugin = plugin;
        this.reloadLanguage();
        this.rebuildCache();
    }

    /** (Re)create the PCM cache from config (called on enable and reload). */
    public void rebuildCache() {
        boolean cacheEnabled = plugin.getConfig().getBoolean("playback.pcm-cache", true);
        int max = Math.max(1, plugin.getConfig().getInt("playback.pcm-cache-max", 12));
        boolean keepStereo = !"normal".equalsIgnoreCase(plugin.getConfig().getString("audio-quality.default-quality", "high"));
        pcmCache = cacheEnabled ? new PcmCache(max, keepStereo) : null;
    }

    /** Active surround layout for a player (null = mono / disabled). */
    public com.vmse.spatial.SurroundLayout surroundOf(UUID uuid) {
        return surroundLayouts.get(uuid);
    }

    public void setSurround(UUID uuid, com.vmse.spatial.SurroundLayout layout) {
        if (layout == null || layout == com.vmse.spatial.SurroundLayout.MONO) {
            surroundLayouts.remove(uuid);
        } else {
            surroundLayouts.put(uuid, layout);
        }
    }

    /** True when the given player has surround enabled and the audio has 2+ channels. */
    public boolean wantsSurround(UUID uuid, AudioData data) {
        com.vmse.spatial.SurroundLayout l = surroundOf(uuid);
        return l != null && data.channelCount >= 2;
    }

    /** Active effect preset for a player (null / NONE = no effect). */
    public com.vmse.audio.EffectPreset effectOf(UUID uuid) {
        return effectPresets.get(uuid);
    }

    public void setEffect(UUID uuid, com.vmse.audio.EffectPreset preset) {
        if (preset == null || preset == com.vmse.audio.EffectPreset.NONE) {
            effectPresets.remove(uuid);
        } else {
            effectPresets.put(uuid, preset);
        }
    }

    public void ensureFolders() {
        try {
            Files.createDirectories(musicFolder(), new FileAttribute[0]);
            Files.createDirectories(cacheFolder(), new FileAttribute[0]);
            Files.createDirectories(wordFolder(), new FileAttribute[0]);
            Files.createDirectories(lyricsFolder(), new FileAttribute[0]);
            Path template = wordFolder().resolve("template.yml");
            if (!Files.exists(template, new LinkOption[0]) && plugin.getResource("word/template.yml") != null) {
                plugin.saveResource("word/template.yml", false);
            }
            Path sample = lyricsFolder().resolve("fanwutuobang.json");
            if (!Files.exists(sample, new LinkOption[0]) && plugin.getResource("lyrics/fanwutuobang.json") != null) {
                plugin.saveResource("lyrics/fanwutuobang.json", false);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Unable to create music folders: " + e.getMessage());
        }
    }

    public Path musicFolder() {
        return plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString("music-folder", "music"));
    }

    public Path cacheFolder() {
        return plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString("cache-folder", "cache"));
    }

    public Path wordFolder() {
        return plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString("word-folder", "word"));
    }

    public Path lyricsFolder() {
        return plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString("lyrics-folder", "lyrics"));
    }

    public void setVoicechatApi(VoicechatServerApi api) {
        synchronized (apiLock) {
            this.voicechatApi = api;
        }
    }

    public boolean isReady() {
        return voicechatApi != null;
    }

    public void setVoiceMessagesIntegration(VoiceMessagesIntegration integration) {
        this.voiceMessagesIntegration = integration;
    }

    public boolean isVoiceMessagesReady() {
        return voiceMessagesIntegration != null && voiceMessagesIntegration.isReady();
    }

    public void reloadLanguage() {
        File file;
        String language = plugin.getConfig().getString("language", "zh_CN");
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Unable to create language folder.");
        }
        if (!(file = new File(dir, language + ".yml")).exists()) {
            String resource = "lang/" + language + ".yml";
            if (plugin.getResource(resource) != null) {
                plugin.saveResource(resource, false);
            } else {
                language = "zh_CN";
                file = new File(dir, "zh_CN.yml");
                if (!file.exists()) {
                    plugin.saveResource("lang/zh_CN.yml", false);
                }
            }
        }
        lang = YamlConfiguration.loadConfiguration(file);
    }

    public List<String> helpLines() {
        String prefix = plugin.getConfig().getString("prefix", "");
        return lang.getStringList("help").stream()
                .map(line -> line.replace("%prefix%", prefix))
                .map(MusicManager::color)
                .toList();
    }

    public String msg(String key, Map<String, String> replacements) {
        String raw = lang.getString(key, key);
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        String prefix = plugin.getConfig().getString("prefix", "");
        raw = raw.replace("%prefix%", prefix);
        return color(raw);
    }

    public List<String> listMusicFiles() {
        List<String> list;
        try {
            Files.createDirectories(musicFolder(), new FileAttribute[0]);
        } catch (IOException e) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(musicFolder())) {
            list = stream.filter(p -> Files.isRegularFile(p, new LinkOption[0]))
                    .filter(this::isAudioFile)
                    .map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
        return list;
    }

    private boolean isAudioFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".ogg") || n.endsWith(".wav") || n.endsWith(".aiff")
                || n.endsWith(".aif") || n.endsWith(".au") || n.endsWith(".flac");
    }

    public Path resolveLocalMusic(String name) throws IOException {
            String clean = name.replace('\\', '/');
            if (clean.contains("..") || clean.startsWith("/") || clean.contains(":")) {
                throw new IOException("Invalid file name");
            }
            Path base = musicFolder().toAbsolutePath().normalize();
            Path file = base.resolve(clean).normalize();
            if (!file.startsWith(base)) {
                throw new IOException("Invalid file name");
            }
            if (!Files.isRegularFile(file, new LinkOption[0])) {
                throw new FileNotFoundException(name);
            }
            return file;
        }

        /** True if the given name resolves to an existing music file (with or without extension). */
        public boolean existsMusic(String name) {
            try {
                resolveLocalMusic(name);
                return true;
            } catch (Exception e) {
                // try appending common extensions
                String clean = name.trim();
                for (String ext : new String[]{".mp3", ".ogg", ".wav", ".flac", ".aiff"}) {
                    try {
                        resolveLocalMusic(clean + ext);
                        return true;
                    } catch (Exception ignored) {
                    }
                }
                return false;
            }
        }

    public void playLocal(String fileName, Collection<Player> targets, Consumer<Result> callback) {
        playLocal(fileName, targets, false, callback);
    }

    public void playLocal(String fileName, Collection<Player> targets, boolean original, Consumer<Result> callback) {
        Path file;
        try {
            file = resolveLocalMusic(fileName);
        } catch (Exception e) {
            callback.accept(Result.failure(e.getMessage() == null ? "not found" : e.getMessage()));
            return;
        }
        loadAndPlay(file, fileName, targets, null, original, callback);
    }

    public void playUrl(String rawUrl, Collection<Player> targets, Consumer<Result> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Path downloaded = null;
            try {
                Path finalFile = downloaded = downloadToCache(rawUrl);
                Bukkit.getScheduler().runTask(plugin, () -> loadAndPlay(finalFile, rawUrl, targets, finalFile, false, callback));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> callback.accept(Result.failure(e.getMessage() == null ? "download failed" : e.getMessage())));
            }
        });
    }

    public void download(String rawUrl, String name, Consumer<Result> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URI uri = validateUrl(rawUrl);
                String clean = sanitizeName(name);
                if (clean.isBlank()) {
                    throw new IOException("Invalid name");
                }
                Path output = musicFolder().resolve(clean + ".mp3").normalize();
                if (!output.getParent().equals(musicFolder().normalize())) {
                    throw new IOException("Invalid name");
                }
                Files.createDirectories(musicFolder(), new FileAttribute[0]);
                download(uri, output);
                String result = output.getFileName().toString();
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(Result.success(result)));
            } catch (Exception e) {
                String error = e.getMessage() == null ? "download failed" : e.getMessage();
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(Result.failure(error)));
            }
        });
    }

    private void loadAndPlay(Path file, String displayName, Collection<Player> targets, Path deleteAfterLoad,
                             boolean original, Consumer<Result> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int maxSeconds = plugin.getConfig().getInt("playback.max-audio-seconds", 900);
                AudioData data;
                if (original) {
                    // 原声: 直接解码原始 PCM, 跳过音质处理和缓存(缓存是处理过的)
                    data = AudioLoader.load(file, maxSeconds, true, true);
                } else {
                    PcmCache cache = pcmCache;
                    if (cache != null) {
                        data = cache.get(file, displayName, maxSeconds);
                        if (data == null) {
                            throw new IOException("Audio decode failed or empty");
                        }
                    } else {
                        boolean keepStereo = !"normal".equalsIgnoreCase(plugin.getConfig().getString("audio-quality.default-quality", "high"));
                        data = AudioLoader.load(file, maxSeconds, keepStereo);
                    }
                }
                if (deleteAfterLoad != null) {
                    Files.deleteIfExists(deleteAfterLoad);
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean isGlobal = targets.size() > 1;
                    boolean countdown = isGlobal && plugin.getConfig().getBoolean("playback.countdown-on-global", true);
                    final java.util.List<Player> validTargets = new ArrayList<>();
                    for (Player target : targets) {
                        if (target != null && target.isOnline()) {
                            validTargets.add(target);
                        }
                    }
                    if (validTargets.isEmpty()) {
                        callback.accept(Result.failure("No voice-chat targets are available"));
                        return;
                    }
                    // 全局播放: 加载完成后先显示经验栏倒计时, 避免惊吓玩家
                    int secs = Math.max(1, plugin.getConfig().getInt("playback.global-countdown-seconds", 3));
                    if (countdown) {
                        int[] remaining = {secs};
                        for (Player t : validTargets) {
                            t.sendActionBar(color("&e即将播放: &f" + displayTitle(displayName) + " &7(&b" + secs + "&7)"));
                        }
                        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                            private boolean done;
                            @Override
                            public void run() {
                                if (done) {
                                    return;
                                }
                                remaining[0]--;
                                if (remaining[0] <= 0) {
                                    done = true;
                                    doStart(validTargets, displayName, data);
                                } else {
                                    for (Player t : validTargets) {
                                        t.sendActionBar(color("&e即将播放: &f" + displayTitle(displayName) + " &7(&b" + remaining[0] + "&7)"));
                                    }
                                }
                            }
                        }, 20L, 20L);
                    } else {
                        doStart(validTargets, displayName, data);
                    }
                });
            } catch (Exception e) {
                if (deleteAfterLoad != null) {
                    try {
                        Files.deleteIfExists(deleteAfterLoad);
                    } catch (IOException ignored) {
                    }
                }
                String error = e.getMessage() == null ? "decode failed" : e.getMessage();
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(Result.failure(error)));
            }
        });
    }

    /**
     * Actually start playback for the given targets (after any countdown).
     * Announces the song title / author / duration when enabled.
     */
    private void doStart(java.util.List<Player> validTargets, String displayName, AudioData data) {
        int count = 0;
        for (Player target : validTargets) {
            if (!startSession(target, displayName, data)) {
                continue;
            }
            count++;
        }
        // announce song info on the exp bar after start
        if (count > 0 && plugin.getConfig().getBoolean("playback.announce-on-start", true)) {
            int durSec = data.durationSeconds();
            String time = String.format("%d:%02d", durSec / 60, durSec % 60);
            // 显示歌词 JSON 里的歌名/作者, 没有时才用文件名(去后缀)
            String displayTitle = displayTitle(displayName);
            for (Player t : validTargets) {
                if (t.isOnline()) {
                    t.sendActionBar(color("&a♪ &f" + displayTitle + " &7(&e" + time + "&7)"));
                }
            }
        }
    }

    /**
     * 合成用于经验栏显示的歌曲标题: 优先取歌词 JSON 的 song.title / song.artist,
     * 否则回退为文件名(不含后缀)。
     */
    private String displayTitle(String displayName) {
        LyricsDisplay defaults = LyricsDisplay.fromConfig(plugin.getConfig(), "lyrics-display");
        LyricsData lyrics = LyricsLoader.load(plugin, lyricsFolder(), wordFolder(), displayName, defaults);
        String title = lyrics.title();
        if (title == null || title.isBlank()) {
            int dot = displayName.lastIndexOf('.');
            return dot > 0 ? displayName.substring(0, dot) : displayName;
        }
        String artist = lyrics.artist();
        if (artist != null && !artist.isBlank()) {
            return artist + " - " + title;
        }
        return title;
    }

    private boolean startSession(Player player, String displayName, AudioData data) {
        return startSession(player, displayName, data, false);
    }

    private boolean startSession(Player player, String displayName, AudioData data, boolean regionPlayed) {
        VoicechatServerApi api = voicechatApi;
        if (api == null) {
            return false;
        }
        VoicechatConnection connection = api.getConnectionOf(player.getUniqueId());
        if (connection == null) {
            return false;
        }
        stop(player.getUniqueId());

        // Surround: build a spatial player around the listener when the audio
        // has real stereo and the player opted in. Otherwise fall back to the
        // original single static source (mono).
        SurroundLayout layout = surroundOf(player.getUniqueId());
        boolean surround = layout != null && data.channelCount >= 2;

        AudioChannel channel;
        SpatialPlayer spatial = null;
        if (surround) {
            ServerLevel level = api.fromServerLevel(player.getWorld());
            float vol = (float) plugin.getConfig().getDouble("playback.volume", 1.0);
            boolean reverb = plugin.getConfig().getBoolean("surround.reverb-sim", true);
            float reverbGain = (float) plugin.getConfig().getDouble("surround.reverb-gain", 0.15);
            boolean fallback = plugin.getConfig().getBoolean("surround.fallback-to-mono", true);
            spatial = new SpatialPlayer(api, plugin, level, data, layout, fallback, vol, reverb, reverbGain);
            // 声源跟随玩家实时位置: 玩家移动时声源跟着左右两侧走
            spatial.start(List.of(connection), () -> player.isOnline() ? player.getLocation() : null);
            channel = null; // spatial owns its channels
        } else {
            StaticAudioChannel c = api.createStaticAudioChannel(UUID.randomUUID());
            if (c == null) {
                return false;
            }
            c.addTarget(connection);
            c.setBypassGroupIsolation(plugin.getConfig().getBoolean("playback.bypass-group-isolation", true));
            channel = c;
        }

        // Lyrics: mc_lyrics v1 JSON preferred, legacy yml/txt kept as fallback.
        LyricsDisplay defaults = LyricsDisplay.fromConfig(plugin.getConfig(), "lyrics-display");
        LyricsData lyrics = LyricsLoader.load(plugin, lyricsFolder(), wordFolder(), displayName, defaults);
        if (lyrics.fromJson()) {
            plugin.getLogger().info("Loaded mc_lyrics JSON lyrics for '" + displayName + "' ("
                    + lyrics.lines().size() + " lines, offset " + lyrics.offsetMs() + " ms, mode "
                    + lyrics.display().mode() + ")");
        }

        // Apply per-player effect preset (DSP processing on decoded PCM).
        // Clone first so cached AudioData is never mutated in place.
        EffectPreset fx = effectOf(player.getUniqueId());
        if (fx != null && fx != EffectPreset.NONE) {
            data = data.clone();
            short[] left = data.channels[0];
            short[] right = data.channelCount > 1 ? data.channels[1] : null;
            DspEngine.apply(left, right, fx);
        }

        PlaybackSession session = new PlaybackSession(player.getUniqueId(), displayName, data,
                surround, spatial, channel, lyrics, regionPlayed);
        sessions.put(player.getUniqueId(), session);
        session.start();
        return true;
    }

    public boolean stop(UUID uuid) {
        PlaybackSession session = sessions.remove(uuid);
        if (session == null) {
            return false;
        }
        session.stop();
        clearPlaylistContext(uuid);
        return true;
    }

    // ------------------------------------------------------------------
    // Region playback
    // ------------------------------------------------------------------

    /**
     * Called when a player enters a music region: start (or keep) the region's
     * playlist / track using a fixed-anchor spatial player. If the player is
     * already playing personal music we do not preempt it.
     */
    public void onRegionEnter(Player p, com.vmse.region.MusicRegion region) {
        if (sessions.containsKey(p.getUniqueId())) {
            return; // personal music already playing - do not preempt
        }
        VoicechatServerApi api = voicechatApi;
        if (api == null) {
            return;
        }
        VoicechatConnection connection = api.getConnectionOf(p.getUniqueId());
        if (connection == null) {
            return;
        }
        String track = region.track != null ? region.track
                : (region.playlistName != null ? firstOfPlaylist(region.playlistName) : null);
        if (track == null) {
            return;
        }
        try {
            Path file = resolveLocalMusic(track);
            int maxSeconds = plugin.getConfig().getInt("playback.max-audio-seconds", 900);
            PcmCache cache = pcmCache;
            AudioData data = cache != null ? cache.get(file, track, maxSeconds)
                    : AudioLoader.load(file, maxSeconds, true);
            if (data == null) {
                return;
            }
            // Register playlist context so PlaybackSession.finish() can auto-advance
            if (region.playlistName != null && playlistManager != null) {
                com.vmse.playlist.Playlist pl = playlistManager.get(null, region.playlistName);
                if (pl != null) {
                    startPlaylistPlayback(p.getUniqueId(), pl);
                }
            }
            // Build a fixed-anchor surround (or mono) player at the region centre.
            boolean surround = region.surround && data.channelCount >= 2;
            SurroundLayout layout = SurroundLayout.parse(region.surroundLayout);
            org.bukkit.World w = Bukkit.getWorld(region.world);
            if (w == null) {
                return;
            }
            org.bukkit.Location anchor = new org.bukkit.Location(w, region.x, region.y, region.z);
            if (surround) {
                ServerLevel level = api.fromServerLevel(w);
                boolean reverb = plugin.getConfig().getBoolean("surround.reverb-sim", true);
                SpatialPlayer sp = new SpatialPlayer(api, plugin, level, data, layout, true,
                        region.volume, reverb, (float) plugin.getConfig().getDouble("surround.reverb-gain", 0.15));
                sp.start(null, anchor);
                // 区域声学反射: 基于玩家与声源间方块遮挡的动态音量衰减
                float acoustic = (float) plugin.getConfig().getDouble("region.acoustic-filter", 0.6);
                sp.enableAcoustics(w, p.getLocation(), acoustic);
                PlaybackSession session = new PlaybackSession(p.getUniqueId(), track, data, true, sp, null, LyricsData.empty(LyricsDisplay.fromConfig(plugin.getConfig(), "lyrics-display")), true);
                sessions.put(p.getUniqueId(), session);
                session.start();
            } else {
                startSession(p, track, data, true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Region playback failed for '" + track + "': " + e.getMessage());
        }
    }

    /** Called when a player leaves a region: stop the region-sourced music. */
    public void onRegionExit(Player p, com.vmse.region.MusicRegion region) {
        PlaybackSession s = sessions.get(p.getUniqueId());
        if (s != null && s.regionPlayed) {
            stop(p.getUniqueId());
        }
    }

    private String firstOfPlaylist(String playlistName) {
        return playlistManager != null ? playlistManager.firstSong(playlistName) : null;
    }

    public boolean pause(UUID uuid) {
        PlaybackSession session = sessions.get(uuid);
        return session != null && session.pause();
    }

    public boolean resume(UUID uuid) {
        PlaybackSession session = sessions.get(uuid);
        return session != null && session.resume();
    }

    public boolean isPaused(UUID uuid) {
        PlaybackSession s = sessions.get(uuid);
        return s != null && s.paused;
    }

    public boolean isPlaying(UUID uuid) {
        PlaybackSession s = sessions.get(uuid);
        return s != null && !s.paused && s.audioPlayer != null && s.audioPlayer.isPlaying();
    }

    public String currentFile(UUID uuid) {
        PlaybackSession s = sessions.get(uuid);
        return s == null ? null : s.displayName;
    }

    public void sendVoiceMessage(Player sender, String fileName, Consumer<Result> callback) {
        Path file;
        VoiceMessagesApi api;
        if (!isReady()) {
            callback.accept(Result.failure("voicechat unavailable"));
            return;
        }
        VoiceMessagesIntegration integration = voiceMessagesIntegration;
        VoiceMessagesApi voiceMessagesApi = api = integration == null ? null : integration.getApi();
        if (api == null) {
            callback.accept(Result.failure("voicemessages unavailable"));
            return;
        }
        try {
            file = resolveLocalMusic(fileName);
        } catch (Exception e) {
            callback.accept(Result.failure("not found"));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int maxSeconds = Math.max(3, plugin.getConfig().getInt("vmsg.max-seconds", 600));
                AudioData data = AudioLoader.load(file, maxSeconds);
                List<byte[]> frames = encodeVoiceMessage(data.samples());
                List<UUID> recipients = Bukkit.getOnlinePlayers().stream()
                        .filter(OfflinePlayer::isOnline)
                        .map(OfflinePlayer::getUniqueId)
                        .filter(api::isPlayerHasCompatibleModVersion)
                        .toList();
                if (recipients.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(Result.failure("no recipients")));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        api.sendVoiceMessage(sender.getUniqueId(), recipients, frames,
                                plugin.getConfig().getString("vmsg.display-target", "all"));
                        callback.accept(Result.success(Integer.toString(recipients.size())));
                    } catch (Exception e) {
                        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        plugin.getLogger().log(Level.WARNING,
                                "Voice Messages API rejected the message for '" + fileName + "': " + error, e);
                        callback.accept(Result.failure(error));
                    }
                });
            } catch (Exception e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                plugin.getLogger().log(Level.WARNING,
                        "Voice Messages processing failed for '" + fileName + "': " + error, e);
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(Result.failure(error)));
            }
        });
    }

    private List<byte[]> encodeVoiceMessage(short[] samples) throws Exception {
        VoicechatServerApi api = voicechatApi;
        if (api == null) {
            throw new IOException("Voice chat API unavailable");
        }
        OpusEncoder encoder = api.createEncoder(OpusEncoderMode.AUDIO);
        if (encoder == null) {
            throw new IOException("Unable to create Opus encoder");
        }
        List<byte[]> frames = new ArrayList<>();
        try {
            for (int pos = 0; pos < samples.length; pos += 960) {
                short[] frame = new short[960];
                int copy = Math.min(960, samples.length - pos);
                System.arraycopy(samples, pos, frame, 0, copy);
                byte[] encoded = encoder.encode(frame);
                if (encoded == null || encoded.length <= 0) {
                    continue;
                }
                frames.add(encoded);
            }
        } finally {
            try {
                encoder.close();
            } catch (Exception ignored) {
            }
        }
        return frames;
    }

    public void stopAll() {
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            stop(uuid);
        }
    }

    public Collection<Player> onlinePlayers() {
        return new ArrayList<>(Bukkit.getOnlinePlayers());
    }

    private Path downloadToCache(String rawUrl) throws Exception {
        URI uri = validateUrl(rawUrl);
        String ext = extensionForUrl(uri.toString());
        Path output = cacheFolder().resolve(UUID.randomUUID() + ext);
        Files.createDirectories(cacheFolder(), new FileAttribute[0]);
        download(uri, output);
        return output;
    }

    private void download(URI uri, Path output) throws Exception {
        URLConnection connection = uri.toURL().openConnection();
        connection.setConnectTimeout(plugin.getConfig().getInt("network.connect-timeout-ms", 8000));
        connection.setReadTimeout(plugin.getConfig().getInt("network.read-timeout-ms", 15000));
        connection.setRequestProperty("User-Agent", "Vmse/2.0");
        long maxBytes = plugin.getConfig().getLong("network.max-download-mb", 50L) * 1024L * 1024L;
        long declared = connection.getContentLengthLong();
        if (declared > maxBytes) {
            throw new IOException("File exceeds maximum download size");
        }
        if (connection instanceof HttpURLConnection http && http.getResponseCode() >= 400) {
            throw new IOException("HTTP " + http.getResponseCode());
        }
        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
            int read;
            byte[] buffer = new byte[8192];
            long total = 0L;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("File exceeds maximum download size");
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private URI validateUrl(String raw) throws Exception {
        URI uri = URI.create(raw);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("Only HTTP/HTTPS URLs are supported");
        }
        if (uri.getHost() == null) {
            throw new IOException("Invalid URL");
        }
        return uri;
    }

    private String extensionForUrl(String url) {
        String path = url.toLowerCase(Locale.ROOT);
        if (path.contains(".ogg")) {
            return ".ogg";
        }
        if (path.contains(".wav")) {
            return ".wav";
        }
        if (path.contains(".flac")) {
            return ".flac";
        }
        if (path.contains(".aiff") || path.contains(".aif")) {
            return ".aiff";
        }
        return ".mp3";
    }

    private String sanitizeName(String name) {
        String n = name.trim().replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff ]", "_");
        return n.replaceAll("\\s+", "_");
    }

    public static String color(String text) {
        return text == null ? "" : text.replace('&', '\u00a7');
    }

    public record Result(boolean ok, String value) {
        public static Result success(String value) {
            return new Result(true, value);
        }

        public static Result failure(String value) {
            return new Result(false, value);
        }
    }

    // ------------------------------------------------------------------
    // Playback session with mc_lyrics-aware lyric rendering
    // ------------------------------------------------------------------

    private final class PlaybackSession {
        private final UUID playerId;
        private final String displayName;
        private final AudioData data;
        private final boolean surround;
        private final SpatialPlayer spatial;      // non-null when surround
        private final StaticAudioChannel monoChannel; // non-null when mono
        private final LyricsData lyrics;
        private final LyricsDisplay display;
        private volatile int position;
        private BukkitTask lyricTask;
        private String lastRendered = null;   // actionbar dedup
        private String lastLineKey = null;    // title/chat/bossbar line-change detection
        private BossBar bossBar;
        private volatile boolean paused;
        private volatile boolean stopped;
        private volatile AudioPlayer audioPlayer;
        private volatile OpusEncoder encoder;
        private final boolean regionPlayed;

        private PlaybackSession(UUID playerId, String displayName, AudioData data,
                                boolean surround, SpatialPlayer spatial, AudioChannel channel,
                                LyricsData lyrics) {
            this(playerId, displayName, data, surround, spatial, channel, lyrics, false);
        }

        private PlaybackSession(UUID playerId, String displayName, AudioData data,
                                boolean surround, SpatialPlayer spatial, AudioChannel channel,
                                LyricsData lyrics, boolean regionPlayed) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.data = data;
            this.surround = surround;
            this.spatial = spatial;
            this.monoChannel = surround ? null : (StaticAudioChannel) channel;
            this.lyrics = lyrics;
            this.display = lyrics.display();
            this.regionPlayed = regionPlayed;
        }

        synchronized void start() {
            stopped = false;
            paused = false;
            startFrom(position);
            startLyricTask();
        }

        private void startLyricTask() {
            if (lyricTask != null) {
                lyricTask.cancel();
            }
            lyricTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickLyrics, 0L, 1L);
        }

        private void tickLyrics() {
            if (stopped) {
                return;
            }
            if (paused) {
                hideLyrics();
                return;
            }
            // mc_lyrics v1: t = playback position + global offset.
            // offset_ms > 0 shifts lyrics later, so it is subtracted here.
            long playMs = position / 48L; // 48000 Hz mono clock
            long t = playMs - lyrics.offsetMs();
            LyricLine line = lyrics.lineAt(t);
            if (line == null) {
                hideLyrics();
            } else {
                renderLyrics(line, t);
            }
        }

        private void hideLyrics() {
            Player p = player();
            if (p == null || !p.isOnline()) {
                return;
            }
            switch (display.mode()) {
                case "title" -> {
                    if (lastLineKey != null) {
                        p.sendTitle("", "", 0, 0, 0);
                    }
                }
                case "bossbar" -> {
                    if (bossBar != null) {
                        bossBar.removeAll();
                    }
                }
                default -> {
                    if (lastRendered != null && !lastRendered.isEmpty()) {
                        p.sendActionBar("");
                    }
                }
            }
            lastRendered = null;
            lastLineKey = null;
        }

        private void renderLyrics(LyricLine line, long t) {
            Player p = player();
            if (p == null || !p.isOnline()) {
                return;
            }
            boolean perWord = display.perWordHighlight();
            String rendered = LyricsRenderer.render(line, display, t, perWord);
            String lineKey = line.startMs() + ":" + line.text();

            switch (display.mode()) {
                case "title" -> {
                    if (!lineKey.equals(lastLineKey)) {
                        int fadeIn = Math.max(0, display.fadeInMs()) / 50;
                        int fadeOut = Math.max(0, display.fadeOutMs()) / 50;
                        long lineDur = Math.max(50L, line.endMs() - Math.max(t, line.startMs()));
                        int stay = (int) Math.max(10L, lineDur / 50L - fadeIn - fadeOut + 2L);
                        p.sendTitle("", rendered, fadeIn, stay, fadeOut);
                    }
                }
                case "bossbar" -> {
                    if (bossBar == null) {
                        bossBar = Bukkit.createBossBar(rendered, nearestBarColor(display.highlightColor()), BarStyle.SOLID);
                        bossBar.addPlayer(p);
                    }
                    if (!lineKey.equals(lastLineKey)) {
                        bossBar.setTitle(rendered);
                    }
                    if (plugin.getConfig().getBoolean("lyrics-display.bossbar-progress", true)) {
                        long dur = Math.max(1L, line.endMs() - line.startMs());
                        double progress = (double) (t - line.startMs()) / dur;
                        bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                    }
                }
                case "chat" -> {
                    if (!lineKey.equals(lastLineKey)) {
                        String prefix = display.chatPrefix() == null ? "" : display.chatPrefix();
                        p.sendMessage(MusicManager.color(prefix + rendered));
                    }
                }
                default -> {
                    // actionbar: refresh on line change OR text change
                    // (fixes repeated identical lyric lines being skipped)
                    if (!lineKey.equals(lastLineKey) || !Objects.equals(rendered, lastRendered)) {
                        p.sendActionBar(rendered);
                    }
                }
            }
            lastRendered = rendered;
            lastLineKey = lineKey;
        }

        private void cleanupLyrics() {
            if (lyricTask != null) {
                lyricTask.cancel();
                lyricTask = null;
            }
            Player p = player();
            if (p != null && p.isOnline()) {
                if ("title".equals(display.mode()) && lastLineKey != null) {
                    p.sendTitle("", "", 0, 0, 0);
                } else if (!"bossbar".equals(display.mode()) && !"chat".equals(display.mode())) {
                    p.sendActionBar("");
                }
            }
            if (bossBar != null) {
                bossBar.removeAll();
                bossBar = null;
            }
            lastRendered = null;
            lastLineKey = null;
        }

        private BarColor nearestBarColor(String hex) {
            Color target = LyricsRenderer.parseColor(hex);
            BarColor best = BarColor.WHITE;
            double bestDist = Double.MAX_VALUE;
            BarColor[] candidates = {BarColor.BLUE, BarColor.GREEN, BarColor.PINK, BarColor.PURPLE,
                    BarColor.RED, BarColor.WHITE, BarColor.YELLOW};
            int[][] rgbs = {{0x55, 0x55, 0xFF}, {0x55, 0xFF, 0x55}, {0xFF, 0x55, 0xFF}, {0xAA, 0x00, 0xAA},
                    {0xFF, 0x55, 0x55}, {0xFF, 0xFF, 0xFF}, {0xFF, 0xFF, 0x55}};
            for (int i = 0; i < candidates.length; ++i) {
                double dist = sq(target.getRed() - rgbs[i][0]) + sq(target.getGreen() - rgbs[i][1])
                        + sq(target.getBlue() - rgbs[i][2]);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = candidates[i];
                }
            }
            return best;
        }

        private double sq(double v) {
            return v * v;
        }

        private Player player() {
            return Bukkit.getPlayer(playerId);
        }

        synchronized void startFrom(int start) {
            if (stopped) {
                return;
            }
            if (start >= data.sampleCount()) {
                finish();
                return;
            }
            if (surround) {
                if (spatial != null) {
                    spatial.resume();
                }
                return;
            }
            if (encoder != null) {
                try {
                    encoder.close();
                } catch (Exception ignored) {
                }
            }
            encoder = MusicManager.this.voicechatApi.createEncoder(OpusEncoderMode.AUDIO);
            short[] mono = data.mono();
            int[] cursor = {Math.max(0, start)};
            audioPlayer = MusicManager.this.voicechatApi.createAudioPlayer((AudioChannel) monoChannel, encoder, () -> {
                synchronized (PlaybackSession.this) {
                    if (stopped || paused) {
                        return null;
                    }
                    if (cursor[0] >= mono.length) {
                        position = mono.length;
                        return null;
                    }
                    int end = Math.min(cursor[0] + 960, mono.length);
                    short[] frame = new short[960];
                    System.arraycopy(mono, cursor[0], frame, 0, end - cursor[0]);
                    cursor[0] = end;
                    position = end;
                    return frame;
                }
            });
            audioPlayer.setOnStopped(() -> {
                synchronized (PlaybackSession.this) {
                    if (!stopped && !paused && position >= data.sampleCount()) {
                        finish();
                    }
                }
            });
            audioPlayer.startPlaying();
        }

        synchronized boolean pause() {
            if (stopped || paused) {
                return false;
            }
            paused = true;
            if (surround) {
                if (spatial != null) {
                    spatial.pause();
                }
            } else if (audioPlayer != null) {
                audioPlayer.stopPlaying();
            }
            return true;
        }

        synchronized boolean resume() {
            if (stopped || !paused) {
                return false;
            }
            paused = false;
            if (!surround) {
                startFrom(position);
            } else if (spatial != null) {
                spatial.resume();
            }
            return true;
        }

        synchronized void stop() {
            stopped = true;
            paused = false;
            cleanupLyrics();
            if (surround) {
                if (spatial != null) {
                    spatial.stop();
                }
            } else {
                if (audioPlayer != null) {
                    audioPlayer.stopPlaying();
                }
                if (encoder != null) {
                    try {
                        encoder.close();
                    } catch (Exception ignored) {
                    }
                    encoder = null;
                }
                if (monoChannel != null) {
                    monoChannel.clearTargets();
                }
            }
        }

        synchronized void finish() {
            stopped = true;
            paused = false;
            cleanupLyrics();
            if (surround) {
                if (spatial != null) {
                    spatial.stop();
                }
            } else {
                if (encoder != null) {
                    try {
                        encoder.close();
                    } catch (Exception ignored) {
                    }
                    encoder = null;
                }
                if (monoChannel != null) {
                    monoChannel.clearTargets();
                }
            }
            MusicManager.this.sessions.remove(playerId, this);
            // 歌单连播: 若该玩家在歌单播放中, 自动推进下一首
            com.vmse.playlist.Playlist pl = activePlaylists.get(playerId);
            if (pl != null) {
                scheduleNextFromPlaylist(pl);
            }
        }

        /** Advance to the next track of the playlist (auto-advance). */
        private void scheduleNextFromPlaylist(com.vmse.playlist.Playlist pl) {
            pl.advance();
            String next = pl.current();
            if (next == null) {
                // sequential finished -> stop the playlist context
                clearPlaylistContext(playerId);
                return;
            }
            final String fnext = next;
            final com.vmse.playlist.Playlist fpl = pl;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (fpl.cursor >= fpl.songs.size() && fpl.mode == com.vmse.playlist.Playlist.Mode.SEQUENTIAL) {
                    clearPlaylistContext(playerId);
                    return;
                }
                playNextTrack(playerId, fnext, fpl);
            });
        }
    }

    /**
     * Play a single track as part of a playlist continuation (used by
     * PlaybackSession auto-advance).
     */
    private void playNextTrack(UUID uuid, String fileName, com.vmse.playlist.Playlist pl) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null || !p.isOnline()) {
            clearPlaylistContext(uuid);
            return;
        }
        startPlaylistPlayback(uuid, pl);
        playLocal(fileName, List.of(p), result -> {
            if (!result.ok()) {
                // track missing -> skip to next (with a loop guard)
                PlaybackSession s = sessions.get(uuid);
                int[] guard = skipGuard.computeIfAbsent(uuid, k -> new int[]{0});
                guard[0]++;
                if (guard[0] >= pl.songs.size()) {
                    // too many consecutive failures - stop to avoid infinite loop
                    clearPlaylistContext(uuid);
                    skipGuard.remove(uuid);
                    return;
                }
                if (s != null) {
                    s.finish();
                }
            } else {
                int[] guard = skipGuard.get(uuid);
                if (guard != null) {
                    guard[0] = 0;
                }
            }
        });
    }
}