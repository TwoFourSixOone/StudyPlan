# Verify：管理端修改密码接口

> **Change**：`2026-11-19_admin_password_change`　**验证**：2026-11-19（本地单测 + 前端构建；真机冒烟待跑）

## 需求 → 实现

| 需求 | 实现 | 证据 |
|---|---|---|
| 改密接口 | `POST /api/admin/auth/change-password`（`AdminAuthController#changePassword`） | 控制器测试 5 例 |
| 校验原密码 | `AdminAuthServiceImpl#verifyPassword(oldPassword, hash)` | 原密码错 → 1194，且 `updateById` 未被调用 |
| 禁止新旧相同 | `verifyPassword(newPassword, hash)` 命中即 1195 | 服务层用例 |
| 身份只取服务端上下文 | `AdminContext.getAdminId()`（JWT `sub`），请求体只有 `oldPassword`/`newPassword` | 控制器测试断言 `changePassword(eq(7L), …)`，请求体无 adminId |
| 密码只存哈希 | `hashPassword`（PBKDF2-HMAC-SHA256 / 210k / 16B 盐 / 32B 密钥） | 用例断言新哈希可验、旧密码失效、且不等于任何明文 |
| 仅管理员可调用 | 控制器标注 `@AdminApi` + `JwtInterceptor` 角色门禁 | 学生 token → 403 且 `verify(..., never())` |
| 前端入口 | `AppLayout.vue` 顶部「管理员」下拉 → 修改密码弹窗（原密码 / 新密码 / 确认） | `npx vite build` 通过 |
| 改后强制重登 | 成功回调清 `VITE_DEMO_TOKEN` + `router.replace('/login')` | 代码路径（真机待冒烟） |
| 参数校验 | `@NotBlank` 原密码、`@Size(6,64)` 新密码；前端同步 6~64 + 两次一致 | 控制器测试：新密码过短被拒（未进业务层） |

## 测试

```
mvn -o -q compile                                     → exit 0
mvn -o '-Dtest=AdminAuthServiceTest,AdminAuthControllerTest' test → Tests run: 19, Failures: 0, Errors: 0
mvn -o test                                           → Tests run: 1034, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
cd admin-web && npx vite build                        → ✓ built（仅既有 chunk>500kB 警告）
```

| 用例 | 覆盖 |
|---|---|
| `AdminAuthServiceTest` +4 | 改密成功（新密码可验、旧密码失效）、原密码错 1194 不写库、新旧相同 1195 不写库、账号不存在/停用/无身份统一 401 |
| `AdminAuthControllerTest`（5 例） | admin token 改密成功且 adminId 取自上下文、学生 token 403、原密码错返回 1194、新密码过短被参数校验拦截、`@AdminApi` 注解存在 |

## 未验证

| 项 | 说明 |
|---|---|
| 真机冒烟 | 需真实 MySQL + 已 bootstrap 的 admin 账号：登录 → 改密 → 自动回登录页 → 新密码登录成功、旧密码失败 |
| 旧 token 失效 | JWT 无状态，改密后**旧 token 在过期前仍有效**；本次只做前端强制重登，服务端强制失效需 token 版本号（未实现） |
| 多租户改密 | `admin_user.username` 租户内唯一，登录/改密均按当前租户（管理端上下文固定 `default`）；跨租户改密场景不存在，无需覆盖 |

## 已知限制与后续改进

1. **改密不吊销旧 token**：如要"改密即踢掉所有会话"，需引入 token 版本号（`admin_user.token_version` + 签发/校验时比对）或黑名单，另立 change；
2. **忘记密码无自助找回**：仍走运维兜底（删号重建 / 直接更新哈希，见根 README）；
3. **顺带发现（不在本次范围）**：`admin-web/src/api/auth.js#getAdminInfo` 调用的 `GET /api/admin/auth/current` 后端**不存在**，且该函数当前无调用方——属既有死代码，建议后续清理或补齐接口。
