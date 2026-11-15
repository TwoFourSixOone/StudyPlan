package com.jiang.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 信号检测配置类
 * 修复 IMP-28 + NOTE-3：@Component + @Validated + 校验注解
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "plan.adjustment")
public class SignalConfig {

    @Valid
    private Sig4Config sig4 = new Sig4Config();
    @Valid
    private Sig1Config sig1 = new Sig1Config();
    @Valid
    private Sig2Config sig2 = new Sig2Config();
    @Valid
    private Sig3Config sig3 = new Sig3Config();

    /**
     * 同一目标调整去重窗口（小时，产品规则 #5）
     *
     * <p>窗口内已有 {@code plan_adjustment_log.action='confirmed'} 记录时不再触发新的调整；
     * 手动 / 01:30 自动 / 考试结算三个入口共用本配置（2026-11-12 起）。</p>
     */
    @Min(value = 1, message = "dedup-hours 最小为 1")
    private int dedupHours = 12;

    @Data
    public static class Sig4Config {
        @DecimalMin(value = "0.1", message = "cap-day 最小为 0.1")
        @DecimalMax(value = "20.0", message = "cap-day 最大为 20.0")
        private double capDay = 3.0;

        @DecimalMin(value = "0.1", message = "efficiency 最小为 0.1")
        @DecimalMax(value = "1.0", message = "efficiency 最大为 1.0")
        private double efficiency = 0.8;
    }

    @Data
    public static class Sig1Config {
        @Min(value = 1)
        private int windowDays = 7;
        @DecimalMin(value = "0.01")
        @DecimalMax(value = "1.0")
        private double threshold = 0.6;
        @Min(value = 1)
        private int consecutiveCount = 3;
    }

    @Data
    public static class Sig2Config {
        @DecimalMin(value = "0.01")
        @DecimalMax(value = "1.0")
        private double masteryDrop = 0.15;
    }

    @Data
    public static class Sig3Config {
        @Min(value = 1)
        private int consecutiveDays = 2;
    }
}
