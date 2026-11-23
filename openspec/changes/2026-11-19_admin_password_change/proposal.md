# Proposal：管理端修改密码接口

> **Change**：`2026-11-19_admin_password_change`

## 背景

管理端账号已从「源码硬编码」改为 `admin_user` 表 + PBKDF2 哈希（见 `2026-11-19_prod_hardening`），
首个账号由 `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` 环境变量初始化。

但这留下一个实际运维缺口：**上线后没有任何改密入口**。当前只能：
1. `DELETE FROM admin_user WHERE username='admin'` 后带新密码重启（会中断服务、且要求还留着环境变量能力）；
2. 手工用 PBKDF2 生成哈希后 `UPDATE admin_user`（易出错，需要知道 210k 迭代 / 16B 盐 / 32B 密钥等细节）。

管理员无法自助改密 = 初始密码长期不变，属于安全风险，也影响 demo 交付的完整性。

## 目标

1. 后端提供 `POST /api/admin/auth/change-password`：校验原密码 → 生成新哈希 → 更新库；
2. 管理端页面提供「修改密码」入口（顶部栏），成功后强制回登录页；
3. 保持既有安全口径：只有 `role=admin` 可调用（`@AdminApi` 注解 + 拦截器），不新增任何明文凭证。

## 不在本次范围

- **token 失效机制**：JWT 无状态，改密后旧 token 在过期前仍然有效；本次通过前端强制重新登录缓解，
  彻底方案（token 版本号 / 黑名单）另立 change；
- 忘记密码的自助找回（需邮箱/短信通道，demo 阶段不引入）；仍保留"删号重建 / 改库"兜底；
- 多租户下"指定租户登录/改密"（现状固定 `default` 租户）。

## 验收

- 原密码正确 → 返回成功，库中 `password_hash` 变化且新密码可登录、旧密码不可登录；
- 原密码错误 → `code=1194`（原密码不正确），不泄露账号是否存在；
- 新密码与原密码相同 → `code=1195`（新密码不能与原密码相同）；
- 未登录 / 学生 token 调用 → HTTP 403（拦截器既有行为）；
- `admin-web` 顶部栏可见「修改密码」，成功后清 token 并跳 `/login`；
- 全量 `mvn -o test` 与 `admin-web vite build` 通过。
