package com.jiang.common.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageResult 单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
class PageResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("测试分页结果创建")
    void testPageResultCreation() {
        List<String> records = Arrays.asList("item1", "item2", "item3");
        PageResult<String> result = PageResult.of(100L, 1, 10, records);
        
        assertEquals(100L, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getSize());
        assertEquals(3, result.getRecords().size());
    }

    @Test
    @DisplayName("测试总页数计算")
    void testTotalPages() {
        // 100 条记录，每页 10 条，共 10 页
        PageResult<String> result1 = PageResult.of(100L, 1, 10, List.of());
        assertEquals(10, result1.getTotalPages());
        
        // 105 条记录，每页 10 条，共 11 页（向上取整）
        PageResult<String> result2 = PageResult.of(105L, 1, 10, List.of());
        assertEquals(11, result2.getTotalPages());
        
        // 0 条记录
        PageResult<String> result3 = PageResult.of(0L, 1, 10, List.of());
        assertEquals(0, result3.getTotalPages());
    }

    @Test
    @DisplayName("测试是否有下一页")
    void testHasNext() {
        // 第 1 页，共 10 页，有下一页
        PageResult<String> result1 = PageResult.of(100L, 1, 10, List.of());
        assertTrue(result1.hasNext());
        
        // 第 10 页，共 10 页，无下一页
        PageResult<String> result2 = PageResult.of(100L, 10, 10, List.of());
        assertFalse(result2.hasNext());
    }

    @Test
    @DisplayName("测试是否有上一页")
    void testHasPrevious() {
        // 第 1 页，无上一页
        PageResult<String> result1 = PageResult.of(100L, 1, 10, List.of());
        assertFalse(result1.hasPrevious());
        
        // 第 2 页，有上一页
        PageResult<String> result2 = PageResult.of(100L, 2, 10, List.of());
        assertTrue(result2.hasPrevious());
    }

    @Test
    @DisplayName("测试分页参数从 1 开始（约定 #3）")
    void testPageStartFromOne() {
        // 验证分页参数 page 从 1 开始（非 0）
        PageResult<String> result = PageResult.of(50L, 1, 20, List.of());
        assertEquals(1, result.getPage(), "分页参数 page 应从 1 开始");
        assertEquals(20, result.getSize(), "默认每页条数应为 10 或自定义值");
    }

    @Test
    @DisplayName("测试 JSON 序列化格式")
    void testJsonSerialization() throws Exception {
        List<String> records = Arrays.asList("item1", "item2");
        PageResult<String> result = PageResult.of(2L, 1, 10, records);
        String json = objectMapper.writeValueAsString(result);
        
        assertTrue(json.contains("\"total\":2"));
        assertTrue(json.contains("\"page\":1"));
        assertTrue(json.contains("\"size\":10"));
        assertTrue(json.contains("\"records\""));
    }
}
