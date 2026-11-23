<template>
  <el-container class="app-layout">
    <!-- 侧边栏 -->
    <el-aside width="200px" class="sidebar">
      <div class="logo">智学伴管理后台</div>
      <el-menu
        :default-active="activeMenu"
        class="sidebar-menu"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409eff"
        router
      >
        <el-menu-item index="/questions">
          <el-icon><Document /></el-icon>
          <span>题库管理</span>
        </el-menu-item>
        <el-menu-item index="/knowledge-points">
          <el-icon><Collection /></el-icon>
          <span>知识点管理</span>
        </el-menu-item>
        <el-menu-item index="/dependencies">
          <el-icon><Connection /></el-icon>
          <span>先修关系管理</span>
        </el-menu-item>
        <el-menu-item index="/standards">
          <el-icon><Notebook /></el-icon>
          <span>课程标准管理</span>
        </el-menu-item>
        <el-menu-item index="/import-logs">
          <el-icon><Upload /></el-icon>
          <span>导入日志</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶部栏 -->
      <el-header class="header">
        <div class="header-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="currentRoute">{{ currentRoute.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="user-info user-info--clickable">
              管理员
              <el-icon class="user-info__arrow"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="changePassword">修改密码</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-divider direction="vertical" />
          <el-button link type="primary" class="logout-btn" @click="handleUserCommand('logout')">
            退出登录
          </el-button>
        </div>
      </el-header>

      <!-- 修改密码弹窗（Change: 2026-11-19_admin_password_change） -->
      <el-dialog
        v-model="pwdDialogVisible"
        title="修改密码"
        width="420px"
        :close-on-click-modal="false"
        @closed="resetPasswordForm"
      >
        <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="96px">
          <el-form-item label="原密码" prop="oldPassword">
            <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入原密码" />
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="6~64 位" />
          </el-form-item>
          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="请再次输入新密码" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="pwdDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="pwdSubmitting" @click="submitChangePassword">确定</el-button>
        </template>
      </el-dialog>

      <!-- 内容区域 -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowDown, Document, Collection, Connection, Notebook, Upload } from '@element-plus/icons-vue';
import { changePassword } from '@/api/auth';

const route = useRoute();
const router = useRouter();

const activeMenu = computed(() => {
  return route.path;
});

const currentRoute = computed(() => {
  return route;
});

// ========== 顶部栏：修改密码 / 退出登录（Change: 2026-11-19_admin_password_change）==========

const pwdDialogVisible = ref(false);
const pwdSubmitting = ref(false);
const pwdFormRef = ref(null);
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '新密码长度需为 6~64 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== pwdForm.newPassword) {
          callback(new Error('两次输入的新密码不一致'));
          return;
        }
        callback();
      },
      trigger: 'blur'
    }
  ]
};

function handleUserCommand(command) {
  if (command === 'changePassword') {
    pwdDialogVisible.value = true;
  } else if (command === 'logout') {
    logout();
  }
}

function resetPasswordForm() {
  Object.assign(pwdForm, { oldPassword: '', newPassword: '', confirmPassword: '' });
  pwdFormRef.value?.clearValidate?.();
}

/** 改密成功后强制回登录页（JWT 无状态，旧 token 在过期前仍有效，故清 token 重新登录） */
async function submitChangePassword() {
  try {
    await pwdFormRef.value.validate();
  } catch {
    return;
  }
  pwdSubmitting.value = true;
  try {
    await changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword });
    ElMessage.success('密码修改成功，请使用新密码重新登录');
    pwdDialogVisible.value = false;
    logout();
  } catch (error) {
    // 业务码提示（原密码不正确 1194 / 新旧相同 1195）由 axios 拦截器统一弹出
  } finally {
    pwdSubmitting.value = false;
  }
}

function logout() {
  localStorage.removeItem('VITE_DEMO_TOKEN');
  router.replace('/login');
}
</script>

<style scoped>
.app-layout {
  height: 100vh;
}

.sidebar {
  background-color: #304156;
  overflow-y: auto;
}

.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  font-size: 18px;
  font-weight: bold;
  color: #fff;
  background-color: #2b3a4a;
}

.sidebar-menu {
  border-right: none;
}

.header {
  background-color: #fff;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.header-left {
  flex: 1;
}

.header-right {
  display: flex;
  align-items: center;
}

.user-info {
  font-size: 14px;
  color: #666;
}

.user-info--clickable {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  outline: none;
}

.user-info--clickable:hover {
  color: #409eff;
}

.user-info__arrow {
  font-size: 12px;
}

.logout-btn {
  margin-left: 12px;
  font-size: 14px;
}

.main-content {
  background-color: #f0f2f5;
  padding: 20px;
  overflow-y: auto;
}
</style>
