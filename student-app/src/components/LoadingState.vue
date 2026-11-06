<template>
  <view v-if="loading" class="loading-state">
    <view class="loading-spinner"></view>
    <text class="loading-text">{{ text }}</text>
  </view>
</template>

<script setup>
defineProps({
  loading: { type: Boolean, default: true },
  text: { type: String, default: '加载中...' }
});
</script>

<style lang="scss" scoped>
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 120rpx 0;
}

.loading-spinner {
  width: 60rpx;
  height: 60rpx;
  border: 4rpx solid $border-light;
  border-top-color: $primary-color;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.loading-text {
  margin-top: $spacing-md;
  font-size: $font-md;
  color: $text-secondary;
}
</style>
