<template>
  <view class="empty-state">
    <text class="empty-icon">{{ icon === 'empty' ? '📭' : '⚠️' }}</text>
    <text class="empty-text">{{ text }}</text>
    <slot></slot>
  </view>
</template>

<script setup>
defineProps({
  text: { type: String, default: '暂无数据' },
  icon: { type: String, default: 'empty' }
});
</script>

<style lang="scss" scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 160rpx $spacing-xl;
}

.empty-icon {
  font-size: 96rpx;
  margin-bottom: $spacing-lg;
}

.empty-text {
  font-size: $font-md;
  color: $text-secondary;
  text-align: center;
}
</style>
