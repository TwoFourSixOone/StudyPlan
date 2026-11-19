-- V16：试卷图片页序（2026-11-17，Change 2026-11-17_exam_parse_reliability）
-- 背景：小程序并发上传多张图，服务端按请求到达顺序追加 image_urls，卷面页序丢失 →
--       整卷解析按错误页序重排题号、配图裁到别的页（实测 4 张图变成 第4→第1→第3→第2 页）。
-- 方案：新增与 image_urls **等长、同下标** 的页序数组；解析/配图/详情按 pageNo 还原页序。
--       NULL = 存量数据，按 image_urls 到达顺序（不回填）。
ALTER TABLE `exam_paper`
    ADD COLUMN `image_page_nos` JSON DEFAULT NULL
    COMMENT '与 image_urls 等长的页序数组（1..8，选择顺序）；NULL=旧数据/按到达顺序'
    AFTER `image_urls`;

-- 回滚（仅开发环境，需人工审批）：
-- ALTER TABLE `exam_paper` DROP COLUMN `image_page_nos`;
