package com.jiang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.converter.StudentConverter;
import com.jiang.mapper.StudentMapper;
import com.jiang.model.entity.Student;
import com.jiang.security.JwtUtil;
import com.jiang.vo.LoginResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录事务服务（独立 Bean，解决 @Transactional 自调用失效）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Service
@Slf4j
public class StudentLoginTxService {

    @Autowired
    private StudentMapper studentMapper;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 事务内登录逻辑（仅包含 DB 操作）
     * 由 AuthServiceImpl.login() 调用，跨 Bean 确保 @Transactional 生效
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse transactionalLogin(String openid) {
        // 查询学生（TenantLineInnerInterceptor 自动追加 tenant_id = 'default'）
        Student student = studentMapper.selectOne(
                new LambdaQueryWrapper<Student>()
                        .eq(Student::getOpenid, openid)
        );

        if (student == null) {
            try {
                student = new Student();
                student.setOpenid(openid);
                // 默认昵称：openid 后 6 位作为区分（后续 change 由用户主动设置）
                String suffix = openid.length() >= 6 ? openid.substring(openid.length() - 6) : openid;
                student.setNickname("同学_" + suffix);
                // tenantId 由 AutoFillHandler 自动填充
                studentMapper.insert(student);
            } catch (DuplicateKeyException e) {
                // 并发首登兜底：唯一索引冲突，回查已有记录
                log.warn("并发首登触发唯一索引冲突，回查学生记录: openid={}", openid);
                student = studentMapper.selectOne(
                        new LambdaQueryWrapper<Student>()
                                .eq(Student::getOpenid, openid)
                );
                if (student == null) {
                    // 极端并发窗口：冲突后、回查前另一事务删除了记录
                    throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "登录失败，请重试");
                }
            }
        }

        // 签发 JWT
        String token = jwtUtil.generateToken(openid, student.getStudentId(), student.getTenantId());

        // 构造响应
        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setStudentInfo(StudentConverter.toVO(student));

        return response;
    }
}
