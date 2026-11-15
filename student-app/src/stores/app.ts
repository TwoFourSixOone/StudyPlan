/**
 * 全局业务状态 Store
 *
 * 管理跨页面共享的筛选参数和缓存：
 * - currentSubjectId：当前选中科目
 * - currentDate：当前查看日期（YYYY-MM-DD）
 * - subjectList：科目列表缓存
 * - goalPrefill：建目标表单预填（诊断结果页 → 我的页；tab 页无法用 URL 传参，走 store）
 */
import { defineStore } from 'pinia';
import { ref } from 'vue';
import { get } from '@/utils/request';
import { formatDate } from '@/utils/date';

/**
 * 科目信息类型
 */
interface Subject {
  subjectId: number;
  name: string;
  [key: string]: any;
}

/**
 * 建目标表单预填（新用户引导方案 A）
 *
 * 为什么不走 URL 参数：「我的」页是 tabBar 页，微信小程序的 uni.switchTab
 * 不支持 query 参数（会被直接丢弃），因此诊断页把预填科目放进全局 store，
 * 「我的」页 onShow 时消费一次后清空。
 */
export interface GoalPrefill {
  /** 预选科目 ID */
  subjectId: number;
  /** true = 来自诊断结果页（表单展示「建议周期 3–6 个月」提示） */
  fromDiagnosis?: boolean;
}

export const useAppStore = defineStore('app', () => {
  // ========== State ==========
  const currentSubjectId = ref<number | null>(null);
  const currentDate = ref<string>(formatDate(new Date(), 'YYYY-MM-DD'));
  const subjectList = ref<Subject[]>([]);
  const goalPrefill = ref<GoalPrefill | null>(null);

  // ========== Actions ==========

  /**
   * 初始化：加载科目列表，默认选中第一个
   * 在 App.vue onLaunch 中调用，登录态恢复后执行
   */
  async function init(): Promise<void> {
    try {
      const data: Subject[] = await get('/student/subject');
      subjectList.value = data || [];
      // 自动选中第一个科目
      if (subjectList.value.length > 0 && currentSubjectId.value === null) {
        currentSubjectId.value = subjectList.value[0].subjectId;
      }
    } catch (e: any) {
      // 加载失败不阻塞登录流程，进入首页后由页面重试
      console.warn('[appStore.init] 科目列表加载失败:', e.message);
    }
  }

  /**
   * 切换当前科目
   * @param subjectId 科目 ID
   */
  function setSubject(subjectId: number): void {
    currentSubjectId.value = subjectId;
  }

  /**
   * 切换当前日期
   * @param dateStr YYYY-MM-DD
   */
  function setDate(dateStr: string): void {
    currentDate.value = dateStr;
  }

  /**
   * 写入建目标预填（诊断页「根据这次诊断创建目标」时调用）
   * @param prefill 预填内容；null 表示清空
   */
  function setGoalPrefill(prefill: GoalPrefill | null): void {
    goalPrefill.value = prefill;
  }

  return {
    currentSubjectId,
    currentDate,
    subjectList,
    goalPrefill,
    init,
    setSubject,
    setDate,
    setGoalPrefill
  };
});
