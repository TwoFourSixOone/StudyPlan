package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MasteryFormulaUtil 单元测试
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@DisplayName("MasteryFormulaUtil 单元测试")
class MasteryFormulaUtilTest {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    // ==================== 答对公式测试 ====================

    @Test
    @DisplayName("答对公式：m=0.5, homework, standard → 掌握度上升")
    void testCalculateMasteryCorrect_homeworkStandard() {
        BigDecimal mCurrent = new BigDecimal("0.5000");
        BigDecimal wSource = new BigDecimal("0.4"); // HOMEWORK
        BigDecimal dNorm = new BigDecimal("0.6");   // standard
        BigDecimal confidence = new BigDecimal("0.1000");

        BigDecimal result = MasteryFormulaUtil.calculateMasteryCorrect(mCurrent, wSource, dNorm, confidence);

        // C_norm = 1.0 - 0.5 * 0.1 = 0.95
        // delta = 0.3 * 0.4 * 0.6 * 0.95 * (1 - 0.5) = 0.0342
        // m_new = 0.5 + 0.0342 = 0.5342
        assertEquals(new BigDecimal("0.5342"), result);
    }

    @Test
    @DisplayName("答对公式：m=0.8, exam, challenge → 掌握度上升但趋近上限")
    void testCalculateMasteryCorrect_examChallenge() {
        BigDecimal mCurrent = new BigDecimal("0.8000");
        BigDecimal wSource = BigDecimal.ONE; // EXAM
        BigDecimal dNorm = new BigDecimal("0.9"); // challenge
        BigDecimal confidence = new BigDecimal("0.5000");

        BigDecimal result = MasteryFormulaUtil.calculateMasteryCorrect(mCurrent, wSource, dNorm, confidence);

        // C_norm = 1.0 - 0.5 * 0.5 = 0.75
        // delta = 0.3 * 1.0 * 0.9 * 0.75 * (1 - 0.8) = 0.0405
        // m_new = 0.8 + 0.0405 = 0.8405
        assertEquals(new BigDecimal("0.8405"), result);
    }

    @Test
    @DisplayName("答对公式：m=1.0 → 保持上限 1.0")
    void testCalculateMasteryCorrect_maxMastery() {
        BigDecimal mCurrent = BigDecimal.ONE;
        BigDecimal wSource = BigDecimal.ONE;
        BigDecimal dNorm = BigDecimal.ONE;
        BigDecimal confidence = BigDecimal.ONE;

        BigDecimal result = MasteryFormulaUtil.calculateMasteryCorrect(mCurrent, wSource, dNorm, confidence);

        assertEquals(BigDecimal.ONE.setScale(SCALE, ROUNDING), result);
    }

    // ==================== 答错公式测试 ====================

    @Test
    @DisplayName("答错公式：m=0.5, exam, challenge → 掌握度下降")
    void testCalculateMasteryIncorrect_examChallenge() {
        BigDecimal mCurrent = new BigDecimal("0.5000");
        BigDecimal wSource = BigDecimal.ONE; // EXAM
        BigDecimal dNorm = new BigDecimal("0.9"); // challenge
        BigDecimal confidence = new BigDecimal("0.1000");

        BigDecimal result = MasteryFormulaUtil.calculateMasteryIncorrect(mCurrent, wSource, dNorm, confidence);

        // C_norm = 1.0 - 0.5 * 0.1 = 0.95
        // delta = 0.3 * 1.0 * 0.9 * 0.95 * 0.5 = 0.12825 → 0.1283
        // m_new = 0.5 - 0.1283 = 0.3717
        assertEquals(new BigDecimal("0.3718"), result);
    }

    @Test
    @DisplayName("答错公式：m=0.05 → 保持下限 0.05")
    void testCalculateMasteryIncorrect_minMastery() {
        BigDecimal mCurrent = new BigDecimal("0.05");
        BigDecimal wSource = BigDecimal.ONE;
        BigDecimal dNorm = BigDecimal.ONE;
        BigDecimal confidence = BigDecimal.ONE;

        BigDecimal result = MasteryFormulaUtil.calculateMasteryIncorrect(mCurrent, wSource, dNorm, confidence);

        assertEquals(new BigDecimal("0.0500"), result);
    }

    @Test
    @DisplayName("答错公式：m=0 → 截断至下限 0.05（边界场景）")
    void testCalculateMasteryIncorrect_zeroMastery() {
        BigDecimal mCurrent = BigDecimal.ZERO;
        BigDecimal wSource = BigDecimal.ONE;
        BigDecimal dNorm = BigDecimal.ONE;
        BigDecimal confidence = BigDecimal.ONE;

        BigDecimal result = MasteryFormulaUtil.calculateMasteryIncorrect(mCurrent, wSource, dNorm, confidence);

        // m=0 时，delta=0，m_new=0，但被截断至 0.05
        assertEquals(new BigDecimal("0.0500"), result);
    }

    // ==================== C_norm 计算测试 ====================

    @Test
    @DisplayName("C_norm 计算：confidence=0.1 → 0.95")
    void testCalculateCNorm_lowConfidence() {
        BigDecimal confidence = new BigDecimal("0.1");
        BigDecimal result = MasteryFormulaUtil.calculateCNorm(confidence);
        assertEquals(new BigDecimal("0.9500"), result);
    }

    @Test
    @DisplayName("C_norm 计算：confidence=1.0 → 0.5")
    void testCalculateCNorm_maxConfidence() {
        BigDecimal confidence = BigDecimal.ONE;
        BigDecimal result = MasteryFormulaUtil.calculateCNorm(confidence);
        assertEquals(new BigDecimal("0.5000"), result);
    }

    // ==================== D_norm 校验测试 ====================

    @Test
    @DisplayName("D_norm 校验：avgDifficulty 为 null → 返回默认值 0.5000")
    void testValidateDNorm_nullAvg() {
        BigDecimal declared = new BigDecimal("0.6");
        BigDecimal result = MasteryFormulaUtil.validateDNorm(declared, null);
        assertEquals(new BigDecimal("0.5000"), result);
    }

    @Test
    @DisplayName("D_norm 校验：偏离 <= 0.2 → 使用声明值")
    void testValidateDNorm_withinThreshold() {
        BigDecimal declared = new BigDecimal("0.6");
        BigDecimal avg = new BigDecimal("0.5");
        BigDecimal result = MasteryFormulaUtil.validateDNorm(declared, avg);
        assertEquals(new BigDecimal("0.6000"), result);
    }

    @Test
    @DisplayName("D_norm 校验：偏离 > 0.2 → 降级为默认值 0.5000")
    void testValidateDNorm_exceedsThreshold() {
        BigDecimal declared = new BigDecimal("0.9");
        BigDecimal avg = new BigDecimal("0.5");
        BigDecimal result = MasteryFormulaUtil.validateDNorm(declared, avg);
        assertEquals(new BigDecimal("0.5000"), result);
    }

    // ==================== 难度映射测试 ====================

    @Test
    @DisplayName("难度映射：basic → 0.3")
    void testMapDifficulty_basic() {
        BigDecimal result = MasteryFormulaUtil.mapDifficulty("basic");
        assertEquals(new BigDecimal("0.3"), result);
    }

    @Test
    @DisplayName("难度映射：standard → 0.6")
    void testMapDifficulty_standard() {
        BigDecimal result = MasteryFormulaUtil.mapDifficulty("standard");
        assertEquals(new BigDecimal("0.6"), result);
    }

    @Test
    @DisplayName("难度映射：challenge → 0.9")
    void testMapDifficulty_challenge() {
        BigDecimal result = MasteryFormulaUtil.mapDifficulty("challenge");
        assertEquals(new BigDecimal("0.9"), result);
    }

    @Test
    @DisplayName("难度映射：无效值 → 抛出异常")
    void testMapDifficulty_invalid() {
        assertThrows(IllegalArgumentException.class, () -> MasteryFormulaUtil.mapDifficulty("unknown"));
    }

    @Test
    @DisplayName("难度映射：null → 抛出异常")
    void testMapDifficulty_null() {
        assertThrows(IllegalArgumentException.class, () -> MasteryFormulaUtil.mapDifficulty(null));
    }

    // ==================== 置信度更新测试 ====================

    @Test
    @DisplayName("置信度更新：初始值 0.1 → 0.15")
    void testUpdateConfidence_initial() {
        BigDecimal current = new BigDecimal("0.1");
        BigDecimal result = MasteryFormulaUtil.updateConfidence(current);
        assertEquals(new BigDecimal("0.1500"), result);
    }

    @Test
    @DisplayName("置信度更新：接近上限 0.98 → 1.0")
    void testUpdateConfidence_nearMax() {
        BigDecimal current = new BigDecimal("0.98");
        BigDecimal result = MasteryFormulaUtil.updateConfidence(current);
        assertEquals(BigDecimal.ONE.setScale(SCALE, ROUNDING), result);
    }

    @Test
    @DisplayName("置信度更新：null → 初始值 0.1")
    void testUpdateConfidence_null() {
        BigDecimal result = MasteryFormulaUtil.updateConfidence(null);
        // 验证数值等于 0.10，忽略 scale 差异
        assertEquals(0, new BigDecimal("0.10").compareTo(result));
    }

    @Test
    @DisplayName("置信度更新：已达上限 1.0 → 保持 1.0")
    void testUpdateConfidence_atMax() {
        BigDecimal current = BigDecimal.ONE;
        BigDecimal result = MasteryFormulaUtil.updateConfidence(current);
        assertEquals(BigDecimal.ONE.setScale(SCALE, ROUNDING), result);
    }
}
