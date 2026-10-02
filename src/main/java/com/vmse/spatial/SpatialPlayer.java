package com.vmse.spatial;

import com.vmse.audio.AudioData;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.List;
import java.util.UUID;

/**
 * Plays one audio track through a spatial layout of {@link LocationalAudioChannel}
 * sources around a moving anchor (a player, or a fixed region centre). Each
 * source is an independent AudioPlayer fed from the matching audio channel,
 * so a stereo file genuinely separates L/R across the world, and 5.1 gets six
 * distinct positions. Sources re-anchor on a configurable tick interval so the
 * effect tracks the listener.
 */
public class SpatialPlayer {
    private final VoicechatServerApi api;
    private final Plugin plugin;
    private final ServerLevel level;
    private final AudioData audio;
    private final SurroundLayout layout;
    private final boolean fallbackToMono;
    private final float masterVolume;
    private final float reverbGain;
    private final boolean useReverbSim;
    private volatile boolean acousticEnabled;
    private volatile org.bukkit.World acousticWorld;
    private volatile float acousticStrength;

    private final List<SourceImpl> sources = new ArrayList<>();
    private volatile boolean stopped;
    private volatile boolean paused;
    private final List<OpusEncoder> encoders = new ArrayList<>();
    private BukkitTask followTask;

    private interface Source {
        AudioPlayer player();
        LocationalAudioChannel channel();
        void stop();
        void close();
    }

    public SpatialPlayer(VoicechatServerApi api, Plugin plugin, ServerLevel level, AudioData audio,
                         SurroundLayout layout, boolean fallbackToMono,
                         float masterVolume, boolean useReverbSim, float reverbGain) {
        this.api = api;
        this.plugin = plugin;
        this.level = level;
        this.audio = audio;
        this.layout = fallbackToMono && audio.channelCount < 2 ? SurroundLayout.MONO : layout;
        this.fallbackToMono = fallbackToMono;
        this.masterVolume = masterVolume;
        this.useReverbSim = useReverbSim;
        this.reverbGain = reverbGain;
        this.acousticEnabled = false;
    }

    /** One concrete sound source at a world position. */
    private final class SourceImpl implements Source {
        final int srcIndex;
        final int feedChannel;   // -1 = mono
        final float baseGain;
        volatile float occlGain = 1f;   // dynamic acoustic attenuation
        int cursor;                      // independent read position for THIS source
        LocationalAudioChannel channel;
        AudioPlayer player;
        OpusEncoder encoder;

        SourceImpl(int srcIndex, int feedChannel, float gain) {
            this.srcIndex = srcIndex;
            this.feedChannel = feedChannel;
            this.baseGain = gain;
        }

        @Override
        public AudioPlayer player() {
            return player;
        }

        @Override
        public LocationalAudioChannel channel() {
            return channel;
        }

        @Override
        public void stop() {
            if (player != null) {
                player.stopPlaying();
            }
        }

        @Override
        public void close() {
            if (channel != null) {
                // locational channels have no clearTargets; drop the filter
                // so the sound fades naturally as players move out of range.
                try {
                    channel.setFilter(p -> false);
                } catch (Exception ignored) {
                }
            }
            if (encoder != null) {
                try {
                    encoder.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Create the spatial sources. {@code targets} are the players that will hear
     * this music; if null, the channel has no explicit target (region case uses
     * {@code getPlayersInRange}).
     */
    public void start(List<de.maxhenkel.voicechat.api.VoicechatConnection> targets, Location anchor) {
        // convenience overload: fixed anchor (region) - sources do not track a moving player
        start(targets, () -> anchor);
    }

    /**
     * Create the spatial sources. {@code targets} are the players that will hear
     * this music; if null, the channel has no explicit target.
     * {@code anchorProvider} yields the anchor position each tick; for a player
     * this is the player's live location so the sources genuinely track them.
     */
    public void start(List<de.maxhenkel.voicechat.api.VoicechatConnection> targets, Supplier<Location> anchorProvider) {
        int[] mapping = layout.sourceChannelMapping();
        float[] gains = layout.sourceGains();
        Location initial = anchorProvider == null ? null : anchorProvider.get();
        if (initial == null) {
            initial = new Location(level.getServerLevel() instanceof org.bukkit.World w ? w : null, 0, 0, 0);
        }
        for (int i = 0; i < mapping.length; i++) {
            SourceImpl s = new SourceImpl(i, mapping[i], gains[i] * masterVolume);
            s.channel = api.createLocationalAudioChannel(UUID.randomUUID(), level, positionAt(initial, i));
            if (s.channel == null) {
                continue;
            }
            // distance (voicechat meters) - scale so the surround reads well
            s.channel.setDistance(layout == SurroundLayout.SURROUND_51 ? 6f : 3.5f);
            if (targets != null && !targets.isEmpty()) {
                // restrict audibility to the given players via filter
                s.channel.setFilter(sp -> targets.stream()
                        .anyMatch(c -> {
                            de.maxhenkel.voicechat.api.ServerPlayer sp2 = c.getPlayer();
                            return sp2 != null && sp2.getPlayer() instanceof org.bukkit.entity.Player bukkit
                                    && sp.getPlayer() instanceof org.bukkit.entity.Player b2
                                    && bukkit.getUniqueId().equals(b2.getUniqueId());
                        }));
            }
            s.encoder = api.createEncoder(OpusEncoderMode.AUDIO);
            final int feed = s.feedChannel;
            s.player = api.createAudioPlayer(s.channel, s.encoder, () -> nextFrame(feed, s));
            s.player.startPlaying();
            sources.add(s);
        }
        stopped = false;
        // follow task re-anchors the sources every N ticks
        int interval = Math.max(2, plugin.getConfig().getInt("surround.follow-interval", 10));
        final Location[] cachedAnchor = {initial};
        followTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (stopped || paused) {
                return;
            }
            Location live = anchorProvider == null ? null : anchorProvider.get();
            if (live == null) {
                return;
            }
            cachedAnchor[0] = live;
            for (SourceImpl s : sources) {
                if (s.channel != null) {
                    s.channel.updateLocation(positionAt(live, s.srcIndex));
                }
            }
        }, interval, interval);
    }

    private short[] nextFrame(int feedChannel, SourceImpl s) {
        if (stopped || paused) {
            return null;
        }
        int pos = s.cursor;
        if (pos >= audio.sampleCount()) {
            return null;
        }
        // feed from the matching audio channel (or mono downmix).
        // each source reads its own cursor: mono-fed sources (centre/rear in
        // 5.1) read the mono stream independently so nothing is double-consumed.
        short[] from = feedChannel < 0 ? audio.mono() : audio.channel(feedChannel);
        int end = Math.min(pos + 960, audio.sampleCount());
        short[] frame = new short[960];
        System.arraycopy(from, pos, frame, 0, end - pos);
        s.cursor = end;
        // per-source gain: base layout gain * acoustic occlusion factor
        float effGain = s.baseGain * s.occlGain;
        if (Math.abs(effGain - 1.0f) > 0.001f) {
            for (int i = 0; i < frame.length; i++) {
                frame[i] = (short) Math.max(-32768, Math.min(32767, Math.round(frame[i] * effGain)));
            }
        }
        if (useReverbSim && layout == SurroundLayout.SURROUND_51 && s.feedChannel < 0) {
            // subtle reverb on mono-fed rear/centre via mild attenuation
            for (int i = 0; i < frame.length; i++) {
                frame[i] = (short) Math.max(-32768, Math.min(32767, Math.round(frame[i] * (1.0f - reverbGain * 0.3f))));
            }
        }
        return frame;
    }

    /**
     * Enable environment acoustics for a fixed-anchor (region) player.
     * Every N ticks we ray-sample occlusion from each source position to the
     * given listener location and scale each source's gain accordingly.
     *
     * @param strength config region.acoustic-filter (0..1); 0 disables.
     */
    public void enableAcoustics(org.bukkit.World world, Location listener, float strength) {
        if (strength <= 0f || world == null) {
            this.acousticEnabled = false;
            return;
        }
        this.acousticEnabled = true;
        this.acousticWorld = world;
        this.acousticStrength = Math.min(1f, strength);
        // a second task keeps the occlusion per source updated
        int interval = Math.max(4, plugin.getConfig().getInt("region.acoustic-interval", 20));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (stopped || paused || !acousticEnabled || listener == null || acousticWorld == null) {
                return;
            }
            org.bukkit.World w = acousticWorld;
            for (SourceImpl s : sources) {
                if (s.channel == null) {
                    continue;
                }
                // source world position (channel location), listener may move
                org.bukkit.Location srcLoc = null;
                try {
                    de.maxhenkel.voicechat.api.Position pos = s.channel.getLocation();
                    if (pos != null) {
                        srcLoc = new org.bukkit.Location(w, pos.getX(), pos.getY(), pos.getZ());
                    }
                } catch (Exception ignored) {
                }
                if (srcLoc == null) {
                    continue;
                }
                float occ = Acoustics.occlusion(w, srcLoc, listener, 12);
                // apply attenuation + reverb simulation to this source's gain
                float att = Acoustics.attenuate(occ, acousticStrength);
                float rev = useReverbSim ? Acoustics.reverb(occ, reverbGain) : 0f;
                s.occlGain = Math.max(0.06f, Math.min(1f, att + rev));
            }
        }, interval, interval);
    }

    private Position positionAt(Location anchor, int srcIndex) {
        double[] off = layout.sourceOffsets()[srcIndex];
        // Minecraft yaw: 0 = facing -Z, increases clockwise (east = +90).
        // Local frame: x = right, z = forward(-Z). World mapping:
        //   right  = ( -sin(yaw), 0, -cos(yaw) )
        //   forward = ( -cos(yaw), 0,  sin(yaw) )
        double yaw = Math.toRadians(anchor.getYaw());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        double radius = layout == SurroundLayout.SURROUND_51
                ? plugin.getConfig().getDouble("surround.surround-radius", 4.0)
                : layout == SurroundLayout.STEREO
                ? plugin.getConfig().getDouble("surround.stereo-separation", 3.0)
                : 0.0;
        // source world offset = right*off[0] + forward*off[2]
        double wx = (-sin) * off[0] + (-cos) * off[2];
        double wz = (-cos) * off[0] + (sin) * off[2];
        return api.createPosition(
                anchor.getX() + wx * radius,
                anchor.getY() + off[1],
                anchor.getZ() + wz * radius);
    }

    public void pause() {
        paused = true;
        for (Source s : sources) {
            s.stop();
        }
    }

    public void resume() {
        paused = false;
        // restart cursors from current position for each source
        for (SourceImpl s : sources) {
            if (s.player != null) {
                s.player.startPlaying();
            }
        }
    }

    public boolean isPlaying() {
        if (stopped) {
            return false;
        }
        for (Source s : sources) {
            if (s.player() != null && s.player().isPlaying()) {
                return true;
            }
        }
        return false;
    }

    public void stop() {
        stopped = true;
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
        for (Source s : sources) {
            s.stop();
        }
        for (Source s : sources) {
            s.close();
        }
        sources.clear();
    }

    public int sourceCount() {
        return sources.size();
    }
}