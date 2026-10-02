package com.vmse.audio;

/**
 * Built-in audio enhancement presets. Each preset is a set of mild DSP
 * parameters (bass / mid / treble shelf gains, virtual-surround space, reverb)
 * that {@link DspEngine} applies to decoded PCM. They rebalance frequencies and
 * widen the perceived stage without ever re-introducing clipping.
 */
public enum EffectPreset {

    NONE(0, "off", "关闭", 1.0f, 1.0f, 1.0f, 0.0f, 0.0f),
    VIPER(1, "viper", "蝰蛇音效", 1.2f, 0.9f, 1.0f, 0.15f, 0.10f),
    VIPER_PANO(2, "viper_pano", "蝰蛇全景音", 1.2f, 1.05f, 1.1f, 0.30f, 0.18f),
    DOLBY(3, "dolby", "杜比音效", 1.1f, 1.2f, 0.9f, 0.35f, 0.0f),
    SURROUND_3D(4, "surround3d", "3D环绕", 1.0f, 1.15f, 1.0f, 0.45f, 0.22f),
    BASS_BOOST(5, "bass", "低音炮", 1.45f, 0.4f, 0.8f, 0.0f, 0.0f),
    CLEAR(6, "clear", "纯净人声", 0.8f, 1.2f, 1.15f, 0.0f, 0.0f);

    public final int ordinal;
    public final String id;
    public final String label;
    public final float bass;
    public final float mid;
    public final float treble;
    public final float space;
    public final float reverb;

    EffectPreset(int ordinal, String id, String label, float bass, float mid, float treble,
                 float space, float reverb) {
        this.ordinal = ordinal;
        this.id = id;
        this.label = label;
        this.bass = bass;
        this.mid = mid;
        this.treble = treble;
        this.space = space;
        this.reverb = reverb;
    }

    public static EffectPreset parse(String s) {
        if (s == null) {
            return NONE;
        }
        switch (s.toLowerCase().trim()) {
            case "viper":
            case "蝰蛇":
                return VIPER;
            case "viper_pano":
            case "全景":
            case "蝰蛇全景":
                return VIPER_PANO;
            case "dolby":
            case "杜比":
                return DOLBY;
            case "surround3d":
            case "3d":
            case "环绕":
                return SURROUND_3D;
            case "bass":
            case "低音炮":
                return BASS_BOOST;
            case "clear":
            case "人声":
                return CLEAR;
            default:
                return NONE;
        }
    }
}