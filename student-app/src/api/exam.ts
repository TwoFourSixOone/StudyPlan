/**
 * 试卷上传 / 解析 / 历史回看 API（契约来源：openspec/changes/2026-11-11_exam_paper_upload_llm/design.md §2.2、§10）
 *
 * 隐性约定 #18：新接口函数**一律并入本文件**（已有 `api/exam.ts` 模块），
 * 不新建 `api/exam-*.ts` —— mp-weixin 下"新增"模块可能在运行期不注册，
 * 只有并入既有模块才稳定。
 * 隐性约定 #19：请求走 `utils/request.ts` / `utils/upload.ts` 的完整基地址，
 * 本文件不出现任何硬编码 baseURL。
 *
 * 后端契约（统一前缀 /api，由 utils 拼接）：
 * - POST /student/exam/papers                      建上传会话
 * - POST /student/exam/papers/{paperId}/images     逐张追加图片（multipart 字段名 file）
 * - POST /student/exam/papers/{paperId}/submit     提交解析（建 task 并入队）
 * - POST /student/exam/papers/{paperId}/retry      整卷重试（仅 failed 且 ocr_retry_count < 2）
 * - GET  /student/exam/tasks/{taskId}              解析任务进度（上传页轮询）
 * - GET  /student/exam/papers                      历史试卷分页列表
 * - GET  /student/exam/papers/{paperId}            试卷详情（全部题目，唯一数据来源）
 *
 * 已下线的旧接口（proposal §5.1，不要恢复）：旧上传、旧任务进度、旧结果详情、旧结果列表、
 * 旧分析详情五条路径已全部收敛到本文件的 `/student/exam/papers*` 与 `/student/exam/tasks/{taskId}`。
 *
 * ⚠️ 类型字段名以**后端 VO 为准**（t24/t28 教训）：题数用 `questionCount`（列表/详情一致）、
 * 题型用 `questionType`（详情题目项），不要凭直觉写 `total*` 或 `*FormatType` 前缀；
 * 改字段前先对照 `src/main/java/com/jiang/vo/Exam*VO.java`。
 */
import { get, post } from '@/utils/request';
import { uploadWithAuth } from '@/utils/upload';

/** 单卷最多图片张数（后端 1182 兜底） */
export const EXAM_MAX_IMAGE_COUNT = 8;

/** 单张图片业务上限（MB，后端 1184 兜底，见 design §2.3） */
export const EXAM_MAX_SINGLE_IMAGE_MB = 9.5;

/** 单卷图片总大小上限（MB，后端 1185 兜底，见 design §2.3 / §11.3） */
export const EXAM_MAX_TOTAL_IMAGE_MB = 30;

/** 整卷解析最多重试次数（后端 1190 兜底，见 design §3.4） */
export const EXAM_MAX_OCR_RETRY = 2;

/** 试卷类型 code（design §2.4，与后端白名单一致，大小写敏感） */
export type ExamPaperType = 'midterm' | 'final' | 'monthly' | 'mock' | 'other';

/** 试卷类型 code → 中文（约定 #21：前端不得直接展示 code） */
export const EXAM_PAPER_TYPE_LABELS: Record<ExamPaperType, string> = {
  midterm: '期中考试',
  final: '期末考试',
  monthly: '月考',
  mock: '模拟考试',
  other: '其他'
};

/** 试卷类型选项（picker 用，顺序固定） */
export const EXAM_PAPER_TYPE_OPTIONS: Array<{ code: ExamPaperType; label: string }> = [
  { code: 'midterm', label: '期中考试' },
  { code: 'final', label: '期末考试' },
  { code: 'monthly', label: '月考' },
  { code: 'mock', label: '模拟考试' },
  { code: 'other', label: '其他' }
];

/** 试卷类型 code → 中文；非法/空值按默认 midterm 兜底（design §2.4 S13） */
export function paperTypeLabel(code?: string): string {
  const key = (code || '').toLowerCase();
  return EXAM_PAPER_TYPE_LABELS[key as ExamPaperType] || EXAM_PAPER_TYPE_LABELS.midterm;
}

/**
 * 试卷/任务状态 code → 中文（design §1.1 状态机）
 * uploading → 上传中；pending → 排队中；processing → 解析中；completed → 已完成；failed → 解析失败
 */
export function examStatusLabel(status?: string): string {
  const map: Record<string, string> = {
    uploading: '上传中',
    pending: '排队中',
    processing: '解析中',
    completed: '已完成',
    failed: '解析失败'
  };
  return map[status || ''] || '未知状态';
}

/** 建上传会话入参 */
export interface ExamPaperCreateParams {
  subjectId: number;
  /** 考试日期 yyyy-MM-dd */
  examDate: string;
  paperType: ExamPaperType;
  /** 本次声明的图片张数，1–8 */
  imageCount: number;
}

/** 建上传会话出参（后端 ExamUploadVO） */
export interface ExamPaperCreateVO {
  paperId: number;
  status: string;
  imageCount: number;
  /** 是否复用 30 分钟内的 uploading 会话（后端返回值，前端暂不消费） */
  reused?: boolean;
}

/** 追加单张图片出参（后端 ExamImageUploadVO） */
export interface ExamPaperImageVO {
  paperId: number;
  uploadedCount: number;
  status: string;
  /** 达到声明张数、后端自动触发解析时返回（否则为空） */
  taskId?: number;
}

/** 提交解析 / 重试解析出参 */
export interface ExamPaperSubmitVO {
  paperId: number;
  taskId: number;
  status: string;
  ocrRetryCount?: number;
}

/** 解析任务进度出参（design §2.2，旧 /task 已下线） */
export interface ExamTaskProgressVO {
  taskId: number;
  paperId?: number;
  status: string;
  ocrRetryCount?: number;
  attributionRetryCount?: number;
  startedAt?: string;
  completedAt?: string;
  /** ≤500 字脱敏错误信息，仅 failed 时有值 */
  errorMessage?: string;
}

/** 题目项出参（design §4.2；字段名与后端 ExamQuestionItemVO 一致） */
export interface ExamQuestionItemVO {
  questionId: number;
  questionIndex: number;
  /** choice | numeric | short | self（后端字段名 questionType） */
  questionType?: string;
  difficulty?: string;
  /** 题干原始文本（与 stem 同值，后端保留的兼容字段） */
  questionContent?: string;
  /** 题干（codec 解码） */
  stem?: string;
  /** 选项纯文本数组（label 由前端按序号渲染） */
  choices?: string[];
  /** 学生作答（独立列） */
  studentAnswer?: string;
  /** 正确答案（codec 解码；选择题为选项标签 A/B/C…） */
  correctAnswer?: string;
  /** 解析（codec 解码） */
  explanation?: string;
  /** null = 待人工判定 */
  isCorrect?: boolean | null;
  score?: number | null;
  fullScore?: number | null;
  /** 知识点 ID（后端为 String；待人工归因时为 null） */
  knowledgePointId?: string | null;
  knowledgePointName?: string;
  /** 归因状态，pending_manual_review = 待人工归因 */
  attributionStatus?: string;
  /** blank | unreadable | judged（后端推导） */
  answerState?: string;
}

/** 历史试卷列表项出参（design §10.1；字段名与后端 ExamPaperListItemVO 一致） */
export interface ExamPaperListItemVO {
  paperId: number;
  subjectId: number;
  subjectName?: string;
  examDate?: string;
  paperType?: string;
  /** 试卷类型中文名（后端已给，前端不得直接展示 code） */
  paperTypeName?: string;
  status?: string;
  gradeStatus?: string;
  /** 题目总数（后端字段名 questionCount） */
  questionCount?: number;
  correctCount?: number;
  judgedCount?: number;
  /** 1 题 1 分制：userScore = correctCount */
  userScore?: number;
  /** 1 题 1 分制：totalScore = judgedCount */
  totalScore?: number;
  createTime?: string;
}

/** 试卷详情出参（design §10.2；字段名与后端 ExamPaperDetailVO 一致） */
export interface ExamPaperDetailVO extends ExamPaperListItemVO {
  imageUrls?: string[];
  /** 图片张数（后端字段名 imageCount） */
  imageCount?: number;
  /** 部分图片未识别时的提示（design §3.1 降级） */
  imageWarning?: string;
  questions?: ExamQuestionItemVO[];
}

/** 分页结果（与后端 PageResult 对齐） */
export interface ExamPaperPageResult {
  records: ExamPaperListItemVO[];
  total: number;
  page?: number;
  size?: number;
}

/**
 * ① 建上传会话（POST /student/exam/papers）
 * 同学生 + 科目 + 日期已有非 uploading 试卷 → 后端 1131；30 分钟内的 uploading 会话会被复用。
 */
export function createPaper(params: ExamPaperCreateParams): Promise<ExamPaperCreateVO> {
  return post('/student/exam/papers', params);
}

/**
 * ② 逐张追加图片（POST /student/exam/papers/{paperId}/images）
 * 隐性约定 #19 + S5：multipart 字段名必须是 `file`（`uploadWithAuth` 默认是 `files`，此处显式覆盖）。
 * @param paperId 上传会话 ID
 * @param filePath 本地图片路径（uni.chooseImage / uni.compressImage 返回值）
 */
export function uploadPaperImage(paperId: number, filePath: string): Promise<ExamPaperImageVO> {
  return uploadWithAuth(`/student/exam/papers/${paperId}/images`, filePath, 'file');
}

/**
 * ③ 显式提交解析（POST /student/exam/papers/{paperId}/submit）
 * 上传成功张数可小于声明的 imageCount（"少传几张也解析"）。
 */
export function submitPaper(paperId: number): Promise<ExamPaperSubmitVO> {
  return post(`/student/exam/papers/${paperId}/submit`);
}

/**
 * ④ 整卷重试解析（POST /student/exam/papers/{paperId}/retry）
 * 仅 status=failed 且 ocr_retry_count < 2 允许（后端 1190 兜底）。
 */
export function retryPaper(paperId: number): Promise<ExamPaperSubmitVO> {
  return post(`/student/exam/papers/${paperId}/retry`);
}

/**
 * ⑤ 查询解析任务进度（GET /student/exam/tasks/{taskId}）
 * 上传页轮询用；详情页改为轮询详情接口的 status（design D10）。
 */
export function getPaperTask(taskId: number): Promise<ExamTaskProgressVO> {
  return get(`/student/exam/tasks/${taskId}`);
}

/**
 * ⑥ 历史试卷分页列表（GET /student/exam/papers）
 * page 从 1；size 上限 50（后端夹取）。列表不含 image_urls 大字段。
 */
export function getPapers(params: { page?: number; size?: number } = {}): Promise<ExamPaperPageResult> {
  return get('/student/exam/papers', {
    page: params.page || 1,
    size: params.size || 10
  });
}

/**
 * ⑦ 试卷详情（GET /student/exam/papers/{paperId}）
 * 详情页**只接收 paperId**，题目全部由本接口返回（禁止 URL 传 items）。
 */
export function getPaperDetail(paperId: number): Promise<ExamPaperDetailVO> {
  return get(`/student/exam/papers/${paperId}`);
}
