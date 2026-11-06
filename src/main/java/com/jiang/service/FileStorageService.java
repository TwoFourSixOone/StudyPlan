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
     * 删除文件
     *
     * @param url 文件 URL
     */
    void delete(String url);
}
