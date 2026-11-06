package com.jiang.config;

import com.jiang.service.FileStorageService;
import com.jiang.service.impl.CosFileStorageServiceImpl;
import com.jiang.service.impl.LocalFileStorageServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储配置（策略模式）
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Configuration
public class FileStorageConfig {

    @Bean
    @ConditionalOnProperty(name = "file.storage.type", havingValue = "local", matchIfMissing = true)
    public FileStorageService localStorageService() {
        return new LocalFileStorageServiceImpl();
    }

    @Bean
    @ConditionalOnProperty(name = "file.storage.type", havingValue = "cos")
    public FileStorageService cosStorageService() {
        return new CosFileStorageServiceImpl();
    }
}
