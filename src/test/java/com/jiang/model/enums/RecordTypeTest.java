package com.jiang.model.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecordType 单元测试
 *
 * <p>覆盖 2026-11-09 的序列化契约：DB / MyBatis 使用大写枚举名，
 * API JSON 通过 {@code @JsonValue} 输出小写 code。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@DisplayName("RecordType 枚举测试")
class RecordTypeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("序列化：输出小写 code 而非枚举名")
    void should_serializeCode_when_jsonSerialized() throws Exception {
        assertEquals("\"homework\"", objectMapper.writeValueAsString(RecordType.HOMEWORK));
        assertEquals("\"assessment\"", objectMapper.writeValueAsString(RecordType.ASSESSMENT));
        assertEquals("\"exam\"", objectMapper.writeValueAsString(RecordType.EXAM));
    }

    @Test
    @DisplayName("fromCode：大小写不敏感且忽略首尾空格")
    void should_mapFromCode_when_codeHasDifferentCaseOrSpaces() {
        assertEquals(RecordType.HOMEWORK, RecordType.fromCode("homework"));
        assertEquals(RecordType.HOMEWORK, RecordType.fromCode("  HOMEWORK  "));
        assertEquals(RecordType.ASSESSMENT, RecordType.fromCode("Assessment"));
        assertEquals(RecordType.EXAM, RecordType.fromCode("exam"));
    }

    @Test
    @DisplayName("fromCode：非法值 → 抛 INVALID_RECORD_TYPE")
    void should_throw_when_codeIsInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RecordType.fromCode("unknown"));
        assertEquals(ErrorCodes.INVALID_RECORD_TYPE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("fromCode：null / 空白 → 抛 INVALID_RECORD_TYPE")
    void should_throw_when_codeIsBlank() {
        assertThrows(BusinessException.class, () -> RecordType.fromCode(null));
        assertThrows(BusinessException.class, () -> RecordType.fromCode("   "));
    }

    @Test
    @DisplayName("code 与枚举名分离：DB 用枚举名，API 用 code")
    void should_keepEnumNameAndCodeSeparated() {
        assertEquals("HOMEWORK", RecordType.HOMEWORK.name());
        assertEquals("homework", RecordType.HOMEWORK.getCode());
    }
}
