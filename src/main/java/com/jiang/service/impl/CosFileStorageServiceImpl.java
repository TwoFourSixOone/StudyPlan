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
}
