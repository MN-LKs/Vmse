package com.vmse.region;

import com.vmse.VmsePlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Stores music regions in regions.yml, and every few ticks checks whether
 * online players have entered / left a region so playback auto-starts and
 * auto-stops. Persist on every change for hot-reload safety.
 */
public class RegionManager {

    private final VmsePlugin plugin;
    private final Map<String, MusicRegion> regions = new ConcurrentHashMap<>();
    private final Map<UUID, MusicRegion> inside = new ConcurrentHashMap<>();
    private BukkitTask checkTask;

    public RegionManager(VmsePlugin plugin) {
        this.plugin = plugin;
    }

    public Path file() {
        return plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString("region-file", "regions.yml"));
    }

    public void load() {
        regions.clear();
        inside.clear();
        File f = file().toFile();
        if (!f.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        for (String key : cfg.getKeys(false)) {
            try {
                MusicRegion r = new MusicRegion(key, cfg.getString(key + ".world"));
                r.shape = MusicRegion.Shape.parse(cfg.getString(key + ".shape", "circle"));
                r.x = cfg.getDouble(key + ".x");
                r.y = cfg.getDouble(key + ".y");
                r.z = cfg.getDouble(key + ".z");
                r.radius = cfg.getDouble(key + ".radius");
                r.playlistName = cfg.getString(key + ".playlist");
                r.track = cfg.getString(key + ".track");
                r.volume = (float) cfg.getDouble(key + ".volume", 1.0);
                r.surround = cfg.getBoolean(key + ".surround", true);
                r.surroundLayout = cfg.getString(key + ".layout", "stereo");
                regions.put(key.toLowerCase(Locale.ROOT), r);
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping bad region '" + key + "': " + e.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (MusicRegion r : regions.values()) {
            String k = r.name;
            cfg.set(k + ".world", r.world);
            cfg.set(k + ".shape", r.shape.name());
            cfg.set(k + ".x", r.x);
            cfg.set(k + ".y", r.y);
            cfg.set(k + ".z", r.z);
            cfg.set(k + ".radius", r.radius);
            cfg.set(k + ".playlist", r.playlistName);
            cfg.set(k + ".track", r.track);
            cfg.set(k + ".volume", r.volume);
            cfg.set(k + ".surround", r.surround);
            cfg.set(k + ".layout", r.surroundLayout);
        }
        try {
            Files.createDirectories(file().getParent());
            cfg.save(file().toFile());
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to save regions: " + e.getMessage());
        }
    }

    public MusicRegion get(String name) {
        return regions.get(name.toLowerCase(Locale.ROOT));
    }

    public MusicRegion create(String name, String world, MusicRegion.Shape shape,
                              double x, double y, double z, double radius) {
        MusicRegion r = new MusicRegion(name, world);
        r.shape = shape;
        r.x = x;
        r.y = y;
        r.z = z;
        r.radius = radius;
        regions.put(name.toLowerCase(Locale.ROOT), r);
        save();
        return r;
    }

    public void remove(String name) {
        MusicRegion r = regions.remove(name.toLowerCase(Locale.ROOT));
        if (r != null) {
            save();
        }
    }

    public void removePlayer(UUID uuid) {
        inside.remove(uuid);
    }

    public List<MusicRegion> all() {
        return new ArrayList<>(regions.values());
    }

    /** The region the player is inside, or null. */
    public MusicRegion regionOf(Player p) {
        for (MusicRegion r : regions.values()) {
            if (r.contains(p.getWorld().getName(), p.getLocation().getX(),
                    p.getLocation().getY(), p.getLocation().getZ())) {
                return r;
            }
        }
        return null;
    }

    public boolean isInside(UUID uuid) {
        return inside.containsKey(uuid);
    }

    public void setInside(UUID uuid, MusicRegion r) {
        if (r == null) {
            inside.remove(uuid);
        } else {
            inside.put(uuid, r);
        }
    }

    /** Start the periodic region check task. Call on enable. */
    public void startCheck() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        checkTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                MusicRegion r = regionOf(p);
                MusicRegion prev = inside.get(p.getUniqueId());
                if (r != null && (prev == null || prev != r)) {
                    setInside(p.getUniqueId(), r);
                    plugin.getMusicManager().onRegionEnter(p, r);
                } else if (r == null && prev != null) {
                    setInside(p.getUniqueId(), null);
                    plugin.getMusicManager().onRegionExit(p, prev);
                }
            }
        }, 20L, 20L);
    }

    public void stopCheck() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }
}