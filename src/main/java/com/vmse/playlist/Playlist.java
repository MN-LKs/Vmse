package com.vmse.playlist;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named ordered list of songs. Supports playback modes: sequential, shuffle,
 * loop (repeat all) and loop-one (repeat single track).
 */
public class Playlist {

    public enum Mode {
        SEQUENTIAL("顺序"),
        SHUFFLE("随机"),
        LOOP("循环"),
        LOOP_ONE("单曲循环");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public static Mode parse(String s) {
            if (s == null) {
                return SEQUENTIAL;
            }
            switch (s.toLowerCase().trim()) {
                case "shuffle":
                case "random":
                case "随机":
                    return SHUFFLE;
                case "loop":
                case "循环":
                    return LOOP;
                case "loopone":
                case "single":
                case "单曲循环":
                    return LOOP_ONE;
                default:
                    return SEQUENTIAL;
            }
        }
    }

    public final UUID id = UUID.randomUUID();
    public String name;
    public String owner;
    public boolean global;
    public Mode mode = Mode.SEQUENTIAL;
    public final List<String> songs = new ArrayList<>();
    public int cursor = 0;

    public Playlist(String name, String owner, boolean global) {
        this.name = name;
        this.owner = owner;
        this.global = global;
    }

    public boolean add(String song) {
        if (song == null || song.isBlank()) {
            return false;
        }
        String s = song.trim();
        if (songs.contains(s)) {
            return false;
        }
        songs.add(s);
        return true;
    }

    public boolean remove(String song) {
        return songs.remove(song);
    }

    /** Next song index according to mode and cursor. Returns -1 if empty. */
    public int nextIndex() {
        if (songs.isEmpty()) {
            return -1;
        }
        switch (mode) {
            case SHUFFLE:
                return (int) (Math.random() * songs.size());
            case LOOP_ONE:
                return Math.max(0, cursor % songs.size());
            case LOOP:
                return (cursor + 1) % songs.size();
            case SEQUENTIAL:
            default:
                if (cursor >= songs.size()) {
                    return -1;
                }
                return cursor;
        }
    }

    public String current() {
        if (songs.isEmpty() || cursor < 0 || cursor >= songs.size()) {
            return null;
        }
        return songs.get(cursor);
    }

    public void advance() {
        int n = nextIndex();
        if (n >= 0) {
            cursor = n;
        }
    }
}