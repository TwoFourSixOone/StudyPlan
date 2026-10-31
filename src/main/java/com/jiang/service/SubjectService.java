package com.jiang.service;

import com.jiang.vo.SubjectVO;

import java.util.List;

/**
 * 科目查询 Service 接口
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
public interface SubjectService {

    /** 查询可用科目列表（有已启用知识点的科目） */
    List<SubjectVO> getAvailableSubjects();
}
