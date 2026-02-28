package jp.vstone.sotavisiontest;

/**
 * Social relationship states between Sota and a user.
 * Transitions are based on cumulative interaction count.
 *
 * STRANGER      -> first encounter, no history
 * ACQUAINTANCE  -> after 1 interaction
 * FRIENDLY      -> after 5 interactions
 * CLOSE         -> after 15 interactions
 */
public enum SocialState {

    STRANGER("stranger", 0),
    ACQUAINTANCE("acquaintance", 1),
    FRIENDLY("friendly", 5),
    CLOSE("close", 15);

    private final String label;
    private final int minInteractions;

    SocialState(String label, int minInteractions) {
        this.label = label;
        this.minInteractions = minInteractions;
    }

    public String getLabel() {
        return label;
    }

    public int getMinInteractions() {
        return minInteractions;
    }

    /**
     * Resolve the correct SocialState for a given interaction count.
     */
    public static SocialState fromInteractionCount(int count) {
        if (count >= CLOSE.minInteractions) return CLOSE;
        if (count >= FRIENDLY.minInteractions) return FRIENDLY;
        if (count >= ACQUAINTANCE.minInteractions) return ACQUAINTANCE;
        return STRANGER;
    }

    /**
     * Parse from a stored string label. Falls back to STRANGER.
     */
    public static SocialState fromLabel(String label) {
        if (label == null) return STRANGER;
        for (SocialState s : values()) {
            if (s.label.equalsIgnoreCase(label)) return s;
        }
        return STRANGER;
    }
}
