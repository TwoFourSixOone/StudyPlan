<template>
  <div class="import-log-list">
    <h2>导入日志</h2>

    <!-- 搜索表单 -->
    <SearchForm
      :fields="searchFields"
      v-model="searchParams"
      @search="handleSearch"
      @reset="handleReset"
    />

    <!-- 数据表格 -->
    <DataTable
      :columns="columns"
      :data="tableData"
      :loading="loading"
      :pagination="pagination"
      @page-change="handlePageChange"
    >
      <template #dataType="{ row }">
        <el-tag :type="getDataTypeType(row.dataType)">
          {{ getDataTypeLabel(row.dataType) }}
        </el-tag>
      </template>
      <template #action="{ row }">
        <el-button
          v-if="row.failCount > 0"
          type="danger"
          size="small"
          @click="handleViewErrors(row.id)"
        >
          查看错误记录（{{ row.failCount }}）
        </el-button>
      </template>
    </DataTable>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import SearchForm from '@/components/SearchForm.vue';
import DataTable from '@/components/DataTable.vue';
import { getImportLogs } from '@/api/import';

const router = useRouter();

// 搜索字段配置（仅数据类型筛选，修复 D7）
const searchFields = ref([
  {
    label: '数据类型',
    prop: 'dataType',
    type: 'select',
    options: [
      { label: '题目', value: 'question' },
      { label: '知识点', value: 'topic' },
      { label: '先修关系', value: 'dependency' },
      { label: '课程标准', value: 'standard' }
    ],
    placeholder: '请选择数据类型'
  }
]);

// 搜索参数
const searchParams = reactive({
  dataType: ''
});

// 表格列配置
const columns = ref([
  { prop: 'id', label: '导入 ID', width: 100 },
  { prop: 'dataType', label: '数据类型', width: 120, slot: 'dataType' },
  { prop: 'fileSource', label: '文件来源', width: 200 },
  { prop: 'successCount', label: '成功数', width: 100 },
  { prop: 'failCount', label: '失败数', width: 100 },
  { prop: 'skipCount', label: '跳过数', width: 100 },
  { prop: 'importedAt', label: '导入时间', width: 180 },
  { prop: 'action', label: '操作', width: 180, slot: 'action' }
]);

// 表格数据
const tableData = ref([]);
const loading = ref(false);

// 分页
const pagination = reactive({
  page: 1,
  size: 20,
  total: 0
});

// 加载表格数据
async function loadData() {
  loading.value = true;
  try {
    const params = {
      ...searchParams,
      page: pagination.page,
      size: pagination.size
    };
    const data = await getImportLogs(params);
    tableData.value = data.records;
    pagination.total = data.total;
  } catch (error) {
    ElMessage.error('加载导入日志失败：' + error.message);
  } finally {
    loading.value = false;
  }
}

// 搜索
function handleSearch() {
  pagination.page = 1;
  loadData();
}

// 重置
function handleReset() {
  Object.assign(searchParams, {
    dataType: ''
  });
  handleSearch();
}

// 分页变化
function handlePageChange({ page, size }) {
  pagination.page = page;
  pagination.size = size;
  loadData();
}

// 查看错误记录
function handleViewErrors(importId) {
  router.push(`/import-errors?importId=${importId}`);
}

// 数据类型标签类型
function getDataTypeType(dataType) {
  const map = {
    question: 'primary',
    topic: 'success',
    dependency: 'warning',
    standard: 'danger'
  };
  return map[dataType] || 'info';
}

// 数据类型标签文本
function getDataTypeLabel(dataType) {
  const map = {
    question: '题目',
    topic: '知识点',
    dependency: '先修关系',
    standard: '课程标准'
  };
  return map[dataType] || dataType;
}

// 生命周期
onMounted(() => {
  loadData();
});
</script>

<style scoped>
.import-log-list {
  padding: 20px;
}

h2 {
  margin-bottom: 20px;
}
</style>
