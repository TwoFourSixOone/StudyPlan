package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.PendingReviewAlert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 校对超期提醒 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface PendingReviewAlertMapper extends BaseMapper<PendingReviewAlert> {
}
