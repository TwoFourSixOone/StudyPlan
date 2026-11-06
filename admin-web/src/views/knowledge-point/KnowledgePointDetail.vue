<template>
  <div class="knowledge-point-detail">
    <el-page-header @back="handleBack" content="知识点详情" />

    <div v-loading="loading" class="detail-content">
      <!-- 基本信息 -->
      <el-card class="info-card">
        <template #header>
          <div class="card-header">
            <span>基本信息</span>
            <el-tag :type="getStatusType(detail.status)">
              {{ getStatusLabel(detail.status) }}
            </el-tag>
          </div>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="知识点 ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="学科">{{ detail.subjectName }}</el-descriptions-item>
          <el-descriptions-item label="层级">{{ detail.level }}</el-descriptions-item>
          <el-descriptions-item label="父知识点">{{ detail.parentName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="关联题目数量">{{ detail.questionCount }}</el-descriptions-item>
          <el-descriptions-item label="学习学生数量">{{ detail.studentCount }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(detail.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="描述" :span="2">{{ detail.description || '-' }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 先修关系 -->
      <el-card class="prerequisites-card">
        <template #header>
          <span>先修关系（需要先学习的知识点）</span>
        </template>
        <el-table :data="detail.prerequisites || []" border stripe>
          <el-table-column prop="knowledgePointId" label="知识点 ID" width="120" />
          <el-table-column prop="knowledgePointName" label="名称" />
          <el-table-column prop="dependencyType" label="关系类型" width="100">
            <template #default="{ row }">
              <el-tag :type="row.dependencyType === 'hard' ? 'danger' : 'warning'">
                {{ row.dependencyType === 'hard' ? '强依赖' : '弱依赖' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!detail.prerequisites?.length" description="暂无先修关系" />
      </el-card>

      <!-- 被依赖关系 -->
      <el-card class="dependents-card">
        <template #header>
          <span>被依赖关系（后续可学习的知识点）</span>
        </template>
        <el-table :data="detail.dependents || []" border stripe>
          <el-table-column prop="knowledgePointId" label="知识点 ID" width="120" />
          <el-table-column prop="knowledgePointName" label="名称" />
          <el-table-column prop="dependencyType" label="关系类型" width="100">
            <template #default="{ row }">
              <el-tag :type="row.dependencyType === 'hard' ? 'danger' : 'warning'">
                {{ row.dependencyType === 'hard' ? '强依赖' : '弱依赖' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!detail.dependents?.length" description="暂无被依赖关系" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { getKnowledgePointDetail } from '@/api/knowledgePoint';
import { formatDateTime } from '@/utils/date';

const route = useRoute();
const router = useRouter();

const loading = ref(false);
const detail = ref({});

// 加载详情数据
async function loadDetail() {
  const id = route.params.id;
  loading.value = true;
  try {
    const data = await getKnowledgePointDetail(id);
    detail.value = data;
  } catch (error) {
    ElMessage.error('加载知识点详情失败：' + error.message);
  } finally {
    loading.value = false;
  }
}

// 返回
function handleBack() {
  router.push('/knowledge-points');
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
  loadDetail();
});
</script>

<style scoped>
.knowledge-point-detail {
  padding: 20px;
}

.el-page-header {
  margin-bottom: 20px;
}

.detail-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
