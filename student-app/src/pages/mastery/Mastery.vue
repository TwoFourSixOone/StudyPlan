<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <text class="nav-title">掌握度看板</text>
      </view>
    </view>

    <!-- 掌握度列表（按学科分组） -->
    <view class="subject-groups" v-if="groupedMasteryList.length > 0">
      <!-- 遍历每个学科 -->
      <view
        class="subject-group"
        v-for="(group, groupIndex) in groupedMasteryList"
        :key="group.subject"
      >
        <!-- 学科标题 -->
        <view class="subject-header" @click="toggleGroup(groupIndex)">
          <text class="subject-name">{{ group.subject }}</text>
          <view class="header-right">
            <text class="subject-count">{{ group.items.length }} 个知识点</text>
            <text class="collapse-icon">{{ collapsedGroups.has(groupIndex) ? '▶' : '▼' }}</text>
          </view>
        </view>

        <!-- 该学科的知识点列表（根据收缩状态显示/隐藏） -->
        <view v-if="!collapsedGroups.has(groupIndex)">
          <view
            class="mastery-item"
            v-for="(item, itemIndex) in group.items"
            :key="item.knowledgePointId"
          >
          <!-- 知识点摘要 -->
          <view class="item-summary" @click="toggleHistory(item, groupIndex, itemIndex)">
            <view class="item-info">
              <text class="kp-name">{{ item.knowledgePointName }}</text>
            </view>
            <view class="mastery-bar-wrapper">
              <view
                class="mastery-bar"
                :class="barColorClass(item.masteryScore)"
                :style="{ width: barWidth(item.masteryScore) }"
              ></view>
            </view>
            <view class="item-meta">
              <text class="mastery-score">{{ percentLabel(item.masteryScore) }}</text>
              <text class="confidence">置信度 {{ percentLabel(item.confidence) }}</text>
              <text class="direction" v-if="item.lastChangeDirection">
                {{ directionIcon(item.lastChangeDirection) }}
              </text>
            </view>
          </view>

          <!-- 展开的变更历史 -->
          <view class="history-panel" v-if="expandedGroupIndex === groupIndex && expandedItemIndex === itemIndex">
            <view class="history-header">
              <text class="history-title">变更历史</text>
              <text class="history-close" @click="closeHistory">收起 ▲</text>
            </view>

            <view class="history-list" v-if="historyMap[item.knowledgePointId]?.length > 0">
              <view
                class="history-item"
                v-for="log in historyMap[item.knowledgePointId]"
                :key="log.logId"
              >
                <view class="info-row">
                  <text class="log-type">{{ changeTypeLabel(log.changeType) }}</text>
                  <text class="log-delta" :class="log.delta >= 0 ? 'delta-up' : 'delta-down'">
                    {{ log.delta >= 0 ? '+' : '' }}{{ (log.delta * 100).toFixed(1) }}%
                  </text>
                </view>
                <view class="info-row">
                  <text class="log-values">
                    {{ percentLabel(log.masteryBefore) }} → {{ percentLabel(log.masteryAfter) }}
                  </text>
                  <text class="log-time">{{ formatLogTime(log.createdAt) }}</text>
                </view>
              </view>
            </view>

            <EmptyState v-else-if="!historyLoadingMap[item.knowledgePointId]" text="暂无变更记录" />

            <LoadingState :loading="!!historyLoadingMap[item.knowledgePointId]" text="加载历史..." />

            <!-- 上拉加载更多 -->
            <view class="load-more" v-if="canLoadMore(item.knowledgePointId)" @click="loadMoreHistory(item.knowledgePointId)">
              <text class="load-more-text">加载更多</text>
            </view>
          </view>
          </view>
        </view>
      </view>
    </view>

    <!-- 空状态 -->
    <EmptyState
      v-if="!loading && masteryList.length === 0"
      text="暂无掌握度数据，请先完成作业或评测"
    />

    <!-- 加载中 -->
    <LoadingState :loading="loading" text="加载中..." />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { useAppStore } from '@/stores/app';
import { useAuthGuard } from '@/utils/useAuthGuard.js';
import { getDashboard, getChangeHistory } from '@/api/mastery.js';
import { formatDate } from '@/utils/date.js';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

const appStore = useAppStore();

// ========== 状态 ==========
const statusBarHeight = ref(0);
const masteryList = ref([]);
const loading = ref(false);
const expandedGroupIndex = ref(-1);   // 当前展开的学科组索引
const expandedItemIndex = ref(-1);     // 当前展开的知识点索引
const historyMap = ref({});        // { knowledgePointId: [log1, log2, ...] }
const historyLoadingMap = ref({}); // { knowledgePointId: boolean }
const historyPageMap = ref({});    // { knowledgePointId: currentPage }
const historyTotalMap = ref({});   // { knowledgePointId: totalRecords }
const collapsedGroups = ref(new Set()); // 已收缩的学科组索引集合

// ========== 计算属性 ==========
// 按学科分组展示
const groupedMasteryList = computed(() => {
  if (!masteryList.value || masteryList.value.length === 0) {
    return [];
  }

  // 按 subject 字段分组
  const groups = {};
  masteryList.value.forEach(item => {
    const subject = item.subject || '未分类';
    if (!groups[subject]) {
      groups[subject] = [];
    }
    groups[subject].push(item);
  });

  // 转换为数组格式
  return Object.keys(groups)
    .sort() // 按学科名称排序
    .map(subject => ({
      subject,
      items: groups[subject]
    }));
});

// ========== 生命周期 ==========
onShow(() => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;

  console.log('[Mastery] onShow - 自动加载所有学科的掌握度数据');

  // 每次页面显示时重新拉取数据（确保从首页提交作业后切过来能看到最新）
  fetchDashboard();
});

// ========== 数据加载 ==========
async function fetchDashboard() {
  loading.value = true;
  // 展开状态重置
  expandedGroupIndex.value = -1;
  expandedItemIndex.value = -1;
  historyMap.value = {};
  historyPageMap.value = {};
  historyTotalMap.value = {};
  
  try {
    // 不传 subject 参数，后端返回所有学科的数据
    console.log('[Mastery] 请求看板数据, subject: undefined（所有学科）');
    
    const result = await getDashboard(undefined);
    console.log('[Mastery] 看板数据:', result);
    masteryList.value = result || [];
    
    // 默认收缩所有学科组（将索引添加到 collapsedGroups）
    if (result && result.length > 0) {
      const grouped = Object.keys(
        result.reduce((groups, item) => {
          const subject = item.subject || '未分类';
          if (!groups[subject]) groups[subject] = [];
          groups[subject].push(item);
          return groups;
        }, {})
      );
      // 将所有学科组索引标记为已收缩
      grouped.forEach((_, index) => {
        collapsedGroups.value.add(index);
      });
    }
  } catch (e) {
    console.error('[Mastery] 获取看板数据失败:', e.message);
    masteryList.value = [];
    uni.showToast({ title: '加载失败: ' + e.message, icon: 'none' });
  } finally {
    loading.value = false;
  }
}

// ========== 展开/收起变更历史 ==========
async function toggleHistory(item, groupIndex, itemIndex) {
  if (expandedGroupIndex.value === groupIndex && expandedItemIndex.value === itemIndex) {
    // 收起
    closeHistory();
    return;
  }

  expandedGroupIndex.value = groupIndex;
  expandedItemIndex.value = itemIndex;
  const kpId = item.knowledgePointId;

  // 如果还没加载过，则首次加载
  if (!historyMap.value[kpId]) {
    await loadHistory(kpId, 1);
  }
}

function closeHistory() {
  expandedGroupIndex.value = -1;
  expandedItemIndex.value = -1;
}

async function loadHistory(knowledgePointId, page = 1) {
  historyLoadingMap.value[knowledgePointId] = true;
  try {
    const result = await getChangeHistory(knowledgePointId, page, 10);
    if (page === 1) {
      historyMap.value[knowledgePointId] = result?.records || [];
    } else {
      // 追加
      const existing = historyMap.value[knowledgePointId] || [];
      historyMap.value[knowledgePointId] = [...existing, ...(result?.records || [])];
    }
    historyPageMap.value[knowledgePointId] = page;
    historyTotalMap.value[knowledgePointId] = result?.total || 0;
  } catch (e) {
    console.warn('[Mastery] 获取变更历史失败:', e.message);
    if (page === 1) {
      historyMap.value[knowledgePointId] = [];
    }
  } finally {
    historyLoadingMap.value[knowledgePointId] = false;
  }
}

function canLoadMore(knowledgePointId) {
  const loaded = (historyMap.value[knowledgePointId] || []).length;
  const total = historyTotalMap.value[knowledgePointId] || 0;
  return loaded < total;
}

function loadMoreHistory(knowledgePointId) {
  const currentPage = historyPageMap.value[knowledgePointId] || 1;
  loadHistory(knowledgePointId, currentPage + 1);
}

// ========== 学科组收缩/展开 ==========
function toggleGroup(groupIndex) {
  if (collapsedGroups.value.has(groupIndex)) {
    // 展开
    collapsedGroups.value.delete(groupIndex);
  } else {
    // 收缩
    collapsedGroups.value.add(groupIndex);
    
    // 如果当前展开的知识点属于这个学科组，关闭它
    if (expandedGroupIndex.value === groupIndex) {
      closeHistory();
    }
  }
}

// ========== 工具函数 ==========
function barColorClass(score) {
  if (score == null) return '';
  if (score < 0.4) return 'bar-red';
  if (score < 0.7) return 'bar-yellow';
  return 'bar-green';
}

function barWidth(score) {
  if (score == null) return '0%';
  return Math.round(score * 100) + '%';
}

function percentLabel(score) {
  if (score == null) return '--';
  return Math.round(score * 100) + '%';
}

function directionIcon(dir) {
  const map = { up: '📈', down: '📉', stable: '➡️' };
  return map[dir] || '';
}

function changeTypeLabel(type) {
  const map = { homework: '作业', assessment: '评测', exam: '考试', agent: 'Agent' };
  return map[type] || type;
}

function formatLogTime(dt) {
  return formatDate(dt, 'YYYY-MM-DD HH:mm');
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
  justify-content: center;
}
.nav-title {
  font-size: $font-lg;
  font-weight: bold;
  color: $text-primary;
}

/* 掌握度列表 */
.mastery-list {
  padding: $spacing-md $spacing-lg;
}

/* 学科分组容器 */
.subject-groups {
  padding: $spacing-md $spacing-lg;
}

/* 学科组 */
.subject-group {
  margin-bottom: $spacing-xl;
}

/* 学科标题 */
.subject-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: $spacing-md 0;
  border-bottom: 2rpx solid #e5e5e5;
  cursor: pointer;
}

.header-right {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}

.subject-name {
  font-size: $font-lg;
  font-weight: bold;
  color: $text-primary;
}

.subject-count {
  font-size: $font-sm;
  color: $text-secondary;
}

.collapse-icon {
  font-size: $font-xs;
  color: $text-secondary;
  transition: transform 0.3s ease;
}
.mastery-item {
  background-color: $bg-white;
  border-radius: $radius-md;
  margin-bottom: $spacing-md;
  box-shadow: $shadow-sm;
  overflow: hidden;
}
.item-summary {
  padding: $spacing-lg;
}
.item-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $spacing-sm;
}
.kp-name {
  font-size: $font-md;
  color: $text-primary;
  font-weight: 500;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kp-subject {
  font-size: $font-xs;
  color: $text-secondary;
  background-color: #f5f5f5;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
  margin-left: $spacing-sm;
  flex-shrink: 0;
}

/* 进度条 */
.mastery-bar-wrapper {
  height: 16rpx;
  background-color: #f0f0f0;
  border-radius: 8rpx;
  overflow: hidden;
  margin-bottom: $spacing-sm;
}
.mastery-bar {
  height: 100%;
  border-radius: 8rpx;
  transition: width 0.3s ease;
}
.bar-red { background-color: $danger-color; }
.bar-yellow { background-color: $warning-color; }
.bar-green { background-color: $success-color; }

/* 元信息 */
.item-meta {
  display: flex;
  align-items: center;
  gap: $spacing-md;
}
.mastery-score {
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
}
.confidence {
  font-size: $font-sm;
  color: $text-secondary;
}
.direction {
  font-size: $font-sm;
  margin-left: auto;
}

/* 变更历史面板 */
.history-panel {
  border-top: 1rpx solid $border-light;
  padding: $spacing-lg;
  background-color: #fafafa;
}
.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $spacing-md;
}
.history-title {
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
}
.history-close {
  font-size: $font-sm;
  color: $primary-color;
}

/* 历史列表 */
.history-item {
  padding: $spacing-md 0;
  border-bottom: 1rpx solid $border-light;
}
.history-item:last-child {
  border-bottom: none;
}
.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4rpx;
}
.log-type {
  font-size: $font-sm;
  color: $text-regular;
}
.log-delta {
  font-size: $font-sm;
  font-weight: 500;
}
.delta-up { color: $success-color; }
.delta-down { color: $danger-color; }
.log-values {
  font-size: $font-sm;
  color: $text-secondary;
}
.log-time {
  font-size: $font-xs;
  color: $text-secondary;
}

/* 加载更多 */
.load-more {
  padding: $spacing-md 0;
  text-align: center;
}
.load-more-text {
  font-size: $font-sm;
  color: $primary-color;
}
</style>
