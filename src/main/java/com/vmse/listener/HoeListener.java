package com.vmse.listener;

import com.vmse.VmsePlugin;
import com.vmse.manager.MusicManager;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The Vmse golden-hoe tool. An admin holding it can right-click inside a music
 * region to be prompted (in chat) whether to place a sound source at that
 * location. For Vmse 2.0 the sound source is represented by a particle/visual
 * hint; the actual spatial source follows the region's anchor already.
 */
public class HoeListener implements Listener {

    private static final String TOOL_TAG = "vmse-hoe";
    private final VmsePlugin plugin;

    public HoeListener(VmsePlugin plugin) {
        this.plugin = plugin;
    }

    /** Build the hoe item (displayed with custom model / name). */
    public static ItemStack makeHoe(VmsePlugin plugin) {
        ItemStack hoe = new ItemStack(Material.GOLDEN_HOE);
        ItemMeta meta = hoe.getItemMeta();
        if (meta != null) {
            String name = plugin.getConfig().getString("hoe-tool.item-name", "&b声源锄 &7(音乐区域工具)");
            meta.setDisplayName(MusicManager.color(name));
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(MusicManager.color("&7在音乐区域内右键放置声源"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey(plugin, TOOL_TAG),
                    org.bukkit.persistence.PersistentDataType.BOOLEAN, true);
            hoe.setItemMeta(meta);
        }
        return hoe;
    }

    public static boolean isHoe(VmsePlugin plugin, ItemStack item) {
        if (item == null || item.getType() != Material.GOLDEN_HOE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(
                new org.bukkit.NamespacedKey(plugin, TOOL_TAG),
                org.bukkit.persistence.PersistentDataType.BOOLEAN);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        ItemStack item = event.getItem();
        if (!isHoe(plugin, item)) {
            return;
        }
        org.bukkit.entity.Player p = event.getPlayer();
        if (!p.hasPermission("vmse.hoe")) {
            p.sendMessage(MusicManager.color(plugin.getMusicManager().msg("no-permission", null)));
            return;
        }
        event.setCancelled(true);
        com.vmse.region.MusicRegion region = plugin.getRegionManager().regionOf(p);
        if (region == null) {
            p.sendMessage(MusicManager.color(plugin.getMusicManager().msg("hoe-not-in-region", null)));
            return;
        }
        org.bukkit.Location loc = p.getLocation();
        // chat prompt asking whether to place a sound source here
        String prompt = plugin.getMusicManager().msg("hoe-prompt",
                java.util.Map.of("x", String.valueOf((int) loc.getX()),
                        "y", String.valueOf((int) loc.getY()),
                        "z", String.valueOf((int) loc.getZ()),
                        "region", region.name));
        // clickable confirm uses /vmse confirm source <x> <y> <z> <region>
        net.md_5.bungee.api.chat.TextComponent tc =
                new net.md_5.bungee.api.chat.TextComponent(MusicManager.color(prompt));
        String cmd = "/vmse confirm source " + (int) loc.getX() + " " + (int) loc.getY()
                + " " + (int) loc.getZ() + " " + region.name;
        net.md_5.bungee.api.chat.ClickEvent click = new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, cmd);
        tc.setClickEvent(click);
        p.spigot().sendMessage(tc);
    }
}