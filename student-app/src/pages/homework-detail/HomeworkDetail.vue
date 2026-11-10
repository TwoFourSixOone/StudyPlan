<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <button class="btn-back" @click="goBack" aria-label="返回">
          <view class="back-icon"></view>
        </button>
        <text class="nav-title">{{ subjectName }}</text>
        <text class="nav-progress">{{ currentIndex + 1 }}/{{ totalQuestions }}</text>
      </view>
    </view>

    <!-- 题目内容区域 -->
    <view class="question-area" v-if="currentQuestion">
      <!-- 题型和难度标签 -->
      <view class="question-tags">
        <text class="type-tag" :class="'tag-' + currentQuestion.questionFormatType">
          {{ formatTypeLabel(currentQuestion.questionFormatType) }}
        </text>
        <text class="diff-tag" :class="'diff-' + currentQuestion.difficulty">
          {{ difficultyLabel(currentQuestion.difficulty) }}
        </text>
      </view>

      <!-- 题目内容 -->
      <text class="question-content">{{ currentQuestion.questionContent }}</text>

      <!-- 选择题选项 -->
      <view class="choices" v-if="currentQuestion.questionFormatType === 'choice' && currentQuestion.choices">
        <view
          class="choice-item"
          v-for="(choice, cIdx) in currentQuestion.choices"
          :key="cIdx"
          :class="{
            'choice-disabled': currentQuestion.studentAnswer != null,
            'choice-selected': (currentQuestion.studentAnswer || answer) === choiceLabel(cIdx),
            'choice-correct': currentQuestion.isCorrect === true && (currentQuestion.studentAnswer || answer) === choiceLabel(cIdx),
            'choice-wrong': currentQuestion.isCorrect === false && (currentQuestion.studentAnswer || answer) === choiceLabel(cIdx)
          }"
          @click="selectChoice(choiceLabel(cIdx))"
        >
          <text class="choice-label">{{ choiceLabel(cIdx) }}</text>
          <text class="choice-text">{{ choice }}</text>
        </view>
      </view>

      <!-- 填空题输入 -->
      <view class="input-area" v-else-if="currentQuestion.questionFormatType === 'numeric' || currentQuestion.questionFormatType === 'short'">
        <input
          class="answer-input"
          type="text"
          :value="currentQuestion.studentAnswer || answer || ''"
          :disabled="currentQuestion.studentAnswer != null"
          @input="answer = $event.detail.value"
          placeholder="输入答案"
        />
      </view>

      <!-- 主观题输入 -->
      <view class="input-area" v-else-if="currentQuestion.questionFormatType === 'self'">
        <textarea
          class="answer-textarea"
          :value="currentQuestion.studentAnswer || answer || ''"
          :disabled="currentQuestion.studentAnswer != null"
          @input="answer = $event.detail.value"
          placeholder="请输入你的答案"
          :maxlength="500"
        />
      </view>

      <!-- 已提交答案展示 -->
      <view class="answer-row" v-if="currentQuestion.studentAnswer">
        <text class="answer-label">我的答案：</text>
        <text class="answer-value">{{ currentQuestion.studentAnswer }}</text>
        <text class="correct-icon" v-if="currentQuestion.isCorrect === true">✅</text>
        <text class="wrong-icon" v-else-if="currentQuestion.isCorrect === false">❌</text>
      </view>

      <!-- 标准答案（批改后显示） -->
      <view class="answer-row correct-answer-row" v-if="currentQuestion.isCorrect === false && currentQuestion.correctAnswer">
        <text class="answer-label">标准答案：</text>
        <text class="answer-value correct-answer-text">{{ currentQuestion.correctAnswer }}</text>
      </view>

      <!-- AI 解析（批改后显示） -->
      <view class="analysis-section" v-if="currentQuestion.isCorrect !== null && currentQuestion.isCorrect !== undefined">
        <view class="analysis-header">
          <text class="analysis-title">📝 题目解析</text>
        </view>
        <view class="analysis-content">
          <text class="analysis-text">{{ currentQuestion.analysis || '暂无解析' }}</text>
        </view>
      </view>

      <!-- 知识点 -->
      <view class="item-footer" v-if="currentQuestion.knowledgePointName">
        <text class="kp-name"> {{ currentQuestion.knowledgePointName }}</text>
      </view>
    </view>

    <!-- 底部操作栏 -->
    <view class="bottom-bar">
      <button class="btn-nav" :disabled="currentIndex === 0" @click="prevQuestion">上一题</button>
      <button 
        class="btn-submit" 
        :class="{ 'btn-readonly': readOnly }"
        :disabled="!canSubmit" 
        @click="submitCurrent"
      >
        {{ readOnly ? '只读' : '提交答案' }}
      </button>
      <button class="btn-nav" :disabled="currentIndex === totalQuestions - 1" @click="nextQuestion">下一题</button>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { useAppStore } from '@/stores/app';
import { useAuthGuard } from '@/utils/useAuthGuard';
import { submitAnswer, getHomeworkByDate } from '@/api/homework';
import { showSuccess } from '@/utils/message';

// ========== 登录态守卫 ==========
useAuthGuard();

const appStore = useAppStore();

// ========== 状态 ==========
const statusBarHeight = ref(0);
const subjectName = ref('');
const subjectId = ref(null);
const questions = ref([]);
const currentIndex = ref(0);
const answer = ref(''); // 当前题目的临时答案
const loading = ref(false);
const readOnly = ref(false); // 只读模式

// ========== 计算属性 ==========
const currentQuestion = computed(() => questions.value[currentIndex.value]);
const totalQuestions = computed(() => questions.value.length);
const canSubmit = computed(() => {
  if (readOnly.value) return false; // 只读模式不可提交
  if (!currentQuestion.value) return false;
  if (currentQuestion.value.studentAnswer != null) return false; // 已提交
  return !!answer.value; // 所有题型都需要有答案
});

// ========== 生命周期 ==========
onMounted(() => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;

  // 从路由参数获取数据
  const pages = getCurrentPages();
  const currentPage = pages[pages.length - 1];
  const options = currentPage.options;

  subjectName.value = decodeURIComponent(options.subjectName || '');
  subjectId.value = options.subjectId ? parseInt(options.subjectId) : null;
  readOnly.value = options.readOnly === 'true'; // 解析只读模式
  try {
    questions.value = JSON.parse(decodeURIComponent(options.items || '[]'));
  } catch (e) {
    console.error('[HomeworkDetail] 解析题目失败:', e);
    questions.value = [];
  }
  
  console.log('[HomeworkDetail] 初始化 - subjectName:', subjectName.value, 'subjectId:', subjectId.value, 'readOnly:', readOnly.value);
});

// 监听页面显示，重新加载数据（批改后返回时刷新）
onShow(() => {
  if (subjectId.value && questions.value.length > 0) {
    // 检查是否需要刷新（如果当前题目没有 analysis 但可能有新数据）
    const needsRefresh = questions.value.some(q => 
      q.isCorrect !== null && q.isCorrect !== undefined && !q.analysis
    );
    if (needsRefresh) {
      reloadHomeworkData();
    }
  }
});

// 重新加载作业数据
async function reloadHomeworkData() {
  if (!subjectId.value) return;
  
  loading.value = true;
  try {
    const freshItems = await getHomeworkByDate(subjectId.value, appStore.currentDate);
    if (freshItems && freshItems.length > 0) {
      questions.value = freshItems;
      console.log('[HomeworkDetail] 数据已刷新，包含 analysis');
    }
  } catch (e) {
    console.warn('[HomeworkDetail] 刷新数据失败:', e.message);
  } finally {
    loading.value = false;
  }
}

// ========== 答题交互 ==========
function selectChoice(label) {
  if (currentQuestion.value?.studentAnswer != null) return; // 已提交不可修改
  answer.value = label;
}

async function submitCurrent() {
  if (!canSubmit.value) {
    if (readOnly.value) {
      uni.showToast({ title: '该科目无活跃目标，仅可查看', icon: 'none' });
    }
    return;
  }

  const item = currentQuestion.value;
  try {
    await submitAnswer({
      planId: item.planId,
      questionId: item.questionId,
      questionSource: item.questionSource,
      taskType: item.taskType,
      studentAnswer: answer.value || ''
    });
    showSuccess('提交成功');
    // 更新本地状态
    item.studentAnswer = answer.value || '';
    answer.value = '';
    // 自动进入下一题（非最后一题时）
    if (currentIndex.value < totalQuestions.value - 1) {
      nextQuestion();
    }
  } catch (e) {
    console.warn('[HomeworkDetail] 提交答案失败:', e.message);
  }
}

// ========== 导航 ==========
function prevQuestion() {
  if (currentIndex.value > 0) {
    currentIndex.value--;
    answer.value = ''; // 清空临时答案
  }
}

function nextQuestion() {
  if (currentIndex.value < totalQuestions.value - 1) {
    currentIndex.value++;
    answer.value = ''; // 清空临时答案
  }
}

function goBack() {
  uni.navigateBack();
}

// ========== 工具函数 ==========
function choiceLabel(cIdx) {
  return String.fromCharCode(65 + cIdx);
}

function formatTypeLabel(type) {
  const map = { choice: '选择', numeric: '填空', short: '解答', self: '主观' };
  return map[type] || type;
}

function difficultyLabel(diff) {
  const map = { basic: '基础', standard: '标准', challenge: '挑战' };
  return map[diff] || diff;
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
.nav-progress {
  font-size: $font-sm;
  color: $text-secondary;
}

/* 题目区域 */
.question-area {
  flex: 1;
  padding: $spacing-lg;
  overflow-y: auto;
}
.question-tags {
  display: flex;
  gap: $spacing-sm;
  margin-bottom: $spacing-md;
}
.type-tag {
  font-size: $font-xs;
  padding: 4rpx 16rpx;
  border-radius: $radius-sm;
  color: #fff;
  background-color: $info-color;
}
.tag-choice { background-color: $primary-color; }
.tag-numeric { background-color: $success-color; }
.tag-short { background-color: $warning-color; }
.tag-self { background-color: $info-color; }
.diff-tag {
  font-size: $font-xs;
  padding: 4rpx 16rpx;
  border-radius: $radius-sm;
}
.diff-basic { color: $success-color; background-color: #f0f9eb; }
.diff-standard { color: $warning-color; background-color: #fdf6ec; }
.diff-challenge { color: $danger-color; background-color: #fef0f0; }
.question-content {
  font-size: $font-md;
  color: $text-primary;
  line-height: 1.6;
  display: block;
  margin-bottom: $spacing-lg;
}

/* 选择题选项 */
.choices {
  display: flex;
  flex-direction: column;
  gap: $spacing-sm;
  margin-bottom: $spacing-md;
}
.choice-item {
  display: flex;
  align-items: center;
  padding: $spacing-md;
  border: 2rpx solid $border-color;
  border-radius: $radius-md;
  transition: all 0.2s;
}
.choice-disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.choice-selected {
  border-color: $primary-color;
  background-color: $primary-light;
}
.choice-correct {
  border-color: $success-color;
  background-color: #f0f9eb;
}
.choice-wrong {
  border-color: $danger-color;
  background-color: #fef0f0;
}
.choice-label {
  width: 48rpx;
  height: 48rpx;
  border-radius: 50%;
  background-color: $border-light;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: $font-sm;
  font-weight: bold;
  color: $text-primary;
  margin-right: $spacing-md;
  flex-shrink: 0;
}
.choice-selected .choice-label {
  background-color: $primary-color;
  color: #fff;
}
.choice-text {
  font-size: $font-md;
  color: $text-primary;
}

/* 填空题输入 */
.input-area {
  margin-bottom: $spacing-md;
}
.answer-input {
  border: 2rpx solid $border-color;
  border-radius: $radius-md;
  padding: $spacing-md;
  font-size: $font-md;
}
.answer-textarea {
  border: 2rpx solid $border-color;
  border-radius: $radius-md;
  padding: $spacing-md;
  font-size: $font-md;
  min-height: 200rpx;
  width: 100%;
  box-sizing: border-box;
}

/* 已提交答案 */
.answer-row {
  display: flex;
  align-items: center;
  padding: $spacing-sm $spacing-md;
  background-color: #f8f8f8;
  border-radius: $radius-md;
  margin-bottom: $spacing-md;
}
.answer-label {
  font-size: $font-sm;
  color: $text-secondary;
}
.answer-value {
  font-size: $font-md;
  color: $text-primary;
  font-weight: 500;
  margin: 0 $spacing-sm;
}
.correct-icon, .wrong-icon {
  margin-left: auto;
}

/* 标准答案 */
.correct-answer-row {
  background-color: #f0f9f0;
  border-left: 6rpx solid #52c41a;
}
.correct-answer-text {
  color: #52c41a;
}

/* AI 解析 */
.analysis-section {
  margin-top: $spacing-md;
  background-color: $bg-white;
  border-radius: $radius-md;
  padding: $spacing-md;
  box-shadow: $shadow-sm;
}
.analysis-header {
  margin-bottom: $spacing-sm;
}
.analysis-title {
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
}
.analysis-content {
  background-color: #fafafa;
  border-radius: $radius-sm;
  padding: $spacing-md;
}
.analysis-text {
  font-size: $font-md;
  color: $text-primary;
  line-height: 1.6;
}

.item-footer {
  padding-top: $spacing-sm;
  border-top: 1rpx solid $border-light;
}
.kp-name {
  font-size: $font-sm;
  color: $text-secondary;
}

/* 底部操作栏 */
.bottom-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: $spacing-md $spacing-lg;
  background-color: $bg-white;
  box-shadow: 0 -2rpx 8rpx rgba(0, 0, 0, 0.05);
}
.btn-nav {
  background-color: #f5f5f5;
  color: $text-primary;
  font-size: $font-sm;
  border-radius: $radius-md;
  padding: $spacing-sm $spacing-lg;
  min-width: 160rpx;
}
.btn-nav[disabled] {
  opacity: 0.4;
}
.btn-submit {
  background-color: $primary-color;
  color: #fff;
  font-size: $font-md;
  border-radius: $radius-md;
  padding: $spacing-sm $spacing-xl;
  min-width: 200rpx;
}
.btn-submit[disabled] {
  opacity: 0.4;
}
.btn-readonly {
  background-color: $text-secondary; // 只读模式使用灰色
}
</style>
