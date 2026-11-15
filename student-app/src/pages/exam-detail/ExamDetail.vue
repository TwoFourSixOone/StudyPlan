<template>
  <view class="page">
    <!-- 顶部导航栏：科目 + 当前题号/总题数（与作业答题区一致） -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <button class="btn-back" @click="goBack" aria-label="返回">
          <view class="back-icon"></view>
        </button>
        <text class="nav-title">{{ subjectName }}</text>
        <text class="nav-progress" v-if="totalQuestions > 0">{{ currentIndex + 1 }}/{{ totalQuestions }}</text>
      </view>
    </view>

    <!-- 加载中 -->
    <LoadingState :loading="loading" text="加载中..." />

    <!-- 失败态 -->
    <EmptyState v-if="loadError && !loading" icon="error" text="试卷加载失败，请稍后重试">
      <button class="btn-retry" @click="reload">重新加载</button>
    </EmptyState>

    <template v-else-if="paper">
      <!-- 解析中 / 失败提示 -->
      <view class="banner banner--info" v-if="isParsing">
        <text class="banner-text">试卷解析中，题目将自动刷新（无需手动操作）</text>
      </view>
      <view class="banner banner--error" v-if="paper.status === 'failed'">
        <text class="banner-text">试卷解析失败，请在「我的 → 试卷上传」中重新解析（最多 2 次）</text>
      </view>
      <view class="banner banner--warn" v-if="paper.status === 'uploading'">
        <text class="banner-text">该试卷尚未提交解析，请到「我的 → 试卷上传」继续上传并提交</text>
      </view>
      <view class="banner banner--warn" v-if="paper.imageWarning">
        <text class="banner-text">{{ paper.imageWarning }}</text>
      </view>

      <!-- 试卷概要 -->
      <view class="summary">
        <view class="summary-item">
          <!-- 字段对齐后端 ExamPaperDetailVO：questionCount（design §10.2） -->
          <text class="summary-value">{{ paper.questionCount != null ? paper.questionCount : totalQuestions }}</text>
          <text class="summary-label">题目数</text>
        </view>
        <view class="summary-item">
          <text class="summary-value">{{ paper.correctCount || 0 }}</text>
          <text class="summary-label">答对</text>
        </view>
        <view class="summary-item">
          <text class="summary-value">{{ paper.judgedCount != null ? paper.judgedCount : '—' }}</text>
          <text class="summary-label">已判定</text>
        </view>
        <view class="summary-item">
          <text class="summary-value">
            {{ paper.userScore != null && paper.totalScore != null ? paper.userScore + '/' + paper.totalScore : '—' }}
          </text>
          <text class="summary-label">得分</text>
        </view>
      </view>

      <!-- 试卷元信息：类型中文 / 考试日期 / 图片张数（均来自 ExamPaperDetailVO） -->
      <view class="paper-meta">
        <text class="meta-text">{{ paper.paperTypeName || paperTypeLabel(paper.paperType) }}</text>
        <text class="meta-text" v-if="paper.examDate">{{ paper.examDate }}</text>
        <text class="meta-text" v-if="paper.imageCount != null">{{ paper.imageCount }} 张图片</text>
      </view>

      <!-- 题目内容区域（只读，与 HomeworkDetail 相同的交互骨架，无提交按钮） -->
      <view class="question-area" v-if="currentQuestion">
        <view class="question-tags">
          <!-- 字段对齐后端 ExamQuestionItemVO：questionType（design §4.2） -->
          <text class="type-tag" :class="'tag-' + currentQuestion.questionType">
            {{ typeLabel(currentQuestion) }}
          </text>
          <text class="diff-tag" :class="'diff-' + currentQuestion.difficulty">
            {{ difficultyLabel(currentQuestion.difficulty) }}
          </text>
          <text class="verdict-tag" :class="'verdict-' + verdictClass(currentQuestion)">
            {{ verdictLabel(currentQuestion) }}
          </text>
          <text class="manual-tag" v-if="currentQuestion.attributionStatus === 'pending_manual_review'">
            待人工归因
          </text>
        </view>

        <!-- 题干 -->

        <!-- 题目配图（2026-11-12：优先用后端落库的 base64，避免文件地址在真机不可达） -->
        <view class="figure-wrap" v-if="figureSrc">
          <image
            class="figure-img"
            :src="figureSrc"
            mode="widthFix"
            @click="previewFigure"
          />
          <text class="figure-tip">👆 点击查看大图</text>
        </view>
        <text class="question-content">{{ currentQuestion.stem || currentQuestion.questionContent || '题干缺失' }}</text>

        <!-- 选择题选项（只读：我的答案高亮 + 正确答案高亮） -->
        <view
          class="choices"
          v-if="currentQuestion.questionType === 'choice' && currentQuestion.choices && currentQuestion.choices.length"
        >
          <view
            class="choice-item"
            v-for="(choice, cIdx) in currentQuestion.choices"
            :key="cIdx"
            :class="{
              'choice-mine': isMyChoice(cIdx),
              'choice-right': isRightChoice(cIdx),
              'choice-mine-wrong': isMyChoice(cIdx) && !isRightChoice(cIdx) && currentQuestion.isCorrect === false
            }"
          >
            <text class="choice-label">{{ choiceLabel(cIdx) }}</text>
            <text class="choice-text">{{ choice }}</text>
          </view>
        </view>

        <!-- 选项缺失兜底：不渲染空白选项区 -->
        <view class="choices-missing" v-else-if="currentQuestion.questionType === 'choice'">
          <text class="choices-missing-text">本题选项数据异常，已按文本展示</text>
        </view>

        <!-- 非选择题：只读答案框（展示学生作答） -->
        <view class="input-area" v-else>
          <view class="answer-readonly">
            <text class="answer-readonly-text">{{ studentAnswerText(currentQuestion) }}</text>
          </view>
        </view>

        <!-- 学生作答 -->
        <view class="answer-row" v-if="currentQuestion.questionType === 'choice'">
          <text class="answer-label">我的答案：</text>
          <text class="answer-value">{{ studentAnswerText(currentQuestion) }}</text>
          <text class="correct-icon" v-if="currentQuestion.isCorrect === true">✅</text>
          <text class="wrong-icon" v-else-if="currentQuestion.isCorrect === false">❌</text>
          <text class="unknown-icon" v-else-if="currentQuestion.answerState !== 'blank'">待判定</text>
        </view>

        <!-- 标准答案 -->
        <view class="answer-row correct-answer-row" v-if="currentQuestion.correctAnswer">
          <text class="answer-label">标准答案：</text>
          <text class="answer-value correct-answer-text">{{ currentQuestion.correctAnswer }}</text>
        </view>

        <!-- 单题得分（1 题 1 分制） -->
        <view class="score-row">
          <text class="score-label">本题得分</text>
          <text class="score-value">{{ scoreLabel(currentQuestion) }}</text>
        </view>

        <!-- 题目解析 -->
        <view class="analysis-section">
          <view class="analysis-header">
            <text class="analysis-title">📝 题目解析</text>
          </view>
          <view class="analysis-content">
            <text class="analysis-text">{{ currentQuestion.explanation || '暂无解析' }}</text>
          </view>
        </view>

        <!-- 知识点 -->
        <view class="item-footer">
          <text class="kp-name">{{ currentQuestion.knowledgePointName || '未归因知识点' }}</text>
        </view>
      </view>

      <!-- 题目为空 -->
      <EmptyState
        v-else-if="!loading && !isParsing"
        text="暂无题目，可能仍在解析中，请稍后重新进入"
      />

      <!-- 底部操作栏：仅上一题 / 下一题 -->
      <view class="bottom-bar" v-if="totalQuestions > 0">
        <button class="btn-nav" :disabled="currentIndex === 0" @click="prevQuestion">上一题</button>
        <text class="bottom-progress">{{ currentIndex + 1 }} / {{ totalQuestions }}</text>
        <button class="btn-nav" :disabled="currentIndex === totalQuestions - 1" @click="nextQuestion">下一题</button>
      </view>
    </template>
  </view>
</template>

<script setup>
/**
 * 试卷详情页（design.md §4.2 出参 / §10.2 接口 / §10.3 页面栈）
 *
 * 数据来源：**只接收 `paperId`**，进入后调用 `GET /student/exam/papers/{paperId}` 取全部题目。
 * 禁止把题目数组塞进 URL 参数（`homework-detail` 的 items JSON 是反例，不复制该做法）。
 *
 * 页面栈规则（必须遵守）：
 * - 层级：我的 → 历史试卷 → 试卷详情，最多 3 层；**详情内切题不跳页**（本地 currentIndex 切换）。
 * - 从列表/上传页进入：`navigateTo`（上传页成功后用 `redirectTo` 替换上传页，避免回到已提交的上传页）；
 *   返回：`navigateBack`。
 * - 防重复入栈：进入本页前由调用方（ExamList）比对页面栈，已存在同 paperId 详情则 `navigateBack`。
 * - `onUnload` 必须清空轮询定时器，避免离开页面后仍每 2s 打接口。
 */
import { ref, computed } from 'vue';
import { onLoad, onUnload } from '@dcloudio/uni-app';
import { useAuthGuard } from '@/utils/useAuthGuard';
import { getPaperDetail, paperTypeLabel } from '@/api/exam';
import { showError } from '@/utils/message';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

// ========== 常量 ==========
const POLL_INTERVAL = 2000;
const POLL_MAX_TIMES = 150; // ≈5 分钟（整卷单次解析更慢，2026-11-12）

// ========== 状态 ==========
const statusBarHeight = ref(0);
const paperId = ref(null);
const paper = ref(null);
const currentIndex = ref(0);
const loading = ref(false);
const loadError = ref(false);

// 轮询定时器（onUnload 必须清理）
let pollTimer = null;
let pollActive = false;
let pollTimes = 0;

// ========== 计算属性 ==========
const questions = computed(() => paper.value?.questions || []);
const subjectName = computed(() => paper.value?.subjectName || '试卷详情');
const totalQuestions = computed(() => questions.value.length);
const currentQuestion = computed(() => questions.value[currentIndex.value]);
const isParsing = computed(
  () => paper.value?.status === 'pending' || paper.value?.status === 'processing'
);
const myChoice = computed(() => normalizeAnswer(currentQuestion.value?.studentAnswer));
const rightChoice = computed(() => normalizeAnswer(currentQuestion.value?.correctAnswer));

// ========== 生命周期 ==========
onLoad((options) => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;

  // 只解析 paperId（design §10.3：禁止 URL 传 items / subjectName）
  const raw = options?.paperId;
  if (raw == null || !/^\d+$/.test(String(raw))) {
    showError('试卷参数缺失，请返回历史试卷重新进入');
    exitPage();
    return;
  }
  paperId.value = Number(raw);
  reload();
});

onUnload(() => {
  // 页面卸载：清空轮询定时器
  stopPolling();
});

// ========== 数据加载 ==========
async function reload() {
  await fetchDetail(false);
  maybeStartPolling();
}

/**
 * 拉取详情
 * @param silent true = 轮询中的静默刷新（不闪 loading、不弹错误）
 */
async function fetchDetail(silent) {
  if (!paperId.value) return null;
  if (!silent) {
    loading.value = true;
    loadError.value = false;
  }
  try {
    const data = await getPaperDetail(paperId.value);
    paper.value = data || null;
    const total = (data?.questions || []).length;
    if (total > 0 && currentIndex.value > total - 1) currentIndex.value = total - 1;
    return data;
  } catch (e) {
    console.warn('[ExamDetail] 获取试卷详情失败:', e.message);
    if (!silent) {
      paper.value = null;
      loadError.value = true;
    }
    return null;
  } finally {
    if (!silent) loading.value = false;
  }
}

// ========== 解析中轮询（详情接口的 status，D10） ==========
function maybeStartPolling() {
  const status = paper.value?.status;
  if (status === 'pending' || status === 'processing') {
    startPolling();
  }
}

function startPolling() {
  stopPolling();
  pollActive = true;
  pollTimes = 0;
  pollTimer = setTimeout(pollTick, POLL_INTERVAL);
}

async function pollTick() {
  if (!pollActive) return;
  pollTimes += 1;
  const data = await fetchDetail(true);
  if (!pollActive) return;

  const status = data?.status;
  if (status === 'pending' || status === 'processing') {
    if (pollTimes >= POLL_MAX_TIMES) {
      stopPolling();
      return;
    }
    pollTimer = setTimeout(pollTick, POLL_INTERVAL);
    return;
  }
  // completed / failed / 未知 → 停止轮询
  stopPolling();
}

function stopPolling() {
  pollActive = false;
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

// ========== 切题（详情内不跳页） ==========
function prevQuestion() {
  if (currentIndex.value > 0) currentIndex.value -= 1;
}

function nextQuestion() {
  if (currentIndex.value < totalQuestions.value - 1) currentIndex.value += 1;
}

function goBack() {
  exitPage();
}

/** 返回上一页；若本页是栈底（无上一页）则回历史试卷列表 */
function exitPage() {
  uni.navigateBack({
    fail: () => uni.redirectTo({ url: '/pages/exam-list/ExamList' })
  });
}

// ========== 展示工具 ==========
function choiceLabel(cIdx) {
  return String.fromCharCode(65 + cIdx);
}

function normalizeAnswer(value) {
  return String(value == null ? '' : value).trim().toUpperCase();
}

function isMyChoice(cIdx) {
  return !!myChoice.value && myChoice.value === choiceLabel(cIdx);
}

function isRightChoice(cIdx) {
  return !!rightChoice.value && rightChoice.value === choiceLabel(cIdx);
}

function studentAnswerText(q) {
  if (!q) return '未作答';
  const text = String(q.studentAnswer == null ? '' : q.studentAnswer).trim();
  if (text) return text;
  if (q.answerState === 'unreadable') return '作答无法识别';
  return '未作答';
}

/**
 * 题目配图放大查看（2026-11-12）
 *
 * <p>后端把裁剪件以 data URL 落库；旧数据可能只有文件 URL（可能不可达），
 * 因此优先 base64，退化到 URL。</p>
 */
const figureSrc = computed(() => {
  const q = currentQuestion.value;
  if (!q) return '';
  return q.figureBase64 || q.questionImageUrl || '';
});

function previewFigure() {
  const url = figureSrc.value;
  if (!url) return;
  uni.previewImage({ urls: [url], current: url });
}

function verdictLabel(q) {
  if (!q) return '';
  if (q.isCorrect === true) return '答对';
  if (q.isCorrect === false) return '答错';
  return '待人工判定';
}

function verdictClass(q) {
  if (!q) return 'unknown';
  if (q.isCorrect === true) return 'right';
  if (q.isCorrect === false) return 'wrong';
  return 'unknown';
}

/** 1 题 1 分制：is_correct=1→1/1、=0→0/1、NULL→待判定 */
function scoreLabel(q) {
  if (!q || q.isCorrect == null) return '待判定';
  const score = q.score != null ? q.score : q.isCorrect ? 1 : 0;
  const full = q.fullScore != null ? q.fullScore : 1;
  return `${score}/${full}`;
}

function formatTypeLabel(type) {
  const map = { choice: '选择', numeric: '填空', short: '解答', self: '主观' };
  return map[type] || '未分类';
}

/**
 * 题型标签：选择题区分单选/多选（2026-11-12，后端按 choiceMode 判定）
 */
function typeLabel(q) {
  if (!q) return '';
  if (q.questionType === 'choice') {
    return q.multiChoice ? '多选' : '单选';
  }
  return formatTypeLabel(q.questionType);
}

function difficultyLabel(diff) {
  const map = { basic: '基础', standard: '标准', challenge: '挑战' };
  return map[diff] || '难度未知';
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background-color: $bg-color;
  display: flex;
  flex-direction: column;
  padding-bottom: 140rpx;
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
.nav-progress {
  font-size: $font-sm;
  color: $text-secondary;
}

/* 横幅提示 */
.banner {
  margin: $spacing-md $spacing-lg 0;
  padding: $spacing-md;
  border-radius: $radius-md;
}
.banner--info {
  background-color: $primary-light;
}
.banner--error {
  background-color: #fef0f0;
}
.banner--warn {
  background-color: #fdf6ec;
}
.banner-text {
  font-size: $font-sm;
  color: $text-regular;
  line-height: 1.6;
}

/* 概要 */
.summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $spacing-lg;
  margin: $spacing-md $spacing-lg 0;
  background-color: $bg-white;
  border-radius: $radius-md;
  box-shadow: $shadow-sm;
}
.summary-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
}
.summary-value {
  font-size: $font-lg;
  font-weight: bold;
  color: $text-primary;
}
.summary-label {
  font-size: $font-xs;
  color: $text-secondary;
  margin-top: 4rpx;
}

/* 试卷元信息（类型中文 / 考试日期 / 图片张数） */
.paper-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: $spacing-md;
  padding: $spacing-sm $spacing-lg 0;
}
.meta-text {
  font-size: $font-sm;
  color: $text-secondary;
}

/* 题目区域 */
.question-area {
  flex: 1;
  padding: $spacing-lg;
  margin-top: $spacing-md;
  background-color: $bg-white;
  border-radius: $radius-md;
  margin-left: $spacing-lg;
  margin-right: $spacing-lg;
}
.question-tags {
  display: flex;
  flex-wrap: wrap;
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
.verdict-tag {
  font-size: $font-xs;
  padding: 4rpx 16rpx;
  border-radius: $radius-sm;
}
.verdict-right { color: $success-color; background-color: #f0f9eb; }
.verdict-wrong { color: $danger-color; background-color: #fef0f0; }
.verdict-unknown { color: $warning-color; background-color: #fdf6ec; }
.manual-tag {
  font-size: $font-xs;
  padding: 4rpx 16rpx;
  border-radius: $radius-sm;
  color: $info-color;
  background-color: #f4f4f5;
}
.multi-tag {
  font-size: $font-xs;
  padding: 4rpx 16rpx;
  border-radius: $radius-sm;
  color: #fff;
  background-color: #9c27b0;
}
/* 题目配图（2026-11-12） */
.figure-wrap {
  margin-bottom: $spacing-lg;
}
.figure-img {
  width: 100%;
  border-radius: $radius-md;
  border: 1rpx solid $border-light;
  background-color: #fff;
}
.figure-tip {
  display: block;
  margin-top: 8rpx;
  font-size: $font-xs;
  color: $text-secondary;
  text-align: center;
}
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
}
.choice-mine {
  border-color: $primary-color;
  background-color: $primary-light;
}
.choice-right {
  border-color: $success-color;
  background-color: #f0f9eb;
}
.choice-mine-wrong {
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
.choice-mine .choice-label {
  background-color: $primary-color;
  color: #fff;
}
.choice-right .choice-label {
  background-color: $success-color;
  color: #fff;
}
.choice-text {
  font-size: $font-md;
  color: $text-primary;
}
.choices-missing {
  padding: $spacing-md;
  margin-bottom: $spacing-md;
  border: 2rpx dashed $border-color;
  border-radius: $radius-md;
  background-color: #fdf6ec;
}
.choices-missing-text {
  font-size: $font-sm;
  color: $warning-color;
  line-height: 1.5;
}

/* 非选择题只读答案框 */
.input-area {
  margin-bottom: $spacing-md;
}
.answer-readonly {
  border: 2rpx dashed $border-color;
  border-radius: $radius-md;
  padding: $spacing-md;
  background-color: #fafafa;
  min-height: 80rpx;
}
.answer-readonly-text {
  font-size: $font-md;
  color: $text-primary;
  line-height: 1.6;
}

/* 作答 / 标准答案 */
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
.correct-icon,
.wrong-icon,
.unknown-icon {
  margin-left: auto;
  font-size: $font-sm;
  color: $text-secondary;
}
.correct-answer-row {
  background-color: #f0f9f0;
  border-left: 6rpx solid #52c41a;
}
.correct-answer-text {
  color: #52c41a;
}

/* 单题得分 */
.score-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $spacing-sm $spacing-md;
  background-color: #f8f8f8;
  border-radius: $radius-md;
  margin-bottom: $spacing-md;
}
.score-label {
  font-size: $font-sm;
  color: $text-secondary;
}
.score-value {
  font-size: $font-md;
  color: $warning-color;
  font-weight: 500;
}

/* 解析 */
.analysis-section {
  margin-top: $spacing-md;
  background-color: #fafafa;
  border-radius: $radius-md;
  padding: $spacing-md;
}
.analysis-header {
  margin-bottom: $spacing-sm;
}
.analysis-title {
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
}
.analysis-text {
  font-size: $font-md;
  color: $text-primary;
  line-height: 1.6;
}

.item-footer {
  padding-top: $spacing-md;
  margin-top: $spacing-md;
  border-top: 1rpx solid $border-light;
}
.kp-name {
  font-size: $font-sm;
  color: $text-secondary;
}

/* 底部操作栏：仅上一题 / 下一题 */
.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
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
.bottom-progress {
  font-size: $font-md;
  color: $primary-color;
  font-weight: 600;
}
.btn-retry {
  margin-top: $spacing-lg;
  background-color: $primary-color;
  color: #fff;
  font-size: $font-sm;
  border-radius: $radius-md;
  padding: 0 $spacing-xl;
}
</style>
