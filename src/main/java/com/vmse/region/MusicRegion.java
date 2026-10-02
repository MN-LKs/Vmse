package com.vmse.region;

import com.vmse.playlist.Playlist;

import java.util.UUID;

/**
 * A music region: a named circular or rectangular zone in one world. When a
 * player is inside, the region plays its playlist (optionally with surround),
 * with acoustic filtering based on block occlusion.
 */
public class MusicRegion {

    public enum Shape {
        CIRCLE("圆形"),
        SQUARE("方形");

        public final String label;

        Shape(String label) {
            this.label = label;
        }

        public static Shape parse(String s) {
            if (s == null) {
                return CIRCLE;
            }
            switch (s.toLowerCase().trim()) {
                case "square":
                case "rect":
                case "方形":
                    return SQUARE;
                default:
                    return CIRCLE;
            }
        }
    }

    public final UUID id = UUID.randomUUID();
    public String name;
    public String world;
    public Shape shape = Shape.CIRCLE;
    public double x;
    public double y;
    public double z;
    /** Radius (blocks). For SQUARE this is half the side length. */
    public double radius;
    /** Playlist name (global playlist) or null for single-track mode. */
    public String playlistName;
    /** Fixed track for single-track mode. */
    public String track;
    public float volume = 1.0f;
    public boolean surround = true;
    public String surroundLayout = "stereo";

    public MusicRegion(String name, String world) {
        this.name = name;
        this.world = world;
    }

    /** True when the point is inside the region bounds. */
    public boolean contains(String worldName, double px, double py, double pz) {
        if (worldName == null || !world.equalsIgnoreCase(worldName)) {
            return false;
        }
        double dx = px - x;
        double dz = pz - z;
        switch (shape) {
            case SQUARE:
                return Math.abs(dx) <= radius && Math.abs(dz) <= radius && Math.abs(py - y) <= radius;
            case CIRCLE:
            default:
                return dx * dx + dz * dz <= radius * radius && Math.abs(py - y) <= radius;
        }
    }

    /** Distance ratio 0..1 from the region centre (for volume / gain). */
    public double distanceRatio(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        double d = Math.sqrt(dx * dx + dz * dz);
        return Math.max(0.0, Math.min(1.0, d / Math.max(1.0, radius)));
    }
}