package com.vmse.command;

import com.vmse.VmsePlugin;
import com.vmse.audio.EffectPreset;
import com.vmse.manager.MusicManager;
import com.vmse.playlist.Playlist;
import com.vmse.playlist.PlaylistManager;
import com.vmse.region.MusicRegion;
import com.vmse.region.RegionManager;
import com.vmse.spatial.SurroundLayout;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class VmseCommand implements CommandExecutor, TabCompleter {
    private final VmsePlugin plugin;
    private final MusicManager music;

    public VmseCommand(VmsePlugin plugin, MusicManager music) {
        this.plugin = plugin;
        this.music = music;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            // /vmse         -> page 1
            // /vmse help 2   -> page 2
            // /vmse help play-> play 用法
            if (args.length >= 2) {
                String arg1 = args[1];
                if (arg1.matches("\\d+")) {
                    sendHelpPage(sender, Integer.parseInt(arg1));
                } else {
                    sendCommandHelp(sender, arg1.toLowerCase(Locale.ROOT));
                }
            } else {
                sendHelpPage(sender, 1);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "play" -> play(sender, args);
            case "url" -> url(sender, args);
            case "stop" -> stop(sender, args);
            case "download" -> download(sender, args);
            case "reload" -> reload(sender, args);
            case "vmsg" -> vmsg(sender, args);
            case "pause" -> pause(sender, args);
            case "list" -> list(sender);
            case "surround" -> surround(sender, args);
            case "playlist" -> playlist(sender, args);
            case "region" -> region(sender, args);
            case "hoe" -> hoe(sender);
            case "confirm" -> confirm(sender, args);
            case "effect" -> effect(sender, args);
            default -> {
                sender.sendMessage(music.msg("unknown-command", null));
                yield true;
            }
        };
    }

    private boolean play(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(music.msg("usage.play", null));
            return true;
        }
        String targetArg = args[1];
        // 解析文件参数, 支持末尾的 "-original" 原声标志
        boolean original = false;
        String last = args[args.length - 1];
        if (last.equalsIgnoreCase("-original")) {
            original = true;
            // 文件 = 从 index 2 到倒数第2个
            String file;
            if (args.length >= 4) {
                file = join(args, 2, args.length - 1);
            } else {
                file = args[2];
            }
            playWithFile(sender, targetArg, file, original);
        } else {
            String file = join(args, 2);
            playWithFile(sender, targetArg, file, false);
        }
        return true;
    }

    private void playWithFile(CommandSender sender, String targetArg, String file, boolean original) {
        Collection<Player> targets = resolveTargets(sender, targetArg, "vmse.play.other");
        if (targets == null) {
            return;
        }
        if (targets.size() == 1 && targetArg.equalsIgnoreCase("me") && !sender.hasPermission("vmse.play.me")) {
            sender.sendMessage(music.msg("no-permission", null));
            return;
        }
        if (!music.isReady()) {
            sender.sendMessage(music.msg("voicechat-unavailable", null));
            return;
        }
        sender.sendMessage(music.msg("loading", null));
        music.playLocal(file, targets, original, result -> {
            if (result.ok()) {
                sender.sendMessage(music.msg("started", Map.of("file", file + (original ? " (原声)" : ""))));
            } else {
                sender.sendMessage(music.msg(result.value().equals("not found") ? "not-found" : "invalid-audio", Map.of("file", file)));
            }
        });
    }

    private boolean url(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(music.msg("usage.url", null));
            return true;
        }
        if (!sender.hasPermission("vmse.url")) {
            sender.sendMessage(music.msg("no-permission", null));
            return true;
        }
        if (!music.isReady()) {
            sender.sendMessage(music.msg("voicechat-unavailable", null));
            return true;
        }
        String rawUrl = args[1];
        Collection<Player> targets = resolveTargets(sender, args[2], "vmse.url");
        if (targets == null) {
            return true;
        }
        sender.sendMessage(music.msg("loading", null));
        music.playUrl(rawUrl, targets, result -> {
            if (result.ok()) {
                sender.sendMessage(music.msg("started", Map.of("file", rawUrl)));
            } else {
                sender.sendMessage(music.msg("url-invalid", null));
            }
        });
        return true;
    }

    private boolean stop(CommandSender sender, String[] args) {
        String target = args.length >= 2 ? args[1] : "me";
        Collection<Player> targets = resolveTargets(sender, target, "vmse.stop.other");
        if (targets == null) {
            return true;
        }
        if (target.equalsIgnoreCase("me") && !sender.hasPermission("vmse.stop.me")) {
            sender.sendMessage(music.msg("no-permission", null));
            return true;
        }
        int stopped = 0;
        for (Player player : targets) {
            if (music.stop(player.getUniqueId())) {
                ++stopped;
            }
        }
        sender.sendMessage(stopped > 0 ? music.msg("stopped", null) : music.msg("no-playing", null));
        return true;
    }

    private boolean download(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(music.msg("usage.download", null));
            return true;
        }
        if (!sender.hasPermission("vmse.download")) {
            sender.sendMessage(music.msg("no-permission", null));
            return true;
        }
        String name = join(args, 2);
        sender.sendMessage(music.msg("loading", null));
        music.download(args[1], name, result -> {
            if (result.ok()) {
                sender.sendMessage(music.msg("downloaded", Map.of("file", result.value())));
            } else {
                sender.sendMessage(music.msg("download-failed", Map.of("error", result.value())));
            }
        });
        return true;
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vmse.reload")) {
            sender.sendMessage(music.msg("no-permission", null));
            return true;
        }
        String type = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "all";
        if (type.equals("config") || type.equals("all")) {
            plugin.reloadConfig();
        }
        if (type.equals("lang") || type.equals("all")) {
            music.reloadLanguage();
        }
        if (!(type.equals("config") || type.equals("lang") || type.equals("all"))) {
            sender.sendMessage(music.msg("usage.reload", null));
            return true;
        }
        music.ensureFolders();
        if (type.equals("config") || type.equals("all")) {
            // rebuild PCM cache and reload playlists/regions (hot-reload friendly)
            music.rebuildCache();
            if (plugin.getPlaylistManager() != null) {
                plugin.getPlaylistManager().loadAll();
            }
            if (plugin.getRegionManager() != null) {
                plugin.getRegionManager().load();
            }
        }
        sender.sendMessage(music.msg("reload", null));
        return true;
    }

    private boolean vmsg(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vmse.vmsg")) {
            sender.sendMessage(music.msg("no-permission", null));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(music.msg("player-only", null));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(music.msg("usage.vmsg", null));
            return true;
        }
        if (!music.isVoiceMessagesReady()) {
            sender.sendMessage(music.msg("vmsg-unavailable", null));
            return true;
        }
        String file = join(args, 1);
        sender.sendMessage(music.msg("loading", null));
        music.sendVoiceMessage(player, file, result -> {
            if (!result.ok()) {
                if ("not found".equalsIgnoreCase(result.value())) {
                    sender.sendMessage(music.msg("not-found", Map.of("file", file)));
                } else if (result.value().contains("no recipients")) {
                    sender.sendMessage(music.msg("vmsg-no-recipients", null));
                } else if (result.value().contains("voicemessages unavailable") || result.value().contains("voicechat unavailable")) {
                    sender.sendMessage(music.msg("vmsg-unavailable", null));
                } else if (result.value().contains("configured limit") || result.value().contains("duration limit")
                        || result.value().contains("longer than")) {
                    int limit = plugin.getConfig().getInt("vmsg.max-seconds", 600);
                    sender.sendMessage(music.msg("vmsg-too-long", Map.of("seconds", Integer.toString(limit))));
                } else {
                    plugin.getLogger().warning("/dmc vmsg failed for '" + file + "': " + result.value());
                    sender.sendMessage(music.msg("vmsg-failed", Map.of("error", result.value())));
                }
            }
        });
        return true;
    }

    private boolean pause(CommandSender sender, String[] args) {
        String target = args.length >= 2 ? args[1] : "me";
        if (target.equalsIgnoreCase("me")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(music.msg("player-only", null));
                return true;
            }
            Player player = (Player) sender;
            if (!sender.hasPermission("vmse.pause")) {
                sender.sendMessage(music.msg("no-permission", null));
                return true;
            }
            return togglePause(sender, player);
        }
        if (!sender.hasPermission("vmse.pause.other")) {
            sender.sendMessage(music.msg("no-permission", null));
            return true;
        }
        Collection<Player> targets = resolveTargets(sender, target, "vmse.pause.other");
        if (targets == null) {
            return true;
        }
        for (Player p : targets) {
            togglePause(sender, p);
        }
        return true;
    }

    private boolean togglePause(CommandSender sender, Player player) {
        if (music.isPaused(player.getUniqueId())) {
            if (music.resume(player.getUniqueId())) {
                sender.sendMessage(music.msg("resumed", null));
            } else {
                sender.sendMessage(music.msg("no-playing", null));
            }
        } else if (music.pause(player.getUniqueId())) {
            sender.sendMessage(music.msg("paused", null));
        } else {
            sender.sendMessage(music.msg("no-playing", null));
        }
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!sender.hasPermission("vmse.list")) {
            sender.sendMessage(music.msg("no-permission", null));
            return true;
        }
        List<String> files = music.listMusicFiles();
        if (files.isEmpty()) {
            sender.sendMessage(music.msg("list-empty", null));
            return true;
        }
        sender.sendMessage(music.msg("list-header", null));
        for (String file : files) {
            sender.sendMessage(music.msg("list-entry", Map.of("file", file)));
        }
        return true;
    }

    private Collection<Player> resolveTargets(CommandSender sender, String target, String otherPermission) {
        if (target.equalsIgnoreCase("me")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(music.msg("player-only", null));
                return null;
            }
            return List.of((Player) sender);
        }
        if (!sender.hasPermission(otherPermission)) {
            sender.sendMessage(music.msg("no-permission", null));
            return null;
        }
        if (target.equalsIgnoreCase("all")) {
            return music.onlinePlayers();
        }
        Player player = Bukkit.getPlayerExact(target);
        if (player == null) {
            sender.sendMessage(music.msg("player-not-found", Map.of("player", target)));
            return null;
        }
        return List.of(player);
    }

    /** 每页 8 行, 支持 /vmse help <页码> */
    private void sendHelpPage(CommandSender sender, int page) {
        java.util.List<String> lines = music.helpLines();
        if (lines.isEmpty()) {
            sender.sendMessage(music.msg("unknown-command", null));
            return;
        }
        int perPage = 8;
        int pages = (lines.size() + perPage - 1) / perPage;
        page = Math.max(1, Math.min(page, pages));
        sender.sendMessage(color("&8&m-----&r &3Vmse &b帮助 &e(&f" + page + "&e/&f" + pages + "&e) &8&m-----"));
        int from = (page - 1) * perPage;
        int to = Math.min(lines.size(), from + perPage);
        for (int i = from; i < to; i++) {
            sender.sendMessage(lines.get(i));
        }
        sender.sendMessage(color("&7使用 &e/vmse help <页码>&7 翻页, &e/vmse help <子命令>&7 查看用法"));
    }

    /** /vmse help <子命令>: 该子命令的详细用法 */
    private void sendCommandHelp(CommandSender sender, String sub) {
        java.util.Map<String, String> usage = new java.util.HashMap<>();
        usage.put("play", "&e/vmse play <玩家|me|all> <歌曲> [-original]&7 - 播放音乐(可跟 -original 原声)");
        usage.put("url", "&e/vmse url <url> <玩家|me|all>&7 - 播放网络音乐");
        usage.put("stop", "&e/vmse stop [玩家|me|all]&7 - 停止音乐");
        usage.put("pause", "&e/vmse pause [玩家|all]&7 - 暂停/继续");
        usage.put("download", "&e/vmse download <url> <名字>&7 - 下载音乐到 music 文件夹");
        usage.put("vmsg", "&e/vmse vmsg <歌曲>&7 - 发送音乐语音条");
        usage.put("list", "&e/vmse list&7 - 列出所有音乐");
        usage.put("reload", "&e/vmse reload [config|lang]&7 - 重载配置/语言");
        usage.put("surround", "&e/vmse surround [玩家|me] [stereo|5.1|mono]&7 - 立体声阵型(声源跟随玩家)");
        usage.put("playlist", "&e/vmse playlist <create|add|remove|play|stop|mode|list> ...&7 - 歌单管理");
        usage.put("region", "&e/vmse region <create|remove|list|info> ...&7 - 音乐区域管理");
        usage.put("hoe", "&e/vmse hoe&7 - 领取金锄头声源工具");
        usage.put("confirm", "&e/vmse confirm <source> <x> <y> <z> <区域>&7 - 确认放置声源");
        usage.put("effect", "&e/vmse effect <list|set|reset> [效果]&7 - 音效增强配置(蝰蛇/杜比/3D环绕/低音炮)");
        String u = usage.getOrDefault(sub, music.msg("unknown-command", null));
        sender.sendMessage(color(u));
        if (sub.equals("playlist")) {
            sender.sendMessage(color("&7例: &e/vmse playlist create 我的歌单 &7→ &e/vmse playlist add 我的歌单 歌曲 &7→ &e/vmse playlist play 我的歌单"));
        }
        if (sub.equals("region")) {
            sender.sendMessage(color("&7例: &e/vmse region create 大厅 circle 10 &7→ 在当前脚下建一个半径10格的圆形区域"));
        }
    }

    private static String color(String s) {
        return MusicManager.color(s);
    }

    private static String join(String[] args, int from) {
        return Arrays.stream(args, from, args.length).collect(Collectors.joining(" "));
    }

    private static String join(String[] args, int from, int to) {
        return Arrays.stream(args, from, Math.min(to, args.length)).collect(Collectors.joining(" "));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        int n = args.length;
        if (n == 1) {
            return partial(args[0], List.of("play", "url", "stop", "download", "reload", "vmsg", "pause",
                    "list", "surround", "playlist", "region", "hoe", "confirm", "effect", "help"));
        }
        // ---- play / url / stop / pause: 目标玩家 ----
        if (sub.equals("play") && n == 2) {
            return partial(args[1], List.of("me", "all"));
        }
        if (sub.equals("play") && n == 3) {
            return partial(args[2], music.listMusicFiles());
        }
        if (sub.equals("url") && n == 2) {
            return partial(args[1], List.of("me", "all"));
        }
        if (sub.equals("stop") || sub.equals("pause")) {
            return partial(args[n - 1], List.of("me", "all"));
        }
        // ---- download: url + 名字 ----
        if (sub.equals("download") && n >= 2) {
            return Collections.emptyList(); // URL 无法补全
        }
        // ---- reload ----
        if (sub.equals("reload") && n == 2) {
            return partial(args[1], List.of("config", "lang"));
        }
        // ---- vmsg: 歌名 ----
        if (sub.equals("vmsg")) {
            return partial(args[n - 1], music.listMusicFiles());
        }
        // ---- surround ----
        if (sub.equals("surround") && n == 2) {
            return partial(args[1], List.of("me", "all"));
        }
        if (sub.equals("surround") && n == 3) {
            return partial(args[2], List.of("stereo", "5.1", "mono"));
        }
        // ---- effect: 音效增强配置 ----
        if (sub.equals("effect") && n == 2) {
            return partial(args[1], List.of("list", "set", "reset"));
        }
        if (sub.equals("effect") && n == 3 && args[1].equalsIgnoreCase("set")) {
            return partial(args[2], List.of("viper", "dolby", "surround3d", "bass", "clear", "off"));
        }
        // ---- playlist ----
        if (sub.equals("playlist") && n == 2) {
            return partial(args[1], List.of("create", "add", "remove", "play", "stop", "mode", "list"));
        }
        if (sub.equals("playlist") && (n == 3 || n == 4)) {
            String op = args[1].toLowerCase(Locale.ROOT);
            List<String> plNames = new java.util.ArrayList<>();
            if (plugin.getPlaylistManager() != null) {
                for (Playlist pl : plugin.getPlaylistManager().accessible(sender.getName())) {
                    plNames.add(pl.name);
                }
            }
            if (n == 3 && (op.equals("add") || op.equals("remove") || op.equals("play")
                    || op.equals("mode") || op.equals("stop"))) {
                return partial(args[2], plNames);
            }
            if (n == 4 && op.equals("add")) {
                return partial(args[3], music.listMusicFiles()); // 补歌名
            }
            if (n == 4 && op.equals("mode")) {
                return partial(args[3], List.of("SEQUENTIAL", "SHUFFLE", "LOOP", "LOOP_ONE"));
            }
        }
        // ---- region ----
        if (sub.equals("region") && n == 2) {
            return partial(args[1], List.of("create", "remove", "list", "info"));
        }
        if (sub.equals("region") && n == 4 && args[1].equalsIgnoreCase("create")) {
            return partial(args[3], List.of("circle", "square"));
        }
        return Collections.emptyList();
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
    }

    private List<String> partial(String input, Collection<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    // ------------------------------------------------------------------
    // Surround (stereo / 5.1) toggles
    // ------------------------------------------------------------------
    private boolean surround(CommandSender sender, String[] args) {
        String target = args.length >= 2 ? args[1] : "me";
        String layoutArg = args.length >= 3 ? args[2] : "stereo";
        SurroundLayout layout = SurroundLayout.parse(layoutArg);
        if (layout == SurroundLayout.MONO) {
            // disable surround
            if (target.equalsIgnoreCase("me")) {
                if (!(sender instanceof Player pl)) {
                    sender.sendMessage(music.msg("player-only", null));
                    return true;
                }
                if (!sender.hasPermission("vmse.surround")) {
                    sender.sendMessage(music.msg("no-permission", null));
                    return true;
                }
                music.setSurround(pl.getUniqueId(), null);
                sender.sendMessage(music.msg("surround-off", null));
            } else {
                Player p = Bukkit.getPlayerExact(target);
                if (p == null) {
                    sender.sendMessage(music.msg("player-not-found", Map.of("player", target)));
                    return true;
                }
                if (!sender.hasPermission("vmse.surround.other")) {
                    sender.sendMessage(music.msg("no-permission", null));
                    return true;
                }
                music.setSurround(p.getUniqueId(), null);
                sender.sendMessage(music.msg("surround-player-off", Map.of("player", target)));
            }
            return true;
        }
        // enable surround
        if (target.equalsIgnoreCase("me")) {
            if (!(sender instanceof Player pl)) {
                sender.sendMessage(music.msg("player-only", null));
                return true;
            }
            if (!sender.hasPermission("vmse.surround")) {
                sender.sendMessage(music.msg("no-permission", null));
                return true;
            }
            music.setSurround(pl.getUniqueId(), layout);
            sender.sendMessage(music.msg("surround-on", Map.of("layout", layout.label())));
            // if a song is already playing for this player, restart it with surround
            if (music.isPlaying(pl.getUniqueId())) {
                String f = music.currentFile(pl.getUniqueId());
                if (f != null) {
                    sender.sendMessage(music.msg("loading", null));
                    music.playLocal(f, List.of(pl), r -> {
                        if (!r.ok()) {
                            sender.sendMessage(music.msg("invalid-audio", Map.of("file", f)));
                        }
                    });
                }
            }
        } else {
            Player p = Bukkit.getPlayerExact(target);
            if (p == null) {
                sender.sendMessage(music.msg("player-not-found", Map.of("player", target)));
                return true;
            }
            if (!sender.hasPermission("vmse.surround.other")) {
                sender.sendMessage(music.msg("no-permission", null));
                return true;
            }
            music.setSurround(p.getUniqueId(), layout);
            sender.sendMessage(music.msg("surround-on", Map.of("layout", layout.label())));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Playlists
    // ------------------------------------------------------------------
    private boolean playlist(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(music.msg("usage.playlist", null));
            return true;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        PlaylistManager pm = plugin.getPlaylistManager();
        if (pm == null) {
            sender.sendMessage(music.msg("playlist-not-found", Map.of("name", "?")));
            return true;
        }
        if (op.equals("create")) {
            if (args.length < 3) {
                sender.sendMessage(music.msg("usage.playlist", null));
                return true;
            }
            String name = args[2];
            boolean global = sender.hasPermission("vmse.playlist.admin") && args.length >= 4 && args[3].equalsIgnoreCase("global");
            if (pm.get(sender.getName(), name) != null) {
                sender.sendMessage(music.msg("playlist-exists", Map.of("name", name)));
                return true;
            }
            pm.getOrCreate(global ? "server" : sender.getName(), name, global);
            sender.sendMessage(music.msg("playlist-created", Map.of("name", name)));
            return true;
        }
        if (op.equals("list")) {
            List<Playlist> all = pm.accessible(sender.getName());
            if (all.isEmpty()) {
                sender.sendMessage(music.msg("playlist-none", null));
                return true;
            }
            for (Playlist pl : all) {
                sender.sendMessage(music.msg("playlist-header",
                        Map.of("name", pl.name, "n", String.valueOf(pl.songs.size()), "mode", pl.mode.label)));
            }
            return true;
        }
        // remaining ops need a playlist name
        if (args.length < 3) {
            sender.sendMessage(music.msg("usage.playlist", null));
            return true;
        }
        String name = args[2];
        Playlist pl = pm.get(sender.getName(), name);
        if (pl == null) {
            // allow admin to target global playlists too
            pl = pm.get("server", name);
        }
        if (pl == null && !sender.hasPermission("vmse.playlist.admin")) {
            sender.sendMessage(music.msg("playlist-not-found", Map.of("name", name)));
            return true;
        }
        if (pl == null) {
            pl = pm.get("server", name);
        }
        if (pl == null) {
            sender.sendMessage(music.msg("playlist-not-found", Map.of("name", name)));
            return true;
        }
        switch (op) {
            case "add" -> {
                if (args.length < 4) {
                    sender.sendMessage(music.msg("usage.playlist", null));
                    return true;
                }
                String song = join(args, 3);
                // 校验歌名对应的音乐文件确实存在
                if (!music.existsMusic(song)) {
                    sender.sendMessage(music.msg("not-found", Map.of("file", song)));
                    return true;
                }
                if (pl.add(song)) {
                    pm.save(pl);
                    sender.sendMessage(music.msg("playlist-added", Map.of("name", name, "song", song)));
                } else {
                    sender.sendMessage(music.msg("playlist-not-found", Map.of("name", song)));
                }
            }
            case "remove" -> {
                if (args.length < 4) {
                    sender.sendMessage(music.msg("usage.playlist", null));
                    return true;
                }
                String song = join(args, 3);
                if (pl.remove(song)) {
                    pm.save(pl);
                    sender.sendMessage(music.msg("playlist-removed", Map.of("name", name, "song", song)));
                } else {
                    sender.sendMessage(music.msg("playlist-not-found", Map.of("name", song)));
                }
            }
            case "mode" -> {
                if (args.length < 4) {
                    sender.sendMessage(music.msg("usage.playlist", null));
                    return true;
                }
                pl.mode = Playlist.Mode.parse(args[3]);
                pm.save(pl);
                sender.sendMessage(music.msg("playlist-mode", Map.of("name", name, "mode", pl.mode.label)));
            }
            case "play" -> {
                if (pl.songs.isEmpty()) {
                    sender.sendMessage(music.msg("playlist-empty", Map.of("name", name)));
                    return true;
                }
                // play first song as a starting point
                String songRef = pl.current();
                if (songRef == null) {
                    pl.cursor = 0;
                    songRef = pl.songs.get(0);
                }
                final String song = songRef;
                if (!(sender instanceof Player)) {
                    sender.sendMessage(music.msg("player-only", null));
                    return true;
                }
                sender.sendMessage(music.msg("loading", null));
                // 注册连播上下文: 播放结束后自动推进到歌单下一首
                music.startPlaylistPlayback(((Player) sender).getUniqueId(), pl);
                music.playLocal(song, List.of((Player) sender), r -> {
                    if (r.ok()) {
                        sender.sendMessage(music.msg("playlist-playing", Map.of("name", name, "song", song)));
                    } else {
                        sender.sendMessage(music.msg("invalid-audio", Map.of("file", song)));
                    }
                });
            }
            case "stop" -> {
                if (sender instanceof Player) {
                    music.stop(((Player) sender).getUniqueId());
                    sender.sendMessage(music.msg("stopped", null));
                }
            }
            default -> sender.sendMessage(music.msg("usage.playlist", null));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Music regions (admin)
    // ------------------------------------------------------------------
    private boolean region(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vmse.region")) {
            sender.sendMessage(music.msg("no-permission", null));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(music.msg("usage.region", null));
            return true;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        RegionManager rm = plugin.getRegionManager();
        if (rm == null) {
            sender.sendMessage(music.msg("region-not-found", Map.of("name", "?")));
            return true;
        }
        switch (op) {
            case "create" -> {
                if (args.length < 5 || !(sender instanceof Player pl)) {
                    sender.sendMessage(music.msg("usage.region", null));
                    return true;
                }
                String name = args[2];
                if (rm.get(name) != null) {
                    sender.sendMessage(music.msg("region-exists", Map.of("name", name)));
                    return true;
                }
                MusicRegion.Shape shape = MusicRegion.Shape.parse(args[3]);
                double radius;
                try {
                    radius = Double.parseDouble(args[4]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(music.msg("usage.region", null));
                    return true;
                }
                org.bukkit.Location loc = pl.getLocation();
                MusicRegion r = rm.create(name, loc.getWorld().getName(), shape,
                        loc.getX(), loc.getY(), loc.getZ(), radius);
                // optionally attach a playlist/track
                if (args.length >= 6) {
                    r.playlistName = join(args, 5);
                }
                rm.save();
                sender.sendMessage(music.msg("region-created", Map.of("name", name)));
            }
            case "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(music.msg("usage.region", null));
                    return true;
                }
                String name = args[2];
                if (rm.get(name) == null) {
                    sender.sendMessage(music.msg("region-not-found", Map.of("name", name)));
                    return true;
                }
                rm.remove(name);
                sender.sendMessage(music.msg("region-removed", Map.of("name", name)));
            }
            case "list" -> {
                List<MusicRegion> all = rm.all();
                if (all.isEmpty()) {
                    sender.sendMessage(music.msg("region-none", null));
                    return true;
                }
                for (MusicRegion r : all) {
                    sender.sendMessage(music.msg("region-entry", Map.of(
                            "name", r.name, "shape", r.shape.label,
                            "radius", String.valueOf((int) r.radius), "world", r.world)));
                }
            }
            default -> sender.sendMessage(music.msg("usage.region", null));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Golden hoe tool
    // ------------------------------------------------------------------
    private boolean hoe(CommandSender sender) {
        if (!sender.hasPermission("vmse.hoe")) {
            sender.sendMessage(music.msg("no-permission", null));
            return true;
        }
        if (!(sender instanceof Player pl)) {
            sender.sendMessage(music.msg("player-only", null));
            return true;
        }
        pl.getInventory().addItem(com.vmse.listener.HoeListener.makeHoe(plugin));
        sender.sendMessage(music.msg("hoe-got", null));
        return true;
    }

    // ------------------------------------------------------------------
    // Confirm (bandwidth / source placement)
    // ------------------------------------------------------------------
    private boolean confirm(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(music.msg("confirm-title", Map.of("prompt", "?")));
            return true;
        }
        String kind = args[1].toLowerCase(Locale.ROOT);
        if (kind.equals("source")) {
            // /vmse confirm source <x> <y> <z> <region>
            if (args.length < 6) {
                sender.sendMessage(music.msg("usage.region", null));
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage(music.msg("player-only", null));
                return true;
            }
            RegionManager rm = plugin.getRegionManager();
            if (rm == null) {
                return true;
            }
            MusicRegion r = rm.get(args[5]);
            if (r == null) {
                sender.sendMessage(music.msg("region-not-found", Map.of("name", args[5])));
                return true;
            }
            sender.sendMessage(music.msg("source-placed", Map.of(
                    "x", args[2], "y", args[3], "z", args[4])));
            return true;
        }
        if (kind.equals("bandwidth")) {
            // /vmse confirm bandwidth <target> <file>
            if (args.length < 4) {
                sender.sendMessage(music.msg("confirm-title", Map.of("prompt", "Bandwidth confirmation needed")));
                return true;
            }
            String target = args[2];
            String file = args[3];
            sender.sendMessage(music.msg("confirm-title", Map.of(
                    "prompt", "Are you sure you want to load &e" + file + " &7for &e" + target + "?")));
            return true;
        }
        sender.sendMessage(music.msg("unknown-command", null));
        return true;
    }

    // ------------------------------------------------------------------
    // Effect presets
    // ------------------------------------------------------------------
    private boolean effect(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(music.msg("effect-usage", null));
            return true;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "list" -> {
                StringBuilder sb = new StringBuilder(music.msg("effect-list-header", null));
                for (EffectPreset p : EffectPreset.values()) {
                    if (p == EffectPreset.NONE) continue;
                    sb.append("\n").append(music.msg("effect-list-entry",
                            Map.of("id", p.id, "label", p.label,
                                    "bass", String.valueOf(p.bass),
                                    "mid", String.valueOf(p.mid),
                                    "treble", String.valueOf(p.treble))));
                }
                sender.sendMessage(sb.toString());
            }
            case "set" -> {
                if (args.length < 3) {
                    sender.sendMessage(music.msg("effect-set-usage", null));
                    return true;
                }
                EffectPreset preset = EffectPreset.parse(args[2]);
                String target = args.length >= 4 ? args[3] : "me";
                if (target.equalsIgnoreCase("me")) {
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(music.msg("player-only", null));
                        return true;
                    }
                    music.setEffect(((Player) sender).getUniqueId(), preset);
                    sender.sendMessage(music.msg("effect-set",
                            Map.of("preset", preset.label)));
                } else {
                    Player p = Bukkit.getPlayerExact(target);
                    if (p == null) {
                        sender.sendMessage(music.msg("player-not-found", Map.of("player", target)));
                        return true;
                    }
                    if (!sender.hasPermission("vmse.effect.other")) {
                        sender.sendMessage(music.msg("no-permission", null));
                        return true;
                    }
                    music.setEffect(p.getUniqueId(), preset);
                    sender.sendMessage(music.msg("effect-set-other",
                            Map.of("preset", preset.label, "player", target)));
                }
            }
            case "info" -> {
                String id = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
                EffectPreset p = EffectPreset.parse(id);
                if (p == EffectPreset.NONE && !id.isEmpty()) {
                    sender.sendMessage(music.msg("effect-not-found", Map.of("id", id)));
                    return true;
                }
                sender.sendMessage(music.msg("effect-info",
                        Map.of("id", p.id, "label", p.label,
                                "bass", String.valueOf(p.bass),
                                "mid", String.valueOf(p.mid),
                                "treble", String.valueOf(p.treble))));
            }
            default -> sender.sendMessage(music.msg("effect-usage", null));
        }
        return true;
    }
}
