package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.DataImportLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据导入日志 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface DataImportLogMapper extends BaseMapper<DataImportLog> {
}
