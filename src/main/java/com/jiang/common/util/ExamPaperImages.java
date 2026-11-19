package com.jiang.common.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 试卷图片页序还原（Change 2026-11-17_exam_parse_reliability）
 *
 * <p>并行上传时 {@code image_urls} 按请求到达顺序追加，页序记录在等长的 {@code image_page_nos}。
 * 本工具把 URL 列表按页序稳定排序；页序缺失/长度不一致（旧数据）时原样返回，保持到达顺序。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
public final class ExamPaperImages {

    private ExamPaperImages() {
    }

    /**
     * 按页序返回 URL 列表（不改入参）
     *
     * @param imageUrls 到达顺序的 URL 列表
     * @param pageNos   与 imageUrls 等长的页序（1-based）；null/长度不一致/含 null 时回退
     * @return 有序 URL 列表（pageNos 不可用时即入参顺序）
     */
    public static List<String> ordered(List<String> imageUrls, List<Integer> pageNos) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }
        if (pageNos == null || pageNos.size() != imageUrls.size()) {
            return imageUrls;
        }
        for (Integer pageNo : pageNos) {
            if (pageNo == null) {
                return imageUrls;
            }
        }
        List<Integer> order = new ArrayList<>(imageUrls.size());
        for (int index = 0; index < imageUrls.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator.comparingInt(pageNos::get));
        List<String> sorted = new ArrayList<>(imageUrls.size());
        for (Integer index : order) {
            sorted.add(imageUrls.get(index));
        }
        return sorted;
    }
}
