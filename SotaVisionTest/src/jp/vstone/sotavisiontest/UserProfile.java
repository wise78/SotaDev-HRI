package jp.vstone.sotavisiontest;

/**
 * Stores all known information about a single user.
 * Serialized to/from JSON by MemoryManager.
 */
public class UserProfile {

    private String  userId;
    private String  name;
    private int     estimatedAge;
    private String  gender;            // "male", "female", "unknown"
    private int     interactionCount;
    private long    lastInteractionTime;
    private SocialState socialState;
    private String  shortMemorySummary;

    // ----------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------

    public UserProfile() {
        this.userId = "";
        this.name = "";
        this.estimatedAge = 0;
        this.gender = "unknown";
        this.interactionCount = 0;
        this.lastInteractionTime = 0;
        this.socialState = SocialState.STRANGER;
        this.shortMemorySummary = "";
    }

    public UserProfile(String userId, String name, int estimatedAge, String gender) {
        this.userId = userId;
        this.name = name;
        this.estimatedAge = estimatedAge;
        this.gender = gender;
        this.interactionCount = 0;
        this.lastInteractionTime = System.currentTimeMillis();
        this.socialState = SocialState.STRANGER;
        this.shortMemorySummary = "";
    }

    // ----------------------------------------------------------------
    // Getters & Setters
    // ----------------------------------------------------------------

    public String getUserId()                     { return userId; }
    public void   setUserId(String userId)        { this.userId = userId; }

    public String getName()                       { return name; }
    public void   setName(String name)            { this.name = name; }

    public int    getEstimatedAge()               { return estimatedAge; }
    public void   setEstimatedAge(int age)        { this.estimatedAge = age; }

    public String getGender()                     { return gender; }
    public void   setGender(String gender)        { this.gender = gender; }

    public int    getInteractionCount()           { return interactionCount; }
    public void   setInteractionCount(int count)  { this.interactionCount = count; }

    public long   getLastInteractionTime()                  { return lastInteractionTime; }
    public void   setLastInteractionTime(long millis)       { this.lastInteractionTime = millis; }

    public SocialState getSocialState()                     { return socialState; }
    public void        setSocialState(SocialState state)    { this.socialState = state; }

    public String getShortMemorySummary()                   { return shortMemorySummary; }
    public void   setShortMemorySummary(String summary)     { this.shortMemorySummary = summary; }

    // ----------------------------------------------------------------
    // Increment interaction and update timestamp
    // ----------------------------------------------------------------

    public void recordInteraction() {
        this.interactionCount++;
        this.lastInteractionTime = System.currentTimeMillis();
    }

    // ----------------------------------------------------------------
    // JSON serialization (manual — no Gson, Java 1.8 compatible)
    // ----------------------------------------------------------------

    /** Serialize this profile to a JSON object string. */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"userId\":\"").append(escJson(userId)).append("\",");
        sb.append("\"name\":\"").append(escJson(name)).append("\",");
        sb.append("\"estimatedAge\":").append(estimatedAge).append(",");
        sb.append("\"gender\":\"").append(escJson(gender)).append("\",");
        sb.append("\"interactionCount\":").append(interactionCount).append(",");
        sb.append("\"lastInteractionTime\":").append(lastInteractionTime).append(",");
        sb.append("\"socialState\":\"").append(socialState.getLabel()).append("\",");
        sb.append("\"shortMemorySummary\":\"").append(escJson(shortMemorySummary)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    /** Deserialize a JSON object string into a UserProfile. */
    public static UserProfile fromJson(String json) {
        UserProfile p = new UserProfile();
        p.userId              = extractString(json, "userId");
        p.name                = extractString(json, "name");
        p.estimatedAge        = extractInt(json, "estimatedAge");
        p.gender              = extractString(json, "gender");
        p.interactionCount    = extractInt(json, "interactionCount");
        p.lastInteractionTime = extractLong(json, "lastInteractionTime");
        p.socialState         = SocialState.fromLabel(extractString(json, "socialState"));
        p.shortMemorySummary  = extractString(json, "shortMemorySummary");
        return p;
    }

    // ----------------------------------------------------------------
    // Minimal JSON helpers
    // ----------------------------------------------------------------

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    static String extractString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return "";
        start += pattern.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"')  { sb.append('"');  i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else if (next == 'n')  { sb.append('\n'); i++; }
                else if (next == 'r')  { sb.append('\r'); i++; }
                else if (next == 't')  { sb.append('\t'); i++; }
                else { sb.append(c); }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static int extractInt(String json, String key) {
        return (int) extractLong(json, key);
    }

    static long extractLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return 0;
        start += pattern.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if ((c >= '0' && c <= '9') || (c == '-' && sb.length() == 0)) {
                sb.append(c);
            } else {
                break;
            }
        }
        if (sb.length() == 0) return 0;
        return Long.parseLong(sb.toString());
    }

    @Override
    public String toString() {
        return "UserProfile{" + name + ", age=" + estimatedAge
             + ", interactions=" + interactionCount
             + ", state=" + socialState.getLabel() + "}";
    }
}
