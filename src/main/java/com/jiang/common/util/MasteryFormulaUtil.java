package com.jiang.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 掌握度公式计算工具类
 * <p>
 * 纯静态工具类，无状态，不依赖 Spring 容器。
 * 所有计算方法的结果统一保留 4 位小数（scale=4），舍入模式为 RoundingMode.HALF_UP，
 * 与数据库 DECIMAL(5,4) 精度对齐。
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
public final class MasteryFormulaUtil {

    // ==================== 常量定义 ====================

    /**
     * 基础学习率 alpha
     */
    public static final BigDecimal ALPHA = new BigDecimal("0.3");

    /**
     * 初始置信度
     */
    public static final BigDecimal CONFIDENCE_INITIAL = new BigDecimal("0.10");

    /**
     * 置信度递增步长
     */
    public static final BigDecimal CONFIDENCE_INCREMENT = new BigDecimal("0.05");

    /**
     * 置信度上限
     */
    public static final BigDecimal CONFIDENCE_MAX = BigDecimal.ONE;

    /**
     * 掌握度下限
     */
    public static final BigDecimal MASTERY_MIN = new BigDecimal("0.05");

    /**
     * 掌握度上限
     */
    public static final BigDecimal MASTERY_MAX = BigDecimal.ONE;

    /**
     * 默认难度归一化值（新题偏离过大或无历史数据时使用）
     */
    public static final BigDecimal D_NORM_DEFAULT = new BigDecimal("0.5000");

    /**
     * 新题难度偏离阈值
     */
    public static final BigDecimal D_NORM_DEVIATION_THRESHOLD = new BigDecimal("0.2");

    /**
     * 首次创建记录时的初始掌握度
     */
    public static final BigDecimal MASTERY_INITIAL = new BigDecimal("0.50");

    // ==================== 难度枚举映射 ====================

    /**
     * basic 难度对应的数值
     */
    private static final BigDecimal DIFFICULTY_BASIC = new BigDecimal("0.3");

    /**
     * standard 难度对应的数值
     */
    private static final BigDecimal DIFFICULTY_STANDARD = new BigDecimal("0.6");

    /**
     * challenge 难度对应的数值
     */
    private static final BigDecimal DIFFICULTY_CHALLENGE = new BigDecimal("0.9");

    private MasteryFormulaUtil() {
        // 私有构造，防止实例化
    }

    // ==================== 核心计算方法 ====================

    /**
     * 计算答对时的新掌握度
     * <p>
     * 公式：delta_m_up = alpha * W_source * D_norm * C_norm * (1 - m_current)
     *       m_new = min(1.0, m_current + delta_m_up)
     *
     * @param mCurrent   当前掌握度
     * @param wSource    来源权重
     * @param dNorm      难度归一化值
     * @param confidence 置信度
     * @return 新的掌握度
     */
    public static BigDecimal calculateMasteryCorrect(
            BigDecimal mCurrent, BigDecimal wSource,
            BigDecimal dNorm, BigDecimal confidence) {

        BigDecimal cNorm = calculateCNorm(confidence);
        BigDecimal delta = ALPHA.multiply(wSource)
                .multiply(dNorm)
                .multiply(cNorm)
                .multiply(BigDecimal.ONE.subtract(mCurrent));

        BigDecimal mNew = mCurrent.add(delta);
        return mNew.min(MASTERY_MAX).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算答错时的新掌握度
     * <p>
     * 公式：delta_m_down = alpha * W_source * D_norm * C_norm * m_current
     *       m_new = max(0.05, m_current - delta_m_down)
     *
     * @param mCurrent   当前掌握度
     * @param wSource    来源权重
     * @param dNorm      难度归一化值
     * @param confidence 置信度
     * @return 新的掌握度
     */
    public static BigDecimal calculateMasteryIncorrect(
            BigDecimal mCurrent, BigDecimal wSource,
            BigDecimal dNorm, BigDecimal confidence) {

        BigDecimal cNorm = calculateCNorm(confidence);
        BigDecimal delta = ALPHA.multiply(wSource)
                .multiply(dNorm)
                .multiply(cNorm)
                .multiply(mCurrent);

        BigDecimal mNew = mCurrent.subtract(delta);
        return mNew.max(MASTERY_MIN).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算置信度衰减因子 C_norm
     * <p>
     * 公式：C_norm = 1.0 - 0.5 * confidence
     *
     * @param confidence 置信度
     * @return 置信度衰减因子
     */
    public static BigDecimal calculateCNorm(BigDecimal confidence) {
        return BigDecimal.ONE.subtract(new BigDecimal("0.5").multiply(confidence))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 校验 D_norm：新题偏离均值 > 0.2 时降级为 0.5
     * <p>
     * 注意：declaredDifficulty 必须先通过 mapDifficulty() 转为数值再传入本方法
     *
     * @param declaredDifficulty 声明难度（已转换为数值型）
     * @param avgDifficulty      历史平均难度
     * @return 校验后的 D_norm 值
     */
    public static BigDecimal validateDNorm(
            BigDecimal declaredDifficulty, BigDecimal avgDifficulty) {

        if (avgDifficulty == null) {
            // 新知识点无历史数据，使用默认值
            return D_NORM_DEFAULT;
        }

        BigDecimal deviation = declaredDifficulty.subtract(avgDifficulty).abs();
        if (deviation.compareTo(D_NORM_DEVIATION_THRESHOLD) > 0) {
            // 偏离过大，降级为默认值
            return D_NORM_DEFAULT;
        }

        return declaredDifficulty.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 难度枚举映射
     * <p>
     * basic → 0.3, standard → 0.6, challenge → 0.9
     *
     * @param difficulty 难度字符串
     * @return 对应的数值
     * @throws IllegalArgumentException 无效的难度值
     */
    public static BigDecimal mapDifficulty(String difficulty) {
        if (difficulty == null) {
            throw new IllegalArgumentException("Difficulty cannot be null");
        }
        return switch (difficulty.toLowerCase()) {
            case "basic" -> DIFFICULTY_BASIC;
            case "standard" -> DIFFICULTY_STANDARD;
            case "challenge" -> DIFFICULTY_CHALLENGE;
            default -> throw new IllegalArgumentException("Unknown difficulty: " + difficulty);
        };
    }

    /**
     * 更新置信度
     * <p>
     * 公式：confidence_new = min(1.00, current + 0.05)
     *
     * @param current 当前置信度
     * @return 新的置信度
     */
    public static BigDecimal updateConfidence(BigDecimal current) {
        if (current == null) {
            return CONFIDENCE_INITIAL;
        }
        BigDecimal newConfidence = current.add(CONFIDENCE_INCREMENT);
        return newConfidence.min(CONFIDENCE_MAX).setScale(4, RoundingMode.HALF_UP);
    }
}
