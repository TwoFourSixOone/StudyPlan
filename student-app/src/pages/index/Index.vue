<template>
  <view class="page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <text class="nav-title">智学伴</text>
      </view>
    </view>

    <!-- 活跃目标列表 -->
    <view class="section-title" v-if="activeGoals.length > 0">我的学习目标</view>
    <template v-if="activeGoals.length > 0">
      <view
        class="goal-card"
        v-for="goal in activeGoals"
        :key="goal.goalId"
      >
        <view class="goal-header">
          <text class="goal-direction">{{ goal.direction || '学习目标' }}</text>
          <text class="goal-subject">{{ goal.subjectName }}</text>
        </view>
        <view class="goal-footer">
          <text class="goal-remaining">剩余 {{ calcRemainingDays(goal.endDate) }} 天</text>
          <text class="goal-end-date">至 {{ goal.endDate }}</text>
        </view>
      </view>
    </template>
    <view class="goal-card goal-card--empty" v-else-if="!goalLoading">
      <text class="goal-empty-text">暂无活跃目标，去个人中心设定</text>
    </view>

    <!-- 加载中 -->
    <LoadingState :loading="goalLoading" text="加载中..." />
  </view>
</template>

<script setup>
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { useAuthStore } from '@/stores/auth';
import { useAuthGuard } from '@/utils/useAuthGuard';
import { getGoalList } from '@/api/goal';
import LoadingState from '@/components/LoadingState.vue';

// ========== 登录态守卫 ==========
useAuthGuard();

const authStore = useAuthStore();

// ========== 状态 ==========
const statusBarHeight = ref(0);
const activeGoals = ref([]);
const goalLoading = ref(false);

// ========== 计算属性 ==========
function calcRemainingDays(endDate) {
  if (!endDate) return 0;
  const end = new Date(endDate);
  const now = new Date();
  const diff = Math.ceil((end - now) / (1000 * 60 * 60 * 24));
  return diff > 0 ? diff : 0;
}

// 页面显示时加载活跃目标
onShow(() => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;

  if (authStore.isLoggedIn) {
    fetchGoals();
  }
});

// ========== 数据加载 ==========
async function fetchGoals() {
  goalLoading.value = true;
  try {
    const result = await getGoalList(1, 50);
    // 前端过滤活跃目标
    activeGoals.value = (result?.records || []).filter(g => g.status === 'active');
  } catch (e) {
    console.warn('[Index] 获取活跃目标失败:', e.message);
    activeGoals.value = [];
  } finally {
    goalLoading.value = false;
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
  padding: 0 $spacing-lg;
}
.nav-title {
  font-size: $font-lg;
  font-weight: bold;
  color: $text-primary;
}

/* 区域标题 */
.section-title {
  font-size: $font-lg;
  font-weight: bold;
  color: $text-primary;
  padding: $spacing-lg $spacing-lg $spacing-sm;
}

/* 目标卡片 */
.goal-card {
  margin: $spacing-md $spacing-lg;
  padding: $spacing-lg;
  background: linear-gradient(135deg, $primary-color, #66b1ff);
  border-radius: $radius-lg;
  color: #fff;
  box-shadow: $shadow-md;
}
.goal-card--empty {
  background: $bg-white;
  border: 2rpx dashed $border-color;
  box-shadow: none;
}
.goal-empty-text {
  font-size: $font-md;
  color: $text-secondary;
  text-align: center;
}
.goal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: $spacing-sm;
}
.goal-direction {
  font-size: $font-lg;
  font-weight: bold;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.goal-subject {
  font-size: $font-sm;
  opacity: 0.85;
  margin-left: $spacing-sm;
  background-color: rgba(255, 255, 255, 0.2);
  padding: 4rpx 16rpx;
  border-radius: $radius-round;
}
.goal-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.goal-remaining {
  font-size: $font-md;
  font-weight: 500;
}
.goal-end-date {
  font-size: $font-sm;
  opacity: 0.8;
}
</style>
