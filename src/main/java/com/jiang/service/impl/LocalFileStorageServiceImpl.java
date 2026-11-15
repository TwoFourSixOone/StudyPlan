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

    /**
     * 上传内存字节（题目配图裁剪件；2026-11-12）
     */
    @Override
    public String uploadBytes(byte[] data, String path, String extension) {
        if (data == null || data.length == 0) {
            throw new BusinessException(ErrorCodes.FILE_UPLOAD_FAILED);
        }
        String ext = (extension == null || extension.isBlank()) ? ".bin" : extension;
        String newFilename = UUID.randomUUID() + ext;
        Path fullPath = Paths.get(basePath, path, newFilename);
        try {
            Files.createDirectories(fullPath.getParent());
            Files.write(fullPath, data);
            String url = baseUrl + "/" + path + "/" + newFilename;
            log.info("文件上传成功（字节）: {} ({} bytes)", url, data.length);
            return url;
        } catch (IOException e) {
            log.error("文件上传失败（字节）: path={}", path, e);
            throw new BusinessException(ErrorCodes.FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 读取文件原始字节（试卷视觉解析用）
     *
     * <p>安全约束：只允许读取 {@code base-url} 前缀下的文件；路径 {@code normalize} 后必须仍在
     * {@code base-path} 之内，防止路径穿越（design §A6）。</p>
     */
    @Override
    public byte[] readBytes(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, "试卷图片地址为空");
        }
        String baseUrlPrefix = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        if (!url.startsWith(baseUrlPrefix)) {
            log.warn("图片读取拒绝：URL 不属于本地存储");
            throw new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, "试卷图片地址无效");
        }

        String relativePath = url.substring(baseUrlPrefix.length());
        Path root = Paths.get(basePath).toAbsolutePath().normalize();
        Path fullPath = root.resolve(relativePath).normalize();
        if (!fullPath.startsWith(root)) {
            log.warn("图片读取拒绝：疑似路径穿越");
            throw new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, "试卷图片路径非法");
        }
        if (!Files.isRegularFile(fullPath)) {
            log.warn("图片读取失败：文件不存在");
            throw new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, "试卷图片不存在");
        }

        try {
            return Files.readAllBytes(fullPath);
        } catch (IOException e) {
            log.error("图片读取失败: path={}", fullPath, e);
            throw new BusinessException(ErrorCodes.EXAM_PARSE_FAILED, "试卷图片读取失败");
        }
    }

    /**
     * 查询已存储文件大小（不读取文件内容）
     */
    @Override
    public long size(String url) {
        if (url == null || url.isBlank()) {
            return 0L;
        }
        String baseUrlPrefix = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        if (!url.startsWith(baseUrlPrefix)) {
            return 0L;
        }
        Path root = Paths.get(basePath).toAbsolutePath().normalize();
        Path fullPath = root.resolve(url.substring(baseUrlPrefix.length())).normalize();
        if (!fullPath.startsWith(root) || !Files.isRegularFile(fullPath)) {
            log.warn("文件大小查询跳过：路径非法或文件不存在");
            return 0L;
        }
        try {
            return Files.size(fullPath);
        } catch (IOException e) {
            log.warn("文件大小查询失败: {}", e.getMessage());
            return 0L;
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
