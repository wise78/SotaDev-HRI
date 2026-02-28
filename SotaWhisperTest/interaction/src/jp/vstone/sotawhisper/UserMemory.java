package jp.vstone.sotawhisper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User memory system for WhisperInteraction.
 * Stores and persists user profiles (name, origin, language, social state, etc.)
 * for face recognition and personalized interaction.
 *
 * Combines UserProfile + MemoryManager + SocialState into a single file.
 * Hand-rolled JSON for Java 1.8 robot compatibility (no Gson/Jackson).
 */
public class UserMemory {

    private static final String TAG = "UserMemory";

    private final String filePath;
    private final Map profiles;  // String userId -> UserProfile

    // ================================================================
    // Inner enum: SocialState
    // ================================================================

    public static final String SOCIAL_STRANGER     = "stranger";
    public static final String SOCIAL_ACQUAINTANCE = "acquaintance";
    public static final String SOCIAL_FRIENDLY     = "friendly";
    public static final String SOCIAL_CLOSE        = "close";

    /** Get social state label based on interaction count. */
    public static String socialStateFromCount(int count) {
        if (count >= 15) return SOCIAL_CLOSE;
        if (count >= 5)  return SOCIAL_FRIENDLY;
        if (count >= 1)  return SOCIAL_ACQUAINTANCE;
        return SOCIAL_STRANGER;
    }

    // ================================================================
    // Inner class: UserProfile
    // ================================================================

    public static class UserProfile {
        public String userId;
        public String name;
        public int    estimatedAge;
        public String gender;              // "male", "female", "unknown"
        public String origin;              // country/region (user-confirmed)
        public String detectedLanguage;    // Whisper language code
        public String preferredLanguage;   // user's actual preferred language
        public String culturalContext;     // free-text cultural notes
        public int    interactionCount;
        public long   lastInteractionTime;
        public String socialState;
        public String shortMemorySummary;  // LLM-generated summary of past conversations

        public UserProfile() {
            this.userId = "";
            this.name = "";
            this.estimatedAge = 0;
            this.gender = "unknown";
            this.origin = "";
            this.detectedLanguage = "";
            this.preferredLanguage = "";
            this.culturalContext = "";
            this.interactionCount = 0;
            this.lastInteractionTime = 0;
            this.socialState = SOCIAL_STRANGER;
            this.shortMemorySummary = "";
        }

        public UserProfile(String userId, String name) {
            this();
            this.userId = userId;
            this.name = name;
            this.lastInteractionTime = System.currentTimeMillis();
        }

        /** Record a new interaction: increment count, update timestamp, update social state. */
        public void recordInteraction() {
            this.interactionCount++;
            this.lastInteractionTime = System.currentTimeMillis();
            this.socialState = socialStateFromCount(this.interactionCount);
        }

        /** Serialize to JSON string. */
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"userId\":\"").append(esc(userId)).append("\",");
            sb.append("\"name\":\"").append(esc(name)).append("\",");
            sb.append("\"estimatedAge\":").append(estimatedAge).append(",");
            sb.append("\"gender\":\"").append(esc(gender)).append("\",");
            sb.append("\"origin\":\"").append(esc(origin)).append("\",");
            sb.append("\"detectedLanguage\":\"").append(esc(detectedLanguage)).append("\",");
            sb.append("\"preferredLanguage\":\"").append(esc(preferredLanguage)).append("\",");
            sb.append("\"culturalContext\":\"").append(esc(culturalContext)).append("\",");
            sb.append("\"interactionCount\":").append(interactionCount).append(",");
            sb.append("\"lastInteractionTime\":").append(lastInteractionTime).append(",");
            sb.append("\"socialState\":\"").append(esc(socialState)).append("\",");
            sb.append("\"shortMemorySummary\":\"").append(esc(shortMemorySummary)).append("\"");
            sb.append("}");
            return sb.toString();
        }

        /** Deserialize from JSON string. */
        public static UserProfile fromJson(String json) {
            UserProfile p = new UserProfile();
            p.userId              = extractStr(json, "userId");
            p.name                = extractStr(json, "name");
            p.estimatedAge        = extractInt(json, "estimatedAge");
            p.gender              = extractStr(json, "gender");
            p.origin              = extractStr(json, "origin");
            p.detectedLanguage    = extractStr(json, "detectedLanguage");
            p.preferredLanguage   = extractStr(json, "preferredLanguage");
            p.culturalContext     = extractStr(json, "culturalContext");
            p.interactionCount    = extractInt(json, "interactionCount");
            p.lastInteractionTime = extractLong(json, "lastInteractionTime");
            p.socialState         = extractStr(json, "socialState");
            p.shortMemorySummary  = extractStr(json, "shortMemorySummary");
            if (p.socialState == null || p.socialState.isEmpty()) {
                p.socialState = SOCIAL_STRANGER;
            }
            if (p.gender == null || p.gender.isEmpty()) {
                p.gender = "unknown";
            }
            return p;
        }

        public String toString() {
            return "UserProfile{" + name + ", origin=" + origin
                 + ", interactions=" + interactionCount
                 + ", state=" + socialState + "}";
        }
    }

    // ================================================================
    // Constructor
    // ================================================================

    public UserMemory(String dataDir) {
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.filePath = dataDir + File.separator + "user_profiles.json";
        this.profiles = new HashMap();
        loadProfiles();
    }

    // ================================================================
    // Public API
    // ================================================================

    /** Get profile by userId. Returns null if not found. */
    public UserProfile getProfileByUserId(String userId) {
        return (UserProfile) profiles.get(userId);
    }

    /** Get profile by name (case-insensitive). Returns null if not found. */
    public UserProfile getProfileByName(String name) {
        if (name == null) return null;
        Object[] values = profiles.values().toArray();
        for (int i = 0; i < values.length; i++) {
            UserProfile p = (UserProfile) values[i];
            if (name.equalsIgnoreCase(p.name)) {
                return p;
            }
        }
        return null;
    }

    /** Add or replace a profile. Saves immediately. */
    public void addProfile(UserProfile profile) {
        profiles.put(profile.userId, profile);
        saveProfiles();
        log("Added profile: " + profile);
    }

    /** Update an existing profile. Saves immediately. */
    public void updateProfile(UserProfile profile) {
        profiles.put(profile.userId, profile);
        saveProfiles();
    }

    /** Get all profiles. */
    public List getAllProfiles() {
        return new ArrayList(profiles.values());
    }

    /** Generate a unique userId. */
    public String generateUserId() {
        return "user_" + System.currentTimeMillis() + "_" + profiles.size();
    }

    /** Get profile count. */
    public int getProfileCount() {
        return profiles.size();
    }

    // ================================================================
    // Persistence — JSON file I/O
    // ================================================================

    public void loadProfiles() {
        profiles.clear();
        File file = new File(filePath);
        if (!file.exists()) {
            log("No profile file at " + filePath + ". Starting fresh.");
            return;
        }

        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            String content = sb.toString().trim();
            if (content.isEmpty() || !content.startsWith("[")) {
                log("Profile file empty or malformed. Starting fresh.");
                return;
            }

            List objects = splitJsonArray(content);
            for (int i = 0; i < objects.size(); i++) {
                try {
                    UserProfile p = UserProfile.fromJson((String) objects.get(i));
                    if (p.userId != null && !p.userId.isEmpty()) {
                        profiles.put(p.userId, p);
                    }
                } catch (Exception e) {
                    log("WARN: Skipping malformed profile: " + e.getMessage());
                }
            }
            log("Loaded " + profiles.size() + " profiles from " + filePath);

        } catch (Exception e) {
            log("ERROR loading profiles: " + e.getMessage());
        }
    }

    public void saveProfiles() {
        try {
            PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(filePath), "UTF-8"));
            pw.println("[");
            Object[] values = profiles.values().toArray();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) pw.println(",");
                pw.print("  " + ((UserProfile) values[i]).toJson());
            }
            pw.println();
            pw.println("]");
            pw.close();
        } catch (Exception e) {
            log("ERROR saving profiles: " + e.getMessage());
        }
    }

    // ================================================================
    // JSON array splitter — extract top-level {...} from [...]
    // ================================================================

    private List splitJsonArray(String arrayJson) {
        List objects = new ArrayList();
        int depth = 0;
        int objStart = -1;

        for (int i = 0; i < arrayJson.length(); i++) {
            char c = arrayJson.charAt(i);

            if (c == '"') {
                i++;
                while (i < arrayJson.length()) {
                    char sc = arrayJson.charAt(i);
                    if (sc == '\\') { i++; }
                    else if (sc == '"') { break; }
                    i++;
                }
                continue;
            }

            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    objects.add(arrayJson.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return objects;
    }

    // ================================================================
    // JSON helpers
    // ================================================================

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    static String extractStr(String json, String key) {
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

    // ================================================================
    // Language-to-country mapping (for culture inference)
    // ================================================================

    /** Map Whisper language code to likely country/region. */
    public static String languageToCountry(String langCode) {
        if (langCode == null) return null;
        if ("zh".equals(langCode)) return "China";
        if ("ja".equals(langCode)) return "Japan";
        if ("ko".equals(langCode)) return "Korea";
        if ("id".equals(langCode)) return "Indonesia";
        if ("ms".equals(langCode)) return "Malaysia";
        if ("th".equals(langCode)) return "Thailand";
        if ("vi".equals(langCode)) return "Vietnam";
        if ("hi".equals(langCode)) return "India";
        if ("ar".equals(langCode)) return "the Middle East";
        if ("fr".equals(langCode)) return "France";
        if ("de".equals(langCode)) return "Germany";
        if ("es".equals(langCode)) return "Spain";
        if ("pt".equals(langCode)) return "Brazil";
        if ("ru".equals(langCode)) return "Russia";
        if ("it".equals(langCode)) return "Italy";
        if ("nl".equals(langCode)) return "the Netherlands";
        if ("tr".equals(langCode)) return "Turkey";
        if ("pl".equals(langCode)) return "Poland";
        if ("sv".equals(langCode)) return "Sweden";
        if ("fi".equals(langCode)) return "Finland";
        return null;  // unknown or English (too ambiguous)
    }

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
