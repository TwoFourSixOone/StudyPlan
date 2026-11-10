/**
 * 年级展示工具（产品规则 #11）
 *
 * 数据层用整数 1–12，界面统一展示中文年级名，避免列表里出现裸数字。
 */

export const GRADE_OPTIONS = [
  { label: '一年级', value: 1 },
  { label: '二年级', value: 2 },
  { label: '三年级', value: 3 },
  { label: '四年级', value: 4 },
  { label: '五年级', value: 5 },
  { label: '六年级', value: 6 },
  { label: '初一', value: 7 },
  { label: '初二', value: 8 },
  { label: '初三', value: 9 },
  { label: '高一', value: 10 },
  { label: '高二', value: 11 },
  { label: '高三', value: 12 }
];

const LABELS = GRADE_OPTIONS.map((o) => o.label);

/**
 * 单个年级 → 中文名：9 → 初三
 * @param {number|string} grade
 * @returns {string}
 */
export function gradeLabel(grade) {
  const g = Number(grade);
  return LABELS[g - 1] || `${grade} 年级`;
}

/**
 * 年级范围 → 中文名
 * "2" → 二年级；"7-9" → 初一~初三；"1,2,3" → 一年级、二年级、三年级；空 → 全年级
 * @param {string} range
 * @returns {string}
 */
export function gradeRangeText(range) {
  if (!range) {
    return '全年级';
  }
  return String(range)
    .split(',')
    .map((token) => {
      const t = token.trim();
      if (t.includes('-')) {
        const [from, to] = t.split('-');
        return `${gradeLabel(from)}~${gradeLabel(to)}`;
      }
      return gradeLabel(t);
    })
    .join('、');
}
