package com.jiang.common.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * JSON 字符串透传类型处理器
 * 
 * 解决 JacksonTypeHandler 将 JSON 数组/对象反序列化为 String 字段时抛出
 * MismatchedInputException 的问题。本处理器直接将数据库 JSON 列的原始字符串
 * 透传给 Java String 字段，不经过 Jackson 解析，由业务代码按需手动解析。
 * 
 * 使用 getObject() 而非 getString() 读取，兼容 MySQL JSON 列类型在不同
 * JDBC 驱动版本下的行为差异。
 *
 * @author StudyMate Team
 * @since 2026-11-05
 */
public class JsonStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toJsonString(rs.getObject(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toJsonString(rs.getObject(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toJsonString(cs.getObject(columnIndex));
    }

    /**
     * 将 JDBC 返回值统一转为 String。
     * MySQL JSON 列在某些驱动版本中 getObject() 返回 byte[] 或内部 JSON 对象，
     * 此处统一通过 toString() 获取 JSON 文本。
     */
    private static String toJsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, java.nio.charset.StandardCharsets.UTF_8);
        }
        return value.toString();
    }
}
