package com.jiang.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j 接口文档配置
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("智学伴 API")
                        .version("1.0.0")
                        .description("智学伴（StudyMate）— AI 自适应学习辅助小程序后端接口文档")
                        .contact(new Contact()
                                .name("StudyMate Team")
                                .email("support@studymate.com")));
    }
}
