<template>
  <el-upload
    ref="uploadRef"
    class="file-uploader"
    drag
    :auto-upload="false"
    :show-file-list="false"
    :accept="accept"
    :on-change="handleFileChange"
    :before-upload="beforeUpload"
  >
    <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
    <div class="el-upload__text">
      将文件拖到此处，或<em>点击上传</em>
    </div>
    <template #tip>
      <div class="el-upload__tip">
        {{ tipText }}
      </div>
    </template>
  </el-upload>
</template>

<script setup>
import { ref, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { UploadFilled } from '@element-plus/icons-vue';

const props = defineProps({
  accept: {
    type: String,
    default: '.json'
  },
  maxSize: {
    type: Number,
    default: 10 * 1024 * 1024 // 10MB
  },
  tip: {
    type: String,
    default: ''
  }
});

const emit = defineEmits(['file-selected']);

const uploadRef = ref(null);

const tipText = computed(() => {
  if (props.tip) return props.tip;
  const maxSizeMB = (props.maxSize / 1024 / 1024).toFixed(0);
  return `仅支持 ${props.accept} 格式文件，且大小不超过 ${maxSizeMB}MB`;
});

function beforeUpload(file) {
  // 校验文件格式
  const fileExt = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
  if (!props.accept.split(',').includes(fileExt)) {
    ElMessage.error(`文件格式不正确，仅支持 ${props.accept}`);
    return false;
  }

  // 校验文件大小
  if (file.size > props.maxSize) {
    const maxSizeMB = (props.maxSize / 1024 / 1024).toFixed(0);
    ElMessage.error(`文件大小不能超过 ${maxSizeMB}MB`);
    return false;
  }

  return true;
}

function handleFileChange(file) {
  // 校验文件
  if (!beforeUpload(file.raw)) {
    return;
  }

  // 发射 file-selected 事件
  emit('file-selected', file.raw);

  // 清空上传列表
  if (uploadRef.value) {
    uploadRef.value.clearFiles();
  }
}
</script>

<style scoped>
.file-uploader {
  width: 100%;
}

.file-uploader :deep(.el-upload) {
  width: 100%;
}

.file-uploader :deep(.el-upload-dragger) {
  width: 100%;
  padding: 40px 0;
}
</style>
