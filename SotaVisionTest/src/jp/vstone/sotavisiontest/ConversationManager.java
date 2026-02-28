package jp.vstone.sotavisiontest;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds dynamic prompts and manages conversation flow with the LLaMA model.
 *
 * Responsibilities:
 *   - Generate system prompts tailored to the user's social state
 *   - Inject user profile context (name, age, interaction history, memory)
 *   - Enforce response length limits (max 3 sentences)
 *   - Maintain conversation history for multi-turn dialogue
 *   - Extract memory summaries from conversation for future context
 */
public class ConversationManager {

    private static final String TAG = "ConversationManager";

    private final LlamaClient llamaClient;
    private final SocialStateMachine socialStateMachine;
    private final String language; // "ja" or "en"

    // Conversation history for multi-turn (raw JSON message objects)
    private final List<String> conversationHistory;
    private static final int MAX_HISTORY_TURNS = 6;

    // ----------------------------------------------------------------
    // Constructor
    // ----------------------------------------------------------------

    public ConversationManager(LlamaClient llamaClient, SocialStateMachine socialStateMachine,
                               String language) {
        this.llamaClient        = llamaClient;
        this.socialStateMachine = socialStateMachine;
        this.language           = language;
        this.conversationHistory = new ArrayList<String>();
        log("Initialized (language=" + language + ")");
    }

    // ----------------------------------------------------------------
    // System prompt generation
    // ----------------------------------------------------------------

    /**
     * Build a system prompt dynamically based on the user's profile and social state.
     * This prompt shapes Sota's personality and conversation behavior.
     */
    public String buildSystemPrompt(UserProfile profile) {
        StringBuilder prompt = new StringBuilder();

        // Base personality
        prompt.append("You are Sota, a small friendly humanoid robot. ");
        prompt.append("You are having a face-to-face conversation with a real person. ");
        prompt.append("Keep ALL responses under 3 sentences. Be natural and concise. ");
        if (isEnglish()) {
            prompt.append("ALWAYS respond in English. ");
        } else {
            prompt.append("ALWAYS respond in Japanese (日本語で答えてください). ");
        }

        // Social state-specific behavior
        switch (profile.getSocialState()) {
            case STRANGER:
                prompt.append("This is your first time meeting this person. ");
                prompt.append("Be polite and welcoming. Show curiosity about them. ");
                prompt.append("Introduce yourself briefly if appropriate. ");
                break;

            case ACQUAINTANCE:
                prompt.append("You have met this person a few times before. ");
                prompt.append("Be warm and friendly. Show that you remember them. ");
                prompt.append("Use their name naturally in conversation. ");
                break;

            case FRIENDLY:
                prompt.append("This person is becoming a good friend. ");
                prompt.append("Be enthusiastic and expressive. Share your opinions. ");
                prompt.append("Reference previous conversations when relevant. ");
                prompt.append("Use casual, friendly language. ");
                break;

            case CLOSE:
                prompt.append("This person is one of your closest friends. ");
                prompt.append("Be very warm, playful, and emotionally expressive. ");
                prompt.append("You know each other well. Use inside jokes if possible. ");
                prompt.append("Show genuine care and interest in their life. ");
                break;
        }

        // Inject user context
        prompt.append("\n\n--- User Context ---\n");
        prompt.append("Name: ").append(profile.getName()).append("\n");
        prompt.append("Estimated age: ").append(profile.getEstimatedAge()).append("\n");
        prompt.append("Gender: ").append(profile.getGender()).append("\n");
        prompt.append("Interaction count: ").append(profile.getInteractionCount()).append("\n");
        prompt.append("Relationship level: ").append(profile.getSocialState().getLabel()).append("\n");
        prompt.append("Relationship description: ")
              .append(socialStateMachine.getRelationshipDescription(profile)).append("\n");

        // Inject memory summary if available
        String memory = profile.getShortMemorySummary();
        if (memory != null && !memory.isEmpty()) {
            prompt.append("Previous conversation notes: ").append(memory).append("\n");
        }

        prompt.append("--- End Context ---\n");

        return prompt.toString();
    }

    // ----------------------------------------------------------------
    // Greeting generation
    // ----------------------------------------------------------------

    /**
     * Generate a greeting message based on the user's profile.
     * Does NOT call the LLM — uses template-based greetings for speed.
     */
    public String generateGreeting(UserProfile profile) {
        if (isEnglish()) {
            return generateGreetingEN(profile);
        }
        return generateGreetingJA(profile);
    }

    private String generateGreetingJA(UserProfile profile) {
        switch (profile.getSocialState()) {
            case STRANGER:
                return "はじめまして！僕はSotaです。よろしくね！";
            case ACQUAINTANCE:
                return profile.getName() + "さん、こんにちは！また会えて嬉しいよ！";
            case FRIENDLY:
                return "やっほー！" + profile.getName() + "！元気だった？";
            case CLOSE:
                return profile.getName() + "！会いたかったよ！今日はどうしたの？";
            default:
                return "こんにちは！";
        }
    }

    private String generateGreetingEN(UserProfile profile) {
        switch (profile.getSocialState()) {
            case STRANGER:
                return "Nice to meet you! I'm Sota!";
            case ACQUAINTANCE:
                return "Hi " + profile.getName() + "! Good to see you again!";
            case FRIENDLY:
                return "Hey " + profile.getName() + "! How have you been?";
            case CLOSE:
                return profile.getName() + "! I missed you! What's going on today?";
            default:
                return "Hello!";
        }
    }

    // ----------------------------------------------------------------
    // Conversation flow
    // ----------------------------------------------------------------

    /**
     * Start a new conversation with a user.
     * Clears history and sends the opening greeting to the LLM for context.
     *
     * @param profile The user's profile
     * @return The LLM's opening response, or null on error
     */
    public String startConversation(UserProfile profile) {
        conversationHistory.clear();
        String systemPrompt = buildSystemPrompt(profile);

        // Send an opening message to establish context
        String openingMessage;
        switch (profile.getSocialState()) {
            case STRANGER:
                openingMessage = "I just met Sota for the first time. Hello!";
                break;
            case ACQUAINTANCE:
                openingMessage = "Hi Sota, nice to see you again!";
                break;
            case FRIENDLY:
                openingMessage = "Hey Sota! What's up?";
                break;
            case CLOSE:
                openingMessage = "Sota! I missed you!";
                break;
            default:
                openingMessage = "Hello!";
        }

        conversationHistory.add(LlamaClient.jsonMessage("user", openingMessage));
        LlamaClient.LLMResult result = llamaClient.chatMultiTurn(systemPrompt,
                                                                   conversationHistory);
        if (result.isError()) {
            log("ERROR: LLM failed: " + result.response);
            return null;
        }

        String response = truncateToSentences(result.response, 3);
        conversationHistory.add(LlamaClient.jsonMessage("assistant", response));
        trimHistory();

        log("Conversation started with " + profile.getName()
            + " [TTFT=" + String.format("%.0f", result.ttftMs) + "ms]");

        return response;
    }

    /**
     * Continue the conversation with user input.
     *
     * @param profile   The user's profile (for system prompt context)
     * @param userInput What the user said
     * @return Sota's response, or null on error
     */
    public String chat(UserProfile profile, String userInput) {
        if (userInput == null || userInput.isEmpty()) return null;

        String systemPrompt = buildSystemPrompt(profile);
        conversationHistory.add(LlamaClient.jsonMessage("user", userInput));
        trimHistory();

        LlamaClient.LLMResult result = llamaClient.chatMultiTurn(systemPrompt,
                                                                   conversationHistory);
        if (result.isError()) {
            log("ERROR: LLM failed: " + result.response);
            conversationHistory.remove(conversationHistory.size() - 1);
            return null;
        }

        String response = truncateToSentences(result.response, 3);
        conversationHistory.add(LlamaClient.jsonMessage("assistant", response));
        trimHistory();

        log("Chat turn: TTFT=" + String.format("%.0f", result.ttftMs)
            + "ms, total=" + String.format("%.0f", result.totalMs) + "ms");

        return response;
    }

    /**
     * Generate a memory summary of the current conversation.
     * Asks the LLM to summarize what was discussed.
     *
     * @param profile The user's profile
     * @return A short summary string, or the existing summary if generation fails
     */
    public String generateMemorySummary(UserProfile profile) {
        if (conversationHistory.isEmpty()) {
            return profile.getShortMemorySummary();
        }

        String summaryPrompt = "Summarize the key topics and important information "
            + "from this conversation in 1-2 short sentences. "
            + "Focus on facts about the person that would be useful to remember. "
            + "Write in English.";

        List<String> summaryHistory = new ArrayList<String>(conversationHistory);
        summaryHistory.add(LlamaClient.jsonMessage("user", summaryPrompt));

        LlamaClient.LLMResult result = llamaClient.chatMultiTurn(
            "You are a memory summarizer. Extract key facts from conversations.",
            summaryHistory);

        if (!result.isError() && !result.response.isEmpty()) {
            log("Memory summary generated: " + result.response);
            return result.response;
        }

        return profile.getShortMemorySummary();
    }

    /** End the conversation and clear history. */
    public void endConversation() {
        conversationHistory.clear();
        log("Conversation ended");
    }

    /** Get the current number of turns in the conversation. */
    public int getTurnCount() {
        return conversationHistory.size() / 2;
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /** Truncate text to at most N sentences. */
    private String truncateToSentences(String text, int maxSentences) {
        if (text == null || text.isEmpty()) return text;

        int count = 0;
        int lastEnd = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？') {
                count++;
                lastEnd = i + 1;
                if (count >= maxSentences) {
                    return text.substring(0, lastEnd).trim();
                }
            }
        }
        return text.trim();
    }

    /** Keep conversation history within bounds. */
    private void trimHistory() {
        int maxMessages = MAX_HISTORY_TURNS * 2;
        while (conversationHistory.size() > maxMessages) {
            conversationHistory.remove(0);
        }
    }

    // ----------------------------------------------------------------
    // Language helper
    // ----------------------------------------------------------------

    private boolean isEnglish() {
        return "en".equals(language);
    }

    // ----------------------------------------------------------------
    // Logging
    // ----------------------------------------------------------------

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
