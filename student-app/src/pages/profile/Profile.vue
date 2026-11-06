<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <text class="nav-title">个人中心</text>
      </view>
    </view>

    <!-- 用户信息卡片 -->
    <view class="user-card">
      <view class="user-avatar">
        <text class="avatar-text">{{ avatarText }}</text>
      </view>
      <view class="user-info">
        <text class="user-name">{{ displayName }}</text>
        <text class="user-grade" v-if="studentGrade">{{ studentGrade }}</text>
        <text class="user-id">ID: {{ displayId }}</text>
      </view>
    </view>

    <!-- 功能菜单 -->
    <view class="menu-list">
      <view class="menu-item" @click="activeSection = activeSection === 'goal' ? '' : 'goal'">
        <text class="menu-icon">🎯</text>
        <text class="menu-text">学习目标</text>
        <text class="menu-arrow">{{ activeSection === 'goal' ? '▲' : '▼' }}</text>
      </view>
      <view class="menu-section" v-if="activeSection === 'goal'">
        <!-- 活跃目标列表 -->
        <view class="goals-active-list" v-if="activeGoalsList && activeGoalsList.length > 0">
          <text class="section-subtitle">活跃目标 ({{ activeGoalsList.length }})</text>
          <view class="goal-active" v-for="(goal, index) in activeGoalsList" :key="goal.goalId">
            <view class="goal-info">
              <text class="goal-direction">{{ goal.direction || '未设置方向' }}</text>
              <text class="goal-subject">{{ goal.subjectName }}</text>
            </view>
            <view class="goal-dates">
              <text class="goal-date">{{ goal.startDate }} ~ {{ goal.endDate }}</text>
              <text class="goal-remaining">剩余 {{ getRemainingDays(goal.endDate) }} 天</text>
            </view>
            <button class="btn-complete" size="mini" @click="handleCompleteGoal(goal)">结束目标</button>
          </view>
        </view>
        <view class="goal-empty" v-else-if="!goalLoading">
          <text class="empty-hint">暂无活跃目标</text>
        </view>
        <!-- 创建目标表单 -->
        <view class="goal-form">
          <text class="form-title">创建新目标</text>
          <view class="form-item">
            <text class="form-label">科目</text>
            <picker :value="goalSubjectIndex" :range="subjectNames" @change="onGoalSubjectChange">
              <view class="form-picker">
                <text>{{ goalSubjectNames[goalSubjectIndex] || '选择科目' }}</text>
                <text class="picker-arrow">▼</text>
              </view>
            </picker>
          </view>
          <view class="form-item">
            <text class="form-label">方向描述</text>
            <input class="form-input" v-model="goalForm.direction" placeholder="如：提高数学成绩" maxlength="500" />
          </view>
          <view class="form-item">
            <text class="form-label">目标分数</text>
            <input class="form-input" v-model="goalForm.targetScore" type="number" placeholder="0-150" />
          </view>
          <view class="form-item">
            <text class="form-label">开始日期</text>
            <picker mode="date" :value="goalForm.startDate" @change="goalForm.startDate = $event.detail.value">
              <view class="form-picker">
                <text>{{ goalForm.startDate || '选择开始日期' }}</text>
                <text class="picker-arrow">▼</text>
              </view>
            </picker>
          </view>
          <view class="form-item">
            <text class="form-label">结束日期</text>
            <picker mode="date" :value="goalForm.endDate" :start="goalForm.startDate || today" @change="goalForm.endDate = $event.detail.value">
              <view class="form-picker">
                <text>{{ goalForm.endDate || '选择结束日期' }}</text>
                <text class="picker-arrow">▼</text>
              </view>
            </picker>
          </view>
          <button class="btn-create-goal" size="mini" @click="handleCreateGoal" :disabled="goalSubmitting">
            {{ goalSubmitting ? '创建中...' : '创建目标' }}
          </button>
        </view>
        <!-- 历史目标列表 -->
        <view class="goal-history" v-if="goalHistory.length > 0">
          <text class="section-subtitle">历史目标</text>
          <view class="history-item" v-for="g in goalHistory" :key="g.goalId">
            <view class="history-info">
              <text class="history-direction">{{ g.direction || '未设置方向' }}</text>
              <text class="history-status" :class="'status-' + g.status">{{ g.status }}</text>
            </view>
            <text class="history-dates">{{ g.startDate }} ~ {{ g.endDate }}</text>
          </view>
        </view>
      </view>

      <view class="menu-item" @click="activeSection = activeSection === 'exam' ? '' : 'exam'">
        <text class="menu-icon">📝</text>
        <text class="menu-text">试卷上传</text>
        <text class="menu-arrow">{{ activeSection === 'exam' ? '▲' : '▼' }}</text>
      </view>
      <view class="menu-section" v-if="activeSection === 'exam'">
        <view class="exam-form">
          <view class="form-item">
            <text class="form-label">科目</text>
            <picker :value="examSubjectIndex" :range="subjectNames" @change="onExamSubjectChange">
              <view class="form-picker">
                <text>{{ subjectNames[examSubjectIndex] || '选择科目' }}</text>
                <text class="picker-arrow">▼</text>
              </view>
            </picker>
          </view>
          <view class="form-item">
            <text class="form-label">考试日期</text>
            <picker mode="date" :value="examForm.examDate" @change="examForm.examDate = $event.detail.value">
              <view class="form-picker">
                <text>{{ examForm.examDate || '选择日期' }}</text>
                <text class="picker-arrow">▼</text>
              </view>
            </picker>
          </view>
          <view class="form-item">
            <text class="form-label">试卷类型</text>
            <picker :value="examPaperTypeIndex" :range="examPaperTypes" @change="onPaperTypeChange">
              <view class="form-picker">
                <text>{{ examPaperTypeNames[examPaperTypeIndex] || examForm.paperType }}</text>
                <text class="picker-arrow">▼</text>
              </view>
            </picker>
          </view>
          <button class="btn-upload" @click="handleChooseAndUpload" :disabled="examUploading">
            {{ examUploading ? '上传中...' : '选择图片并上传' }}
          </button>
        </view>
      </view>

      <!-- 错题本入口 -->
      <view class="menu-item" @click="goToErrorBook">
        <text class="menu-icon">❌</text>
        <text class="menu-text">错题本</text>
        <text class="menu-arrow">▶</text>
      </view>

      <view class="menu-item" @click="activeSection = activeSection === 'record' ? '' : 'record'">
        <text class="menu-icon">📋</text>
        <text class="menu-text">学习记录</text>
        <text class="menu-arrow">{{ activeSection === 'record' ? '▲' : '▼' }}</text>
      </view>
      <view class="menu-section" v-if="activeSection === 'record'">
        <!-- 筛选 -->
        <view class="filter-bar">
          <picker :value="recordTypeIndex" :range="recordTypes" :range-name="recordTypeNames" @change="onRecordTypeChange">
            <view class="filter-item">
              <text>{{ recordTypeNames[recordTypeIndex] || '全部类型' }}</text>
              <text class="picker-arrow">▼</text>
            </view>
          </picker>
        </view>
        <!-- 记录列表 -->
        <view class="record-list" v-if="recordList.length > 0">
          <view class="record-item" v-for="r in recordList" :key="r.recordId" @click="viewRecordDetail(r)">
            <view class="record-info">
              <text class="record-title">{{ r.title }}</text>
              <text class="record-type-tag">{{ r.recordType }}</text>
            </view>
            <view class="record-meta">
              <text class="record-subject">{{ r.subjectName }}</text>
              <text class="record-score" v-if="r.score != null">{{ r.score }}分</text>
              <text class="record-time">{{ formatRecordTime(r.createTime) }}</text>
            </view>
          </view>
        </view>
        <EmptyState v-else-if="!recordLoading" text="暂无学习记录" />
        <LoadingState :loading="recordLoading" text="加载中..." />
        <!-- 记录详情弹窗 -->
        <view class="detail-overlay" v-if="recordDetail" @click="recordDetail = null">
          <view class="detail-popup" @click.stop>
            <view class="detail-header">
              <text class="detail-title">记录详情</text>
              <text class="detail-close" @click="recordDetail = null">✕</text>
            </view>
            <scroll-view scroll-y class="detail-body">
              <view class="detail-row" v-if="recordDetail.title">
                <text class="detail-label">标题</text>
                <text class="detail-value">{{ recordDetail.title }}</text>
              </view>
              <view class="detail-row" v-if="recordDetail.recordType">
                <text class="detail-label">类型</text>
                <text class="detail-value">{{ recordDetail.recordType }}</text>
              </view>
              <view class="detail-row" v-if="recordDetail.subjectName">
                <text class="detail-label">科目</text>
                <text class="detail-value">{{ recordDetail.subjectName }}</text>
              </view>
              <view class="detail-row" v-if="recordDetail.score != null">
                <text class="detail-label">分数</text>
                <text class="detail-value">{{ recordDetail.score }}</text>
              </view>
              <view class="detail-row" v-if="recordDetail.createTime">
                <text class="detail-label">时间</text>
                <text class="detail-value">{{ formatRecordTime(recordDetail.createTime) }}</text>
              </view>
              <view class="detail-row" v-if="recordDetail.content">
                <text class="detail-label">内容</text>
                <text class="detail-value detail-content">{{ recordDetail.content }}</text>
              </view>
            </scroll-view>
          </view>
        </view>
      </view>

      <view class="menu-item" @click="activeSection = activeSection === 'adjust' ? '' : 'adjust'">
        <text class="menu-icon">🔄</text>
        <text class="menu-text">计划调整</text>
        <text class="menu-arrow">{{ activeSection === 'adjust' ? '▲' : '▼' }}</text>
      </view>
      <view class="menu-section" v-if="activeSection === 'adjust'">
        <view class="adjust-area">
          <button class="btn-adjust" @click="handleTriggerAdjust" :disabled="adjustTriggering || activeGoalsList.length === 0">
            {{ adjustTriggering ? '触发中...' : '触发计划调整' }}
          </button>
          <text class="adjust-hint" v-if="!activeGoal">需先创建学习目标</text>
        </view>
        <!-- 调整历史 -->
        <view class="adjust-history" v-if="adjustHistory.length > 0">
          <text class="section-subtitle">调整历史</text>
          <view class="history-item" v-for="a in adjustHistory" :key="a.logId">
            <view class="history-info">
              <text class="history-reason">{{ a.reason || '无原因说明' }}</text>
              <text class="history-action" :class="'action-' + a.action">{{ actionLabel(a.action) }}</text>
            </view>
            <text class="history-time">{{ formatRecordTime(a.actionAt || a.createTime) }}</text>
          </view>
        </view>
        <EmptyState v-else-if="!adjustHistoryLoading" text="暂无调整记录" />
        <LoadingState :loading="adjustHistoryLoading" text="加载中..." />
      </view>
    </view>

    <!-- 登出按钮 -->
    <view class="logout-area">
      <button class="btn-logout" @click="handleLogout">退出登录</button>
    </view>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';
import { useAuthGuard } from '@/utils/useAuthGuard.js';
import { createGoal, getActiveGoals, getGoalList, completeGoal } from '@/api/goal.js';
import { EXAM_UPLOAD_PATH } from '@/api/exam.js';
import { getRecordList, getRecordDetail } from '@/api/record.js';
import { triggerAdjustment, getAdjustmentHistory } from '@/api/plan.js';
import { uploadWithAuth } from '@/utils/upload.js';
import { showSuccess, showError, showConfirm } from '@/utils/message.js';
import { formatDate } from '@/utils/date.js';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

const authStore = useAuthStore();
const appStore = useAppStore();

// ========== 通用状态 ==========
const statusBarHeight = ref(0);
const activeSection = ref('');
const today = formatDate(new Date(), 'YYYY-MM-DD');

// ========== 目标管理 ==========
const activeGoalsList = ref([]); // 活跃目标列表
const goalHistory = ref([]);
const goalLoading = ref(false);
const goalSubmitting = ref(false);
const goalSubjectIndex = ref(0);
const goalForm = ref({
  direction: '',
  targetScore: '',
  startDate: today,
  endDate: ''
});

const subjectNames = computed(() => appStore.subjectList.map(s => s.name));
const goalSubjectNames = computed(() => appStore.subjectList.map(s => s.name));

// 计算剩余天数
function getRemainingDays(endDate) {
  if (!endDate) return 0;
  const end = new Date(endDate);
  const now = new Date();
  const diff = Math.ceil((end - now) / (1000 * 60 * 60 * 24));
  return diff > 0 ? diff : 0;
}

// ========== 试卷上传 ==========
const examSubjectIndex = ref(0);
const examForm = ref({ examDate: today, paperType: 'midterm' });
const examUploading = ref(false);
const examPaperTypes = ['midterm', 'final', 'monthly', 'mock', 'other'];
const examPaperTypeNames = ['期中考试', '期末考试', '月考', '模拟考试', '其他'];
const examPaperTypeIndex = computed(() => examPaperTypes.indexOf(examForm.value.paperType));

function onPaperTypeChange(e) {
  examForm.value.paperType = examPaperTypes[e.detail.value];
}

// ========== 学习记录 ==========
const recordList = ref([]);
const recordLoading = ref(false);
const recordDetail = ref(null);
const recordTypeIndex = ref(0);
const recordTypes = ['', 'homework', 'assessment', 'exam'];
const recordTypeNames = ['全部类型', '作业', '评测', '考试'];

// ========== 计划调整 ==========
const adjustHistory = ref([]);
const adjustHistoryLoading = ref(false);
const adjustTriggering = ref(false);

// ========== 用户信息 ==========
const displayName = computed(() => {
  return authStore.studentInfo?.nickName || authStore.studentInfo?.name || '同学';
});

const studentGrade = computed(() => {
  return authStore.studentInfo?.grade || '';
});

const displayId = computed(() => {
  return authStore.studentInfo?.openid || authStore.studentInfo?.studentId || '--';
});

const avatarText = computed(() => {
  const name = displayName.value;
  return name ? name.charAt(0) : '👤';
});

// ========== 生命周期 ==========
onShow(async () => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;

  // 如果科目列表为空，重试加载（等待完成后再继续）
  if (authStore.isLoggedIn && appStore.subjectList.length === 0) {
    await appStore.init();
  }
  // 每次显示时刷新目标数据
  fetchActiveGoal();
});

// ========== 目标管理 ==========
async function fetchActiveGoal() {
  goalLoading.value = true;
  console.log('[Profile] fetchActiveGoals - currentSubjectId:', appStore.currentSubjectId);
  try {
    // 不传 subjectId，返回所有科目的活跃目标列表
    activeGoalsList.value = await getActiveGoals() || [];
    console.log('[Profile] fetchActiveGoals - activeGoalsList:', activeGoalsList.value);
    
    // 加载历史目标（排除活跃目标）
    const result = await getGoalList(1, 20);
    const activeGoalIds = new Set(activeGoalsList.value.map(g => g.goalId));
    goalHistory.value = (result?.records || []).filter(
      g => !activeGoalIds.has(g.goalId)
    );
    console.log('[Profile] fetchActiveGoals - goalHistory:', goalHistory.value);
  } catch (e) {
    console.warn('[Profile] 获取目标失败:', e.message);
  } finally {
    goalLoading.value = false;
  }
}

function onGoalSubjectChange(e) {
  goalSubjectIndex.value = e.detail.value;
}

async function handleCreateGoal() {
  const subject = appStore.subjectList[goalSubjectIndex.value];
  if (!subject) {
    showError('请先选择科目');
    return;
  }
  if (!goalForm.value.endDate) {
    showError('请选择结束日期');
    return;
  }

  goalSubmitting.value = true;
  try {
    await createGoal({
      subjectId: subject.subjectId,
      direction: goalForm.value.direction || undefined,
      targetScore: goalForm.value.targetScore ? Number(goalForm.value.targetScore) : undefined,
      startDate: goalForm.value.startDate,
      endDate: goalForm.value.endDate
    });
    showSuccess('目标创建成功');
    // 重置表单
    goalForm.value = { direction: '', targetScore: '', startDate: today, endDate: '' };
    await fetchActiveGoal();
  } catch (e) {
    showError('创建失败：' + (e.message || '未知错误'));
  } finally {
    goalSubmitting.value = false;
  }
}

async function handleCompleteGoal(goal) {
  if (!goal) return;
  const confirmed = await showConfirm('确定要结束当前学习目标吗？');
  if (!confirmed) return;

  try {
    await completeGoal(goal.goalId);
    showSuccess('目标已结束');
    await fetchActiveGoal();
  } catch (e) {
    showError('操作失败：' + (e.message || '未知错误'));
  }
}

// ========== 试卷上传 ==========
function onExamSubjectChange(e) {
  examSubjectIndex.value = e.detail.value;
}

async function handleChooseAndUpload() {
  const subject = appStore.subjectList[examSubjectIndex.value];
  if (!subject) {
    showError('请先选择科目');
    return;
  }
  if (!examForm.value.examDate) {
    showError('请选择考试日期');
    return;
  }

  // 选择图片
  let chooseRes;
  try {
    chooseRes = await new Promise((resolve, reject) => {
      uni.chooseImage({
        count: 1,
        sizeType: ['compressed'],
        sourceType: ['album', 'camera'],
        success: resolve,
        fail: reject
      });
    });
  } catch (e) {
    console.warn('[Profile] 选择图片取消或失败');
    return;
  }

  const filePath = chooseRes.tempFilePaths[0];
  if (!filePath) {
    showError('未选择图片');
    return;
  }

  examUploading.value = true;
  try {
    await uploadWithAuth(
      EXAM_UPLOAD_PATH,
      filePath,
      'files',
      {
        subjectId: subject.subjectId,
        examDate: examForm.value.examDate,
        paperType: examForm.value.paperType
      }
    );
    showSuccess('试卷上传成功');
  } catch (e) {
    // uploadWithAuth 内部已处理错误提示
    console.warn('[Profile] 上传失败:', e.message);
  } finally {
    examUploading.value = false;
  }
}

// ========== 学习记录 ==========
async function fetchRecords() {
  recordLoading.value = true;
  try {
    const params = { page: 1, size: 20 };
    const typeVal = recordTypes[recordTypeIndex.value];
    if (typeVal) params.recordType = typeVal;
    const result = await getRecordList(params);
    recordList.value = result?.records || [];
  } catch (e) {
    console.warn('[Profile] 获取记录失败:', e.message);
    recordList.value = [];
  } finally {
    recordLoading.value = false;
  }
}

function onRecordTypeChange(e) {
  recordTypeIndex.value = e.detail.value;
  fetchRecords();
}

async function viewRecordDetail(record) {
  try {
    recordDetail.value = await getRecordDetail(record.recordType, record.recordId);
  } catch (e) {
    showError('获取详情失败');
    console.warn('[Profile] 获取记录详情失败:', e.message);
  }
}

function formatRecordTime(dt) {
  return formatDate(dt, 'YYYY-MM-DD HH:mm');
}

// ========== 计划调整 ==========
async function fetchAdjustHistory() {
  adjustHistoryLoading.value = true;
  try {
    const result = await getAdjustmentHistory(1, 20);
    adjustHistory.value = result?.records || [];
  } catch (e) {
    console.warn('[Profile] 获取调整历史失败:', e.message);
    adjustHistory.value = [];
  } finally {
    adjustHistoryLoading.value = false;
  }
}

async function handleTriggerAdjust() {
  if (activeGoalsList.value.length === 0) return;
  // 取第一个活跃目标进行计划调整
  const goal = activeGoalsList.value[0];
  adjustTriggering.value = true;
  try {
    await triggerAdjustment(goal.goalId);
    showSuccess('已触发计划调整');
    await fetchAdjustHistory();
  } catch (e) {
    showError('触发失败：' + (e.message || '未知错误'));
  } finally {
    adjustTriggering.value = false;
  }
}

function actionLabel(action) {
  const map = { confirmed: '已确认', rejected: '已拒绝', expired: '已过期', pending: '待确认' };
  return map[action] || action;
}

// ========== 登出 ==========
async function handleLogout() {
  const confirmed = await showConfirm('确定要退出登录吗？');
  if (!confirmed) return;
  authStore.logout();
}

// ========== 错题本 ==========
function goToErrorBook() {
  uni.navigateTo({
    url: '/pages/error/Error'
  });
}

// ========== 监听 section 切换，触发数据加载 ==========
import { watch } from 'vue';
watch(activeSection, (val) => {
  if (val === 'record') fetchRecords();
  if (val === 'adjust') fetchAdjustHistory();
});
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background-color: $bg-color;
  padding-bottom: 120rpx;
}

/* 导航栏 */
.nav-bar {
  background-color: $bg-white;
  position: sticky;
  top: 0;
  z-index: 99;
  box-shadow: $shadow-sm;
}
.nav-bar-inner {
  height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.nav-title {
  font-size: $font-lg;
  font-weight: bold;
  color: $text-primary;
}

/* 用户卡片 */
.user-card {
  display: flex;
  align-items: center;
  padding: $spacing-xl $spacing-lg;
  background-color: $bg-white;
  margin-bottom: $spacing-md;
}
.user-avatar {
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, $primary-color, #66b1ff);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: $spacing-lg;
  flex-shrink: 0;
}
.avatar-text {
  font-size: $font-xl;
  color: #fff;
  font-weight: bold;
}
.user-info {
  display: flex;
  flex-direction: column;
}
.user-name {
  font-size: $font-lg;
  font-weight: bold;
  color: $text-primary;
  margin-bottom: 4rpx;
}
.user-grade {
  font-size: $font-sm;
  color: $primary-color;
  margin-bottom: 4rpx;
}
.user-id {
  font-size: $font-sm;
  color: $text-secondary;
}

/* 功能菜单 */
.menu-list {
  padding: 0 $spacing-lg;
}
.menu-item {
  display: flex;
  align-items: center;
  padding: $spacing-lg;
  background-color: $bg-white;
  border-radius: $radius-md;
  margin-bottom: 2rpx;
  box-shadow: $shadow-sm;
}
.menu-icon {
  font-size: 40rpx;
  margin-right: $spacing-md;
}
.menu-text {
  flex: 1;
  font-size: $font-md;
  color: $text-primary;
  font-weight: 500;
}
.menu-arrow {
  font-size: $font-sm;
  color: $text-secondary;
}

.menu-section {
  background-color: $bg-white;
  border-radius: 0 0 $radius-md $radius-md;
  padding: $spacing-lg;
  margin-bottom: $spacing-md;
  margin-top: -$spacing-md;
  box-shadow: $shadow-sm;
}

/* 目标管理 */
.goal-active {
  padding: $spacing-md;
  background-color: #f0f9eb;
  border-radius: $radius-md;
  margin-bottom: $spacing-lg;
  border-left: 6rpx solid $success-color;
}
.goal-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $spacing-sm;
}
.goal-direction {
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
  flex: 1;
}
.goal-subject {
  font-size: $font-xs;
  color: $success-color;
  background-color: rgba(103, 194, 58, 0.1);
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
}
.goal-dates {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: $spacing-sm;
}
.goal-date, .goal-remaining {
  font-size: $font-sm;
  color: $text-secondary;
}
.btn-complete {
  background-color: $danger-color;
  color: #fff;
  font-size: $font-sm;
  border-radius: $radius-md;
}

.goal-empty {
  padding: $spacing-lg;
  text-align: center;
}
.empty-hint {
  font-size: $font-md;
  color: $text-secondary;
}

/* 表单 */
.goal-form, .exam-form {
  margin-top: $spacing-lg;
}
.form-title {
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
  margin-bottom: $spacing-md;
  display: block;
}
.form-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $spacing-md 0;
  border-bottom: 1rpx solid $border-light;
}
.form-label {
  font-size: $font-md;
  color: $text-regular;
  width: 160rpx;
  flex-shrink: 0;
}
.form-input {
  flex: 1;
  text-align: right;
  font-size: $font-md;
  color: $text-primary;
}
.form-picker {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}

.btn-create-goal {
  margin-top: $spacing-lg;
  background-color: $primary-color;
  color: #fff;
  font-size: $font-sm;
  border-radius: $radius-md;
}

/* 历史目标 */
.goal-history {
  margin-top: $spacing-lg;
}
.section-subtitle {
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
  margin-bottom: $spacing-md;
  display: block;
}
.history-item {
  padding: $spacing-md 0;
  border-bottom: 1rpx solid $border-light;
}
.history-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4rpx;
}
.history-direction {
  font-size: $font-md;
  color: $text-primary;
  flex: 1;
}
.history-status {
  font-size: $font-xs;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
}
.status-completed { color: $success-color; background-color: #f0f9eb; }
.status-expired { color: $text-secondary; background-color: #f5f5f5; }
.status-active { color: $primary-color; background-color: $primary-light; }
.history-dates, .history-time {
  font-size: $font-sm;
  color: $text-secondary;
}
.history-action {
  font-size: $font-xs;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
}
.action-confirmed { color: $success-color; background-color: #f0f9eb; }
.action-rejected { color: $danger-color; background-color: #fef0f0; }
.action-expired { color: $text-secondary; background-color: #f5f5f5; }
.action-pending { color: $warning-color; background-color: #fdf6ec; }
.history-reason {
  font-size: $font-md;
  color: $text-primary;
  flex: 1;
}

/* 试卷上传 */
.btn-upload {
  margin-top: $spacing-lg;
  background-color: $primary-color;
  color: #fff;
  font-size: $font-md;
  border-radius: $radius-md;
}

/* 学习记录 */
.filter-bar {
  display: flex;
  align-items: center;
  margin-bottom: $spacing-md;
}
.filter-item {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  padding: $spacing-sm $spacing-md;
  background-color: #f5f5f5;
  border-radius: $radius-md;
  font-size: $font-sm;
  color: $text-regular;
}
.record-list {
  margin-top: $spacing-md;
}
.record-item {
  padding: $spacing-md 0;
  border-bottom: 1rpx solid $border-light;
}
.record-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4rpx;
}
.record-title {
  font-size: $font-md;
  color: $text-primary;
  flex: 1;
}
.record-type-tag {
  font-size: $font-xs;
  color: $primary-color;
  background-color: $primary-light;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
}
.record-meta {
  display: flex;
  align-items: center;
  gap: $spacing-md;
}
.record-subject, .record-score, .record-time {
  font-size: $font-sm;
  color: $text-secondary;
}
.record-score {
  color: $warning-color;
  font-weight: 500;
}

/* 记录详情弹窗 */
.detail-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  z-index: 999;
  display: flex;
  align-items: center;
  justify-content: center;
}
.detail-popup {
  width: 85%;
  max-height: 70vh;
  background-color: $bg-white;
  border-radius: $radius-lg;
  overflow: hidden;
}
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $spacing-lg;
  border-bottom: 1rpx solid $border-light;
}
.detail-title {
  font-size: $font-lg;
  font-weight: bold;
  color: $text-primary;
}
.detail-close {
  font-size: $font-lg;
color: $text-secondary;
}
.detail-body {
  padding: $spacing-lg;
  max-height: 50vh;
}
.detail-row {
  display: flex;
  margin-bottom: $spacing-md;
}
.detail-label {
  width: 120rpx;
  font-size: $font-sm;
  color: $text-secondary;
  flex-shrink: 0;
}
.detail-value {
  flex: 1;
  font-size: $font-md;
  color: $text-primary;
}
.detail-content {
  line-height: 1.6;
}

/* 计划调整 */
.adjust-area {
  margin-bottom: $spacing-lg;
}
.btn-adjust {
  background-color: $warning-color;
  color: #fff;
  font-size: $font-md;
  border-radius: $radius-md;
}
.adjust-hint {
  display: block;
  text-align: center;
  font-size: $font-sm;
  color: $text-secondary;
  margin-top: $spacing-sm;
}

.adjust-history {
  margin-top: $spacing-md;
}

/* 登出 */
.logout-area {
  padding: $spacing-xl $spacing-lg;
}
.btn-logout {
  background-color: $bg-white;
  color: $danger-color;
  font-size: $font-md;
  border: 2rpx solid $danger-color;
  border-radius: $radius-lg;
}
</style>
