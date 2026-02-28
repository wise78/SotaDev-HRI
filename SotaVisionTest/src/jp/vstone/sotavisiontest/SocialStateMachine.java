package jp.vstone.sotavisiontest;

/**
 * Evaluates and transitions the social state of a UserProfile
 * based on interaction count thresholds.
 *
 * Transition rules:
 *   0  interactions -> STRANGER
 *   1+ interactions -> ACQUAINTANCE
 *   5+ interactions -> FRIENDLY
 *  15+ interactions -> CLOSE
 */
public class SocialStateMachine {

    private static final String TAG = "SocialStateMachine";

    // ----------------------------------------------------------------
    // Evaluate and update
    // ----------------------------------------------------------------

    /**
     * Evaluate the correct social state for the given interaction count.
     * Does NOT modify the profile — call updateState() for that.
     */
    public SocialState evaluate(int interactionCount) {
        return SocialState.fromInteractionCount(interactionCount);
    }

    /**
     * Update the social state of a profile based on its current interaction count.
     * Returns true if a state transition occurred.
     */
    public boolean updateState(UserProfile profile) {
        SocialState current = profile.getSocialState();
        SocialState next = evaluate(profile.getInteractionCount());

        if (current != next) {
            log("State transition for " + profile.getName() + ": "
                + current.getLabel() + " -> " + next.getLabel()
                + " (interactions=" + profile.getInteractionCount() + ")");
            profile.setSocialState(next);
            return true;
        }
        return false;
    }

    /**
     * Get a human-readable description of the relationship level.
     * Used for conversation context injection.
     */
    public String getRelationshipDescription(UserProfile profile) {
        switch (profile.getSocialState()) {
            case STRANGER:
                return "This is a new person you've never met before.";
            case ACQUAINTANCE:
                return "You've met " + profile.getName() + " a few times. "
                     + "You know their name but are still getting to know them.";
            case FRIENDLY:
                return "You and " + profile.getName() + " are becoming good friends. "
                     + "You've had " + profile.getInteractionCount()
                     + " conversations together.";
            case CLOSE:
                return "You and " + profile.getName() + " are close friends. "
                     + "You've shared many conversations ("
                     + profile.getInteractionCount() + " interactions) "
                     + "and know each other well.";
            default:
                return "Unknown relationship state.";
        }
    }

    /**
     * Get the number of interactions remaining until the next state upgrade.
     * Returns -1 if already at CLOSE (max state).
     */
    public int interactionsUntilNextState(UserProfile profile) {
        int count = profile.getInteractionCount();
        SocialState current = profile.getSocialState();

        switch (current) {
            case STRANGER:
                return SocialState.ACQUAINTANCE.getMinInteractions() - count;
            case ACQUAINTANCE:
                return SocialState.FRIENDLY.getMinInteractions() - count;
            case FRIENDLY:
                return SocialState.CLOSE.getMinInteractions() - count;
            case CLOSE:
                return -1;  // already max
            default:
                return -1;
        }
    }

    // ----------------------------------------------------------------
    // Logging
    // ----------------------------------------------------------------

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
