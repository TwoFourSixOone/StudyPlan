<template>
  <div class="question-list">
    <h2>题库管理</h2>

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
      导入题目
    </el-button>

    <!-- 数据表格 -->
    <DataTable
      :columns="columns"
      :data="tableData"
      :loading="loading"
      :pagination="pagination"
      @page-change="handlePageChange"
    >
      <template #difficulty="{ row }">
        <el-tag :type="getDifficultyType(row.difficulty)">
          {{ getDifficultyLabel(row.difficulty) }}
        </el-tag>
      </template>
      <template #questionType="{ row }">
        {{ getQuestionTypeLabel(row.questionType) }}
      </template>
    </DataTable>

    <!-- 导入对话框 -->
    <el-dialog v-model="showImportDialog" title="导入题目" width="500px" :close-on-click-modal="false">
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
import { getQuestionList } from '@/api/question';
import { importData } from '@/api/import';
import { getKnowledgePointList } from '@/api/knowledgePoint';

const router = useRouter();

// 搜索字段配置
const searchFields = ref([
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
      { label: '科学', value: '科学' },
      { label: '道德与法治', value: '道德与法治' }
    ],
    placeholder: '请选择学科'
  },
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
    label: '知识点',
    prop: 'knowledgePointId',
    type: 'select',
    options: [],
    placeholder: '请选择知识点'
  },
  {
    label: '难度',
    prop: 'difficulty',
    type: 'select',
    options: [
      { label: '基础', value: 'basic' },
      { label: '标准', value: 'standard' },
      { label: '挑战', value: 'challenge' }
    ],
    placeholder: '请选择难度'
  },
  {
    label: '题型',
    prop: 'questionType',
    type: 'select',
    options: [
      { label: '数值题', value: 'numeric' },
      { label: '选择题', value: 'choice' }
    ],
    placeholder: '请选择题型'
  }
]);

// 搜索参数
const searchParams = reactive({
  subject: '',
  stage: '',
  knowledgePointId: '',
  difficulty: '',
  questionType: ''
});

// 表格列配置
const columns = ref([
  { prop: 'id', label: '题目 ID', width: 80 },
  { prop: 'subjectName', label: '学科', width: 80 },
  { prop: 'stage', label: '学段', width: 80 },
  { prop: 'knowledgePointName', label: '知识点名称', width: 150 },
  { prop: 'content', label: '题干', showOverflowTooltip: true },
  { prop: 'answer', label: '答案', width: 100, showOverflowTooltip: true },
  { prop: 'analysis', label: '解析', showOverflowTooltip: true },
  { prop: 'difficulty', label: '难度', width: 100, slot: 'difficulty' },
  { prop: 'questionType', label: '题型', width: 100, slot: 'questionType' },
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

// 加载知识点列表（作为下拉选项）
async function loadKnowledgePointOptions() {
  try {
    const data = await getKnowledgePointList({ size: 100 });
    const options = data.records.map((item) => ({
      label: item.name,
      value: item.id
    }));
    searchFields.value[2].options = options;
  } catch (error) {
    ElMessage.error('加载知识点列表失败：' + error.message);
  }
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
    const data = await getQuestionList(params);
    tableData.value = data.records;
    pagination.total = data.total;
  } catch (error) {
    ElMessage.error('加载题目列表失败：' + error.message);
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
    subject: '',
    stage: '',
    knowledgePointId: '',
    difficulty: '',
    questionType: ''
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
    const result = await importData(file, 'question');
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

// 难度标签类型
function getDifficultyType(difficulty) {
  const map = {
    basic: 'success',
    standard: 'warning',
    challenge: 'danger'
  };
  return map[difficulty] || 'info';
}

// 难度标签文本
function getDifficultyLabel(difficulty) {
  const map = {
    basic: '基础',
    standard: '标准',
    challenge: '挑战'
  };
  return map[difficulty] || difficulty;
}

// 题型标签文本
function getQuestionTypeLabel(questionType) {
  const map = {
    numeric: '数值题',
    choice: '选择题'
  };
  return map[questionType] || questionType;
}

// 生命周期
onMounted(() => {
  loadKnowledgePointOptions();
  loadData();
});
</script>

<style scoped>
.question-list {
  padding: 20px;
}

h2 {
  margin-bottom: 20px;
}

.el-button {
  margin-bottom: 16px;
}
</style>
