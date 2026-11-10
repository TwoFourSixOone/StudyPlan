package com.jiang.service;

/**
 * 学生个人资料服务（产品规则 #11）
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
public interface StudentProfileService {

    /**
     * 设置/修改当前登录学生的年级
     *
     * <p>学生身份取自 {@code StudentContext}，不接受客户端传入。</p>
     *
     * @param grade 年级 1–12
     * @throws com.jiang.common.exception.BusinessException 年级非法或学生不存在
     */
    void updateGrade(Integer grade);
}
