package com.vmse.spatial;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Simplified environment acoustics for music regions.
 * <p>
 * Sound propagating from a fixed source (region centre) to a listener is
 * attenuated by opaque blocks in between. We sample along the line-of-sight
 * ray (Bresenham-style, step = 1 block) and count how many opaque blocks the
 * ray passes through; the occlusion ratio drives a gain reduction plus a mild
 * "reverb" (the denser the wall, the more muffled / quieter the sound).
 * <p>
 * This is intentionally cheap: we only re-evaluate every N ticks and only for
 * listeners inside a region, so it adds negligible server load.
 */
public final class Acoustics {

    private Acoustics() {
    }

    /**
     * Occlusion factor 0..1 between two points in a world.
     * 0 = clear line of sight (outdoor / open), 1 = fully walled-off.
     *
     * @param samples number of ray samples (higher = more accurate, costlier)
     */
    public static float occlusion(World world, Location from, Location to, int samples) {
        if (world == null || from == null || to == null || from.getWorld() == null
                || !from.getWorld().equals(to.getWorld())) {
            return 0f;
        }
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.5) {
            return 0f;
        }
        int n = Math.max(2, samples);
        int opaqueHits = 0;
        for (int i = 1; i < n; i++) {
            double t = (double) i / n;
            Location sample = from.clone().add(dx * t, dy * t, dz * t);
            Block b = world.getBlockAt(sample.getBlockX(), sample.getBlockY(), sample.getBlockZ());
            if (isSoundBlocking(b)) {
                opaqueHits++;
            }
        }
        return (float) opaqueHits / n;
    }

    /**
     * Whether a block occludes sound. Air, water (slight) and transparent
     * blocks let sound through; solid full blocks block it. Leaves/glass are
     * treated as partial (0.35) to feel "open" but muffled.
     */
    private static boolean isSoundBlocking(Block b) {
        if (b == null || b.isEmpty() || b.isLiquid()) {
            return false;
        }
        if (!b.getType().isOccluding()) {
            // transparent blocks: treat special partial blockers as passable
            String name = b.getType().name();
            if (name.contains("LEAVES") || name.contains("GLASS") || name.equals("ICE")) {
                return false;
            }
            return false;
        }
        return true;
    }

    /**
     * Final gain applied to a source for a given occlusion factor.
     * Occlusion 0 -> 1.0; occlusion 1 -> attenuated to (1 - strength).
     * Reverb simulation: slightly *boosts* the very first reflections by a
     * small amount when partially occluded (gives a "room" feel), then rolls
     * off as occlusion increases.
     */
    public static float attenuate(float occlusion, float strength) {
        if (occlusion <= 0f) {
            return 1f;
        }
        float s = Math.max(0f, Math.min(1f, strength));
        // linear rolloff; keep a floor so sound never fully vanishes
        float gain = 1f - occlusion * s;
        return Math.max(0.06f, Math.min(1f, gain));
    }

    /** Room-reverb feel: max around occlusion 0.35, zero at 0 or 1. */
    public static float reverb(float occlusion, float reverbGain) {
        if (occlusion <= 0.02f || occlusion >= 0.98f) {
            return 0f;
        }
        // bell curve peaked at 0.35
        double bell = Math.exp(-Math.pow((occlusion - 0.35) / 0.22, 2));
        return (float) (reverbGain * bell);
    }
}