package com.vmse;

import com.vmse.command.VmseCommand;
import com.vmse.integration.VoiceMessagesIntegration;
import com.vmse.listener.HoeListener;
import com.vmse.manager.MusicManager;
import com.vmse.playlist.PlaylistManager;
import com.vmse.region.RegionManager;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class VmsePlugin extends JavaPlugin {
    private VoicechatServerApi voicechatApi;
    private MusicManager musicManager;
    private VmseCommand command;
    private PlaylistManager playlistManager;
    private RegionManager regionManager;
    private VoiceMessagesIntegration voiceMessagesIntegration;
    private boolean voicechatPluginRegistered;

    @Override
    public void onLoad() {
        if (getServer().getPluginManager().getPlugin("voicemessages") != null) {
            voiceMessagesIntegration = new VoiceMessagesIntegration(this);
            voiceMessagesIntegration.register();
            getLogger().info("Voice Messages API callback registered during plugin load.");
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("lang/zh_CN.yml", false);
        saveResource("lang/en_US.yml", false);
        musicManager = new MusicManager(this);
        musicManager.ensureFolders();
        musicManager.rebuildCache();
        runMigration();
        playlistManager = new PlaylistManager(this);
        playlistManager.loadAll();
        musicManager.setPlaylistManager(playlistManager);
        regionManager = new RegionManager(this);
        regionManager.load();
        regionManager.startCheck();
        getServer().getPluginManager().registerEvents(new HoeListener(this), this);
        PluginCommand pc = getCommand("vmse");
        if (pc == null) {
            getLogger().severe("Command vmse is missing from plugin.yml!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command = new VmseCommand(this, musicManager);
        pc.setExecutor(command);
        pc.setTabCompleter(command);
        if (getConfig().getBoolean("vmsg.enabled", true) && voiceMessagesIntegration != null) {
            musicManager.setVoiceMessagesIntegration(voiceMessagesIntegration);
            getLogger().info("Voice Messages integration enabled.");
            if (voiceMessagesIntegration.isReady()) {
                getLogger().info("Voice Messages API was already initialized and is ready.");
            }
        } else {
            getLogger().info("Voice Messages plugin not installed or vmsg integration is disabled; /dmc vmsg will remain unavailable.");
        }
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginEnable(PluginEnableEvent event) {
                if (event.getPlugin().getName().equalsIgnoreCase("voicechat")) {
                    VmsePlugin.this.registerVoicechatPlugin();
                }
            }
        }, this);
        registerVoicechatPlugin();
        getLogger().info("Vmse enabled. Waiting for Simple Voice Chat API initialization...");
    }

    private void registerVoicechatPlugin() {
        if (voicechatPluginRegistered) {
            return;
        }
        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().info("Simple Voice Chat service is not available yet; waiting for Voice Chat to finish enabling...");
            return;
        }
        service.registerPlugin(new VoicechatAddon());
        voicechatPluginRegistered = true;
        getLogger().info("Registered Vmse with Simple Voice Chat. Waiting for Voice Chat API initialization...");
    }

    @Override
    public void onDisable() {
        if (regionManager != null) {
            regionManager.stopCheck();
        }
        if (musicManager != null) {
            musicManager.stopAll();
        }
    }

    public void setVoicechatApi(VoicechatServerApi api) {
        voicechatApi = api;
        if (musicManager != null) {
            musicManager.setVoicechatApi(api);
        }
        getLogger().info("Simple Voice Chat API connected.");
    }

    public VoicechatServerApi getVoicechatApi() {
        return voicechatApi;
    }

    public MusicManager getMusicManager() {
        return musicManager;
    }

    public PlaylistManager getPlaylistManager() {
        return playlistManager;
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }

    /** One-time data migration from the legacy DSMPMusic / dsmpmusic folders. */
    private void runMigration() {
        try {
            if (!getConfig().getBoolean("migration.enabled", true)) {
                return;
            }
            java.io.File dataFolder = getDataFolder();
            String[] legacyNames = {"DSMPMusic", "dsmpmusic"};
            for (String name : legacyNames) {
                java.io.File legacy = new java.io.File(dataFolder.getParentFile(), name);
                if (!legacy.isDirectory() || legacy.equals(dataFolder)) {
                    continue;
                }
                migrateOne(legacy, dataFolder);
            }
        } catch (Exception e) {
            getLogger().warning("Migration failed: " + e.getMessage());
        }
    }

    private void migrateOne(java.io.File legacy, java.io.File target) {
        // copy folders that do not yet exist in the new data folder
        String[] items = {"music", "word", "lyrics", "playlists", "cache"};
        for (String item : items) {
            java.io.File src = new java.io.File(legacy, item);
            java.io.File dst = new java.io.File(target, item);
            if (src.isDirectory() && !dst.exists()) {
                copyTree(src, dst);
                getLogger().info("Migrated " + item + "/ from " + legacy.getName());
            }
        }
        java.io.File cfg = new java.io.File(legacy, "config.yml");
        if (cfg.isFile() && !new java.io.File(target, "config.yml").exists()) {
            try {
                java.nio.file.Files.copy(cfg.toPath(), new java.io.File(target, "config.yml").toPath());
            } catch (Exception ignored) {
            }
        }
        // rename legacy folder as backup unless keep-old
        if (!getConfig().getBoolean("migration.keep-old", true)) {
            java.io.File bak = new java.io.File(legacy.getParentFile(), legacy.getName() + ".migrated");
            if (legacy.renameTo(bak)) {
                getLogger().info("Legacy folder " + legacy.getName() + " moved to " + bak.getName());
            }
        }
    }

    private void copyTree(java.io.File src, java.io.File dst) {
        try {
            if (src.isDirectory()) {
                if (!dst.exists()) {
                    dst.mkdirs();
                }
                java.io.File[] children = src.listFiles();
                if (children != null) {
                    for (java.io.File c : children) {
                        copyTree(c, new java.io.File(dst, c.getName()));
                    }
                }
            } else {
                java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        }
    }

    public VoiceMessagesIntegration getVoiceMessagesIntegration() {
        return voiceMessagesIntegration;
    }

    private final class VoicechatAddon implements VoicechatPlugin {
        @Override
        public String getPluginId() {
            return "vmse";
        }

        @Override
        public void initialize(VoicechatApi api) {
            if (api instanceof VoicechatServerApi serverApi) {
                getLogger().info("Voice chat server API initialized.");
                setVoicechatApi(serverApi);
            }
        }
    }
}
