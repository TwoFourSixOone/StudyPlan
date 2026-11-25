package com.jiang.common.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 内存版 {@link MultipartFile}（Change: 2026-11-23_upload_via_call_container）
 *
 * <p>用途：把「base64 解码后的字节」适配成 MultipartFile，从而**完全复用**既有的
 * {@code ExamUploadValidator} 与 {@code ExamPaperService#uploadImage} 校验链
 * （归属、张数、累计大小、类型魔数、分辨率、落盘、页序追加），避免为第二条上传通道复制一套校验逻辑。</p>
 *
 * <p>只用于小文件（试卷单页图片，单张上限 9.5MB），不做流式落盘。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-23
 */
public class InMemoryMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    public InMemoryMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content == null ? new byte[0] : content;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() {
        return content;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
        throw new UnsupportedOperationException("InMemoryMultipartFile 不支持 transferTo，请使用 getBytes()");
    }
}
