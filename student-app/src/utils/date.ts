import dayjs from 'dayjs';

/**
 * 日期格式化
 * @param date 日期值
 * @param format 格式，默认 'YYYY-MM-DD HH:mm:ss'（约定 #4）
 * @returns 格式化后的日期字符串
 */
export function formatDate(date: string | number | Date, format: string = 'YYYY-MM-DD HH:mm:ss'): string {
  if (!date) return '';
  return dayjs(date).format(format);
}

/**
 * 相对时间（如"3分钟前"）
 * @param date 日期值
 * @returns 相对时间字符串
 */
export function timeAgo(date: string | number | Date): string {
  if (!date) return '';
  const diff = dayjs().diff(dayjs(date), 'second');
  if (diff < 60) return '刚刚';
  if (diff < 3600) return `${Math.floor(diff / 60)}分钟前`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}小时前`;
  if (diff < 2592000) return `${Math.floor(diff / 86400)}天前`;
  return formatDate(date, 'YYYY-MM-DD');
}
