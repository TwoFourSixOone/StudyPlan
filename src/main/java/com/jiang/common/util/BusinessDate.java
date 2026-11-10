package com.jiang.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务日期（产品规则 #1 / 隐性约定：业务日期统一按北京时间）
 *
 * <p>定时任务与业务校验若使用 JVM 默认时区，在 UTC 服务器上会出现：
 * 北京时间 02:00 时 {@code LocalDate.now()} 仍是"昨天"，导致生成的是昨天的作业。
 * 因此业务侧统一用 {@link #today()}，与 cron 的 {@code zone = "Asia/Shanghai"} 对齐。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
public final class BusinessDate {

    /**
     * 业务时区
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessDate() {
    }

    /**
     * 业务"今天"
     */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /**
     * 业务"当前时间"
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
