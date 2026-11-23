# Design：管理端修改密码接口

> **Change**：`2026-11-19_admin_password_change`

## D1 接口契约

```
POST /api/admin/auth/change-password
Authorization: Bearer <admin token>          # role=admin，由 JwtInterceptor + @AdminApi 保证
Content-Type: application/json

{ "oldPassword": "原密码", "newPassword": "新密码（6~64 位）" }

200 → { "code": 200, "message": "success", "data": null }
原密码错误 → { "code": 1194, "message": "原密码不正确" }
新旧相同 → { "code": 1195, "message": "新密码不能与原密码相同" }
未登录/学生 token → HTTP 403（拦截器直写，不进业务层）
参数缺失/长度不合法 → 既有参数校验错误码（`@Valid` + `GlobalExceptionHandler`）
```

## D2 身份来源

- `adminId` **只从服务端上下文取**（`AdminContext.getAdminId()`，由拦截器从 JWT `sub` 写入），
  不接受客户端传 ID；
- 租户由 MyBatis-Plus 租户插件按请求上下文追加（admin 请求已写 `StudentContext.tenantId`），
  因此 `selectById(adminId)` 天然不会跨租户命中。

## D3 校验顺序（避免信息泄漏 + 避免误报）

1. `adminId == null` → `UNAUTHORIZED`（理论上不会发生，防御）；
2. 按 ID 查账号，查不到 → `UNAUTHORIZED`（"账号不存在，请重新登录"，**不区分**是否存在）；
3. 账号非 `active` → `UNAUTHORIZED`（"账号已停用"）；
4. 原密码校验失败 → **`ADMIN_PASSWORD_MISMATCH(1194)`**（业务码，不走 401，避免前端把用户踢下线）；
5. 新密码与原哈希校验一致 → **`ADMIN_PASSWORD_UNCHANGED(1195)`**；
6. `hashPassword(newPassword)` → `updateById`（只更新 `password_hash`，不动 role/status/tenant）。

> 顺序保证：账号层面的判断在前（统一 401），密码层面的判断在后（1194/1195），
> 攻击者无法通过错误码区分"账号不存在"与"密码错"以外的东西。

## D4 为什么改密后前端必须重新登录

JWT 无状态：改密不会让已签发的 token 失效（本次不引入 token 版本号）。为避免"改完密码旧会话还在用"的错觉，
前端在成功回调里 `localStorage.removeItem('VITE_DEMO_TOKEN')` 并跳 `/login`；这是**体验层的收敛**，
真正的强制失效需要服务端 token 版本（已记入未验证/后续改进）。

## D5 表结构

复用 `admin_user`（`V17__admin_user.sql`），**无 DDL 变更**。`password_hash VARCHAR(255)` 足够容纳 `pbkdf2$210000$…`。

## D6 测试策略

| 层 | 用例 |
|---|---|
| Service | 原密码正确 → `updateById` 被调用且新哈希可验；原密码错 → 1194 且不写库；新旧相同 → 1195 且不写库；账号不存在 → 401；账号停用 → 401 |
| Controller | admin token 调接口 → 200 且服务被调用、`adminId` 来自上下文；学生 token → 403；缺少字段 → 参数校验错误 |
| 回归 | 既有登录/鉴权用例全绿（`AdminAuthServiceTest`、`JwtInterceptorTest`、9 个 Admin*ControllerTest） |

## D7 风险与取舍

- **不能自助找回密码**：忘记密码仍需运维删号重建或改库（README 保留该兜底路径）；
- **改密不踢其它设备**：同上，需 token 版本号方案（另立 change）；
- 密码强度只做长度校验（6~64），未做字典/复杂度校验——与登录页现有规则保持一致，避免前后端口径不一致。
