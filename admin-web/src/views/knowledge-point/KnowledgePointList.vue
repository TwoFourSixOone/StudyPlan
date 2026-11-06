<template>
  <div class="knowledge-point-list">
    <h2>知识点管理</h2>

    <!-- 搜索表单 -->
    <SearchForm
      :fields="searchFields"
      v-model="searchParams"
      @search="handleSearch"
      @reset="handleReset"
    />

    <!-- 操作按钮 -->
    <div class="action-buttons">
      <el-button type="primary" @click="showImportDialog = true">
        <el-icon><Upload /></el-icon>
        导入知识点
      </el-button>
      <el-button type="warning" @click="handleBatchReview" :loading="batchReviewLoading">
        批量校验
      </el-button>
    </div>

    <!-- 数据表格 -->
    <DataTable
      :columns="columns"
      :data="tableData"
      :loading="loading"
      :pagination="pagination"
      :selectable="true"
      @page-change="handlePageChange"
      @selection-change="handleSelectionChange"
    >
      <template #status="{ row }">
        <el-tag :type="getStatusType(row.status)">
          {{ getStatusLabel(row.status) }}
        </el-tag>
      </template>
      <template #action="{ row }">
        <el-button
          v-if="row.status === 'pending_review'"
          type="success"
          size="small"
          @click="handleReview(row.id)"
        >
          标记已校对
        </el-button>
        <el-button
          v-if="row.status !== 'disabled'"
          type="danger"
          size="small"
          @click="handleToggleStatus(row.id, 'disabled')"
        >
          停用
        </el-button>
        <el-button
          v-else
          type="success"
          size="small"
          @click="handleToggleStatus(row.id, 'active')"
        >
          启用
        </el-button>
        <el-button type="primary" size="small" @click="handleDetail(row.id)">
          查看详情
        </el-button>
      </template>
    </DataTable>

    <!-- 导入对话框 -->
    <el-dialog v-model="showImportDialog" title="导入知识点" width="500px" :close-on-click-modal="false">
      <FileUploader
        v-loading="importLoading"
        element-loading-text="导入中，请稍候..."
        accept=".json"
        :max-size="10 * 1024 * 1024"
        @file-selected="handleUpload"
      />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue';
import { Upload } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useRouter } from 'vue-router';
import SearchForm from '@/components/SearchForm.vue';
import DataTable from '@/components/DataTable.vue';
import FileUploader from '@/components/FileUploader.vue';
import {
  getKnowledgePointList,
  reviewKnowledgePoint,
  updateKnowledgePointStatus,
  batchReviewKnowledgePoints
} from '@/api/knowledgePoint';
import { importData } from '@/api/import';

const router = useRouter();

// 搜索字段配置
const searchFields = ref([
  {
    label: '知识点名称',
    prop: 'keyword',
    type: 'input',
    placeholder: '请输入知识点名称'
  },
  {
    label: '状态',
    prop: 'status',
    type: 'select',
    options: [
      { label: '待校对', value: 'pending_review' },
      { label: '已启用', value: 'active' },
      { label: '已停用', value: 'disabled' }
    ],
    placeholder: '请选择状态'
  },
  {
    label: '学科',
    prop: 'subject',
    type: 'select',
    options: [],
    placeholder: '请选择学科'
  }
]);

// 搜索参数
const searchParams = reactive({
  keyword: '',
  status: '',
  subject: ''
});

// 表格列配置
const columns = ref([
  { prop: 'id', label: '知识点 ID', width: 100 },
  { prop: 'name', label: '名称', width: 200 },
  { prop: 'status', label: '状态', width: 100, slot: 'status' },
  { prop: 'dependencyCount', label: '先修关系数量', width: 120 },
  { prop: 'questionCount', label: '关联题目数量', width: 120 },
  { prop: 'createTime', label: '创建时间', width: 180 },
  { prop: 'action', label: '操作', width: 280, slot: 'action' }
]);

// 表格数据
const tableData = ref([]);
const loading = ref(false);

// 分页（知识点列表默认 size=10，修复 D3）
const pagination = reactive({
  page: 1,
  size: 10,
  total: 0
});

// 导入对话框
const showImportDialog = ref(false);
const importLoading = ref(false);

// 批量校验
const selectedIds = ref([]);
const batchReviewLoading = ref(false);

// 加载学科列表
function loadSubjectOptions() {
  searchFields.value[2].options = [
    { label: '语文', value: '语文' },
    { label: '数学', value: '数学' },
    { label: '英语', value: '英语' },
    { label: '物理', value: '物理' },
    { label: '化学', value: '化学' },
    { label: '生物', value: '生物' },
    { label: '历史', value: '历史' },
    { label: '地理', value: '地理' },
    { label: '政治', value: '政治' }
  ];
}

// 加载表格数据
async function loadData() {
  loading.value = true;
  try {
    const params = {
      ...searchParams,
      page: pagination.page,
      size: pagination.size
    };
    const data = await getKnowledgePointList(params);
    tableData.value = data.records;
    pagination.total = data.total;
  } catch (error) {
    ElMessage.error('加载知识点列表失败：' + error.message);
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
    keyword: '',
    status: '',
    subject: ''
  });
  handleSearch();
}

// 分页变化
function handlePageChange({ page, size }) {
  pagination.page = page;
  pagination.size = size;
  loadData();
}

// 标记已校对
async function handleReview(id) {
  try {
    await reviewKnowledgePoint(id);
    ElMessage.success('标记已校对成功');
    loadData();
  } catch (error) {
    ElMessage.error('标记失败：' + error.message);
  }
}

// 启用/停用
async function handleToggleStatus(id, status) {
  const action = status === 'active' ? '启用' : '停用';
  try {
    await ElMessageBox.confirm(`确认${action}该知识点？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    });

    await updateKnowledgePointStatus(id, status);
    ElMessage.success(`${action}成功`);
    loadData();
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(`${action}失败：` + error.message);
    }
  }
}

// 查看详情
function handleDetail(id) {
  router.push(`/knowledge-points/${id}`);
}

// 勾选变化
function handleSelectionChange(selection) {
  selectedIds.value = selection.map(item => item.id);
}

// 批量校验
async function handleBatchReview() {
  try {
    const message = selectedIds.value.length > 0
      ? `确认校验选中的 ${selectedIds.value.length} 条知识点？`
      : '确认校验所有待校对知识点？';
    
    await ElMessageBox.confirm(message, '批量校验确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    });

    batchReviewLoading.value = true;
    const ids = selectedIds.value.length > 0 ? selectedIds.value : [];
    const count = await batchReviewKnowledgePoints(ids);
    ElMessage.success(`批量校验完成，成功标记 ${count} 条知识点`);
    loadData();
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('批量校验失败：' + error.message);
    }
  } finally {
    batchReviewLoading.value = false;
  }
}

// 上传文件
async function handleUpload(file) {
  importLoading.value = true;
  try {
    const result = await importData(file, 'topic');
    showImportDialog.value = false;

    ElMessage.success(`导入完成！成功 ${result.successCount} 条，失败 ${result.failCount} 条，跳过 ${result.skipCount} 条。`);
    
    if (result.failCount > 0) {
      await ElMessageBox.confirm(
        `导入完成！成功 ${result.successCount} 条，失败 ${result.failCount} 条，跳过 ${result.skipCount} 条。是否查看错误记录？`,
        '导入结果',
        {
          confirmButtonText: '查看错误记录',
          cancelButtonText: '关闭',
          type: 'warning'
        }
      );
      router.push(`/import-errors?importId=${result.importId}`);
    }
    
    loadData();
  } catch (error) {
    ElMessage.error('导入失败：' + (error.message || '未知错误'));
  } finally {
    importLoading.value = false;
  }
}

// 状态标签类型
function getStatusType(status) {
  const map = {
    pending_review: 'warning',
    active: 'success',
    disabled: 'danger'
  };
  return map[status] || 'info';
}

// 状态标签文本
function getStatusLabel(status) {
  const map = {
    pending_review: '待校对',
    active: '已启用',
    disabled: '已停用'
  };
  return map[status] || status;
}

// 生命周期
onMounted(() => {
  loadSubjectOptions();
  loadData();
});
</script>

<style scoped>
.knowledge-point-list {
  padding: 20px;
}

h2 {
  margin-bottom: 20px;
}

.action-buttons {
  margin-bottom: 16px;
  display: flex;
  gap: 12px;
}
</style>
