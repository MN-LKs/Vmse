package com.vmse.spatial;

/**
 * Surround speaker layout definitions. Each layout describes a set of sound
 * source positions relative to the listener's facing direction, and which
 * audio channel feeds each source (for source-stereo files). A value of -1
 * means the source plays the mono downmix.
 */
public enum SurroundLayout {
    /** Single source at the listener - original DSMPMusic behaviour. */
    MONO("mono", 1),

    /** Two sources: front-left and front-right. */
    STEREO("stereo", 2),

    /** Classic 5.1: FL, FR, C, LFE, RL, RR. */
    SURROUND_51("5.1", 6);

    public final String id;
    public final int sourceCount;

    SurroundLayout(String id, int sourceCount) {
        this.id = id;
        this.sourceCount = sourceCount;
    }

    public static SurroundLayout parse(String s) {
        if (s == null) {
            return MONO;
        }
        switch (s.toLowerCase().trim()) {
            case "stereo":
            case "2.0":
            case "2":
                return STEREO;
            case "5.1":
            case "surround":
            case "51":
                return SURROUND_51;
            default:
                return MONO;
        }
    }

    /**
     * Unit direction vector for each source, in the listener's local frame:
     * x = right(+)/left(-), z = forward(+)/back(-), y = up/down.
     * Angles: yaw rotation about Y; LFE sits low (y negative).
     * <p>
     * Returns {dx, dy, dz} per source.
     */
    public double[][] sourceOffsets() {
        switch (this) {
            case STEREO:
                return new double[][]{
                        {-1, 0, 1}, // FL (left-forward)
                        {+1, 0, 1}  // FR (right-forward)
                };
            case SURROUND_51:
                return new double[][]{
                        {-1.0, 0.0, 1.0},  // FL
                        {+1.0, 0.0, 1.0},  // FR
                        {0.0, 0.0, 1.2},   // C
                        {0.0, -0.15, 0.4}, // LFE (low)
                        {-1.1, 0.0, -1.0}, // RL
                        {+1.1, 0.0, -1.0}  // RR
                };
            case MONO:
            default:
                return new double[][]{{0, 0, 0}};
        }
    }

    /**
     * Which AudioData channel feeds each source. Returns channel index, or -1
     * for the mono downmix. For stereo files: FL=0, FR=1; 5.1 with 2 source
     * channels routes the same L/R to the front, mono downmix to C/LFE/rear.
     */
    public int[] sourceChannelMapping() {
        switch (this) {
            case STEREO:
                return new int[]{0, 1};
            case SURROUND_51:
                return new int[]{0, 1, -1, -1, -1, -1};
            case MONO:
            default:
                return new int[]{-1};
        }
    }

    /** Per-source volume gain (relative). LFE slightly attenuated, rears full. */
    public float[] sourceGains() {
        switch (this) {
            case STEREO:
                return new float[]{1.0f, 1.0f};
            case SURROUND_51:
                return new float[]{1.0f, 1.0f, 0.85f, 0.7f, 1.0f, 1.0f};
            case MONO:
            default:
                return new float[]{1.0f};
        }
    }

    /** Display name for messages. */
    public String label() {
        return this == MONO ? "单声源" : this == STEREO ? "双声道" : "5.1 环绕";
    }
}