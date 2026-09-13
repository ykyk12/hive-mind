package com.hivemind.model;

/** 一次模型调用结果。 */
public record CompletionResponse(String providerId,
                                 String model,
                                 String text,
                                 int promptTokens,
                                 int completionTokens,
                                 long latencyMillis) {

    public double estimatedCost(double costPer1kIn, double costPer1kOut) {
        return promptTokens / 1000.0 * costPer1kIn + completionTokens / 1000.0 * costPer1kOut;
    }
}
