package com.hivemind.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 元信息。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hiveMindOpenApi() {
        return new OpenAPI().info(new Info()
                .title("HiveMind API")
                .version("1.0.0")
                .description("同构蜂巢智能体平台：多模型路由 / 经验蒸馏传播 / 三权分立自改门禁 / 集群选举"));
    }
}
