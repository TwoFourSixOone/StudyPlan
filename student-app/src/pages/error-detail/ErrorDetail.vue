<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <button class="btn-back" @click="goBack" aria-label="返回">
          <view class="back-icon"></view>
        </button>
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
          <text class="label">来源：</text>
          <text class="value">{{ formatSourceLabel(errorDetail.sourceType) }}</text>
        </view>
        <view class="info-row">
          <text class="label">题型：</text>
          <text class="value">{{ formatTypeLabel(errorDetail.questionType) }}</text>
        </view>
        <view class="info-row">
          <text class="label">难度：</text>
          <text class="value">{{ difficultyLabel(errorDetail.questionDifficulty) }}</text>
        </view>
        <view class="info-row">
          <text class="label">知识点：</text>
          <text class="value">{{ errorDetail.knowledgePointName || '未分类' }}</text>
        </view>
        <view class="info-row">
          <text class="label">当前阶段：</text>
          <text class="value stage-badge" :class="'stage-' + errorDetail.currentStage">第{{ errorDetail.currentStage }}阶段</text>
        </view>
        <view class="info-row">
          <text class="label">首次答错：</text>
          <text class="value">{{ errorDetail.firstWrongDate || '—' }}</text>
        </view>
        <view class="info-row">
          <text class="label">最近答错：</text>
          <text class="value">{{ errorDetail.lastWrongDate || '—' }}</text>
        </view>
        <view class="info-row">
          <text class="label">下次复习：</text>
          <text class="value">{{ errorDetail.nextDueDate || '—' }}</text>
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
      <view class="answer-section" v-if="errorDetail.studentAnswer">
        <text class="section-title">我的答案</text>
        <text class="answer-text" :class="{ 'wrong': true }">{{ errorDetail.studentAnswer }}</text>
      </view>

      <!-- 正确答案 -->
      <view class="answer-section correct" v-if="errorDetail.correctAnswer">
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
import { ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import { useAuthGuard } from '@/utils/useAuthGuard';
import { getErrorQuestionDetail } from '@/api/error';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

// ========== 状态 ==========
const statusBarHeight = ref(0);
const errorDetail = ref(null);
const loading = ref(false);

// ========== 生命周期 ==========
onLoad((options) => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;

  // 从路由参数获取 errorId（onLoad 在小程序/H5 下都能可靠拿到参数）
  const errorId = options?.errorId;

  if (errorId) {
    fetchErrorDetail(errorId);
  } else {
    console.warn('[ErrorDetail] 缺少 errorId 参数, options:', options);
  }
});

// ========== 数据加载 ==========
async function fetchErrorDetail(errorId) {
  loading.value = true;
  try {
    // 注意：小程序编译下动态 import() 会被编译成字符串字面量，必须用静态导入
    errorDetail.value = await getErrorQuestionDetail(errorId);
  } catch (e) {
    // 接口报错（含错题不存在）时保持空态展示
    console.warn('[ErrorDetail] 获取错题详情失败:', e.message);
    errorDetail.value = null;
  } finally {
    loading.value = false;
  }
}

// ========== 工具函数 ==========
function goBack() {
  uni.navigateBack();
}

function formatSourceLabel(type) {
  const map = { homework: '作业', assessment: '测评', exam: '考试' };
  return map[type] || type || '未知来源';
}

function formatTypeLabel(type) {
  const map = { 
    choice: '选择题', 
    numeric: '填空题', 
    short: '解答题', 
    self: '主观题'
  };
  return map[type] || type || '—';
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
    let parsed = JSON.parse(optionsStr);
    // 兼容双重序列化：解析结果仍是字符串时再解析一次
    if (typeof parsed === 'string') {
      parsed = JSON.parse(parsed);
    }
    if (Array.isArray(parsed)) return parsed;
    if (typeof parsed === 'string') {
      return parsed.split('\n').filter(o => o.trim());
    }
    return [];
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
