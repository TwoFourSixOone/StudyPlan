<template>
  <div class="standard-list">
    <h2>课程标准管理</h2>

    <!-- 搜索表单 -->
    <SearchForm
      :fields="searchFields"
      v-model="searchParams"
      @search="handleSearch"
      @reset="handleReset"
    />

    <!-- 导入按钮 -->
    <el-button type="primary" @click="showImportDialog = true">
      <el-icon><Upload /></el-icon>
      导入课程标准
    </el-button>

    <!-- 数据表格 -->
    <DataTable
      :columns="columns"
      :data="tableData"
      :loading="loading"
      :pagination="pagination"
      @page-change="handlePageChange"
    >
      <template #stage="{ row }">
        {{ getStageLabel(row.stage) }}
      </template>
    </DataTable>

    <!-- 导入对话框 -->
    <el-dialog v-model="showImportDialog" title="导入课程标准" width="500px" :close-on-click-modal="false">
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
import { getStandardList } from '@/api/standard';
import { importData } from '@/api/import';

const router = useRouter();

// 搜索字段配置
const searchFields = ref([
  {
    label: '学段',
    prop: 'stage',
    type: 'select',
    options: [
      { label: '小学', value: '小学' },
      { label: '初中', value: '初中' },
      { label: '高中', value: '高中' }
    ],
    placeholder: '请选择学段'
  },
  {
    label: '学科',
    prop: 'subject',
    type: 'select',
    options: [
      { label: '语文', value: '语文' },
      { label: '数学', value: '数学' },
      { label: '英语', value: '英语' },
      { label: '物理', value: '物理' },
      { label: '化学', value: '化学' },
      { label: '生物', value: '生物' },
      { label: '历史', value: '历史' },
      { label: '地理', value: '地理' },
      { label: '政治', value: '政治' }
    ],
    placeholder: '请选择学科'
  },
  {
    label: '标准编号',
    prop: 'code',
    type: 'input',
    placeholder: '请输入标准编号'
  }
]);

// 搜索参数
const searchParams = reactive({
  stage: '',
  subject: '',
  code: ''
});

// 表格列配置
const columns = ref([
  { prop: 'code', label: '标准编号', width: 150 },
  { prop: 'stage', label: '学段', width: 100, slot: 'stage' },
  { prop: 'subjectName', label: '学科', width: 100 },
  { prop: 'description', label: '内容描述', showOverflowTooltip: true },
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

// 导入对话框
const showImportDialog = ref(false);
const importLoading = ref(false);

// 加载表格数据
async function loadData() {
  loading.value = true;
  try {
    const params = {
      ...searchParams,
      page: pagination.page,
      size: pagination.size
    };
    const data = await getStandardList(params);
    tableData.value = data.records;
    pagination.total = data.total;
  } catch (error) {
    ElMessage.error('加载课程标准列表失败：' + error.message);
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
    stage: '',
    subject: '',
    code: ''
  });
  handleSearch();
}

// 分页变化
function handlePageChange({ page, size }) {
  pagination.page = page;
  pagination.size = size;
  loadData();
}

// 上传文件
async function handleUpload(file) {
  importLoading.value = true;
  try {
    const result = await importData(file, 'standard');
    showImportDialog.value = false;

    ElMessage.success(`导入完成！成功 ${result.successCount} 条，失败 ${result.failCount} 条，跳过 ${result.skipCount} 条。`);
    
    // 如果有失败记录，询问是否查看
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

// 学段标签
function getStageLabel(stage) {
  return stage || '-';
}

// 生命周期
onMounted(() => {
  loadData();
});
</script>

<style scoped>
.standard-list {
  padding: 20px;
}

h2 {
  margin-bottom: 20px;
}

.el-button {
  margin-bottom: 16px;
}
</style>
