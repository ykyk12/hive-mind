package com.hivemind.model;

import java.util.Map;

/** 模型提供方。实现方只负责"把请求发出去、把文本取回来"，路由与降级由 ModelRouter 统一负责。 */
public interface ModelProvider {

    ModelCaps caps();

    CompletionResponse complete(CompletionRequest request);

    /** 未配置密钥/地址的实现返回 false，路由时直接跳过，避免把"没配"当成"失败"。 */
    default boolean available() {
        return caps().configured();
    }

    default Map<String, Object> diagnostics() {
        return Map.of("id", caps().id(), "provider", caps().provider(), "available", available());
    }
}
