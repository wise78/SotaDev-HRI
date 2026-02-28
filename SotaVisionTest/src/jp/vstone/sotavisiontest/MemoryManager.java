package jp.vstone.sotavisiontest;

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
 * Persists UserProfile data as a JSON file on disk.
 * Provides lookup by userId, add, update, and save operations.
 *
 * File format: a JSON array of UserProfile objects.
 * Uses manual JSON parsing — no external libraries needed.
 */
public class MemoryManager {

    private static final String TAG = "MemoryManager";

    private final String filePath;
    private final Map<String, UserProfile> profiles;   // keyed by userId

    // ----------------------------------------------------------------
    // Constructor
    // ----------------------------------------------------------------

    public MemoryManager(String dataDir) {
        // Ensure data directory exists
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.filePath = dataDir + File.separator + "user_profiles.json";
        this.profiles = new HashMap<String, UserProfile>();
        loadProfiles();
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /** Get a profile by userId. Returns null if not found. */
    public UserProfile getProfile(String userId) {
        return profiles.get(userId);
    }

    /** Get a profile by name (case-insensitive). Returns null if not found. */
    public UserProfile getProfileByName(String name) {
        if (name == null) return null;
        for (UserProfile p : profiles.values()) {
            if (name.equalsIgnoreCase(p.getName())) {
                return p;
            }
        }
        return null;
    }

    /** Add or replace a profile. Saves immediately. */
    public void addProfile(UserProfile profile) {
        profiles.put(profile.getUserId(), profile);
        saveProfiles();
        log("Added profile: " + profile);
    }

    /** Record a new interaction: increment count, update timestamp, save. */
    public void updateInteraction(String userId) {
        UserProfile p = profiles.get(userId);
        if (p != null) {
            p.recordInteraction();
            saveProfiles();
            log("Updated interaction for " + p.getName()
                + " -> count=" + p.getInteractionCount());
        }
    }

    /** Update the short memory summary for a user. */
    public void updateMemorySummary(String userId, String summary) {
        UserProfile p = profiles.get(userId);
        if (p != null) {
            p.setShortMemorySummary(summary);
            saveProfiles();
        }
    }

    /** Update the social state for a user. */
    public void updateSocialState(String userId, SocialState newState) {
        UserProfile p = profiles.get(userId);
        if (p != null) {
            SocialState old = p.getSocialState();
            p.setSocialState(newState);
            saveProfiles();
            if (old != newState) {
                log("Social state transition for " + p.getName()
                    + ": " + old.getLabel() + " -> " + newState.getLabel());
            }
        }
    }

    /** Get all stored profiles. */
    public List<UserProfile> getAllProfiles() {
        return new ArrayList<UserProfile>(profiles.values());
    }

    /** Generate a unique userId based on timestamp + counter. */
    public String generateUserId() {
        return "user_" + System.currentTimeMillis() + "_" + profiles.size();
    }

    // ----------------------------------------------------------------
    // Persistence — load / save JSON
    // ----------------------------------------------------------------

    /** Load profiles from the JSON file on disk. */
    public void loadProfiles() {
        profiles.clear();
        File file = new File(filePath);
        if (!file.exists()) {
            log("No profile file found at " + filePath + ". Starting fresh.");
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
                log("Profile file is empty or malformed. Starting fresh.");
                return;
            }

            // Split the JSON array into individual objects
            List<String> objects = splitJsonArray(content);
            for (String obj : objects) {
                try {
                    UserProfile p = UserProfile.fromJson(obj);
                    if (p.getUserId() != null && !p.getUserId().isEmpty()) {
                        profiles.put(p.getUserId(), p);
                    }
                } catch (Exception e) {
                    log("WARN: Skipping malformed profile entry: " + e.getMessage());
                }
            }
            log("Loaded " + profiles.size() + " profiles from " + filePath);

        } catch (Exception e) {
            log("ERROR loading profiles: " + e.getMessage());
        }
    }

    /** Save all profiles to the JSON file on disk. */
    public void saveProfiles() {
        try {
            PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(filePath), "UTF-8"));
            pw.println("[");
            int i = 0;
            for (UserProfile p : profiles.values()) {
                if (i > 0) pw.println(",");
                pw.print("  " + p.toJson());
                i++;
            }
            pw.println();
            pw.println("]");
            pw.close();
        } catch (Exception e) {
            log("ERROR saving profiles: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // JSON array splitter — extract top-level {...} objects from [...]
    // ----------------------------------------------------------------

    private List<String> splitJsonArray(String arrayJson) {
        List<String> objects = new ArrayList<String>();
        int depth = 0;
        int objStart = -1;

        for (int i = 0; i < arrayJson.length(); i++) {
            char c = arrayJson.charAt(i);

            // Skip string contents
            if (c == '"') {
                i++;
                while (i < arrayJson.length()) {
                    char sc = arrayJson.charAt(i);
                    if (sc == '\\') { i++; }  // skip escaped char
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

    // ----------------------------------------------------------------
    // Logging
    // ----------------------------------------------------------------

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
