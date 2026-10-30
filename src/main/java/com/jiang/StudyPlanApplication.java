package com.jiang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智学伴（StudyMate）应用启动类
 *
 * <p>注意：{@code @MapperScan("com.jiang.mapper")} 统一由 {@code MybatisPlusConfig} 配置，
 * 启动类不重复添加，避免 Mapper 重复扫描产生 WARN。</p>
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@SpringBootApplication
public class StudyPlanApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudyPlanApplication.class, args);
    }
}
