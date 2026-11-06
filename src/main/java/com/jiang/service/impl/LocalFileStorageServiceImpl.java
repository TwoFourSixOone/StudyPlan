package com.jiang.service.impl;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 本地文件存储服务实现
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Slf4j
public class LocalFileStorageServiceImpl implements FileStorageService {

    @Value("${file.storage.local.base-path:./uploads}")
    private String basePath;

    @Value("${file.storage.local.base-url:http://localhost:8080/uploads}")
    private String baseUrl;

    @Override
    public String upload(MultipartFile file, String path) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCodes.FILE_UPLOAD_FAILED);
        }

        // 生成唯一文件名（防路径穿越）
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String newFilename = UUID.randomUUID().toString() + extension;

        // 构建完整路径
        Path fullPath = Paths.get(basePath, path, newFilename);

        try {
            // 确保目录存在
            Files.createDirectories(fullPath.getParent());

            // 保存文件
            Files.copy(file.getInputStream(), fullPath, StandardCopyOption.REPLACE_EXISTING);

            // 返回访问 URL
            String url = baseUrl + "/" + path + "/" + newFilename;
            log.info("文件上传成功: {}", url);
            return url;

        } catch (IOException e) {
            log.error("文件上传失败: path={}", path, e);
            throw new BusinessException(ErrorCodes.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public void delete(String url) {
        if (url == null || !url.startsWith(baseUrl)) {
            log.warn("文件删除跳过：URL 无效或不属于本地存储: {}", url);
            return;
        }

        // 从 URL 提取相对路径
        String relativePath = url.substring(baseUrl.length() + 1);
        Path fullPath = Paths.get(basePath, relativePath);

        try {
            Files.deleteIfExists(fullPath);
            log.info("文件删除成功: {}", fullPath);
        } catch (IOException e) {
            log.error("文件删除失败: path={}", fullPath, e);
        }
    }
}
