package com.jiang.service.impl;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

/**
 * 腾讯云 COS 文件存储服务实现（预留）
 * TODO: 接入腾讯云 COS SDK 实现
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Slf4j
public class CosFileStorageServiceImpl implements FileStorageService {

    @Override
    public String upload(MultipartFile file, String path) {
        log.warn("COS 文件存储尚未实现，请切换到 local 模式");
        throw new BusinessException(ErrorCodes.FILE_UPLOAD_FAILED);
    }

    @Override
    public void delete(String url) {
        log.warn("COS 文件存储尚未实现，请切换到 local 模式");
    }

    /**
     * COS 模式暂不支持字节上传（与 {@link #upload} 一致抛业务异常）
     */
    @Override
    public String uploadBytes(byte[] data, String path, String extension) {
        log.warn("COS 文件存储尚未实现，请切换到 local 模式");
        throw new BusinessException(ErrorCodes.FILE_UPLOAD_FAILED);
    }

    /**
     * COS 模式暂不支持读取（与 {@link #upload} 一致抛业务异常），试卷视觉解析需切回 local
     */
    @Override
    public byte[] readBytes(String url) {
        log.warn("COS 文件存储尚未实现文件读取，请切换到 local 模式");
        throw new BusinessException(ErrorCodes.EXAM_PARSE_FAILED);
    }

    @Override
    public long size(String url) {
        log.warn("COS 文件存储尚未实现文件大小查询，请切换到 local 模式");
        return 0L;
    }
}
