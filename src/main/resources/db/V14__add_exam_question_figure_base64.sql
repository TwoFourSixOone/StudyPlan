-- V14：题目配图 base64 落库（2026-11-12，Change 2026-11-12_exam_quality_v3）
-- 背景：题目配图此前只存文件 URL（question_image_url），小程序端不可达（localhost/跨设备）→ 详情页空白；
--       改为把裁剪后的配图直接以 data URL 落库，页面直接渲染，不再依赖文件服务地址。
-- 影响：仅新增可空列，存量数据为 NULL（前端回退 question_image_url）。
ALTER TABLE `exam_question`
    ADD COLUMN `figure_base64` MEDIUMTEXT NULL COMMENT '题目配图 base64（data:image/jpeg;base64,...）' AFTER `question_image_url`;
