<template>
  <el-form :inline="true" :model="formData" class="search-form" @submit.prevent="handleSearch">
    <el-form-item v-for="field in fields" :key="field.prop" :label="field.label">
      <!-- 输入框 -->
      <el-input
        v-if="field.type === 'input'"
        v-model="formData[field.prop]"
        :placeholder="field.placeholder || `请输入${field.label}`"
        clearable
        style="width: 200px"
        @clear="handleSearch"
      />

      <!-- 下拉选择 -->
      <el-select
        v-else-if="field.type === 'select'"
        v-model="formData[field.prop]"
        :placeholder="field.placeholder || `请选择${field.label}`"
        clearable
        style="width: 120px"
        @change="handleSearch"
      >
        <el-option
          v-for="option in field.options"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>

      <!-- 日期选择 -->
      <el-date-picker
        v-else-if="field.type === 'date-picker'"
        v-model="formData[field.prop]"
        type="date"
        :placeholder="field.placeholder || `请选择${field.label}`"
        value-format="YYYY-MM-DD"
        clearable
        style="width: 140px"
        @change="handleSearch"
      />

      <!-- 日期范围 -->
      <el-date-picker
        v-else-if="field.type === 'date-range'"
        v-model="formData[field.prop]"
        type="daterange"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        value-format="YYYY-MM-DD"
        clearable
        style="width: 240px"
        @change="handleDateRangeChange"
      />
    </el-form-item>

    <el-form-item>
      <el-button type="primary" @click="handleSearch">
        <el-icon><Search /></el-icon>
        搜索
      </el-button>
      <el-button @click="handleReset">
        <el-icon><Refresh /></el-icon>
        重置
      </el-button>
    </el-form-item>
  </el-form>
</template>

<script setup>
import { reactive, watch } from 'vue';
import { Search, Refresh } from '@element-plus/icons-vue';

const props = defineProps({
  fields: {
    type: Array,
    required: true,
    default: () => []
  },
  modelValue: {
    type: Object,
    default: () => ({})
  }
});

const emit = defineEmits(['search', 'reset', 'update:modelValue']);

// 使用 modelValue 初始化表单数据
const formData = reactive({ ...props.modelValue });

// 监听 modelValue 变化（如父组件重置时）
watch(
  () => props.modelValue,
  (newVal) => {
    Object.assign(formData, newVal);
  },
  { deep: true }
);

// 日期范围变化时，拆分为 startTime 和 endTime
function handleDateRangeChange(val) {
  if (val && val.length === 2) {
    formData.startTime = val[0];
    formData.endTime = val[1];
  } else {
    formData.startTime = null;
    formData.endTime = null;
  }
  handleSearch();
}

function handleSearch() {
  // 同步 formData 到父组件
  emit('update:modelValue', { ...formData });
  // 过滤空值后传递给父组件用于 API 请求
  const params = {};
  Object.keys(formData).forEach((key) => {
    if (formData[key] !== null && formData[key] !== undefined && formData[key] !== '') {
      params[key] = formData[key];
    }
  });
  emit('search', params);
}

function handleReset() {
  Object.keys(formData).forEach((key) => {
    formData[key] = '';
  });
  emit('update:modelValue', { ...formData });
  emit('reset');
  emit('search', {});
}

// 暴露获取表单数据的方法
defineExpose({
  getFormData: () => ({ ...formData })
});
</script>

<style scoped>
.search-form {
  margin-bottom: 16px;
  padding: 16px;
  background-color: #fff;
  border-radius: 4px;
}

.search-form .el-form-item {
  margin-bottom: 0;
}
</style>
