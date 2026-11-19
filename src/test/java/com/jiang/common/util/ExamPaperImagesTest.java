package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 试卷图片页序还原（Change 2026-11-17_exam_parse_reliability）
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
@DisplayName("ExamPaperImages 页序还原")
class ExamPaperImagesTest {

    @Test
    @DisplayName("乱序到达 → 按 pageNo 还原为卷面顺序")
    void should_orderByPageNo() {
        List<String> urls = List.of("page4", "page1", "page3", "page2");
        List<Integer> pageNos = List.of(4, 1, 3, 2);
        assertEquals(List.of("page1", "page2", "page3", "page4"),
                ExamPaperImages.ordered(urls, pageNos));
    }

    @Test
    @DisplayName("旧数据：页序为 null / 长度不一致 / 含 null → 原样返回（到达顺序）")
    void should_fallbackWhenPageNosUnavailable() {
        List<String> urls = List.of("a", "b", "c");
        assertEquals(urls, ExamPaperImages.ordered(urls, null));
        assertEquals(urls, ExamPaperImages.ordered(urls, List.of(1, 2)));
        assertEquals(urls, ExamPaperImages.ordered(urls, java.util.Arrays.asList(1, null, 3)));
    }

    @Test
    @DisplayName("重复 pageNo → 稳定排序（相同页序保持到达顺序）")
    void should_beStableWhenDuplicated() {
        List<String> urls = List.of("first", "second", "third");
        List<Integer> pageNos = List.of(2, 1, 1);
        assertEquals(List.of("second", "third", "first"),
                ExamPaperImages.ordered(urls, pageNos));
    }

    @Test
    @DisplayName("空列表 → 空列表")
    void should_handleEmpty() {
        assertEquals(List.of(), ExamPaperImages.ordered(List.of(), List.of()));
        assertEquals(List.of(), ExamPaperImages.ordered(null, null));
    }
}
