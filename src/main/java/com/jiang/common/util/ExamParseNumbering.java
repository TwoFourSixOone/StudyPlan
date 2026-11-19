package com.jiang.common.util;

import com.jiang.dto.ParsedQuestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 卷面题号保真与缺题检测（Change 2026-11-17_exam_parse_reliability）
 *
 * <p>规则：</p>
 * <ul>
 *   <li>全部题目都有合法、互不重复、且不超过总题数的 {@code paperNo} → {@code questionIndex = paperNo}，
 *       按题号稳定排序（题号可能不连续，如缺 16）；</li>
 *   <li>否则回退按图片顺序 1..N，并给出告警；</li>
 *   <li>{@code paperQuestionTotal > 0} 时检测缺号；未知时用「最大题号 ≠ 题数」做粗检。</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-17
 */
public final class ExamParseNumbering {

    /** 题号上限（与 schema 一致） */
    public static final int MAX_PAPER_NO = 60;

    private ExamParseNumbering() {
    }

    /**
     * 编号结果
     *
     * @param preserved         是否保留了卷面题号
     * @param missingPaperNos   缺失的题号（仅 preserved 时有意义）
     * @param duplicatePaperNos 重复的题号（回退原因之一）
     * @param warnings          告警文案（直接并入 imageWarnings）
     */
    public record Result(boolean preserved,
                         List<Integer> missingPaperNos,
                         List<Integer> duplicatePaperNos,
                         List<String> warnings) {

        /** 是否存在缺号/重复（用于降级日志） */
        public boolean gap() {
            return !missingPaperNos.isEmpty() || !duplicatePaperNos.isEmpty();
        }
    }

    /**
     * 计算并回写每题的 {@code questionIndex}
     *
     * @param questions          合并后的题目（原地修改题号并排序）
     * @param paperQuestionTotal 整卷题目总数（null/0 表示模型未给出）
     * @return 编号结果
     */
    public static Result apply(List<ParsedQuestion> questions, Integer paperQuestionTotal) {
        List<String> warnings = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        List<Integer> duplicates = new ArrayList<>();
        if (questions == null || questions.isEmpty()) {
            return new Result(false, missing, duplicates, warnings);
        }
        int total = paperQuestionTotal == null ? 0 : paperQuestionTotal;
        List<Integer> paperNos = new ArrayList<>(questions.size());
        boolean allPresent = true;
        for (ParsedQuestion question : questions) {
            Integer paperNo = question.getPaperNo();
            if (paperNo == null) {
                allPresent = false;
                continue;
            }
            paperNos.add(paperNo);
        }
        boolean inRange = paperNos.stream().allMatch(no -> no >= 1 && no <= MAX_PAPER_NO);

        Set<Integer> seen = new LinkedHashSet<>();
        Set<Integer> duplicateSet = new LinkedHashSet<>();
        for (Integer paperNo : paperNos) {
            if (!seen.add(paperNo)) {
                duplicateSet.add(paperNo);
            }
        }
        duplicates.addAll(duplicateSet);
        int maxPaperNo = paperNos.stream().mapToInt(Integer::intValue).max().orElse(0);
        boolean usable = allPresent && inRange && duplicateSet.isEmpty()
                && (total <= 0 || maxPaperNo <= total);

        if (usable) {
            for (ParsedQuestion question : questions) {
                question.setQuestionIndex(question.getPaperNo());
            }
            questions.sort(Comparator.comparingInt(ParsedQuestion::getQuestionIndex));
            if (total > 0) {
                for (int no = 1; no <= total; no++) {
                    if (!seen.contains(no)) {
                        missing.add(no);
                    }
                }
                if (!missing.isEmpty()) {
                    warnings.add("本卷应有 " + total + " 题，识别到 " + questions.size()
                            + " 题（缺第 " + join(missing) + " 题），请核对");
                }
            } else if (maxPaperNo != questions.size()) {
                warnings.add("题号不连续（最大 " + maxPaperNo + "，共 " + questions.size()
                        + " 题），请核对");
            }
            return new Result(true, missing, duplicates, warnings);
        }

        for (int index = 0; index < questions.size(); index++) {
            questions.get(index).setQuestionIndex(index + 1);
        }
        String reason;
        if (!allPresent) {
            reason = "未能识别卷面题号";
        } else if (!duplicateSet.isEmpty()) {
            reason = "题号重复（第 " + join(duplicates) + " 题）";
        } else if (!inRange) {
            reason = "题号超出范围（应 " + 1 + ".." + MAX_PAPER_NO + "）";
        } else {
            reason = "题号超过整卷题数（最大 " + maxPaperNo + " / 总数 " + total + "）";
        }
        // 缺 paperNo 时输出与隐性约定 #39 完全一致的话术；其余原因前置说明后同样带上结论动作
        warnings.add(reason + "，已按图片顺序编号");
        if (total > 0 && questions.size() != total) {
            warnings.add("本卷应有 " + total + " 题，识别到 " + questions.size() + " 题，请核对");
        }
        return new Result(false, missing, duplicates, warnings);
    }

    private static String join(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sb.append('、');
            }
            sb.append(values.get(index));
        }
        return sb.toString();
    }
}
