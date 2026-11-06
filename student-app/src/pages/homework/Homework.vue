<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <text class="nav-title">每日作业</text>
      </view>
    </view>

    <!-- 日期选择器 -->
    <view class="date-bar">
      <picker mode="date" :value="appStore.currentDate" @change="onDateChange">
        <view class="date-display">
          <text class="date-text">{{ appStore.currentDate }}</text>
          <text class="date-arrow">📅 切换日期</text>
        </view>
      </picker>
    </view>

    <!-- 按科目分组的作业列表 -->
    <view class="homework-list" v-if="groupedHomework.length > 0">
      <view
        class="subject-card"
        :class="{ 'card-readonly': group.isReadOnly }"
        v-for="group in groupedHomework"
        :key="group.subjectName"
      >
        <view 
          class="card-main" 
          @click="goToSubject(group)"
        >
          <text class="card-subject-name">{{ group.subjectName }}</text>
          <text class="card-count">{{ group.answeredCount }}/{{ group.totalCount }}</text>
          <text class="card-arrow" v-if="!group.isReadOnly">▶</text>
          <text class="card-lock" v-else>🔒</text>
        </view>
        <button
          class="btn-grade"
          :class="{ 'btn-disabled': group.isReadOnly || group.answeredCount === 0 }"
          :disabled="grading || group.isReadOnly || group.answeredCount === 0"
          @click.stop="doGrade(group)"
        >
          {{ grading ? '批改中...' : (group.isReadOnly ? '只读' : '批改') }}
        </button>
      </view>
    </view>

    <!-- 空状态：生成作业按钮 -->
    <view class="generate-area" v-if="!loading && homeworkList.length === 0 && activeGoals.length > 0 && canGenerateHomework">
      <EmptyState text="当日暂无作业" />
      <button class="btn-generate" @click="generateToday" :disabled="generating">
        {{ generating ? '生成中...' : '生成今日作业' }}
      </button>
    </view>

    <!-- 加载中 -->
    <LoadingState :loading="loading" text="加载中..." />
  </view>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { useAppStore } from '@/stores/app';
import { useAuthGuard } from '@/utils/useAuthGuard.js';
import { getGoalList } from '@/api/goal.js';
import { getHomeworkByDate, generateHomework, gradeHomework } from '@/api/homework.js';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

const appStore = useAppStore();

// ========== 状态 ==========
const statusBarHeight = ref(0);
const activeGoals = ref([]); // 活跃目标列表
const homeworkList = ref([]);
const loading = ref(false);
const generating = ref(false);
const grading = ref(false);

// ========== 计算属性 ==========
// 按科目分组（包含只读状态）
const groupedHomework = computed(() => {
  const groups = {};
  for (const item of homeworkList.value) {
    const name = item.subjectName || '未知科目';
    if (!groups[name]) groups[name] = [];
    groups[name].push(item);
  }
  return Object.entries(groups).map(([subjectName, items]) => {
    const subjectId = items[0]?.subjectId;
    // 判断该科目是否有活跃目标
    const hasActiveGoal = activeGoals.value.some(g => g.subjectId === subjectId);
    return {
      subjectName,
      subjectId,
      items,
      totalCount: items.length,
      answeredCount: items.filter(i => i.studentAnswer != null).length,
      isReadOnly: !hasActiveGoal // 没有活跃目标则为只读模式
    };
  });
});

// 判断当前日期是否是今天或之后（可以生成作业）
const canGenerateHomework = computed(() => {
  const today = new Date();
  const selectedDate = new Date(appStore.currentDate);
  return selectedDate >= new Date(today.getFullYear(), today.getMonth(), today.getDate());
});

// ========== 生命周期 ==========
onShow(() => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;
  fetchGoals();
});

// 监听日期变化，重新加载作业
watch(
  () => appStore.currentDate,
  () => {
    if (activeGoals.value.length > 0) {
      fetchHomework();
    }
  }
);

// ========== 数据加载 ==========
async function fetchGoals() {
  loading.value = true;
  try {
    const result = await getGoalList(1, 50);
    activeGoals.value = (result?.records || []).filter(g => g.status === 'active');
    console.log('[Homework] fetchGoals - all goals:', result?.records?.length);
    console.log('[Homework] fetchGoals - active goals:', activeGoals.value.length);
    console.log('[Homework] fetchGoals - subjectList:', appStore.subjectList.length);
    // 无论是否有活跃目标，都查询作业
    if (appStore.subjectList && appStore.subjectList.length > 0) {
      await fetchHomework();
    }
  } catch (e) {
    console.warn('[Homework] 获取活跃目标失败:', e.message);
    activeGoals.value = [];
  } finally {
    loading.value = false;
  }
}

async function fetchHomework() {
  loading.value = true;
  try {
    const allItems = [];
    // 遍历所有科目，查询每个科目的作业（包括已结束目标的作业）
    for (const subject of appStore.subjectList) {
      try {
        const items = await getHomeworkByDate(subject.subjectId, appStore.currentDate) || [];
        items.forEach(item => { 
          item.subjectName = subject.name; 
          item.subjectId = subject.subjectId; 
        });
        allItems.push(...items);
      } catch (e) {
        console.warn(`[Homework] 获取${subject.name}作业失败:`, e.message);
      }
    }
    homeworkList.value = allItems;
  } finally {
    loading.value = false;
  }
}

// ========== 生成作业 ==========
async function generateToday() {
  if (generating.value || activeGoals.value.length === 0) return;
  generating.value = true;
  try {
    for (const goal of activeGoals.value) {
      try {
        await generateHomework(goal.subjectId, appStore.currentDate);
      } catch (e) {
        console.warn(`[Homework] ${goal.subjectName}作业生成跳过:`, e.message);
      }
    }
    await fetchHomework();
  } finally {
    generating.value = false;
  }
}

// ========== 事件 ==========
function onDateChange(e) {
  appStore.setDate(e.detail.value);
}

// 跳转到答题页面（只读模式可以查看但不能提交）
function goToSubject(group) {
  const subjectName = group.subjectName;
  const subjectId = group.subjectId;
  const items = group.items;
  uni.navigateTo({
    url: `/pages/homework-detail/HomeworkDetail?subjectName=${encodeURIComponent(subjectName)}&subjectId=${subjectId}&items=${encodeURIComponent(JSON.stringify(items))}&readOnly=${group.isReadOnly}`
  });
}

// 批改作业（只读模式不可批改）
async function doGrade(group) {
  if (grading.value || !group.subjectId || group.isReadOnly) return;
  grading.value = true;
  try {
    const result = await gradeHomework(group.subjectId, appStore.currentDate);
    uni.showModal({
      title: '批改完成',
      content: `共 ${result.totalQuestions} 题，正确 ${result.correctCount} 题，正确率 ${(result.accuracy * 100).toFixed(1)}%`,
      showCancel: false,
      success: () => { fetchHomework(); }
    });
  } catch (e) {
    console.warn('[Homework] 批改失败:', e.message);
    uni.showToast({ title: e.message || '批改失败', icon: 'none' });
  } finally {
    grading.value = false;
  }
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background-color: $bg-color;
  padding-bottom: 120rpx;
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
  justify-content: center;
}
.nav-title {
  font-size: $font-lg;
  font-weight: bold;
  color: $text-primary;
}

/* 日期栏 */
.date-bar {
  padding: $spacing-md $spacing-lg;
  background-color: $bg-white;
  margin-bottom: 2rpx;
}
.date-display {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.date-text {
  font-size: $font-md;
  color: $text-primary;
  font-weight: 500;
}
.date-arrow {
  font-size: $font-sm;
  color: $text-secondary;
}

/* 作业列表 */
.homework-list {
  padding: $spacing-md $spacing-lg;
}

/* 科目卡片 */
.subject-card {
  display: flex;
  align-items: center;
  background-color: $bg-white;
  border-radius: $radius-md;
  padding: $spacing-lg;
  margin-bottom: $spacing-md;
  box-shadow: $shadow-sm;
}
.card-readonly {
  opacity: 0.7; // 只读模式降低透明度
}
.card-main {
  display: flex;
  align-items: center;
  flex: 1;
  cursor: pointer; // 始终允许点击
}
.card-subject-name {
  font-size: $font-md;
  font-weight: bold;
  color: $text-primary;
  flex: 1;
}
.card-count {
  font-size: $font-sm;
  color: $text-secondary;
  margin-right: $spacing-sm;
}
.card-arrow {
  font-size: $font-xs;
  color: $text-secondary;
}
.card-lock {
  font-size: $font-sm;
  color: $text-secondary;
}
.btn-grade {
  background-color: $primary-color;
  color: #fff;
  font-size: $font-sm;
  border-radius: $radius-md;
  padding: $spacing-sm $spacing-md;
  min-width: 120rpx;
  line-height: 1.6;
  margin-left: $spacing-sm;
}
.btn-grade[disabled] {
  opacity: 0.4;
}
.btn-disabled {
  background-color: $text-secondary; // 禁用状态使用灰色
}

/* 生成作业区域 */
.generate-area {
  padding: $spacing-xl $spacing-lg;
}
.btn-generate {
  margin-top: $spacing-lg;
  background-color: $primary-color;
  color: #fff;
  font-size: $font-md;
  border-radius: $radius-lg;
  padding: $spacing-md 0;
}
</style>
