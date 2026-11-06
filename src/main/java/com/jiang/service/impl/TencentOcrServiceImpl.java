package com.jiang.service.impl;

import com.jiang.common.exception.OcrException;
import com.jiang.dto.ExamQuestionOcrDTO;
import com.jiang.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯云 OCR 服务实现（占位）
 * TODO: 接入腾讯云 OCR SDK（tencentcloud-sdk-java-ocr）
 * 密钥通过环境变量 TENCENT_OCR_SECRET_ID / TENCENT_OCR_SECRET_KEY 注入
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Slf4j
@Service
public class TencentOcrServiceImpl implements OcrService {

    @Override
    public List<ExamQuestionOcrDTO> recognize(String imageUrl) {
        log.info("OCR 识别开始: imageUrl={}", imageUrl);

        // TODO: 调用腾讯云 OCR SDK
        // GeneralHandwritingOCRRequest req = new GeneralHandwritingOCRRequest();
        // req.setImageUrl(imageUrl);
        // GeneralHandwritingOCRResponse resp = ocrClient.GeneralHandwritingOCR(req);
        // return parseResponse(resp);

        // 占位实现：返回空列表（实际接入时替换）
        log.warn("OCR 服务尚未实际接入，返回空结果");
        throw new OcrException("OCR 服务尚未实际接入，请配置腾讯云 OCR SDK");
    }

    /**
     * 解析 OCR 响应（示例代码）
     */
    private List<ExamQuestionOcrDTO> parseResponse(Object resp) {
        // TODO: 根据实际 SDK 响应解析题目
        List<ExamQuestionOcrDTO> results = new ArrayList<>();

        // 示例解析逻辑：
        // for (Item item : resp.getItems()) {
        //     ExamQuestionOcrDTO dto = new ExamQuestionOcrDTO();
        //     dto.setQuestionIndex(parseQuestionIndex(item.getText()));
        //     dto.setQuestionContent(item.getText());
        //     dto.setAttributionStatus("pending");
        //     results.add(dto);
        // }

        return results;
    }

    /**
     * 从文本中解析题目序号（示例）
     */
    private Integer parseQuestionIndex(String text) {
        // TODO: 使用正则表达式匹配 "1." "2." 等题号
        return 1;
    }
}
