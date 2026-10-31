package com.jiang.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
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

    private static final String SECURITY_SCHEME_NAME = "Bearer Auth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name("Authorization")
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .description("请输入 JWT Token（Knife4j 会自动添加 Authorization 请求头）")))
                .info(new Info()
                        .title("智学伴 API")
                        .version("1.0.0")
                        .description("智学伴（StudyMate）— AI 自适应学习辅助小程序后端接口文档")
                        .contact(new Contact()
                                .name("StudyMate Team")
                                .email("support@studymate.com")));
    }
}
