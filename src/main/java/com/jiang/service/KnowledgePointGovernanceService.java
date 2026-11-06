package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.vo.KnowledgePointDetailVO;
import com.jiang.vo.KnowledgePointListVO;
import com.jiang.vo.KnowledgePointOverdueVO;

import java.util.List;

/**
 * 知识点治理服务接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface KnowledgePointGovernanceService {

    /**
     * 知识点列表查询（BLOCK-8：使用 selectPage + QueryWrapper）
     */
    PageResult<KnowledgePointListVO> getKnowledgePointList(String subject, String status,
                                                             String keyword, Integer page, Integer size);

    /**
     * 知识点详情查看
     */
    KnowledgePointDetailVO getKnowledgePointDetail(String id);

    /**
     * 知识点启停用
     */
    void updateStatus(String id, String status);

    /**
     * 知识点校对标记（BLOCK-9：幂等）
     */
    void markAsReviewed(String id);

    /**
     * 批量校对标记
     * @param ids 知识点 ID 列表，传空列表则校验所有待校对知识点
     * @return 成功标记的数量
     */
    int batchMarkAsReviewed(List<String> ids);

    /**
     * 校对超期列表（IMP-13：7 个自然日）
     */
    List<KnowledgePointOverdueVO> getOverdueKnowledgePoints();
}
