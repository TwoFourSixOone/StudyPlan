import dayjs from 'dayjs';

/**
 * 格式化日期时间
 * @param {string|Date|dayjs.Dayjs} date - 日期
 * @param {string} format - 格式，默认 'YYYY-MM-DD HH:mm:ss'
 * @returns {string} 格式化后的日期字符串
 */
export function formatDateTime(date, format = 'YYYY-MM-DD HH:mm:ss') {
  if (!date) return '-';
  return dayjs(date).format(format);
}

/**
 * 格式化日期
 * @param {string|Date|dayjs.Dayjs} date - 日期
 * @returns {string} 格式化后的日期字符串 'YYYY-MM-DD'
 */
export function formatDate(date) {
  if (!date) return '-';
  return dayjs(date).format('YYYY-MM-DD');
}

/**
 * 格式化时间
 * @param {string|Date|dayjs.Dayjs} date - 日期
 * @returns {string} 格式化后的时间字符串 'HH:mm:ss'
 */
export function formatTime(date) {
  if (!date) return '-';
  return dayjs(date).format('HH:mm:ss');
}

/**
 * 获取当前时间戳
 * @returns {number} 毫秒时间戳
 */
export function getCurrentTimestamp() {
  return dayjs().valueOf();
}

/**
 * 判断日期是否有效
 * @param {string|Date|dayjs.Dayjs} date - 日期
 * @returns {boolean} 是否有效
 */
export function isValidDate(date) {
  return dayjs(date).isValid();
}
