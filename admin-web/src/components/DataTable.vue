<template>
  <div class="data-table">
    <el-table
      v-loading="loading"
      :data="data"
      border
      stripe
      style="width: 100%"
      empty-text="暂无数据"
      @selection-change="handleSelectionChange"
    >
      <!-- 勾选列 -->
      <el-table-column v-if="selectable" type="selection" width="55" />
      <el-table-column
        v-for="column in columns"
        :key="column.prop"
        :prop="column.prop"
        :label="column.label"
        :width="column.width"
        :formatter="column.formatter"
        :show-overflow-tooltip="column.showOverflowTooltip !== false"
      >
        <!-- 支持自定义插槽 -->
        <template v-if="column.slot" #default="scope">
          <slot :name="column.slot" :row="scope.row" :index="scope.$index" />
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页组件 -->
    <div class="pagination-wrapper">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="currentSize"
        :page-sizes="pageSizes"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';

const props = defineProps({
  columns: {
    type: Array,
    required: true,
    default: () => []
  },
  data: {
    type: Array,
    default: () => []
  },
  loading: {
    type: Boolean,
    default: false
  },
  total: {
    type: Number,
    default: 0
  },
  page: {
    type: Number,
    default: 1
  },
  size: {
    type: Number,
    default: 20
  },
  pagination: {
    type: Object,
    default: () => ({ page: 1, size: 20, total: 0 })
  },
  pageSizes: {
    type: Array,
    default: () => [10, 20, 50, 100]
  },
  selectable: {
    type: Boolean,
    default: false
  }
});

const emit = defineEmits(['page-change', 'selection-change']);

// 优先使用 pagination 对象，否则使用单独的 page/size/total props
const currentPage = ref(props.pagination?.page || props.page);
const currentSize = ref(props.pagination?.size || props.size);
const total = ref(props.pagination?.total || props.total);

// 监听 props 变化
watch(
  () => [props.pagination, props.page, props.size, props.total],
  ([newPagination, newPage, newSize, newTotal]) => {
    currentPage.value = newPagination?.page || newPage;
    currentSize.value = newPagination?.size || newSize;
    total.value = newPagination?.total || newTotal;
  },
  { deep: true }
);

function handleSizeChange(val) {
  currentSize.value = val;
  currentPage.value = 1; // 切换每页大小时重置到第一页
  emit('page-change', { page: 1, size: val });
}

function handleCurrentChange(val) {
  currentPage.value = val;
  emit('page-change', { page: val, size: currentSize.value });
}

function handleSelectionChange(selection) {
  emit('selection-change', selection);
}
</script>

<style scoped>
.data-table {
  background-color: #fff;
  border-radius: 4px;
  padding: 16px;
}

.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
