<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <button class="btn-back" @click="goBack" aria-label="返回">
          <view class="back-icon"></view>
        </button>
        <text class="nav-title">错题本</text>
        <view class="nav-placeholder"></view>
      </view>
    </view>

    <!-- 筛选栏 -->
    <view class="filter-bar">
      <picker :value="subjectIndex" :range="subjectNames" @change="onSubjectChange">
        <view class="filter-item">
          <text>{{ subjectNames[subjectIndex] || '全部科目' }}</text>
          <text class="filter-arrow">▼</text>
        </view>
      </picker>
      <picker :value="statusIndex" :range="statusNames" @change="onStatusChange">
        <view class="filter-item">
          <text>{{ statusNames[statusIndex] }}</text>
          <text class="filter-arrow">▼</text>
        </view>
      </picker>
    </view>

    <!-- 统计信息 -->
    <view class="stats-bar">
      <view class="stat-item">
        <text class="stat-value">{{ totalCount }}</text>
        <text class="stat-label">总错题数</text>
      </view>
      <view class="stat-item">
        <text class="stat-value">{{ statistics.dueCount ?? 0 }}</text>
        <text class="stat-label">待复习</text>
      </view>
      <view class="stat-item">
        <text class="stat-value">{{ completedCount }}</text>
        <text class="stat-label">已掌握</text>
      </view>
    </view>

    <!-- 错题列表 -->
    <view class="error-list" v-if="errorList.length > 0">
      <view 
        class="error-card" 
        v-for="item in errorList" 
        :key="item.errorQuestionId"
        @click="viewDetail(item)"
      >
        <view class="card-header">
          <text class="subject-tag">{{ item.knowledgePointName || '未分类' }}</text>
          <text class="type-tag">{{ formatSourceLabel(item.sourceType) }}</text>
          <text class="stage-tag" :class="'stage-' + item.currentStage">第{{ item.currentStage }}阶段</text>
        </view>
        <view class="card-content">
          <text class="question-text">{{ truncateText(item.questionContent, 100) }}</text>
        </view>
        <view class="card-footer">
          <text class="error-time">错误时间：{{ item.firstWrongDate || '—' }}</text>
          <text class="review-count">下次复习：{{ item.nextDueDate || '—' }}</text>
        </view>
      </view>
    </view>

    <!-- 空状态 -->
    <EmptyState v-else-if="!loading" text="暂无错题记录" />

    <!-- 加载中 -->
    <LoadingState :loading="loading" text="加载中..." />

    <!-- 分页器（超过10条时显示） -->
    <view class="pagination" v-if="totalPages > 1">
      <button 
        class="btn-page" 
        :disabled="currentPage === 1" 
        @click="prevPage"
      >上一页</button>
      <text class="page-info">{{ currentPage }} / {{ totalPages }}</text>
      <button 
        class="btn-page" 
        :disabled="currentPage === totalPages" 
        @click="nextPage"
      >下一页</button>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useAppStore } from '@/stores/app';
import { useAuthGuard } from '@/utils/useAuthGuard';
import { getErrorQuestions, getErrorStatistics } from '@/api/error';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

const appStore = useAppStore();

// ========== 状态 ==========
const statusBarHeight = ref(0);
const errorList = ref([]);
const loading = ref(false);
const statistics = ref({});
const totalCount = ref(0);
const completedCount = ref(0);
const currentPage = ref(1);
const pageSize = ref(10);
const totalRecords = ref(0);

// 筛选条件
const subjectIndex = ref(0); // 0 表示全部科目
const statusIndex = ref(0); // 0: 全部, 1: active, 2: completed
const subjectNames = computed(() => ['全部科目', ...appStore.subjectList.map(s => s.name)]);
const statusNames = ['全部状态', '待复习', '已掌握'];

// 计算总页数
const totalPages = computed(() => Math.ceil(totalRecords.value / pageSize.value));

// ========== 生命周期 ==========
onMounted(() => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;
  fetchStatistics();
  fetchErrors();
});

// ========== 数据加载 ==========
async function fetchStatistics() {
  try {
    // 后端统计只返回 dueCount/activeAndPausedCount/pausedCount；
    // 总错题数、已掌握数通过列表接口的 total 获取
    const [stats, completedResult] = await Promise.all([
      getErrorStatistics(),
      getErrorQuestions({ status: 'completed', page: 1, size: 1 })
    ]);
    statistics.value = stats || {};
    completedCount.value = completedResult?.total || 0;
  } catch (e) {
    console.warn('[Error] 获取统计失败:', e.message);
  }
}

async function fetchErrors() {
  loading.value = true;
  try {
    const params = {
      page: currentPage.value,
      size: pageSize.value
    };

    // 添加科目筛选（通过知识点ID间接筛选，这里简化处理）
    if (subjectIndex.value > 0) {
      const subject = appStore.subjectList[subjectIndex.value - 1];
      if (subject) {
        // TODO: 后端需要支持按 subjectId 筛选，目前只支持 knowledgePointId
        // params.knowledgePointId = subject.knowledgePointId;
      }
    }

    // 添加状态筛选
    if (statusIndex.value > 0) {
      params.status = statusIndex.value === 1 ? 'active' : 'completed';
    }

    const result = await getErrorQuestions(params);
    errorList.value = result?.records || [];
    totalRecords.value = result?.total || 0;
    // 无状态筛选时，total 即总错题数
    if (statusIndex.value === 0) {
      totalCount.value = result?.total || 0;
    }
  } catch (e) {
    console.warn('[Error] 获取错题列表失败:', e.message);
  } finally {
    loading.value = false;
  }
}

// ========== 事件 ==========
function onSubjectChange(e) {
  subjectIndex.value = e.detail.value;
  currentPage.value = 1; // 重置页码
  fetchErrors();
}

function onStatusChange(e) {
  statusIndex.value = e.detail.value;
  currentPage.value = 1; // 重置页码
  fetchErrors();
}

function prevPage() {
  if (currentPage.value > 1) {
    currentPage.value--;
    fetchErrors();
  }
}

function nextPage() {
  if (currentPage.value < totalPages.value) {
    currentPage.value++;
    fetchErrors();
  }
}

// 查看错题详情
function viewDetail(item) {
  uni.navigateTo({
    url: `/pages/error-detail/ErrorDetail?errorId=${item.errorQuestionId}`
  });
}

// ========== 工具函数 ==========
function goBack() {
  // 页面栈 > 1 时正常返回，否则兜底跳回「我的」Tab
  const pages = getCurrentPages();
  if (pages.length > 1) {
    uni.navigateBack();
  } else {
    uni.switchTab({ url: '/pages/profile/Profile' });
  }
}

function formatSourceLabel(type) {
  const map = { homework: '作业', assessment: '测评', exam: '考试' };
  return map[type] || type || '未知来源';
}

function truncateText(text, maxLength) {
  if (!text) return '';
  return text.length > maxLength ? text.substring(0, maxLength) + '...' : text;
}

function formatDate(dateStr) {
  if (!dateStr) return '';
  // 格式：2026-11-05T16:28:43 → 2026-11-05
  return dateStr.split('T')[0];
}
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
  justify-content: space-between;
  padding: 0 $spacing-lg;
}
/* 返回按钮（纯 CSS 箭头图标） */
.btn-back {
  background: none;
  border: none;
  padding: 16rpx 20rpx;
  line-height: 1;
}
.btn-back::after {
  border: none;
}
.back-icon {
  display: block;
  width: 22rpx;
  height: 22rpx;
  border-left: 4rpx solid $primary-color;
  border-bottom: 4rpx solid $primary-color;
  transform: rotate(45deg);
}
.nav-title {
  font-size: $font-lg;
  font-weight: bold;
  color: $text-primary;
  flex: 1;
  text-align: center;
}
.nav-placeholder {
  width: 80rpx;
}

/* 筛选栏 */
.filter-bar {
  display: flex;
  gap: $spacing-md;
  padding: $spacing-md $spacing-lg;
  background-color: $bg-white;
  margin-bottom: 2rpx;
}
.filter-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $spacing-sm $spacing-md;
  background-color: #f5f5f5;
  border-radius: $radius-md;
  font-size: $font-sm;
  color: $text-primary;
}
.filter-arrow {
  font-size: $font-xs;
  color: $text-secondary;
}

/* 统计栏 */
.stats-bar {
  display: flex;
  justify-content: space-around;
  padding: $spacing-md $spacing-lg;
  background-color: $bg-white;
  margin-bottom: 2rpx;
}
.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.stat-value {
  font-size: $font-xl;
  font-weight: bold;
  color: $primary-color;
}
.stat-label {
  font-size: $font-xs;
  color: $text-secondary;
  margin-top: 4rpx;
}

/* 错题列表 */
.error-list {
  padding: $spacing-md $spacing-lg;
}

/* 错题卡片 */
.error-card {
  background-color: $bg-white;
  border-radius: $radius-md;
  padding: $spacing-lg;
  margin-bottom: $spacing-md;
  box-shadow: $shadow-sm;
}
.card-header {
  display: flex;
  gap: $spacing-sm;
  margin-bottom: $spacing-md;
}
.subject-tag {
  background-color: #e3f2fd;
  color: #1976d2;
  font-size: $font-xs;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
}
.type-tag {
  background-color: #fff3e0;
  color: #f57c00;
  font-size: $font-xs;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
}
.stage-tag {
  font-size: $font-xs;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
}
.stage-1 { background-color: #ffebee; color: #c62828; }
.stage-2 { background-color: #fff3e0; color: #ef6c00; }
.stage-3 { background-color: #fff8e1; color: #f9a825; }
.stage-4 { background-color: #e8f5e9; color: #2e7d32; }
.stage-5 { background-color: #e0f2f1; color: #00695c; }

.card-content {
  margin-bottom: $spacing-md;
}
.question-text {
  font-size: $font-md;
  color: $text-primary;
  line-height: 1.6;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  padding-top: $spacing-sm;
  border-top: 1rpx solid $border-light;
}
.error-time, .review-count {
  font-size: $font-xs;
  color: $text-secondary;
}

/* 分页器 */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: $spacing-md;
  padding: $spacing-lg;
}
.btn-page {
  background-color: $primary-color;
  color: #fff;
  font-size: $font-sm;
  border-radius: $radius-md;
  padding: $spacing-sm $spacing-lg;
}
.btn-page[disabled] {
  opacity: 0.4;
}
.page-info {
  font-size: $font-sm;
  color: $text-secondary;
}
</style>
