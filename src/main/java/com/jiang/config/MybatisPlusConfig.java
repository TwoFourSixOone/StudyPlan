package com.jiang.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.jiang.common.handler.TenantHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Configuration
@MapperScan("com.jiang.mapper")
public class MybatisPlusConfig {

    /**
     * 插件配置
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        
        // 租户插件（Demo 阶段固定 tenant_id = 'default'）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantHandler()));
        
        // 乐观锁插件（StudentKnowledgeState 等实体使用 @Version）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        
        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        
        return interceptor;
    }
}
