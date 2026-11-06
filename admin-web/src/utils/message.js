import { ElMessage, ElMessageBox } from 'element-plus';

/**
 * 显示成功消息
 */
export function showSuccess(message) {
  ElMessage.success(message);
}

/**
 * 显示错误消息
 */
export function showError(message) {
  ElMessage.error(message);
}

/**
 * 显示警告消息
 */
export function showWarning(message) {
  ElMessage.warning(message);
}

/**
 * 显示信息消息
 */
export function showInfo(message) {
  ElMessage.info(message);
}

/**
 * 确认对话框
 */
export function confirm(message, title = '提示', options = {}) {
  return ElMessageBox.confirm(message, title, {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
    ...options
  });
}

/**
 * 提示对话框
 */
export function alert(message, title = '提示', options = {}) {
  return ElMessageBox.alert(message, title, {
    confirmButtonText: '确定',
    type: 'info',
    ...options
  });
}
