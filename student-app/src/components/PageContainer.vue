<template>
  <view class="page-container" :style="{ backgroundColor: backgroundColor }">
    <!-- 自定义导航栏 -->
    <view v-if="title" class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-bar-inner">
        <view v-if="showBack" class="nav-back" @click="goBack">
          <text class="back-icon">&#xe600;</text>
        </view>
        <text class="nav-title">{{ title }}</text>
        <view class="nav-right"></view>
      </view>
    </view>

    <!-- 内容区 -->
    <view class="page-content">
      <slot></slot>
    </view>

    <!-- 底部安全区（I-6） -->
    <view class="safe-area-bottom"></view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue';

defineProps({
  title: { type: String, default: '' },
  showBack: { type: Boolean, default: false },
  backgroundColor: { type: String, default: '#f5f5f5' }
});

const statusBarHeight = ref(0);

onMounted(() => {
  const sysInfo = uni.getSystemInfoSync();
  statusBarHeight.value = sysInfo.statusBarHeight || 0;
});

function goBack() {
  uni.navigateBack({ delta: 1, fail: () => uni.switchTab({ url: '/pages/index/Index' }) });
}
</script>

<style lang="scss" scoped>
.page-container {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.nav-bar {
  background-color: #ffffff;
  position: sticky;
  top: 0;
  z-index: 99;
}

.nav-bar-inner {
  height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 $spacing-md;
}

.nav-back {
  width: 60rpx;
  display: flex;
  align-items: center;
}

.back-icon {
  font-size: 36rpx;
  color: $text-primary;
}

.nav-title {
  flex: 1;
  text-align: center;
  font-size: $font-lg;
  font-weight: 600;
  color: $text-primary;
}

.nav-right {
  width: 60rpx;
}

.page-content {
  flex: 1;
}

.safe-area-bottom {
  padding-bottom: env(safe-area-inset-bottom);
}
</style>
