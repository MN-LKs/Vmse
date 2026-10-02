package com.vmse.playlist;

import com.vmse.VmsePlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Stores playlists as per-file YAML in the playlists folder. Playlists are
 * categorised as global (server) or personal (owner). All mutations persist
 * to disk immediately so a reload / hot-reload keeps them.
 */
public class PlaylistManager {

    private final VmsePlugin plugin;
    private final ConcurrentMap<String, Playlist> playlists = new ConcurrentHashMap<>();

    public PlaylistManager(VmsePlugin plugin) {
        this.plugin = plugin;
    }

    public Path folder() {
        return plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString("playlist-folder", "playlists"));
    }

    public void loadAll() {
        playlists.clear();
        Path dir = folder();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                        .forEach(this::loadFile);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to load playlists: " + e.getMessage());
        }
    }

    private void loadFile(Path p) {
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(p.toFile());
            String name = cfg.getString("name");
            if (name == null) {
                return;
            }
            String owner = cfg.getString("owner", "server");
            boolean global = cfg.getBoolean("global", false);
            Playlist pl = new Playlist(name, owner, global);
            pl.mode = Playlist.Mode.parse(cfg.getString("mode", "SEQUENTIAL"));
            List<String> songs = cfg.getStringList("songs");
            for (String s : songs) {
                pl.songs.add(s);
            }
            pl.cursor = Math.max(0, cfg.getInt("cursor", 0));
            playlists.put(key(owner, name), pl);
        } catch (Exception e) {
            plugin.getLogger().warning("Skipping bad playlist file " + p.getFileName() + ": " + e.getMessage());
        }
    }

    private static String key(String owner, String name) {
        return (owner == null ? "server" : owner).toLowerCase(Locale.ROOT) + ":" + name.toLowerCase(Locale.ROOT);
    }

    private File fileFor(Playlist pl) {
        String safe = pl.name.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff]", "_");
        return folder().resolve(safe + ".yml").toFile();
    }

    public void save(Playlist pl) {
        try {
            Files.createDirectories(folder());
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("name", pl.name);
            cfg.set("owner", pl.owner);
            cfg.set("global", pl.global);
            cfg.set("mode", pl.mode.name());
            cfg.set("cursor", pl.cursor);
            cfg.set("songs", new ArrayList<>(pl.songs));
            cfg.save(fileFor(pl));
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to save playlist '" + pl.name + "': " + e.getMessage());
        }
    }

    public void delete(Playlist pl) {
        playlists.remove(key(pl.owner, pl.name));
        File f = fileFor(pl);
        if (f.exists()) {
            f.delete();
        }
    }

    public Playlist get(String owner, String name) {
        return playlists.get(key(owner, name));
    }

    public Playlist getOrCreate(String owner, String name, boolean global) {
        Playlist pl = playlists.get(key(owner, name));
        if (pl != null) {
            return pl;
        }
        pl = new Playlist(name, owner, global);
        playlists.put(key(owner, name), pl);
        save(pl);
        return pl;
    }

    /** All playlists a player may access (their own + all global). */
    public List<Playlist> accessible(String playerName) {
        List<Playlist> out = new ArrayList<>();
        for (Playlist pl : playlists.values()) {
            if (pl.global || pl.owner.equalsIgnoreCase(playerName)) {
                out.add(pl);
            }
        }
        return out;
    }

    public List<Playlist> all() {
        return new ArrayList<>(playlists.values());
    }

    /** First song of a global (or any) playlist, or null. Used by region auto-play. */
    public String firstSong(String playlistName) {
        for (Playlist pl : playlists.values()) {
            if (pl.name.equalsIgnoreCase(playlistName) && (pl.global || "server".equalsIgnoreCase(pl.owner))) {
                return pl.songs.isEmpty() ? null : pl.songs.get(0);
            }
        }
        // fall back to any matching name (personal playlists too)
        for (Playlist pl : playlists.values()) {
            if (pl.name.equalsIgnoreCase(playlistName)) {
                return pl.songs.isEmpty() ? null : pl.songs.get(0);
            }
        }
        return null;
    }
}
