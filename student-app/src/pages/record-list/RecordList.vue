<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <button class="btn-back" @click="goBack" aria-label="返回">
          <view class="back-icon"></view>
        </button>
        <text class="nav-title">学习记录</text>
        <view class="nav-placeholder"></view>
      </view>
    </view>

    <!-- 筛选栏 -->
    <view class="filter-bar">
      <picker :value="typeIndex" :range="recordTypes" :range-name="recordTypeNames" @change="onTypeChange">
        <view class="filter-item">
          <text>{{ recordTypeNames[typeIndex] || '全部类型' }}</text>
          <text class="filter-arrow">▼</text>
        </view>
      </picker>
    </view>

    <!-- 记录列表 -->
    <view class="record-list" v-if="recordList.length > 0">
      <view class="record-item" v-for="r in recordList" :key="r.recordId" @click="viewRecordDetail(r)">
        <view class="record-info">
          <text class="record-title">{{ r.title }}</text>
          <text class="record-type-tag">{{ recordTypeLabel(r.recordType) }}</text>
        </view>
        <view class="record-meta">
          <text class="record-subject">{{ r.subjectName }}</text>
          <text class="record-score" v-if="r.score != null">{{ r.score }}分</text>
          <text class="record-time">{{ formatRecordTime(r.createTime) }}</text>
        </view>
      </view>
      <!-- 底部加载提示 -->
      <view class="load-more">
        <text v-if="loadingMore">加载中...</text>
        <text v-else-if="finished" class="no-more">— 没有更多了 —</text>
      </view>
    </view>

    <!-- 空状态 -->
    <EmptyState v-else-if="!loading" text="暂无学习记录" />

    <!-- 首屏加载中 -->
    <LoadingState :loading="loading" text="加载中..." />

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
            <text class="detail-value">{{ recordTypeLabel(recordDetail.recordType) }}</text>
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
</template>

<script setup>
import { ref, computed } from 'vue';
import { onLoad, onReachBottom } from '@dcloudio/uni-app';
import { useAuthGuard } from '@/utils/useAuthGuard';
import { getRecordList, getRecordDetail } from '@/api/record';
import { showError } from '@/utils/message';
import { formatDate } from '@/utils/date';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

// ========== 状态 ==========
const statusBarHeight = ref(0);
const recordList = ref([]);
const recordDetail = ref(null);
const loading = ref(false);
const loadingMore = ref(false);
const finished = ref(false); // 是否已加载完全部数据
const currentPage = ref(1);
const pageSize = 10;
const total = ref(0);

// 筛选条件
const typeIndex = ref(0); // 0 表示全部类型
const recordTypes = ['', 'homework', 'assessment', 'exam'];
const recordTypeNames = ['全部类型', '作业', '评测', '考试'];

const totalPages = computed(() => Math.ceil(total.value / pageSize));

// ========== 生命周期 ==========
onLoad(() => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;
  fetchRecords();
});

// ========== 数据加载 ==========
async function fetchRecords() {
  loading.value = true;
  try {
    const params = { page: currentPage.value, size: pageSize };
    const typeVal = recordTypes[typeIndex.value];
    if (typeVal) params.recordType = typeVal;
    const result = await getRecordList(params);
    recordList.value = result?.records || [];
    total.value = result?.total || 0;
    finished.value = recordList.value.length >= total.value;
  } catch (e) {
    console.warn('[RecordList] 获取记录失败:', e.message);
    recordList.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

// 滚动到底部时加载下一页（追加合并）
async function loadMore() {
  if (loadingMore.value || finished.value) return;
  if (currentPage.value >= totalPages.value) {
    finished.value = true;
    return;
  }
  loadingMore.value = true;
  try {
    const nextPage = currentPage.value + 1;
    const params = { page: nextPage, size: pageSize };
    const typeVal = recordTypes[typeIndex.value];
    if (typeVal) params.recordType = typeVal;
    const result = await getRecordList(params);
    const records = result?.records || [];
    recordList.value = [...recordList.value, ...records];
    currentPage.value = nextPage;
    finished.value = recordList.value.length >= total.value;
  } catch (e) {
    console.warn('[RecordList] 加载更多失败:', e.message);
  } finally {
    loadingMore.value = false;
  }
}

// 页面级滚动触底钩子
onReachBottom(() => {
  loadMore();
});

// ========== 事件 ==========
function onTypeChange(e) {
  typeIndex.value = e.detail.value;
  currentPage.value = 1;
  finished.value = false;
  fetchRecords();
}

async function viewRecordDetail(record) {
  try {
    recordDetail.value = await getRecordDetail(record.recordType, record.recordId);
  } catch (e) {
    showError('获取详情失败');
    console.warn('[RecordList] 获取记录详情失败:', e.message);
  }
}

// 记录类型 code → 中文标签
function recordTypeLabel(type) {
  const map = { homework: '作业', assessment: '评测', exam: '考试' };
  return map[type] || type;
}

function formatRecordTime(dt) {
  return formatDate(dt, 'YYYY-MM-DD HH:mm');
}

function goBack() {
  uni.navigateBack({
    fail: () => uni.switchTab({ url: '/pages/profile/Profile' })
  });
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background-color: $bg-color;
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
  padding: $spacing-md $spacing-lg;
  background-color: $bg-white;
  margin-bottom: 2rpx;
  position: sticky;
  top: 88rpx;
  z-index: 98;
}
.filter-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $spacing-sm $spacing-md;
  background-color: #f5f5f5;
  border-radius: $radius-md;
  font-size: $font-sm;
  color: $text-primary;
  min-width: 220rpx;
}
.filter-arrow {
  font-size: $font-xs;
  color: $text-secondary;
}

/* 记录列表 */
.record-list {
  padding: $spacing-md $spacing-lg;
}
.record-item {
  background-color: $bg-white;
  border-radius: $radius-md;
  padding: $spacing-lg;
  margin-bottom: $spacing-md;
  box-shadow: $shadow-sm;
}
.record-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8rpx;
}
.record-title {
  font-size: $font-md;
  color: $text-primary;
  font-weight: 500;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.record-type-tag {
  font-size: $font-xs;
  color: $primary-color;
  background-color: $primary-light;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
  margin-left: $spacing-sm;
  flex-shrink: 0;
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

/* 底部加载提示 */
.load-more {
  display: flex;
  justify-content: center;
  padding: $spacing-lg 0;
  font-size: $font-sm;
  color: $text-secondary;
}
.no-more {
  color: $text-secondary;
  opacity: 0.6;
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
</style>
