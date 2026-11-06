/**
 * 全局业务状态 Store
 *
 * 管理跨页面共享的筛选参数和缓存：
 * - currentSubjectId：当前选中科目
 * - currentDate：当前查看日期（YYYY-MM-DD）
 * - subjectList：科目列表缓存
 */
import { defineStore } from 'pinia';
import { ref } from 'vue';
import { get } from '@/utils/request.js';
import { formatDate } from '@/utils/date.js';

export const useAppStore = defineStore('app', () => {
  // ========== State ==========
  const currentSubjectId = ref(null);
  const currentDate = ref(formatDate(new Date(), 'YYYY-MM-DD'));
  const subjectList = ref([]);

  // ========== Actions ==========

  /**
   * 初始化：加载科目列表，默认选中第一个
   * 在 App.vue onLaunch 中调用，登录态恢复后执行
   */
  async function init() {
    try {
      const data = await get('/student/subject');
      subjectList.value = data || [];
      // 自动选中第一个科目
      if (subjectList.value.length > 0 && currentSubjectId.value === null) {
        currentSubjectId.value = subjectList.value[0].subjectId;
      }
    } catch (e) {
      // 加载失败不阻塞登录流程，进入首页后由页面重试
      console.warn('[appStore.init] 科目列表加载失败:', e.message);
    }
  }

  /**
   * 切换当前科目
   * @param {number} subjectId
   */
  function setSubject(subjectId) {
    currentSubjectId.value = subjectId;
  }

  /**
   * 切换当前日期
   * @param {string} dateStr YYYY-MM-DD
   */
  function setDate(dateStr) {
    currentDate.value = dateStr;
  }

  return {
    currentSubjectId,
    currentDate,
    subjectList,
    init,
    setSubject,
    setDate
  };
});
