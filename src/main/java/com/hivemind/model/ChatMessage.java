package com.hivemind.model;

/** 一条对话消息。role: system / user / assistant / tool。 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static ChatMessage tool(String content) {
        return new ChatMessage("tool", content);
    }
}
