package com.jiang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

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

    /**
     * 应用启动完成后打印标识
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        System.out.println();
        System.out.println("===========================================");
        System.out.println("    智学伴（StudyMate）启动成功");
        System.out.println("===========================================");
        System.out.println();
    }
}
