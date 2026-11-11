<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <button class="btn-back" @click="goBack" aria-label="返回">
          <view class="back-icon"></view>
        </button>
        <text class="nav-title">历史试卷</text>
        <view class="nav-placeholder"></view>
      </view>
    </view>

    <!-- 列表 -->
    <view class="exam-list" v-if="paperList.length > 0">
      <view class="exam-card" v-for="item in paperList" :key="item.paperId" @click="goToDetail(item)">
        <view class="card-header">
          <text class="subject-name">{{ item.subjectName || '未知科目' }}</text>
          <text class="status-tag" :class="'status-' + item.status">{{ examStatusLabel(item.status) }}</text>
        </view>
        <view class="card-meta">
          <text class="meta-item">{{ item.examDate || '—' }}</text>
          <!-- 后端 ExamPaperListItemVO 直接给中文名 paperTypeName（约定 #21），缺失时才回退本地映射 -->
          <text class="type-tag">{{ item.paperTypeName || paperTypeLabel(item.paperType) }}</text>
        </view>
        <view class="card-footer">
          <!-- 字段严格对齐后端 ExamPaperListItemVO：questionCount / correctCount / judgedCount -->
          <text class="stat-text">
            共 {{ item.questionCount || 0 }} 题 · 答对 {{ item.correctCount || 0 }} 题 · 已判定 {{ item.judgedCount || 0 }} 题
          </text>
          <!-- 得分沿用 1 题 1 分制的 userScore/totalScore（后端 = correctCount/judgedCount） -->
          <text class="score-text" v-if="item.userScore != null && item.totalScore != null">
            {{ item.userScore }}/{{ item.totalScore }} 分
          </text>
          <text class="score-text score-text--muted" v-else>待判定</text>
        </view>
      </view>

      <!-- 触底加载提示 -->
      <view class="load-more">
        <text v-if="loadingMore">加载中...</text>
        <text v-else-if="finished" class="no-more">— 没有更多了 —</text>
      </view>
    </view>

    <!-- 加载态 -->
    <LoadingState :loading="loading" text="加载中..." />

    <!-- 失败态（可重试） -->
    <view class="fail-box" v-if="loadError && !loading">
      <EmptyState icon="error" text="加载失败，请检查网络后重试">
        <button class="btn-retry" @click="refresh">重新加载</button>
      </EmptyState>
    </view>

    <!-- 空态 -->
    <EmptyState v-else-if="!loading && !loadError && paperList.length === 0" text="暂无历史试卷">
      <button class="btn-upload-entry" @click="goToUpload">去上传试卷</button>
    </EmptyState>
  </view>
</template>

<script setup>
/**
 * 历史试卷列表页（design.md §10.1 / §10.3）
 *
 * 页面栈规则（必须遵守）：
 * - 层级：我的 → 历史试卷（本页）→ 试卷详情，最多 3 层；详情内切题不跳页。
 * - 进入详情：`navigateTo`，且**只传 paperId**；
 *   若页面栈中已存在同一个 paperId 的详情页，则 `navigateBack` 回去而不重复压栈（S8）。
 * - 返回：`navigateBack`；本页的「去上传试卷」用 `navigateTo`（上传完成后上传页会用
 *   `redirectTo` 替换自己，栈里不会残留已提交的上传页）。
 */
import { ref, computed } from 'vue';
import { onLoad, onShow, onReachBottom, onPullDownRefresh } from '@dcloudio/uni-app';
import { useAuthGuard } from '@/utils/useAuthGuard';
import { getPapers, examStatusLabel, paperTypeLabel } from '@/api/exam';
import { showError } from '@/utils/message';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

const DETAIL_ROUTE = 'pages/exam-detail/ExamDetail';

// ========== 状态 ==========
const statusBarHeight = ref(0);
const paperList = ref([]);
const loading = ref(false);
const loadingMore = ref(false);
const loadError = ref(false);
const finished = ref(false);
const currentPage = ref(1);
const pageSize = 10;
const total = ref(0);

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));

// ========== 生命周期 ==========
onLoad(() => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;
});

// onShow 刷新：从详情/上传页返回时能看到最新状态（design §10.3 / tasks F2）
onShow(() => {
  refresh();
});

// 触底加载下一页（与 RecordList 一致的"滚动加载"方案，二选一固定）
onReachBottom(() => {
  loadMore();
});

// 下拉刷新（失败后可直接下拉重试）
onPullDownRefresh(async () => {
  await refresh();
  uni.stopPullDownRefresh();
});

// ========== 数据加载 ==========
async function refresh() {
  loading.value = true;
  loadError.value = false;
  currentPage.value = 1;
  finished.value = false;
  try {
    const result = await getPapers({ page: 1, size: pageSize });
    paperList.value = result?.records || [];
    total.value = Number(result?.total) || 0;
    finished.value = paperList.value.length >= total.value;
  } catch (e) {
    console.warn('[ExamList] 获取历史试卷失败:', e.message);
    paperList.value = [];
    total.value = 0;
    loadError.value = true;
  } finally {
    loading.value = false;
  }
}

async function loadMore() {
  if (loading.value || loadingMore.value || finished.value || loadError.value) return;
  if (currentPage.value >= totalPages.value) {
    finished.value = true;
    return;
  }
  loadingMore.value = true;
  try {
    const nextPage = currentPage.value + 1;
    const result = await getPapers({ page: nextPage, size: pageSize });
    const records = result?.records || [];
    paperList.value = [...paperList.value, ...records];
    total.value = Number(result?.total) || total.value;
    currentPage.value = nextPage;
    finished.value = paperList.value.length >= total.value || records.length === 0;
  } catch (e) {
    console.warn('[ExamList] 加载更多失败:', e.message);
    showError('加载更多失败，请稍后重试');
  } finally {
    loadingMore.value = false;
  }
}

// ========== 跳转 ==========
function goToDetail(item) {
  if (!item || item.paperId == null) return;
  const paperId = String(item.paperId);
  // 防重复入栈（S8）：栈顶已是同一 paperId 的详情 → 返回，而不是再压一层
  const pages = getCurrentPages();
  const top = pages[pages.length - 1];
  const sameDetail =
    top &&
    top.route === DETAIL_ROUTE &&
    String((top.options && top.options.paperId) || '') === paperId;
  if (sameDetail) {
    uni.navigateBack();
    return;
  }
  uni.navigateTo({
    url: `/pages/exam-detail/ExamDetail?paperId=${paperId}`,
    fail: () => showError('跳转失败，请重新编译小程序后重试')
  });
}

function goToUpload() {
  uni.navigateTo({
    url: '/pages/exam-upload/ExamUpload',
    fail: () => showError('跳转失败，请重新编译小程序后重试')
  });
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
  padding-bottom: $spacing-xl;
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

/* 列表 */
.exam-list {
  padding: $spacing-md $spacing-lg 0;
}
.exam-card {
  background-color: $bg-white;
  border-radius: $radius-md;
  padding: $spacing-lg;
  margin-bottom: $spacing-md;
  box-shadow: $shadow-sm;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8rpx;
}
.subject-name {
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status-tag {
  font-size: $font-xs;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
  margin-left: $spacing-sm;
  flex-shrink: 0;
}
.status-completed { color: $success-color; background-color: #f0f9eb; }
.status-failed { color: $danger-color; background-color: #fef0f0; }
.status-processing,
.status-pending { color: $primary-color; background-color: $primary-light; }
.status-uploading { color: $text-secondary; background-color: #f5f5f5; }

.card-meta {
  display: flex;
  align-items: center;
  gap: $spacing-md;
  margin-bottom: $spacing-sm;
}
.meta-item {
  font-size: $font-sm;
  color: $text-secondary;
}
.type-tag {
  font-size: $font-xs;
  color: $primary-color;
  background-color: $primary-light;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
}
.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.stat-text {
  font-size: $font-sm;
  color: $text-secondary;
}
.score-text {
  font-size: $font-sm;
  color: $warning-color;
  font-weight: 500;
}
.score-text--muted {
  color: $text-secondary;
  font-weight: normal;
}

/* 触底提示 */
.load-more {
  display: flex;
  justify-content: center;
  padding: $spacing-lg 0;
  font-size: $font-sm;
  color: $text-secondary;
}
.no-more {
  opacity: 0.6;
}

/* 失败态 / 空态按钮 */
.btn-retry {
  margin-top: $spacing-lg;
  background-color: $primary-color;
  color: #fff;
  font-size: $font-sm;
  border-radius: $radius-md;
  padding: 0 $spacing-xl;
}
.btn-upload-entry {
  margin-top: $spacing-lg;
  background-color: $primary-color;
  color: #fff;
  font-size: $font-sm;
  border-radius: $radius-md;
  padding: 0 $spacing-xl;
}
</style>
