package com.hivemind.model;

import java.util.ArrayList;
import java.util.List;

/** 一次模型调用请求。 */
public record CompletionRequest(TaskType taskType,
                                String systemPrompt,
                                List<ChatMessage> messages,
                                int maxTokens,
                                double temperature,
                                boolean jsonMode) {

    public static CompletionRequest of(TaskType taskType, String systemPrompt, List<ChatMessage> messages) {
        return new CompletionRequest(taskType, systemPrompt, List.copyOf(messages), 1024, 0.2, false);
    }

    public static CompletionRequest json(TaskType taskType, String systemPrompt, String userText) {
        return new CompletionRequest(taskType, systemPrompt, List.of(ChatMessage.user(userText)), 2048, 0.0, true);
    }

    public CompletionRequest withMaxTokens(int maxTokens) {
        return new CompletionRequest(taskType, systemPrompt, messages, maxTokens, temperature, jsonMode);
    }

    /** 追加消息（不可变风格，便于 Agent 循环里安全地构造下一轮上下文）。 */
    public CompletionRequest append(ChatMessage message) {
        List<ChatMessage> next = new ArrayList<>(messages);
        next.add(message);
        return new CompletionRequest(taskType, systemPrompt, List.copyOf(next), maxTokens, temperature, jsonMode);
    }

    public String lastUserText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("user".equals(message.role())) {
                return message.content();
            }
        }
        return "";
    }
}
