package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.dto.ImportResultDTO;
import com.jiang.model.entity.DataImportErrorLog;
import com.jiang.vo.ImportLogVO;

/**
 * 数据导入 Service 接口
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
public interface DataImportService {

    /**
     * 执行数据导入
     *
     * @param dataType 数据类型：topic / question / dependency / standard
     * @return 导入结果（成功数、失败数、跳过数、错误摘要）
     */
    ImportResultDTO importData(String dataType);

    /**
     * 查询导入日志列表（分页）
     *
     * @param dataType 数据类型（可选，null 表示查全部）
     * @param page     页码（从 1 开始）
     * @param size     每页条数
     * @return 分页日志列表
     */
    PageResult<ImportLogVO> getImportLogs(String dataType, int page, int size);

    /**
     * 查询导入错误记录（分页）
     *
     * @param importId 导入批次 ID
     * @param page     页码（从 1 开始）
     * @param size     每页条数
     * @return 分页错误记录
     */
    PageResult<DataImportErrorLog> getImportErrors(Long importId, int page, int size);
}
