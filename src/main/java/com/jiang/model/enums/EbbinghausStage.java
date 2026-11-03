package com.jiang.model.enums;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import lombok.Getter;

/**
 * 艾宾浩斯阶段枚举（1/3/7/15/30 天）
 *
 * IMP-B 修复：fromStage 覆盖 1-5 正常值 + 0/6/-1 异常值测试
 * IMP-4 修复：删除返回 null 的 calculateNextDueDate 方法
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Getter
public enum EbbinghausStage {

    STAGE_1(1, 1, "1天后重做"),
    STAGE_2(2, 3, "3天后重做"),
    STAGE_3(3, 7, "7天后重做"),
    STAGE_4(4, 15, "15天后重做"),
    STAGE_5(5, 30, "30天后重做");

    private final int stage;
    private final int intervalDays;
    private final String description;

    EbbinghausStage(int stage, int intervalDays, String description) {
        this.stage = stage;
        this.intervalDays = intervalDays;
        this.description = description;
    }

    /**
     * 根据阶段值获取枚举
     * IMP-10 修复：统一抛 BusinessException
     *
     * @param stage 阶段值（1-5）
     * @return 对应枚举
     * @throws BusinessException 未知阶段值
     */
    public static EbbinghausStage fromStage(int stage) {
        for (EbbinghausStage s : values()) {
            if (s.stage == stage) {
                return s;
            }
        }
        throw new BusinessException(ErrorCodes.BAD_REQUEST, "Unknown EbbinghausStage: " + stage);
    }

    /**
     * 根据阶段值获取间隔天数
     *
     * @param stage 阶段值（1-5）
     * @return 间隔天数
     */
    public static int getIntervalDays(int stage) {
        return fromStage(stage).getIntervalDays();
    }
}
