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
        <view class="thumb thumb-add" v-if="images.length < MAX_IMAGES && !formLocked" @click="chooseImages">
          <text class="thumb-add-icon">＋</text>
        </view>
      </view>

      <text class="size-tip">{{ SIZE_TIP }}</text>
      <text class="size-tip">一次最多 {{ MAX_IMAGES }} 张，可逐张删除；已上传的图片不能删除</text>

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
 * - 进入本页：`navigateTo`（从「我的 → 试卷上传」）；返回：`navigateBack`。
 * - 上传 → 结果的替换用 `redirectTo`；详情页内部切题不跳页。
 * - 页面卸载（onUnload）必须清空轮询定时器与自动跳转定时器，避免后台残留请求。
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
  EXAM_MAX_OCR_RETRY
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
const UPLOAD_CONCURRENCY = 2;
const POLL_INTERVAL = 2000;
const POLL_MAX_TIMES = 90; // ≈3 分钟（design §10.3）
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

// 图片列表：{ path, size, status: 'pending' | 'uploading' | 'done' | 'failed', error }
const images = ref([]);

// 上传 / 解析状态
const paperId = ref(null);
const taskId = ref(null);
const uploading = ref(false);
const submitting = ref(false);
const taskStatus = ref('');
const ocrRetryCount = ref(0);
const parseError = ref('');

// 定时器（onUnload 必须清理）
let pollTimer = null;
let pollActive = false;
let pollTimes = 0;
let jumpTimer = null;

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
  if (flowLocked.value) return false;
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
  if (uploading.value) return `上传中（${uploadingCount.value}/${images.value.length}）`;
  if (submitting.value) return '提交解析中...';
  if (taskStatus.value === 'pending' || taskStatus.value === 'processing') return '解析中，请稍候...';
  if (taskStatus.value === 'completed') return '解析完成，正在跳转...';
  if (taskStatus.value === 'failed') return '解析失败，请点「重新解析」';
  if (paperId.value) return '继续上传 / 提交解析';
  return '开始上传';
});

// ========== 生命周期 ==========
onLoad(() => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;
});

onUnload(() => {
  // 页面卸载：轮询与自动跳转定时器必须清空（页面栈规则）
  stopPolling();
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
}

/** 选图：count 只给剩余额度，超出即拦截提示（后端 1182 兜底） */
async function chooseImages() {
  if (flowLocked.value) return;
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
    accepted.push({ path: uploadPath, size: uploadSize, status: 'pending', error: '' });
  }
  images.value = [...images.value, ...accepted];
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
    } catch (e) {
      // request.ts 已 toast 后端文案（1131 重复上传 / 1191 类型非法等）
      console.warn('[ExamUpload] 建上传会话失败:', e.message);
      return;
    }
    if (!paperId.value) {
      showError('创建上传会话失败，请重试');
      return;
    }
  }

  // 2. 逐张上传（并发 2）
  await uploadPendingImages();

  // 3. 全部成功才自动提交解析；有失败则等用户选择"重试该张"或"少传几张也解析"
  if (failedImages.value.length === 0 && uploadingCount.value > 0) {
    await submitAndPoll();
  }
}

/** 并发 2 逐张上传所有待上传/失败的图片 */
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
    await uploadPaperImage(paperId.value, img.path);
    img.status = 'done';
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
  try {
    const res = await submitPaper(paperId.value);
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
  jumpTimer = setTimeout(() => {
    jumpTimer = null;
    uni.redirectTo({
      url: `/pages/exam-detail/ExamDetail?paperId=${paperId.value}`,
      fail: () => uni.navigateTo({ url: `/pages/exam-list/ExamList` })
    });
  }, AUTO_JUMP_DELAY);
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
