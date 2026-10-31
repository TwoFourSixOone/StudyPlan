package com.jiang.service.impl;

import com.jiang.client.WechatApiClient;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.converter.StudentConverter;
import com.jiang.mapper.StudentMapper;
import com.jiang.model.entity.Student;
import com.jiang.security.StudentContext;
import com.jiang.service.AuthService;
import com.jiang.service.StudentLoginTxService;
import com.jiang.vo.LoginResponse;
import com.jiang.vo.StudentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 认证 Service 实现
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    @Autowired
    private StudentLoginTxService txService;

    @Autowired
    private StudentMapper studentMapper;

    @Autowired
    private WechatApiClient wechatApiClient;

    @Override
    public LoginResponse login(String code) {
        // 1. 调用微信 API 换取 openid（事务外，避免长事务占用 DB 连接）
        String openid = wechatApiClient.fetchOpenid(code);

        // 2. 跨 Bean 调用事务方法（经过 Spring 代理，@Transactional 生效）
        return txService.transactionalLogin(openid);
    }

    @Override
    public StudentVO getCurrentStudent() {
        Long studentId = StudentContext.getStudentId();
        if (studentId == null) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "未登录或登录已过期");
        }
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "学生数据异常，请重新登录");
        }
        return StudentConverter.toVO(student);
    }
}
