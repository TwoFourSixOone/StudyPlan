package com.jiang.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口（策略模式）
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface FileStorageService {

    /**
     * 上传文件
     *
     * @param file 文件
     * @param path 存储路径（相对路径，如 "exam-papers/123"）
     * @return 文件访问 URL
     */
    String upload(MultipartFile file, String path);

    /**
     * 上传内存字节（服务端生成的题目配图裁剪件等）
     *
     * @param data      文件内容
     * @param path      存储路径（相对路径，如 "exam-figures/9"）
     * @param extension 扩展名（含点，如 ".jpg"）
     * @return 文件访问 URL
     */
    String uploadBytes(byte[] data, String path, String extension);

    /**
     * 删除文件
     *
     * @param url 文件 URL
     */
    void delete(String url);

    /**
     * 读取文件原始字节（试卷视觉解析用：本地图片一律 base64 内联，模型侧不可达 localhost）
     *
     * @param url 文件 URL
     * @return 文件字节
     * @throws com.jiang.common.exception.BusinessException URL 非法/路径穿越/文件不存在时抛出
     *         （错误码 {@code EXAM_PARSE_FAILED}）
     */
    byte[] readBytes(String url);

    /**
     * 查询已存储文件大小（追加图片时累计 ≤30MB 校验用，避免把文件全读进内存）
     *
     * @param url 文件 URL
     * @return 字节数；URL 非法或文件不存在时返回 0（累计校验按 0 处理，不阻塞上传）
     */
    long size(String url);
}
