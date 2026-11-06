/**
 * 消息提示工具（含 500ms 去重节流）
 * 
 * 并发多个请求同时失败时，避免多次弹出相同 Toast。
 * 同一文案在 500ms 内只弹一次。
 */

let lastMessage = '';
let lastTime = 0;
const THROTTLE_MS = 500;

/**
 * 轻提示（Toast）
 * @param {string} msg 提示文案
 * @param {string} icon 图标：'success' | 'error' | 'none'（默认 'none'）
 */
export function showToast(msg, icon = 'none') {
  const now = Date.now();
  if (msg === lastMessage && now - lastTime < THROTTLE_MS) return;
  lastMessage = msg;
  lastTime = now;

  uni.showToast({
    title: msg,
    icon,
    duration: 2000
  });
}

/**
 * 错误提示（快捷方式）
 * @param {string} msg 
 */
export function showError(msg) {
  showToast(msg, 'none');
}

/**
 * 成功提示（快捷方式）
 * @param {string} msg 
 */
export function showSuccess(msg) {
  showToast(msg, 'success');
}

/**
 * 确认弹窗（Modal）
 * @param {string} content 提示内容
 * @param {string} title 标题（默认"提示"）
 * @returns {Promise<boolean>} 用户是否确认
 */
export function showConfirm(content, title = '提示') {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      success: (res) => resolve(res.confirm),
      fail: () => resolve(false)
    });
  });
}
