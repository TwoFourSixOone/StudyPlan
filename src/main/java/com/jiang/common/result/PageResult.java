package com.jiang.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分页响应结构
 *
 * @param <T> 数据类型
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 总记录数
     */
    private Long total;

    /**
     * 当前页码（从1开始）
     */
    private Integer page;

    /**
     * 每页条数
     */
    private Integer size;

    /**
     * 数据列表
     */
    private List<T> records;

    /**
     * 创建分页结果
     */
    public static <T> PageResult<T> of(Long total, Integer page, Integer size, List<T> records) {
        return new PageResult<>(total, page, size, records);
    }

    /**
     * 计算总页数
     */
    public int getTotalPages() {
        if (total == null || size == null || size == 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / size);
    }

    /**
     * 是否有下一页
     */
    public boolean hasNext() {
        return page != null && page < getTotalPages();
    }

    /**
     * 是否有上一页
     */
    public boolean hasPrevious() {
        return page != null && page > 1;
    }
}
