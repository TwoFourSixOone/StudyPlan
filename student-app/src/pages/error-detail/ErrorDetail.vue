<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <button class="btn-back" @click="goBack">← 返回</button>
        <text class="nav-title">错题详情</text>
        <view class="nav-placeholder"></view>
      </view>
    </view>

    <!-- 加载中 -->
    <LoadingState v-if="loading" text="加载中..." />

    <!-- 错题内容 -->
    <view v-else-if="errorDetail" class="detail-content">
      <!-- 基本信息 -->
      <view class="info-section">
        <view class="info-row">
          <text class="label">科目：</text>
          <text class="value">{{ errorDetail.subjectName }}</text>
        </view>
        <view class="info-row">
          <text class="label">题型：</text>
          <text class="value">{{ formatTypeLabel(errorDetail.taskType) }}</text>
        </view>
        <view class="info-row">
          <text class="label">难度：</text>
          <text class="value">{{ difficultyLabel(errorDetail.difficulty) }}</text>
        </view>
        <view class="info-row">
          <text class="label">知识点：</text>
          <text class="value">{{ errorDetail.knowledgePointName || '未分类' }}</text>
        </view>
        <view class="info-row">
          <text class="label">当前阶段：</text>
          <text class="value stage-badge" :class="'stage-' + errorDetail.stage">第{{ errorDetail.stage }}阶段</text>
        </view>
        <view class="info-row">
          <text class="label">错误时间：</text>
          <text class="value">{{ formatDate(errorDetail.createTime) }}</text>
        </view>
        <view class="info-row">
          <text class="label">重做次数：</text>
          <text class="value">{{ errorDetail.reviewCount }} 次</text>
        </view>
      </view>

      <!-- 题目内容 -->
      <view class="question-section">
        <text class="section-title">题目内容</text>
        <text class="question-text">{{ errorDetail.questionContent }}</text>
        
        <!-- 选项（如果是选择题） -->
        <view class="options-list" v-if="errorDetail.options">
          <view 
            class="option-item" 
            v-for="(opt, idx) in parseOptions(errorDetail.options)" 
            :key="idx"
          >
            <text class="option-label">{{ choiceLabel(idx) }}.</text>
            <text class="option-text">{{ opt }}</text>
          </view>
        </view>
      </view>

      <!-- 学生答案 -->
      <view class="answer-section">
        <text class="section-title">我的答案</text>
        <text class="answer-text" :class="{ 'wrong': true }">{{ errorDetail.studentAnswer || '未作答' }}</text>
      </view>

      <!-- 正确答案 -->
      <view class="answer-section correct">
        <text class="section-title">正确答案</text>
        <text class="answer-text" :class="{ 'correct': true }">{{ errorDetail.correctAnswer }}</text>
      </view>

      <!-- 解析 -->
      <view class="analysis-section" v-if="errorDetail.analysis">
        <text class="section-title">解析</text>
        <text class="analysis-text">{{ errorDetail.analysis }}</text>
      </view>

      <!-- 艾宾浩斯周期说明 -->
      <view class="hint-section">
        <text class="hint-title">💡 复习提示</text>
        <text class="hint-text">
          根据艾宾浩斯遗忘曲线，该题将在以下天数后再次出现：
          1天、3天、7天、15天、30天。每次答对推进下一阶段，答错重置当前阶段。
        </text>
      </view>
    </view>

    <!-- 空状态 -->
    <EmptyState v-else text="错题不存在" />
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useAuthGuard } from '@/utils/useAuthGuard.js';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

// ========== 状态 ==========
const statusBarHeight = ref(0);
const errorDetail = ref(null);
const loading = ref(false);

// ========== 生命周期 ==========
onMounted(() => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;

  // 从路由参数获取 errorId
  const pages = getCurrentPages();
  const currentPage = pages[pages.length - 1];
  const options = currentPage.options;
  const errorId = options.errorId;

  if (errorId) {
    fetchErrorDetail(errorId);
  } else {
    console.warn('[ErrorDetail] 缺少 errorId 参数');
  }
});

// ========== 数据加载 ==========
async function fetchErrorDetail(errorId) {
  loading.value = true;
  try {
    // TODO: 后端需要提供单个错题详情的接口
    // 目前先用列表接口模拟，实际应该用 GET /api/error-questions/{errorId}
    const { getErrorQuestions } = await import('@/api/error.js');
    const result = await getErrorQuestions({ page: 1, size: 100 });
    const errors = result?.records || [];
    errorDetail.value = errors.find(e => e.errorId === parseInt(errorId)) || null;
    
    if (!errorDetail.value) {
      console.warn('[ErrorDetail] 未找到错题:', errorId);
    }
  } catch (e) {
    console.warn('[ErrorDetail] 获取错题详情失败:', e.message);
  } finally {
    loading.value = false;
  }
}

// ========== 工具函数 ==========
function goBack() {
  uni.navigateBack();
}

function formatTypeLabel(type) {
  const map = { 
    choice: '选择题', 
    numeric: '填空题', 
    short: '解答题', 
    self: '主观题',
    redo: '重做题'
  };
  return map[type] || type;
}

function difficultyLabel(diff) {
  const map = { basic: '基础', standard: '标准', challenge: '挑战' };
  return map[diff] || diff;
}

function choiceLabel(idx) {
  return String.fromCharCode(65 + idx);
}

function parseOptions(optionsStr) {
  if (!optionsStr) return [];
  try {
    // 假设 options 是 JSON 字符串数组
    return JSON.parse(optionsStr);
  } catch (e) {
    // 如果不是 JSON，尝试按换行符分割
    return optionsStr.split('\n').filter(o => o.trim());
  }
}

function formatDate(dateStr) {
  if (!dateStr) return '';
  // 格式：2026-11-05T16:28:43 → 2026-11-05 16:28
  const parts = dateStr.split('T');
  if (parts.length === 2) {
    return `${parts[0]} ${parts[1].substring(0, 5)}`;
  }
  return dateStr;
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background-color: $bg-color;
  display: flex;
  flex-direction: column;
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
  font-size: $font-md;
  color: $primary-color;
  background: none;
  border: none;
  padding: 0;
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

/* 详情内容 */
.detail-content {
  padding: $spacing-lg;
}

/* 基本信息 */
.info-section {
  background-color: $bg-white;
  border-radius: $radius-md;
  padding: $spacing-lg;
  margin-bottom: $spacing-md;
}
.info-row {
  display: flex;
  padding: $spacing-sm 0;
  border-bottom: 1rpx solid $border-light;
}
.info-row:last-child {
  border-bottom: none;
}
.label {
  font-size: $font-sm;
  color: $text-secondary;
  min-width: 160rpx;
}
.value {
  font-size: $font-sm;
  color: $text-primary;
  flex: 1;
}
.stage-badge {
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
  font-size: $font-xs;
}
.stage-1 { background-color: #ffebee; color: #c62828; }
.stage-2 { background-color: #fff3e0; color: #ef6c00; }
.stage-3 { background-color: #fff8e1; color: #f9a825; }
.stage-4 { background-color: #e8f5e9; color: #2e7d32; }
.stage-5 { background-color: #e0f2f1; color: #00695c; }

/* 题目区域 */
.question-section, .answer-section, .analysis-section {
  background-color: $bg-white;
  border-radius: $radius-md;
  padding: $spacing-lg;
  margin-bottom: $spacing-md;
}
.section-title {
  display: block;
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
  margin-bottom: $spacing-md;
}
.question-text, .answer-text, .analysis-text {
  font-size: $font-md;
  color: $text-primary;
  line-height: 1.6;
}
.answer-text.wrong {
  color: #f44336;
}
.answer-text.correct {
  color: #4caf50;
}

/* 选项列表 */
.options-list {
  margin-top: $spacing-md;
}
.option-item {
  display: flex;
  padding: $spacing-sm 0;
  border-bottom: 1rpx solid $border-light;
}
.option-item:last-child {
  border-bottom: none;
}
.option-label {
  font-size: $font-md;
  color: $text-primary;
  font-weight: bold;
  margin-right: $spacing-sm;
}
.option-text {
  font-size: $font-md;
  color: $text-primary;
  flex: 1;
}

/* 提示区域 */
.hint-section {
  background-color: #fff9c4;
  border-radius: $radius-md;
  padding: $spacing-lg;
  margin-bottom: $spacing-md;
}
.hint-title {
  display: block;
  font-size: $font-md;
  font-weight: bold;
  color: #f57f17;
  margin-bottom: $spacing-sm;
}
.hint-text {
  font-size: $font-sm;
  color: #f9a825;
  line-height: 1.6;
}
</style>
