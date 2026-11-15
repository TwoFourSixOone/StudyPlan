<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <button class="btn-back" @click="goBack" aria-label="返回">
          <view class="back-icon"></view>
        </button>
        <text class="nav-title">诊断结果</text>
        <view class="nav-placeholder"></view>
      </view>
    </view>

    <!-- 加载中 -->
    <LoadingState :loading="loading" text="加载中..." />

    <!-- 失败态（1136 越权 / 网络失败等，可重试） -->
    <EmptyState v-if="loadError && !loading" icon="error" text="诊断结果加载失败，请稍后重试">
      <button class="btn-retry" @click="reload">重新加载</button>
    </EmptyState>

    <template v-else-if="diagnosis">
      <!-- 试卷还在解析中：轮询详情 status，完成后自动刷新诊断 -->
      <view class="card parsing-card" v-if="isParsing">
        <text class="parsing-icon">⏳</text>
        <text class="parsing-text">试卷还在解析中，请稍候…</text>
        <text class="parsing-tip">解析通常需要 60–120 秒，完成后将自动展示诊断结果</text>
        <text class="parsing-tip" v-if="pollTimedOut">
          解析时间较长，可先返回，稍后在「我的 → 历史试卷」中查看结果
        </text>
      </view>

      <!-- F1 双保险 ②：题目已落库但判分（TX-B）未完成，轮询 gradeStatus 到 graded 后自动刷新 -->
      <view class="card parsing-card" v-else-if="isGrading">
        <text class="parsing-icon">✍️</text>
        <text class="parsing-text">试卷还在判分中，请稍候…</text>
        <text class="parsing-tip">判分完成后将自动展示得分、正确率与薄弱知识点</text>
      </view>

      <!-- 解析失败：无诊断数据，给出明确出口 -->
      <EmptyState v-else-if="parseFailed" icon="error" text="试卷解析失败，暂无诊断结果">
        <text class="fail-hint">请在「我的 → 试卷上传」中重新解析（最多 2 次）</text>
      </EmptyState>

      <template v-else>
        <!-- 状态横幅 -->
        <view class="banner banner--warn" v-if="notSubmitted">
          <text class="banner-text">该试卷尚未提交解析，请到「我的 → 试卷上传」继续上传并提交</text>
        </view>
        <view class="banner banner--warn" v-else-if="isZeroData">
          <text class="banner-text">该试卷暂无可诊断的题目数据</text>
        </view>
        <!-- F1：判分等待超时——保留当前数据，提示稍后再看 -->
        <view class="banner banner--warn" v-else-if="gradeTimedOut">
          <text class="banner-text">判分时间较长，当前得分/正确率暂未包含判分结果，可稍后重新进入本页查看</text>
        </view>

        <!-- 试卷概要：科目 + 试卷类型 + 考试日期 -->
        <view class="card paper-card">
          <view class="paper-head">
            <text class="paper-subject">{{ diagnosis.subjectName || '未知科目' }}</text>
            <text class="paper-type-tag">{{ paperTypeLabel(diagnosis.paperType) }}</text>
          </view>
          <text class="paper-date" v-if="diagnosis.examDate">考试日期：{{ diagnosis.examDate }}</text>

          <!-- 关键指标（1 题 1 分制：userScore=答对题数 / totalScore=已判定题数） -->
          <view class="summary">
            <view class="summary-item">
              <text class="summary-value">{{ scoreText }}</text>
              <text class="summary-label">得分</text>
            </view>
            <view class="summary-item">
              <text class="summary-value">{{ diagnosis.totalQuestions || 0 }}</text>
              <text class="summary-label">总题数</text>
            </view>
            <view class="summary-item">
              <text class="summary-value">{{ accuracyText }}</text>
              <text class="summary-label">正确率</text>
            </view>
            <view class="summary-item">
              <text class="summary-value">{{ diagnosis.errorQuestionCount || 0 }}</text>
              <text class="summary-label">错题数</text>
            </view>
          </view>

          <!-- 已判定 / 待判定（未判定题不计入正确率分母） -->
          <view class="judged-row">
            <text class="judged-text">已判定 {{ diagnosis.judgedCount || 0 }} 题</text>
            <text class="judged-divider">·</text>
            <text class="judged-text judged-pending">待判定 {{ pendingCount }} 题</text>
            <text class="judged-divider">·</text>
            <text class="judged-text">答对 {{ diagnosis.correctCount || 0 }} 题</text>
          </view>
        </view>

        <!-- 薄弱知识点（后端已按 答错数降序 + 掌握度升序 截断前 10，原序渲染） -->
        <view class="card">
          <view class="card-header">
            <text class="card-title">薄弱知识点</text>
            <text class="card-count" v-if="weakKps.length > 0">Top {{ weakKps.length }}</text>
          </view>
          <view class="kp-item" v-for="kp in weakKps" :key="kp.knowledgePointId">
            <view class="kp-info">
              <text class="kp-name">{{ kp.knowledgePointName || '未知知识点' }}</text>
              <text class="kp-wrong">答错 {{ kp.wrongCount || 0 }} 题</text>
            </view>
            <view class="kp-mastery">
              <view class="kp-bar-wrapper">
                <view class="kp-bar" :class="barColorClass(kp.masteryScore)" :style="{ width: barWidth(kp.masteryScore) }"></view>
              </view>
              <text class="kp-score">{{ masteryLabel(kp.masteryScore) }}</text>
            </view>
          </view>
          <view class="kp-empty" v-if="weakKps.length === 0">
            <text class="kp-empty-text">本次诊断未发现薄弱知识点</text>
          </view>
        </view>

        <!-- 置信说明（后端固定文案：单份试卷样本量提示） -->
        <view class="banner banner--info" v-if="diagnosis.confidenceNote">
          <text class="banner-text">{{ diagnosis.confidenceNote }}</text>
        </view>
      </template>

      <!-- 底部操作（判分中不展示建目标入口：等数据齐了再引导，F1） -->
      <view class="actions">
        <template v-if="!isParsing && !parseFailed && !isGrading">
          <button class="btn-primary" @click="goCreateGoal">根据这次诊断创建目标</button>
          <text class="action-hint">已为你预填科目，建议目标周期 3–6 个月</text>
        </template>
        <view class="action-row">
          <button class="btn-secondary" @click="goExamList">先去看看历史试卷</button>
          <button class="btn-secondary" @click="goHome">返回首页</button>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
/**
 * 试卷诊断结果页（新用户引导方案 A）
 *
 * 数据来源：onLoad 只接收 `paperId`，调用 GET /student/exam/papers/{paperId}/diagnosis
 * （api/exam.ts getExamDiagnosis，隐性约定 #18/#19：并入既有模块、走 utils/request.ts）。
 *
 * 页面栈规则：
 * - 上传页 from=diagnosis 解析完成后用 `redirectTo` 跳到本页（替换上传页）；
 *   「返回」回「我的」（tab）；「返回首页」switchTab 首页。
 *
 * 空数据 / 未解析完成兜底（诊断接口无 status 字段）：
 * - totalQuestions=0 时用详情接口（GET /papers/{paperId}）的 status 判断：
 *   pending/processing → 显示「试卷还在解析中」并每 2s 轮询，completed 后自动刷新诊断；
 *   failed → 解析失败态；uploading → 提示尚未提交解析。
 * - F1 双保险 ②（2026-11-15，t4 reviewer 高危项）：totalQuestions>0 且 judgedCount=0
 *   时用详情 gradeStatus 判断判分（TX-B）状态——ungraded 则显示「判分中」并 2s 轮询
 *   （≈5 分钟上限），graded 后自动刷新诊断数据；试卷 status=completed 只代表题目落库
 *   （TX-A），判分/错题/掌握度在其后完成，未判分时本页会长期 0/0、正确率 —。
 * - F4：judged=0 时得分显示「—」（后端 userScore/totalScore 为 0/0，非 null），
 *   正确率同样「—」（accuracy=null）。
 * - onUnload 必须清空轮询定时器。
 *
 * 「根据这次诊断创建目标」：建目标表单在「我的」（tabBar 页），switchTab 无法携带
 * query 参数，因此预填科目（subjectId）走 appStore.goalPrefill，「我的」页 onShow
 * 消费一次后清空，并展示「建议周期 3–6 个月」提示；不改变提交逻辑与校验。
 */
import { ref, computed } from 'vue';
import { onLoad, onUnload } from '@dcloudio/uni-app';
import { useAppStore } from '@/stores/app';
import { useAuthGuard } from '@/utils/useAuthGuard';
import { getExamDiagnosis, getPaperDetail, paperTypeLabel } from '@/api/exam';
import { showError } from '@/utils/message';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

const appStore = useAppStore();

// ========== 常量 ==========
const POLL_INTERVAL = 2000;
const POLL_MAX_TIMES = 150; // ≈5 分钟（与试卷详情/上传页口径一致）

// ========== 状态 ==========
const statusBarHeight = ref(0);
const paperId = ref(null);
const diagnosis = ref(null);
const loading = ref(false);
const loadError = ref(false);
/** 试卷仍在解析中（详情 status=pending/processing） */
const isParsing = ref(false);
/** 试卷解析失败（详情 status=failed，含判分阶段失败） */
const parseFailed = ref(false);
/** 试卷尚未提交解析（详情 status=uploading） */
const notSubmitted = ref(false);
/** 解析轮询超时（停止轮询但保留提示，稍后可从历史试卷进入） */
const pollTimedOut = ref(false);
/**
 * F1 双保险 ②：有题目但 0 已判定（judgedCount=0）且详情 gradeStatus=ungraded——
 * 判分/结算（TX-B）未完成，轮询等待 graded 后自动刷新诊断数据。
 */
const isGrading = ref(false);
/** 判分等待超时（停止轮询，保留当前数据 + 提示稍后再看） */
const gradeTimedOut = ref(false);

// 轮询定时器（onUnload 必须清理）
let pollTimer = null;
let pollActive = false;
let pollTimes = 0;
// 判分等待轮询（与解析轮询互斥使用，独立定时器）
let gradeTimer = null;
let gradeActive = false;
let gradeTimes = 0;

// ========== 计算属性 ==========
const weakKps = computed(() => diagnosis.value?.weakKnowledgePoints || []);

/** 已有题目数据（totalQuestions > 0） */
const isZeroData = computed(() => !(Number(diagnosis.value?.totalQuestions) > 0));

/** 有题但 0 已判定：判分未完成窗口的典型特征（F1） */
const isAllUnjudged = computed(() => {
  const total = Number(diagnosis.value?.totalQuestions) || 0;
  const judged = Number(diagnosis.value?.judgedCount) || 0;
  return total > 0 && judged === 0;
});

/** 待判定 = 总题数 - 已判定（不为负） */
const pendingCount = computed(() => {
  const total = Number(diagnosis.value?.totalQuestions) || 0;
  const judged = Number(diagnosis.value?.judgedCount) || 0;
  return Math.max(total - judged, 0);
});

/**
 * 得分（1 题 1 分制：userScore/totalScore）。
 * F4：judged=0（无已判定题）时后端返回 0/0（BigDecimal 0，非 null），
 * 应显示「—」而不是「0/0」——判分未完成或全部不可判定都不是真实得分。
 */
const scoreText = computed(() => {
  const d = diagnosis.value;
  if (!d || d.userScore == null || d.totalScore == null) return '—';
  if (!(Number(d.judgedCount) > 0)) return '—';
  return `${d.userScore}/${d.totalScore}`;
});

/** 正确率（后端 0–1 小数 ×100；judged=0 为 null → —） */
const accuracyText = computed(() => {
  const acc = diagnosis.value?.accuracy;
  if (acc == null) return '—';
  return `${Math.round(Number(acc) * 100)}%`;
});

// ========== 生命周期 ==========
onLoad((options) => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;

  // 只解析 paperId（与试卷详情页同口径，禁止 URL 传其他业务数据）
  const raw = options?.paperId;
  if (raw == null || !/^\d+$/.test(String(raw))) {
    showError('试卷参数缺失，请从历史试卷重新进入');
    exitPage();
    return;
  }
  paperId.value = Number(raw);
  reload();
});

onUnload(() => {
  // 页面卸载：清空轮询定时器（页面栈规则）
  stopPolling();
  stopGradePoll();
});

// ========== 数据加载 ==========
async function reload() {
  if (!paperId.value) return;
  loading.value = true;
  loadError.value = false;
  isParsing.value = false;
  parseFailed.value = false;
  notSubmitted.value = false;
  pollTimedOut.value = false;
  isGrading.value = false;
  gradeTimedOut.value = false;
  stopPolling();
  stopGradePoll();

  const ok = await loadDiagnosis(false);
  if (ok) {
    if (isZeroData.value) {
      // 空数据兜底：诊断接口无 status 字段，0 题时用详情接口判断是否仍在解析
      await syncParseState();
    } else if (isAllUnjudged.value) {
      // F1 双保险 ②：有题但 0 已判定 → 判分（TX-B）可能未完成，按 gradeStatus 决定是否轮询
      await syncGradeState();
    }
  }
  loading.value = false;
}

/**
 * 拉取诊断结果
 * @param silent true = 解析完成后的静默刷新（不闪 loading、不弹错误）
 */
async function loadDiagnosis(silent) {
  try {
    const data = await getExamDiagnosis(paperId.value);
    diagnosis.value = data || null;
    return true;
  } catch (e) {
    console.warn('[ExamDiagnosis] 获取诊断结果失败:', e.message);
    if (!silent) {
      diagnosis.value = null;
      loadError.value = true;
    }
    return false;
  }
}

/**
 * 用详情接口的 status 判断解析状态并按需接管轮询
 * （诊断 VO 不含 status；详情接口只读复用，不改变其语义）
 */
async function syncParseState() {
  try {
    const detail = await getPaperDetail(paperId.value);
    const status = detail?.status;
    if (status === 'pending' || status === 'processing') {
      isParsing.value = true;
      startPolling();
    } else if (status === 'failed') {
      parseFailed.value = true;
    } else if (status === 'uploading') {
      notSubmitted.value = true;
    }
    // completed / 未知 → 维持 0 题零态展示（isZeroData 横幅提示）
  } catch (e) {
    // 详情也查不到（1136 越权等）→ 维持诊断数据零态展示，不额外报错
    console.warn('[ExamDiagnosis] 获取试卷状态失败:', e.message);
  }
}

// ========== 解析中轮询（详情接口 status） ==========
function startPolling() {
  stopPolling();
  pollActive = true;
  pollTimes = 0;
  pollTimer = setTimeout(pollTick, POLL_INTERVAL);
}

async function pollTick() {
  if (!pollActive) return;
  pollTimes += 1;
  try {
    const detail = await getPaperDetail(paperId.value);
    if (!pollActive) return;
    const status = detail?.status;
    if (status === 'completed') {
      stopPolling();
      isParsing.value = false;
      // 解析完成：静默刷新诊断结果（不闪 loading）
      await loadDiagnosis(true);
      // F1：status=completed 只代表题目落库（TX-A）；若判分未完成（0 已判定）
      // 继续按 gradeStatus 接管判分轮询，graded 后再刷新
      if (isAllUnjudged.value) {
        await syncGradeState();
      }
      return;
    }
    if (status === 'failed') {
      stopPolling();
      isParsing.value = false;
      parseFailed.value = true;
      return;
    }
    if (status === 'uploading') {
      stopPolling();
      isParsing.value = false;
      notSubmitted.value = true;
      return;
    }
    if (pollTimes >= POLL_MAX_TIMES) {
      stopPolling();
      pollTimedOut.value = true;
      return;
    }
  } catch (e) {
    // 网络抖动：继续轮询，超过上限次数则放弃（定时器在 onUnload 清理）
    if (pollTimes >= POLL_MAX_TIMES) {
      stopPolling();
      pollTimedOut.value = true;
      return;
    }
  }
  if (pollActive) pollTimer = setTimeout(pollTick, POLL_INTERVAL);
}

function stopPolling() {
  pollActive = false;
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

// ========== 判分等待轮询（F1 双保险 ②：gradeStatus ungraded → graded） ==========
/**
 * 有题但 0 已判定时，用详情接口的 gradeStatus 判断判分（TX-B）状态：
 * - ungraded → 判分进行中，接管 2s 轮询，graded 后静默刷新诊断数据；
 * - graded → 本地诊断数据是结算提交前读到的旧值（竞态）→ 静默刷新一次；
 *   刷新后仍 0 已判定 = 全部题不可判定的最终态（标准答案缺失/无法辨识），正常展示「—」；
 * - status=failed → 判分阶段失败（settle 异常会把试卷置 failed），转失败态。
 */
async function syncGradeState() {
  try {
    const detail = await getPaperDetail(paperId.value);
    if (detail?.status === 'failed') {
      parseFailed.value = true;
      return;
    }
    const gradeStatus = detail?.gradeStatus;
    if (gradeStatus === 'graded') {
      await loadDiagnosis(true);
      return;
    }
    if (gradeStatus === 'ungraded') {
      isGrading.value = true;
      startGradePolling();
    }
    // 其他/未知值 → 维持当前数据展示（得分/正确率均为「—」兜底）
  } catch (e) {
    // 详情查不到（网络抖动等）→ 维持当前数据展示，不额外报错
    console.warn('[ExamDiagnosis] 获取判分状态失败:', e.message);
  }
}

function startGradePolling() {
  stopGradePoll();
  gradeActive = true;
  gradeTimes = 0;
  gradeTimer = setTimeout(gradeTick, POLL_INTERVAL);
}

async function gradeTick() {
  if (!gradeActive) return;
  gradeTimes += 1;
  try {
    const detail = await getPaperDetail(paperId.value);
    if (!gradeActive) return;
    if (detail?.status === 'failed') {
      stopGradePoll();
      isGrading.value = false;
      parseFailed.value = true;
      return;
    }
    if (detail?.gradeStatus === 'graded') {
      stopGradePoll();
      isGrading.value = false;
      // 判分完成：静默刷新诊断结果（得分/正确率/薄弱知识点/错题数）
      await loadDiagnosis(true);
      return;
    }
    if (gradeTimes >= POLL_MAX_TIMES) {
      stopGradePoll();
      isGrading.value = false;
      gradeTimedOut.value = true;
      return;
    }
  } catch (e) {
    // 网络抖动：继续轮询，超过上限次数则放弃（定时器在 onUnload 清理）
    if (gradeTimes >= POLL_MAX_TIMES) {
      stopGradePoll();
      isGrading.value = false;
      gradeTimedOut.value = true;
      return;
    }
  }
  if (gradeActive) gradeTimer = setTimeout(gradeTick, POLL_INTERVAL);
}

function stopGradePoll() {
  gradeActive = false;
  if (gradeTimer) {
    clearTimeout(gradeTimer);
    gradeTimer = null;
  }
}

// ========== 跳转 ==========
/**
 * 根据这次诊断创建目标：预填科目后回「我的」建目标（现有入口）。
 * 「我的」是 tabBar 页，switchTab 不支持 query 参数 → 预填走 appStore.goalPrefill，
 * 由「我的」页 onShow 消费（不改变提交逻辑与校验）。
 */
function goCreateGoal() {
  const subjectId = diagnosis.value?.subjectId;
  if (subjectId != null) {
    appStore.setGoalPrefill({ subjectId: Number(subjectId), fromDiagnosis: true });
  }
  uni.switchTab({
    url: '/pages/profile/Profile',
    fail: () => showError('跳转失败，请稍后重试')
  });
}

function goExamList() {
  uni.navigateTo({
    url: '/pages/exam-list/ExamList',
    fail: () => showError('跳转失败，请重新编译小程序后重试')
  });
}

function goHome() {
  uni.switchTab({
    url: '/pages/index/Index',
    fail: () => showError('跳转失败，请稍后重试')
  });
}

function goBack() {
  exitPage();
}

/** 返回上一页；若本页是栈底（无上一页）则回首页 */
function exitPage() {
  uni.navigateBack({
    fail: () => uni.switchTab({ url: '/pages/index/Index' })
  });
}

// ========== 展示工具（掌握度渲染口径与「掌握度看板」一致：0–1 ×100） ==========
function masteryLabel(score) {
  if (score == null) return '未评测';
  return `${Math.round(Number(score) * 100)}%`;
}

function barColorClass(score) {
  if (score == null) return 'bar-gray';
  if (score < 0.4) return 'bar-red';
  if (score < 0.7) return 'bar-yellow';
  return 'bar-green';
}

function barWidth(score) {
  if (score == null) return '0%';
  return Math.round(Number(score) * 100) + '%';
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background-color: $bg-color;
  padding-bottom: 60rpx;
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

/* 卡片 */
.card {
  background-color: $bg-white;
  border-radius: $radius-md;
  padding: $spacing-lg;
  margin: $spacing-md $spacing-lg;
  box-shadow: $shadow-sm;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $spacing-md;
}
.card-title {
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
}
.card-count {
  font-size: $font-sm;
  color: $text-secondary;
}

/* 解析中 */
.parsing-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: $spacing-xl $spacing-lg;
}
.parsing-icon {
  font-size: 72rpx;
  margin-bottom: $spacing-md;
}
.parsing-text {
  font-size: $font-md;
  font-weight: 500;
  color: $text-primary;
  margin-bottom: $spacing-sm;
}
.parsing-tip {
  font-size: $font-sm;
  color: $text-secondary;
  line-height: 1.6;
  text-align: center;
}
.fail-hint {
  display: block;
  margin-top: $spacing-sm;
  font-size: $font-sm;
  color: $text-secondary;
  line-height: 1.6;
  text-align: center;
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
.banner--warn {
  background-color: #fdf6ec;
}
.banner-text {
  font-size: $font-sm;
  color: $text-regular;
  line-height: 1.6;
}

/* 试卷概要 */
.paper-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $spacing-sm;
}
.paper-subject {
  font-size: $font-xl;
  font-weight: bold;
  color: $text-primary;
  flex: 1;
}
.paper-type-tag {
  font-size: $font-xs;
  color: $primary-color;
  background-color: $primary-light;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
  margin-left: $spacing-sm;
  flex-shrink: 0;
}
.paper-date {
  display: block;
  font-size: $font-sm;
  color: $text-secondary;
  margin-bottom: $spacing-lg;
}

/* 关键指标 */
.summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $spacing-md 0;
  border-top: 1rpx solid $border-light;
}
.summary-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
}
.summary-value {
  font-size: $font-xl;
  font-weight: bold;
  color: $primary-color;
}
.summary-label {
  font-size: $font-xs;
  color: $text-secondary;
  margin-top: 4rpx;
}

/* 已判定 / 待判定 */
.judged-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: $spacing-sm;
  padding-top: $spacing-md;
  border-top: 1rpx solid $border-light;
}
.judged-text {
  font-size: $font-sm;
  color: $text-secondary;
}
.judged-pending {
  color: $warning-color;
}
.judged-divider {
  font-size: $font-sm;
  color: $border-color;
}

/* 薄弱知识点 */
.kp-item {
  padding: $spacing-md 0;
  border-bottom: 1rpx solid $border-light;
}
.kp-item:last-of-type {
  border-bottom: none;
}
.kp-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $spacing-sm;
}
.kp-name {
  font-size: $font-md;
  color: $text-primary;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kp-wrong {
  font-size: $font-xs;
  color: $danger-color;
  background-color: #fef0f0;
  padding: 4rpx 12rpx;
  border-radius: $radius-sm;
  margin-left: $spacing-sm;
  flex-shrink: 0;
}
.kp-mastery {
  display: flex;
  align-items: center;
  gap: $spacing-md;
}
.kp-bar-wrapper {
  flex: 1;
  height: 16rpx;
  border-radius: $radius-round;
  background-color: $border-light;
  overflow: hidden;
}
.kp-bar {
  height: 100%;
  border-radius: $radius-round;
  transition: width 0.3s ease;
}
.bar-red {
  background-color: $danger-color;
}
.bar-yellow {
  background-color: $warning-color;
}
.bar-green {
  background-color: $success-color;
}
.bar-gray {
  background-color: $info-color;
}
.kp-score {
  font-size: $font-sm;
  color: $text-secondary;
  width: 110rpx;
  text-align: right;
  flex-shrink: 0;
}
.kp-empty {
  padding: $spacing-lg 0;
}
.kp-empty-text {
  font-size: $font-sm;
  color: $text-secondary;
  text-align: center;
  display: block;
}

/* 底部操作 */
.actions {
  margin: $spacing-lg $spacing-lg 0;
}
.btn-primary {
  background-color: $primary-color;
  color: #fff;
  font-size: $font-md;
  border-radius: $radius-md;
}
.btn-primary::after {
  border: none;
}
.action-hint {
  display: block;
  text-align: center;
  font-size: $font-xs;
  color: $text-secondary;
  margin-top: $spacing-sm;
}
.action-row {
  display: flex;
  gap: $spacing-md;
  margin-top: $spacing-lg;
}
.btn-secondary {
  flex: 1;
  background-color: $bg-white;
  color: $text-regular;
  font-size: $font-sm;
  border-radius: $radius-md;
  box-shadow: $shadow-sm;
}
.btn-secondary::after {
  border: none;
}

/* 失败态重试按钮（EmptyState 插槽） */
.btn-retry {
  margin-top: $spacing-lg;
  background-color: $primary-color;
  color: #fff;
  font-size: $font-sm;
  border-radius: $radius-md;
  padding: 0 $spacing-xl;
}
</style>
