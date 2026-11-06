package com.jiang.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 题目列表 VO
 * 修复 IMP-1：questionType 字段对应实体 QuestionBank.type
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class QuestionListVO {

    /** 题目 ID */
    private Long id;

    /** 题干内容 */
    private String content;

    /** 关联知识点名称（IMP-5：批量查询，避免 N+1） */
    private String knowledgePointName;

    /** 难度：basic / standard / challenge */
    private String difficulty;

    /** 题型：numeric / choice / short / self（IMP-1：对应 QuestionBank.type） */
    private String questionType;

    /** 答案 */
    private String answer;

    /** 解析 */
    private String analysis;

    /** 学科名称（从知识点关联获取） */
    private String subjectName;

    /** 学段：小学 / 初中 / 高中（从课程标准关联获取） */
    private String stage;

    /** 创建时间 */
    private LocalDateTime createTime;
}
