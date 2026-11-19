<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <button class="btn-back" @click="goBack" aria-label="返回">
          <view class="back-icon"></view>
        </button>
        <text class="nav-title">试卷上传</text>
        <view class="nav-placeholder"></view>
      </view>
    </view>

    <!-- 试卷信息 -->
    <view class="card">
      <view class="form-item">
        <text class="form-label">科目</text>
        <picker :value="subjectIndex" :range="subjectNames" :disabled="formLocked" @change="onSubjectChange">
          <view class="form-picker">
            <text>{{ subjectNames[subjectIndex] || '选择科目' }}</text>
            <text class="picker-arrow">▼</text>
          </view>
        </picker>
      </view>
      <view class="form-item">
        <text class="form-label">考试日期</text>
        <picker mode="date" :value="examForm.examDate" :disabled="formLocked" @change="onExamDateChange">
          <view class="form-picker">
            <text>{{ examForm.examDate || '选择日期' }}</text>
            <text class="picker-arrow">▼</text>
          </view>
        </picker>
      </view>
      <view class="form-item">
        <text class="form-label">试卷类型</text>
        <picker :value="paperTypeIndex" :range="paperTypeNames" :disabled="formLocked" @change="onPaperTypeChange">
          <view class="form-picker">
            <text>{{ paperTypeNames[paperTypeIndex] }}</text>
            <text class="picker-arrow">▼</text>
          </view>
        </picker>
      </view>
    </view>

    <!-- 图片选择 / 预览 -->
    <view class="card">
      <view class="card-header">
        <text class="card-title">试卷图片</text>
        <text class="card-count">{{ images.length }}/{{ MAX_IMAGES }}</text>
      </view>

      <view class="thumb-grid">
        <view class="thumb" v-for="(img, idx) in images" :key="img.path">
          <image class="thumb-img" :src="img.path" mode="aspectFill" @click="previewImage(idx)" />
          <!-- 单张状态角标 -->
          <text class="thumb-badge" :class="'badge-' + img.status">{{ imageBadgeText(img) }}</text>
          <text class="thumb-remove" v-if="img.status !== 'done'" @click.stop="removeImage(idx)">✕</text>
          <!-- 已上传图片不允许删除（服务端已持有 URL），给一次明确提示 -->
          <text class="thumb-remove thumb-remove--disabled" v-else @click.stop="removeImage(idx)">✕</text>
        </view>
        <view class="thumb thumb-add" v-if="images.length < MAX_IMAGES && !formLocked && !processing" @click="chooseImages">
          <text class="thumb-add-icon">＋</text>
        </view>
      </view>

      <text class="size-tip" v-if="processing">正在处理所选图片（压缩 / 体积校验）…</text>

      <text class="size-tip">{{ SIZE_TIP }}</text>
      <text class="size-tip">一次可选 {{ MAX_IMAGES }} 张，点一次「上传并解析」即全部同时上传；已上传的图片不能删除</text>

      <!-- 单张失败重试 -->
      <view class="row-actions" v-if="failedImages.length > 0">
        <button class="btn-secondary" @click="retryFailedImages" :disabled="uploading">
          {{ uploading ? '上传中...' : `重试失败张（${failedImages.length}）` }}
        </button>
        <button class="btn-secondary" @click="submitWithUploaded" :disabled="uploading || uploadingCount === 0">
          少传几张也解析
        </button>
      </view>
    </view>

    <!-- 上传 / 解析进度 -->
    <view class="card" v-if="paperId">
      <view class="card-header">
        <text class="card-title">处理进度</text>
        <text class="progress-text" v-if="uploading">已上传 {{ uploadingCount }}/{{ images.length }}</text>
      </view>

      <view class="step-row">
        <text class="step-label">图片上传</text>
        <text class="step-value">{{ uploadStepText }}</text>
      </view>
      <view class="step-row" v-if="taskStatus">
        <text class="step-label">解析任务</text>
        <text class="step-value">{{ examStatusLabel(taskStatus) }}</text>
      </view>
      <view class="progress-tip" v-if="taskStatus === 'pending' || taskStatus === 'processing'">
        <text>解析通常需要 60–120 秒，请保持页面打开，完成后会自动跳转到结果页</text>
      </view>
      <!-- F1：诊断入口等判分（grade_status=graded）再跳，避免诊断页读到未判分数据 -->
      <view class="progress-tip" v-if="waitingGrade">
        <text>题目解析完成，正在判分（得分 / 错题 / 薄弱知识点）…判分完成后自动跳转诊断结果</text>
      </view>
      <!-- F1：判分等待超时——不自动跳诊断页（此时很可能仍是 ungraded），给出可操作出口 -->
      <view class="grade-timeout-box" v-if="gradeTimedOut">
        <text class="grade-timeout-text">判分时间较长，暂未完成判分。可继续等待，或稍后在「我的 → 历史试卷」中查看该试卷结果</text>
        <view class="row-actions">
          <button class="btn-secondary" @click="retryWaitGrade">继续等待判分</button>
          <button class="btn-secondary" @click="goExamList">去历史试卷</button>
        </view>
      </view>

      <!-- 解析失败 -->
      <view class="error-box" v-if="parseError">
        <text class="error-text">{{ parseError }}</text>
        <view class="row-actions">
          <button class="btn-secondary" v-if="canRetryParse" @click="handleRetryParse" :disabled="submitting">
            {{ submitting ? '提交中...' : '重新解析' }}
          </button>
          <text class="error-hint" v-else>该试卷最多重试 {{ MAX_OCR_RETRY }} 次，请稍后在「历史试卷」查看</text>
        </view>
      </view>
    </view>

    <!-- 主操作 -->
    <view class="bottom-bar">
      <button class="btn-primary" :disabled="!canStart" @click="handleStartUpload">
        {{ mainButtonText }}
      </button>
    </view>
  </view>
</template>

<script setup>
/**
 * 试卷上传页（design.md §2 上传契约 + §10.3 页面栈）
 *
 * 页面栈规则（必须遵守，与「我的」「历史试卷」「试卷详情」一起看）：
 * - 层级：我的（tab）→ 试卷上传（本页）；上传完成后用 `redirectTo` 替换本页为试卷详情，
 *   这样从详情返回不会回到"已提交的上传页"（避免重复上传）。
 * - 进入本页：`navigateTo`（从「我的 → 试卷上传」，或新用户引导 `?from=diagnosis`）；返回：`navigateBack`。
 * - 上传 → 结果的替换用 `redirectTo`；详情页内部切题不跳页。
 *   from=diagnosis（新用户引导方案 A）：解析完成后跳**诊断结果页**而不是试卷详情；
 *   普通入口保持原行为（跳详情）。
 * - 页面卸载（onUnload）必须清空轮询定时器与自动跳转定时器，避免后台残留请求。
 *
 * F1 修复（2026-11-15，t4 reviewer 高危项）：试卷 status=completed 只代表题目落库（TX-A），
 * 判分/错题/掌握度结算（TX-B，grade_status ungraded→graded）在其后；task 完成与 grade CAS
 * 在同一事务。因此：
 * - 满 8 张自动触发的 taskId（第 8 张图片追加响应带回）不再丢弃，优先用**任务轮询**（状态可信），
 *   不再依赖试卷详情 status（它在判分前就会变成 completed）；
 * - from=diagnosis 跳诊断页前先等 grade_status=graded（2s 轮询，≈5 分钟上限），
 *   避免「诊断页长期 0/0、正确率 —、错题数 0」；超时不自动跳诊断页（此时多半仍是
 *   ungraded），改为提示「继续等待判分 / 去历史试卷」；普通入口不变（详情页自有展示与刷新）。
 */
import { ref, computed } from 'vue';
import { onLoad, onUnload } from '@dcloudio/uni-app';
import { useAppStore } from '@/stores/app';
import { useAuthGuard } from '@/utils/useAuthGuard';
import { showError, showSuccess } from '@/utils/message';
import { formatDate } from '@/utils/date';
import {
  createPaper,
  uploadPaperImage,
  submitPaper,
  retryPaper,
  getPaperTask,
  getPaperDetail,
  examStatusLabel,
  EXAM_MAX_IMAGE_COUNT,
  EXAM_MAX_SINGLE_IMAGE_MB,
  EXAM_MAX_TOTAL_IMAGE_MB,
  EXAM_MAX_OCR_RETRY,
  EXAM_MIN_LONG_SIDE,
  EXAM_MIN_SHORT_SIDE
} from '@/api/exam';

// ========== 登录态守卫 ==========
useAuthGuard();

const appStore = useAppStore();

// ========== 常量（与 design §2.3 一致，前端预检 + 后端兜底） ==========
const MAX_IMAGES = EXAM_MAX_IMAGE_COUNT;
const MAX_SINGLE_MB = EXAM_MAX_SINGLE_IMAGE_MB;
const MAX_TOTAL_MB = EXAM_MAX_TOTAL_IMAGE_MB;
const MAX_OCR_RETRY = EXAM_MAX_OCR_RETRY;
const SIZE_TIP = '单张≤9.5MB、合计≤30MB（建议每张压到 2MB 内）';
/**
 * 压缩策略阈值（t20）：**≤800KB 的图不压缩**，直接上传原图。
 * 试卷照片里的选项小字很容易被 JPEG 有损压缩糊掉，小图再压一次只会掉识别率；
 * 只有超过 800KB 的大图才用 quality 85 压缩（不 resize，保住原分辨率）。
 */
const COMPRESS_THRESHOLD_BYTES = 800 * 1024; // 800KB
const COMPRESS_QUALITY = 85;
/**
 * 上传并发：**全部并行**（= 本页最多张数）。
 * 微信 `wx.uploadFile` 的并发上限是 10，本页上限 8 张，因此一次选择的多张图
 * 点一次「上传并解析」就会同时发出所有上传请求（真正的"一次性上传"），不再按批排队。
 */
const UPLOAD_CONCURRENCY = MAX_IMAGES;
const POLL_INTERVAL = 2000;
const POLL_MAX_TIMES = 150; // ≈5 分钟（整卷单次解析更慢，2026-11-12）
const AUTO_JUMP_DELAY = 800;

// ========== 状态 ==========
const statusBarHeight = ref(0);
const today = formatDate(new Date(), 'YYYY-MM-DD');

// 试卷信息表单
const subjectIndex = ref(0);
const examForm = ref({ examDate: today, paperType: 'midterm' });
const examPaperTypes = ['midterm', 'final', 'monthly', 'mock', 'other'];
const examPaperTypeNames = ['期中考试', '期末考试', '月考', '模拟考试', '其他'];
const paperTypeIndex = computed(() => {
  const idx = examPaperTypes.indexOf(examForm.value.paperType);
  return idx >= 0 ? idx : 0; // 非法值兜底回 midterm（S13）
});
const paperTypeNames = examPaperTypeNames;

// 图片列表：{ path, size, pageNo, status: 'pending' | 'uploading' | 'done' | 'failed', error }
// pageNo 是卷面页序（1-based，= 上传时的列表下标）：加入列表时确定，**删除会重排**，
// 上传失败重试沿用该次的值；后端按它稳定排序还原页序（2026-11-17，隐性约定 #38）。
const images = ref([]);

// 上传 / 解析状态
const paperId = ref(null);
const taskId = ref(null);
const uploading = ref(false);
const submitting = ref(false);
/** 选图后的本地预处理（取大小 / 压缩 / 体积校验）进行中 */
const processing = ref(false);
const taskStatus = ref('');
const ocrRetryCount = ref(0);
const parseError = ref('');

/**
 * 入口来源（新用户引导方案 A）：from=diagnosis 时解析完成后跳诊断结果页；
 * 普通入口（我的 → 试卷上传）保持原行为跳试卷详情。
 */
const fromDiagnosis = ref(false);

/**
 * 满 8 张时后端在**第 8 张图片追加响应**里自动触发解析并带回 taskId（F1 修复 ③）。
 * 不再丢弃：优先用它走任务轮询——task 完成发生在 TX-B 判分结算之后，状态可信；
 * 试卷详情 status=completed 只代表题目落库（TX-A），判分未完成时也会出现，不能作为
 * 「可以进诊断页」的依据。
 */
const autoTriggeredTaskId = ref(null);

/**
 * from=diagnosis：解析完成后等待判分（grade_status=graded）再跳诊断页（F1 修复 ①）。
 */
const waitingGrade = ref(false);

/**
 * F1：判分等待超时（≈5 分钟仍未 graded）。此时**不自动跳诊断页**——避免诊断页读到
 * 未判分数据；由用户选择「继续等待判分」或去历史试卷查看。
 */
const gradeTimedOut = ref(false);

// 定时器（onUnload 必须清理）
let pollTimer = null;
let pollActive = false;
let pollTimes = 0;
let jumpTimer = null;
// 判分等待轮询（F1：与解析轮询互斥使用，独立定时器）
let gradeTimer = null;
let gradePollActive = false;
let gradePollTimes = 0;

const subjectNames = computed(() => appStore.subjectList.map((s) => s.name));
const uploadingCount = computed(() => images.value.filter((i) => i.status === 'done').length);
const failedImages = computed(() => images.value.filter((i) => i.status === 'failed'));
const pendingImages = computed(() =>
  images.value.filter((i) => i.status === 'pending' || i.status === 'failed')
);

/** 上传/提交期间锁定表单与选图，避免重复提交 */
const flowLocked = computed(() => uploading.value || submitting.value);

/** 会话已创建（paperId 已有）后表单不可再改，避免"改了科目但纸还是旧会话"的错觉 */
const formLocked = computed(() => flowLocked.value || !!paperId.value);

/** 解析已结束（成功/失败）后允许重新走完整流程 */
const canStart = computed(() => {
  if (flowLocked.value || processing.value) return false;
  if (images.value.length === 0) return false;
  if (taskStatus.value === 'pending' || taskStatus.value === 'processing') return false;
  if (taskStatus.value === 'completed') return false;
  // 解析失败后只能"重新解析"（或返回历史试卷），不允许再走一次上传+提交（后端 1186）
  if (taskStatus.value === 'failed') return false;
  return true;
});

const uploadStepText = computed(() => {
  if (uploading.value) return `上传中 ${uploadingCount.value}/${images.value.length}`;
  if (uploadingCount.value === images.value.length && images.value.length > 0) return '全部上传成功';
  return `已上传 ${uploadingCount.value}/${images.value.length}`;
});

const canRetryParse = computed(() => ocrRetryCount.value < MAX_OCR_RETRY);

const mainButtonText = computed(() => {
  if (processing.value) return '处理图片中…';
  if (uploading.value) return `上传中（${uploadingCount.value}/${images.value.length}）`;
  if (submitting.value) return '提交解析中...';
  if (waitingGrade.value) return '判分中，即将跳转诊断...';
  if (gradeTimedOut.value) return '判分时间较长，请稍后查看';
  if (taskStatus.value === 'pending' || taskStatus.value === 'processing') return '解析中，请稍候...';
  if (taskStatus.value === 'completed') return '解析完成，正在跳转...';
  if (taskStatus.value === 'failed') return '解析失败，请点「重新解析」';
  if (paperId.value) {
    const pending = images.value.filter((i) => i.status === 'pending' || i.status === 'failed').length;
    return pending > 0 ? `继续上传 ${pending} 张 / 提交解析` : '提交解析';
  }
  return images.value.length > 1 ? `一次性上传 ${images.value.length} 张并解析` : '上传并解析';
});

// ========== 生命周期 ==========
onLoad((options) => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;
  // 新用户引导：from=diagnosis → 解析完成后跳诊断结果页（见 onParseCompleted）
  fromDiagnosis.value = options?.from === 'diagnosis';
});

onUnload(() => {
  // 页面卸载：轮询与自动跳转定时器必须清空（页面栈规则）
  stopPolling();
  stopGradePoll();
  if (jumpTimer) {
    clearTimeout(jumpTimer);
    jumpTimer = null;
  }
});

// ========== 表单 ==========
function onSubjectChange(e) {
  subjectIndex.value = Number(e.detail.value) || 0;
}

function onExamDateChange(e) {
  examForm.value.examDate = e.detail.value;
}

function onPaperTypeChange(e) {
  const idx = Number(e.detail.value);
  examForm.value.paperType = examPaperTypes[idx] || 'midterm';
}

function goBack() {
  uni.navigateBack({
    fail: () => uni.switchTab({ url: '/pages/profile/Profile' })
  });
}

/**
 * 同一科目同一天已有试卷（1131）：给出「去历史试卷」入口
 *
 * <p>解析失败的试卷（一题未落库）后端已允许当天重新上传，因此这里只会出现在
 * 「已完成 / 解析中 / 失败但有题目」三种情况——它们都不能重传。</p>
 */
function offerExamListAfterDuplicate() {
  uni.showModal({
    title: '该科目今天已有试卷',
    content: '同一科目同一天只能上传一份试卷。可在「历史试卷」查看解析结果和得分，或换一个考试日期再上传。',
    confirmText: '去历史试卷',
    cancelText: '知道了',
    success: (res) => {
      if (!res.confirm) return;
      uni.redirectTo({
        url: '/pages/exam-list/ExamList',
        fail: () => uni.switchTab({ url: '/pages/profile/Profile' })
      });
    }
  });
}

// ========== 选图 / 预检 / 压缩 ==========
function imageBadgeText(img) {
  const map = { pending: '待上传', uploading: '上传中', done: '已上传', failed: '失败' };
  return map[img.status] || '';
}

function previewImage(idx) {
  const urls = images.value.map((i) => i.path);
  uni.previewImage({ urls, current: urls[idx] });
}

function removeImage(idx) {
  const img = images.value[idx];
  if (!img) return;
  if (img.status === 'done') {
    showError('已上传的图片不能删除，请继续提交解析');
    return;
  }
  if (flowLocked.value) {
    showError('上传进行中，请稍后再删除');
    return;
  }
  images.value.splice(idx, 1);
  // 删除后必须重排页序：pageNo 语义是"上传时的列表下标"，不重排会让后续补选拿到
  // 与残留项重复的 pageNo → 服务端按 pageNo 稳定排序后，解析页序与缩略图顺序不一致
  // （2026-11-17 修复，隐性约定 #38）。
  renumberPageNos();
}

/**
 * 按当前列表顺序重排**未上传**图片的 pageNo（= 列表下标）
 *
 * 只在**上传前**发生（已上传的图片不能删除，且其页序已随 URL 落库，不再改动），
 * 因此不会与"失败重试沿用原值"冲突：重试时 img.pageNo 已是该次上传确定的值。
 */
function renumberPageNos() {
  images.value.forEach((img, index) => {
    if (img.status !== 'done') {
      img.pageNo = index + 1;
    }
  });
}

/** 选图：count 只给剩余额度，超出即拦截提示（后端 1182 兜底） */
async function chooseImages() {
  if (flowLocked.value || processing.value) return;
  const remain = MAX_IMAGES - images.value.length;
  if (remain <= 0) {
    showError(`最多上传 ${MAX_IMAGES} 张试卷图片`);
    return;
  }
  let chooseRes;
  try {
    chooseRes = await new Promise((resolve, reject) => {
      uni.chooseImage({
        count: remain,
        // 取原图：微信的 'compressed' 会在选图时就压一次，属于"看不见的二次损失"，
        // 与 t20「小图不压、大图只压一次 quality 85」的策略冲突；体积由下面的
        // 800KB 阈值压缩 + 单张 9.5MB/合计 30MB 预检兜底（超限有明确提示）。
        sizeType: ['original'],
        sourceType: ['album', 'camera'],
        success: resolve,
        fail: reject
      });
    });
  } catch (e) {
    return; // 用户取消选择
  }

  let paths = chooseRes.tempFilePaths || [];
  if (paths.length > remain) {
    paths = paths.slice(0, remain);
    showError(`最多 ${MAX_IMAGES} 张，已选取前 ${remain} 张`);
  }

  // S2 预检 + 压缩策略（t20）：
  // 小图（≤800KB）不压缩，直接原图上传；只有大图才以 quality 85 压缩，且不做 resize（保持原分辨率）。
  // 校验始终使用"最终要上传的那份文件"的大小（压缩后大小参与 9.5MB/30MB 判定）。
  // 选多张时「取大小 → 压缩 → 上限校验」是逐张串行的（避免 8 张同时压缩造成内存峰值），
  // 期间用内联提示 + 禁用"＋"给出反馈，避免"选完图页面没反应"的错觉。
  // 不用 uni.showLoading：它与 showToast 共用同一个提示通道，单张超限的提示会把 loading 顶掉，
  // 反向调用 hideLoading 又可能连带关闭错误提示。
  processing.value = true;
  try {
    let totalBytes = images.value.reduce((sum, i) => sum + (i.size || 0), 0);
    const accepted = [];
    const tempFiles = chooseRes.tempFiles || [];
    for (let i = 0; i < paths.length; i += 1) {
      const originalPath = paths[i];
      // chooseImage 已带回 size 时直接用，避免多一次 getFileInfo（回退值仅在 getFileInfo 失败时生效）
      const originalSize = await getFileSize(originalPath, tempFiles[i] && tempFiles[i].size);

      let uploadPath = originalPath;
      let uploadSize = originalSize;
      let compressedApplied = false;

      if (originalSize > COMPRESS_THRESHOLD_BYTES) {
        uploadPath = await compressImage(originalPath);
        compressedApplied = uploadPath !== originalPath;
        const compressedSize = await getFileSize(uploadPath);
        // 压缩成功但取不到大小时，保守沿用原图大小参与上限校验（安全优先）
        uploadSize = compressedSize > 0 ? compressedSize : originalSize;
      }

      // 清晰度预检（2026-11-17，后端 1193 兜底）：长边 < EXAM_MIN_LONG_SIDE 或
      // 短边 < EXAM_MIN_SHORT_SIDE 的图直接跳过，不加入待上传列表，避免"传完才被拒"。
      // 压缩不做 resize，取最终上传路径的尺寸即可；循环本身逐张串行，这里直接 await。
      const resolutionOk = await checkImageResolution(uploadPath);
      if (!resolutionOk) continue;

      const sizeMb = uploadSize / 1024 / 1024;
      if (uploadSize > 0 && sizeMb > MAX_SINGLE_MB) {
        // 压缩后仍超限时明确提示，不静默上传（requirement 2）
        showError(
          compressedApplied
            ? `压缩后仍超过 ${MAX_SINGLE_MB}MB（当前 ${sizeMb.toFixed(1)}MB），请裁剪或降低分辨率后重试`
            : `单张试卷图片不能超过 ${MAX_SINGLE_MB}MB（当前 ${sizeMb.toFixed(1)}MB）`
        );
        continue;
      }
      if (uploadSize > 0 && totalBytes + uploadSize > MAX_TOTAL_MB * 1024 * 1024) {
        showError(`试卷图片总大小不能超过 ${MAX_TOTAL_MB}MB`);
        continue;
      }
      totalBytes += uploadSize;
      // 卷面页序：已有张数 + 本次已接受张数 + 1 = 该图在列表中的 1-based 下标。
      // 删除会让列表变短，removeImage 会重排已有序号，因此这里得到的值始终等于当前列表位置；
      // 上传失败重试沿用 img.pageNo 原值（2026-11-17，隐性约定 #38）。
      accepted.push({
        path: uploadPath,
        size: uploadSize,
        pageNo: images.value.length + accepted.length + 1,
        status: 'pending',
        error: ''
      });
    }
    images.value = [...images.value, ...accepted];
  } finally {
    processing.value = false;
  }
}

/**
 * 压缩单张图片；失败/不支持时回退原图（不阻塞上传）。
 *
 * 为什么不再统一 quality 60（t20 真实图验证结论）：
 * 试卷照片的字号本身很小（尤其选择题选项小字），过度压缩会让笔画边缘糊掉，
 * 视觉模型读不出选项文字，直接影响判分正确率（实测 500×684/53KB 的图压到 60 后
 * 6 道选择题有 4 道读不出正确答案）。清晰度是前端可控项，所以策略改为：
 * - ≤800KB：**完全不压**，直接用 chooseImage 返回的原图，避免二次 JPEG 损失；
 * - >800KB：只以 quality 85 压缩，且**不做 resize**（保持原分辨率，保住小字细节）；
 * 体积由后端 9.5MB/30MB 上限与下方预检兜底，不靠"压狠一点"来控制。
 * 后续如需改小 quality，必须先跑真实试卷图验证选项文字是否仍可识别。
 */
function compressImage(src) {
  if (typeof uni.compressImage !== 'function') return Promise.resolve(src);
  return new Promise((resolve) => {
    uni.compressImage({
      src,
      quality: COMPRESS_QUALITY,
      success: (res) => resolve(res.tempFilePath || src),
      fail: () => resolve(src)
    });
  });
}

/**
 * 用 getFileInfo 拿本地文件大小（字节）；失败时回退到 fallbackSize（chooseImage 的 tempFiles.size），
 * 两者都拿不到才返回 0（跳过大小校验，由后端 1184/1185 兜底）
 */
function getFileSize(filePath, fallbackSize) {
  return new Promise((resolve) => {
    const fallback = Number(fallbackSize) > 0 ? Number(fallbackSize) : 0;
    if (typeof uni.getFileInfo !== 'function') {
      resolve(fallback);
      return;
    }
    uni.getFileInfo({
      filePath,
      success: (res) => {
        const size = Number(res.size) || 0;
        resolve(size > 0 ? size : fallback);
      },
      fail: () => resolve(fallback)
    });
  });
}

/**
 * 清晰度预检（2026-11-17 exam_parse_reliability）：长边 ≥ EXAM_MIN_LONG_SIDE 且短边
 * ≥ EXAM_MIN_SHORT_SIDE；低于门槛的图后端会以 1193 拒绝，选图阶段就拦下并给可操作提示。
 *
 * 取的是**最终要上传的那份文件**（压缩后路径）的尺寸——压缩只调 quality、不做 resize，
 * 与原图分辨率一致。尺寸读不到（getImageInfo 不可用 / 宽高为 0）时放行，交后端 1193 兜底：
 * 不能因为前端能力缺失把用户明确选择的图误拦掉。
 *
 * @returns {Promise<boolean>} true = 达标（或无法判定，放行）；false = 已提示并应跳过该张
 */
function checkImageResolution(filePath) {
  return new Promise((resolve) => {
    if (typeof uni.getImageInfo !== 'function') {
      resolve(true);
      return;
    }
    uni.getImageInfo({
      src: filePath,
      success: (res) => {
        const width = Number(res.width) || 0;
        const height = Number(res.height) || 0;
        if (width <= 0 || height <= 0) {
          resolve(true);
          return;
        }
        const longSide = Math.max(width, height);
        const shortSide = Math.min(width, height);
        if (longSide >= EXAM_MIN_LONG_SIDE && shortSide >= EXAM_MIN_SHORT_SIDE) {
          resolve(true);
          return;
        }
        showError(
          `图片分辨率过低（${width}x${height}），请上传原图或重新拍摄（长边≥${EXAM_MIN_LONG_SIDE}px、短边≥${EXAM_MIN_SHORT_SIDE}px）`
        );
        resolve(false);
      },
      fail: () => resolve(true)
    });
  });
}

// ========== 上传主流程 ==========
async function handleStartUpload() {
  if (!canStart.value) return;
  const subject = appStore.subjectList[subjectIndex.value];
  if (!subject) {
    showError('请先选择科目');
    return;
  }
  if (!examForm.value.examDate) {
    showError('请选择考试日期');
    return;
  }
  if (images.value.length === 0) {
    showError('请至少选择 1 张试卷图片');
    return;
  }
  if (images.value.length > MAX_IMAGES) {
    showError(`最多上传 ${MAX_IMAGES} 张试卷图片`);
    return;
  }

  // 1. 建上传会话（已有 paperId 时续传，不重复建）
  if (!paperId.value) {
    try {
      const created = await createPaper({
        subjectId: subject.subjectId,
        examDate: examForm.value.examDate,
        paperType: examForm.value.paperType,
        imageCount: images.value.length
      });
      paperId.value = created?.paperId || null;
      // 新会话：清掉上一会话遗留的自动触发任务 ID（F1 防御，正常流不会命中）
      autoTriggeredTaskId.value = null;
    } catch (e) {
      // 1131：同科目同一天已有试卷（已完成/解析中）。不能重传，给出可操作入口，
      // 避免只弹一句"只能上传一份"然后卡在原地（2026-11-12 联调）。
      if (e && e.code === 1131) {
        offerExamListAfterDuplicate();
        return;
      }
      // request.ts 已 toast 后端文案（1191 类型非法等）
      console.warn('[ExamUpload] 建上传会话失败:', e.message);
      return;
    }
    if (!paperId.value) {
      showError('创建上传会话失败，请重试');
      return;
    }
  }

  // 2. 一次性上传：所有待上传图片同时发出请求（并发 = 张数）
  await uploadPendingImages();

  // 3. 全部成功才自动提交解析；有失败则等用户选择"重试该张"或"少传几张也解析"
  if (failedImages.value.length === 0 && uploadingCount.value > 0) {
    await submitAndPoll();
  }
}

/**
 * 一次性上传所有待上传/失败的图片：**全部并行**（并发 = 队列长度，最多 8）
 *
 * 为什么不再限制并发 2：用户选择多张后期待"一次性上传"，串行分批发会让 8 张图排队 4 轮，
 * 单张 2–4MB 时体感明显；微信 uploadFile 并发上限 10，8 张同时上传是安全区间。
 * 失败仍是"每张独立"的：一张失败不影响其它张，可单独重试。
 */
async function uploadPendingImages() {
  const queue = pendingImages.value.slice();
  if (queue.length === 0) return;

  uploading.value = true;
  try {
    let cursor = 0;
    const worker = async () => {
      while (cursor < queue.length) {
        const img = queue[cursor];
        cursor += 1;
        await uploadOne(img);
      }
    };
    const workers = [];
    for (let i = 0; i < Math.min(UPLOAD_CONCURRENCY, queue.length); i += 1) {
      workers.push(worker());
    }
    await Promise.all(workers);
  } finally {
    uploading.value = false;
  }

  if (failedImages.value.length > 0) {
    showError(`${failedImages.value.length} 张上传失败，可重试该张或直接提交已上传的图片`);
  } else if (uploadingCount.value > 0) {
    showSuccess('图片上传完成');
  }
}

async function uploadOne(img) {
  img.status = 'uploading';
  img.error = '';
  try {
    // img.pageNo 在加入列表时确定、之后不重排；失败重试走同一路径，自动沿用原值。
    const res = await uploadPaperImage(paperId.value, img.path, img.pageNo);
    img.status = 'done';
    // F1 修复 ③：满 8 张时后端自动触发解析并在响应里带回 taskId——记录下来，
    // 提交阶段优先用任务轮询（task 完成发生在 TX-B 判分结算之后，状态可信）。
    // 并行上传中只有"打到第 8 张"的那次响应会带 taskId（后端 JSON_LENGTH 守卫保证）。
    if (res && res.taskId) {
      autoTriggeredTaskId.value = Number(res.taskId) || null;
    }
  } catch (e) {
    img.status = 'failed';
    img.error = e.message || '上传失败';
  }
}

/** 单张失败重试：不计入已上传数，重试成功后若全部成功则自动提交 */
async function retryFailedImages() {
  if (uploading.value || !paperId.value) return;
  await uploadPendingImages();
  if (failedImages.value.length === 0 && uploadingCount.value > 0) {
    await submitAndPoll();
  }
}

/** 少传几张也解析：uploadedCount ≥1 即可 submit（design §2.2 允许 uploadedCount < imageCount） */
async function submitWithUploaded() {
  if (uploading.value || submitting.value) return;
  if (uploadingCount.value === 0) {
    showError('请先上传至少 1 张试卷图片再提交解析');
    return;
  }
  await submitAndPoll();
}

// ========== 提交 + 轮询 ==========
async function submitAndPoll() {
  if (submitting.value || !paperId.value) return;
  submitting.value = true;
  parseError.value = '';
  /**
   * 满 8 张时后端会在最后一张追加成功后**自动触发解析**（design §2.3 兜底路径）。
   * 一次性上传 8 张是常见场景，此时再显式 submit 必然返回 1186（会话已不是 uploading）。
   *
   * F1 修复 ③：第 8 张图片响应带回的 taskId 此时已在 {@link autoTriggeredTaskId}——
   * 优先直接接管**任务轮询**（task 完成发生在 TX-B 判分结算之后，状态可信），
   * 不再先按试卷详情探状态（详情 status=completed 在判分前就会出现，会过早触发跳转）。
   */
  const autoTriggered = uploadingCount.value >= MAX_IMAGES;
  try {
    if (autoTriggeredTaskId.value) {
      taskId.value = autoTriggeredTaskId.value;
      taskStatus.value = 'pending';
      startPolling(taskId.value);
      showSuccess('已提交解析');
      return;
    }
    if (autoTriggered && (await recoverByDetailStatus())) {
      showSuccess('已提交解析');
      return;
    }
    const res = await submitPaper(paperId.value, autoTriggered ? { showError: false } : {});
    taskId.value = res?.taskId || null;
    taskStatus.value = res?.status || 'pending';
    showSuccess('已提交解析');
    if (taskId.value) {
      startPolling(taskId.value);
    } else {
      // 极端情况：submit 未返回 taskId → 退回按详情 status 轮询
      startPaperPolling();
    }
  } catch (e) {
    // 关键兜底：design §2.3 允许"上传数达到声明张数时后端自动触发解析"，
    // 此时显式 submit 会返回 1186（已提交）。若真如此，用详情接口探一次状态继续轮询，
    // 不能把用户卡在失败态（request.ts 已 toast 后端文案）。
    // F1：有自动触发的 taskId 时优先接管任务轮询（含"本地 3 张 + 历史会话 5 张 = 8 张"
    // 的复用场景——autoTriggered 按本地张数判断为 false，但后端已自动触发）。
    if (autoTriggeredTaskId.value) {
      taskId.value = autoTriggeredTaskId.value;
      taskStatus.value = 'pending';
      startPolling(taskId.value);
      return;
    }
    const recovered = await recoverByDetailStatus();
    if (!recovered) parseError.value = e.message || '提交解析失败，请重试';
  } finally {
    submitting.value = false;
  }
}

/** 提交/重试失败后用详情接口兜底探测状态；已进入解析则接管轮询 */
async function recoverByDetailStatus() {
  try {
    const detail = await getPaperDetail(paperId.value);
    const status = detail?.status;
    if (status === 'completed') {
      taskStatus.value = 'completed';
      onParseCompleted();
      return true;
    }
    if (status === 'pending' || status === 'processing') {
      taskStatus.value = status;
      startPaperPolling();
      return true;
    }
  } catch (e) {
    // 详情也查不到（1136 越权/不存在等）→ 交给调用方展示原始错误
  }
  return false;
}

/** 按解析任务轮询进度（design D10：上传页用 /tasks/{taskId}） */
function startPolling(id) {
  taskId.value = id;
  beginPoll(() => getPaperTask(id));
}

/** 兜底：按试卷详情 status 轮询（拿不到 taskId 时使用，不改变主路径） */
function startPaperPolling() {
  beginPoll(() => getPaperDetail(paperId.value));
}

function beginPoll(fetcher) {
  stopPolling();
  pollActive = true;
  pollTimes = 0;
  const tick = async () => {
    if (!pollActive) return;
    pollTimes += 1;
    try {
      const data = await fetcher();
      if (!pollActive) return;
      if (data && data.ocrRetryCount != null) {
        ocrRetryCount.value = Number(data.ocrRetryCount) || 0;
      }
      const status = data?.status || '';
      if (status) taskStatus.value = status;
      if (status === 'completed') {
        stopPolling();
        onParseCompleted();
        return;
      }
      if (status === 'failed') {
        stopPolling();
        parseError.value = data?.errorMessage || '试卷解析失败，请重试';
        return;
      }
      if (pollTimes >= POLL_MAX_TIMES) {
        stopPolling();
        parseError.value = '解析超时，请稍后在「历史试卷」中查看结果';
        return;
      }
    } catch (e) {
      // 网络抖动：继续轮询，超过上限次数则放弃（定时器在 onUnload 清理）
      if (pollTimes >= POLL_MAX_TIMES) {
        stopPolling();
        parseError.value = '解析进度查询失败，请稍后在「历史试卷」中查看结果';
        return;
      }
    }
    if (pollActive) pollTimer = setTimeout(tick, POLL_INTERVAL);
  };
  pollTimer = setTimeout(tick, POLL_INTERVAL);
}

function stopPolling() {
  pollActive = false;
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

/** 解析完成：用 redirectTo 替换上传页，返回时不会回到已提交的上传页 */
function onParseCompleted() {
  showSuccess('试卷解析完成');
  if (jumpTimer) clearTimeout(jumpTimer);
  /**
   * F1 修复 ①（双保险第一层）：from=diagnosis 先等判分（grade_status=graded）再跳诊断页。
   * 试卷 status=completed 只代表题目落库（TX-A），判分/错题/掌握度在 TX-B；
   * 未判分就进诊断页会长期显示 0/0、正确率 —、错题数 0。
   * 普通入口保持原行为（直接进详情，详情页自有展示与刷新，不受判分窗口影响）。
   */
  if (fromDiagnosis.value) {
    waitingGrade.value = true;
    waitForGradeThenJump();
    return;
  }
  jumpTimer = setTimeout(() => {
    jumpTimer = null;
    uni.redirectTo({
      url: `/pages/exam-detail/ExamDetail?paperId=${paperId.value}`,
      fail: () => uni.navigateTo({ url: `/pages/exam-list/ExamList` })
    });
  }, AUTO_JUMP_DELAY);
}

/** 跳诊断结果页（from=diagnosis 专用，redirectTo 替换本页） */
function scheduleDiagnosisJump() {
  if (jumpTimer) clearTimeout(jumpTimer);
  jumpTimer = setTimeout(() => {
    jumpTimer = null;
    uni.redirectTo({
      url: `/pages/exam-diagnosis/ExamDiagnosis?paperId=${paperId.value}`,
      fail: () => uni.navigateTo({ url: `/pages/exam-list/ExamList` })
    });
  }, AUTO_JUMP_DELAY);
}

/**
 * F1 修复 ①：轮询 grade_status，graded 后跳诊断页（2s 一次，上限 POLL_MAX_TIMES≈5 分钟）。
 * - 首次立即探测：任务轮询路径（taskId）的 task 完成即已 graded，通常无需等待；
 * - 判分阶段失败（settle 异常 → 后端把试卷置 failed）：停止等待，走既有失败态（重新解析）；
 * - 超时 / 网络持续失败：**不跳诊断页**（此时很可能仍是 ungraded，进去会读到未判分数据），
 *   展示「继续等待判分 / 去历史试卷」出口；诊断页自身的 ungraded 轮询仍作为直接进入的兜底。
 */
function waitForGradeThenJump() {
  stopGradePoll();
  gradeTimedOut.value = false;
  gradePollActive = true;
  gradePollTimes = 0;
  const tick = async () => {
    if (!gradePollActive) return;
    gradePollTimes += 1;
    let graded = false;
    try {
      const detail = await getPaperDetail(paperId.value);
      if (!gradePollActive) return;
      if (detail?.status === 'failed') {
        // 判分阶段失败：试卷被后端置为 failed，不能带着未判分数据进诊断页
        stopGradePoll();
        waitingGrade.value = false;
        taskStatus.value = 'failed';
        parseError.value = '试卷判分失败，请重新解析';
        return;
      }
      graded = detail?.gradeStatus === 'graded';
    } catch (e) {
      // 网络抖动：继续轮询，超限后按超时处理
    }
    if (graded) {
      stopGradePoll();
      waitingGrade.value = false;
      scheduleDiagnosisJump();
      return;
    }
    if (gradePollTimes >= POLL_MAX_TIMES) {
      // F1：超时仍可能 ungraded——不自动跳诊断页（避免读到未判分数据），
      // 给出「继续等待判分 / 去历史试卷」出口；仍受 5 分钟上限约束。
      stopGradePoll();
      waitingGrade.value = false;
      gradeTimedOut.value = true;
      return;
    }
    if (gradePollActive) gradeTimer = setTimeout(tick, POLL_INTERVAL);
  };
  tick(); // 立即先探一次（任务轮询路径通常已 graded，无需多等 2s）
}

function stopGradePoll() {
  gradePollActive = false;
  if (gradeTimer) {
    clearTimeout(gradeTimer);
    gradeTimer = null;
  }
}

/**
 * F1：判分等待超时后的主动重试（用户点击「继续等待判分」）——
 * 重新开始 2s 轮询（仍 5 分钟上限），仍然只在 graded 后才跳诊断页。
 */
function retryWaitGrade() {
  if (waitingGrade.value) return;
  waitingGrade.value = true;
  waitForGradeThenJump();
}

/** 判分超时出口：去历史试卷（沿用上传页既有兜底链，失败退回「我的」） */
function goExamList() {
  uni.redirectTo({
    url: '/pages/exam-list/ExamList',
    fail: () => uni.switchTab({ url: '/pages/profile/Profile' })
  });
}

/** 重新解析（整卷重试，最多 2 次） */
async function handleRetryParse() {
  if (submitting.value || !paperId.value) return;
  if (!canRetryParse.value) {
    showError(`该试卷最多重试 ${MAX_OCR_RETRY} 次，请稍后在「历史试卷」查看`);
    return;
  }
  submitting.value = true;
  parseError.value = '';
  try {
    const res = await retryPaper(paperId.value);
    taskId.value = res?.taskId || null;
    taskStatus.value = res?.status || 'pending';
    ocrRetryCount.value = Number(res?.ocrRetryCount) || ocrRetryCount.value + 1;
    if (taskId.value) startPolling(taskId.value);
  } catch (e) {
    // 1190（超过 2 次）/ 1130（不属于本人）等由 request.ts toast
    const recovered = await recoverByDetailStatus();
    if (!recovered) parseError.value = e.message || '重新解析失败';
  } finally {
    submitting.value = false;
  }
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background-color: $bg-color;
  padding-bottom: 160rpx;
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

/* 表单 */
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
.form-picker {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  font-size: $font-md;
  color: $text-primary;
}
.picker-arrow {
  font-size: $font-xs;
  color: $text-secondary;
}

/* 缩略图 */
.thumb-grid {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-sm;
}
.thumb {
  position: relative;
  width: 190rpx;
  height: 190rpx;
  border-radius: $radius-md;
  overflow: hidden;
  background-color: #f5f5f5;
}
.thumb-img {
  width: 100%;
  height: 100%;
}
.thumb-badge {
  position: absolute;
  left: 0;
  bottom: 0;
  width: 100%;
  text-align: center;
  font-size: $font-xs;
  color: #fff;
  padding: 4rpx 0;
  background-color: rgba(0, 0, 0, 0.45);
}
.badge-pending { background-color: rgba(144, 147, 153, 0.85); }
.badge-uploading { background-color: rgba(64, 158, 255, 0.85); }
.badge-done { background-color: rgba(103, 194, 58, 0.85); }
.badge-failed { background-color: rgba(245, 108, 108, 0.85); }
.thumb-remove {
  position: absolute;
  top: 0;
  right: 0;
  width: 40rpx;
  height: 40rpx;
  line-height: 40rpx;
  text-align: center;
  font-size: $font-sm;
  color: #fff;
  background-color: rgba(0, 0, 0, 0.5);
}
.thumb-remove--disabled {
  background-color: rgba(0, 0, 0, 0.25);
}
.thumb-add {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2rpx dashed $border-color;
  background-color: #fafafa;
}
.thumb-add-icon {
  font-size: 56rpx;
  color: $text-placeholder;
}

.size-tip {
  display: block;
  font-size: $font-xs;
  color: $text-secondary;
  line-height: 1.6;
  margin-top: $spacing-sm;
}

/* 进度 */
.step-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $spacing-sm 0;
  border-bottom: 1rpx solid $border-light;
}
.step-label {
  font-size: $font-sm;
  color: $text-secondary;
}
.step-value {
  font-size: $font-sm;
  color: $text-primary;
  font-weight: 500;
}
.progress-text {
  font-size: $font-sm;
  color: $primary-color;
}
.progress-tip {
  margin-top: $spacing-sm;
  font-size: $font-xs;
  color: $text-secondary;
  line-height: 1.6;
}

/* 错误提示 */
.error-box {
  margin-top: $spacing-md;
  padding: $spacing-md;
  border-radius: $radius-md;
  background-color: #fef0f0;
}
.error-text {
  font-size: $font-sm;
  color: $danger-color;
  line-height: 1.6;
}
.error-hint {
  font-size: $font-xs;
  color: $text-secondary;
}

/* F1：判分等待超时（不自动跳诊断页，给继续等待 / 历史试卷出口） */
.grade-timeout-box {
  margin-top: $spacing-md;
  padding: $spacing-md;
  border-radius: $radius-md;
  background-color: #fdf6ec;
}
.grade-timeout-text {
  font-size: $font-sm;
  color: $text-regular;
  line-height: 1.6;
}

/* 操作按钮 */
.row-actions {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  margin-top: $spacing-md;
}
.btn-secondary {
  flex: 1;
  background-color: #f5f5f5;
  color: $text-primary;
  font-size: $font-sm;
  border-radius: $radius-md;
  padding: $spacing-sm $spacing-md;
}
.btn-secondary[disabled] {
  opacity: 0.4;
}

/* 底部主操作 */
.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: $spacing-md $spacing-lg;
  background-color: $bg-white;
  box-shadow: 0 -2rpx 8rpx rgba(0, 0, 0, 0.05);
}
.btn-primary {
  background-color: $primary-color;
  color: #fff;
  font-size: $font-md;
  border-radius: $radius-md;
}
.btn-primary[disabled] {
  opacity: 0.4;
}
</style>
