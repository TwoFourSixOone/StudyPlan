package com.jiang.mapper;

import com.jiang.vo.LearningRecordListVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 学习记录 Mapper 接口
 * BLOCK-C 修复：UNION ALL 三类记录统一分页
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Mapper
public interface LearningRecordMapper {

    /**
     * UNION ALL 查询三类学习记录（分页）
     * NOTE-2 修复：homework 分支使用 LEFT JOIN 防御性编程
     */
    List<LearningRecordListVO> selectUnionAllRecords(
            @Param("studentId") Long studentId,
            @Param("tenantId") String tenantId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("offset") int offset,
            @Param("size") int size);

    /**
     * COUNT UNION ALL 查询三类学习记录总数
     */
    long countUnionAllRecords(
            @Param("studentId") Long studentId,
            @Param("tenantId") String tenantId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
