<template>
  <div class="import-error-list">
    <el-page-header @back="handleBack" content="导入错误记录" />

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
      <template #errorReason="{ row }">
        <el-tooltip :content="row.errorReason" placement="top">
          <div class="error-reason">{{ row.errorReason }}</div>
        </el-tooltip>
      </template>
      <template #dataType="{ row }">
        <el-tag :type="getDataTypeType(row.dataType)">
          {{ getDataTypeLabel(row.dataType) }}
        </el-tag>
      </template>
    </DataTable>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import SearchForm from '@/components/SearchForm.vue';
import DataTable from '@/components/DataTable.vue';
import { getImportErrors } from '@/api/import';

const route = useRoute();
const router = useRouter();

// 搜索字段配置
const searchFields = ref([
  {
    label: '导入批次',
    prop: 'importId',
    type: 'input',
    placeholder: '导入批次 ID（从 URL 自动读取）'
  }
]);

// 搜索参数
const searchParams = reactive({
  importId: ''
});

// 表格列配置
const columns = ref([
  { prop: 'id', label: '记录 ID', width: 80 },
  { prop: 'importId', label: '导入批次', width: 100 },
  { prop: 'dataType', label: '数据类型', width: 120, slot: 'dataType' },
  { prop: 'errorReason', label: '失败原因', slot: 'errorReason' },
  { prop: 'createTime', label: '创建时间', width: 180 }
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

// 从 URL 读取 importId
function loadImportIdFromQuery() {
  const importId = route.query.importId;
  if (importId) {
    searchParams.importId = importId;
    searchFields.value[0].placeholder = `导入批次 ID：${importId}`;
  }
}

// 加载表格数据
async function loadData() {
  if (!searchParams.importId) {
    ElMessage.warning('请提供导入批次 ID');
    return;
  }

  loading.value = true;
  try {
    const params = {
      importId: searchParams.importId,
      page: pagination.page,
      size: pagination.size
    };
    const data = await getImportErrors(params);
    tableData.value = data.records;
    pagination.total = data.total;
  } catch (error) {
    ElMessage.error('加载错误记录失败：' + error.message);
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
  const importId = route.query.importId;
  Object.assign(searchParams, {
    importId: importId || ''
  });
  handleSearch();
}

// 分页变化
function handlePageChange({ page, size }) {
  pagination.page = page;
  pagination.size = size;
  loadData();
}

// 返回
function handleBack() {
  router.push('/import-logs');
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
  loadImportIdFromQuery();
  loadData();
});
</script>

<style scoped>
.import-error-list {
  padding: 20px;
}

.el-page-header {
  margin-bottom: 20px;
}

.error-reason {
  max-width: 400px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
