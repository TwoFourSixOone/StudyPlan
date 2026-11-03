package com.jiang.model.enums;

import com.jiang.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EbbinghausStage 枚举单元测试
 *
 * IMP-B 修复：覆盖 1-5 正常值 + 0/6/-1 异常值 + getIntervalDays
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@DisplayName("EbbinghausStage 枚举测试")
class EbbinghausStageTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("正常阶段值 1-5 应返回对应枚举")
    void fromStage_validStage_returnsEnum(int stage) {
        EbbinghausStage result = EbbinghausStage.fromStage(stage);
        assertEquals(stage, result.getStage());
    }

    @Test
    @DisplayName("STAGE_1 间隔 1 天")
    void stage1_intervalIs1() {
        assertEquals(1, EbbinghausStage.STAGE_1.getIntervalDays());
    }

    @Test
    @DisplayName("STAGE_2 间隔 3 天")
    void stage2_intervalIs3() {
        assertEquals(3, EbbinghausStage.STAGE_2.getIntervalDays());
    }

    @Test
    @DisplayName("STAGE_3 间隔 7 天")
    void stage3_intervalIs7() {
        assertEquals(7, EbbinghausStage.STAGE_3.getIntervalDays());
    }

    @Test
    @DisplayName("STAGE_4 间隔 15 天")
    void stage4_intervalIs15() {
        assertEquals(15, EbbinghausStage.STAGE_4.getIntervalDays());
    }

    @Test
    @DisplayName("STAGE_5 间隔 30 天")
    void stage5_intervalIs30() {
        assertEquals(30, EbbinghausStage.STAGE_5.getIntervalDays());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1, 100})
    @DisplayName("异常阶段值应抛 BusinessException")
    void fromStage_invalidStage_throwsException(int stage) {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> EbbinghausStage.fromStage(stage));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("Unknown EbbinghausStage"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("getIntervalDays 静态方法应返回正确间隔")
    void getIntervalDays_validStage_returnsCorrectInterval(int stage) {
        int expected = EbbinghausStage.fromStage(stage).getIntervalDays();
        assertEquals(expected, EbbinghausStage.getIntervalDays(stage));
    }

    @Test
    @DisplayName("getIntervalDays 异常阶段值应抛 BusinessException")
    void getIntervalDays_invalidStage_throwsException() {
        assertThrows(BusinessException.class,
                () -> EbbinghausStage.getIntervalDays(0));
    }

    @Test
    @DisplayName("枚举值数量应为 5")
    void values_countIs5() {
        assertEquals(5, EbbinghausStage.values().length);
    }
}
