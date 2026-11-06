package com.jiang.vo;

import com.jiang.dto.TriggerSignalDTO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 触发调整返回 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
@Builder
public class AdjustTriggerVO {

    /** diff ID（有调整时返回） */
    private Long diffId;

    /** 触发信号列表 */
    private List<TriggerSignalDTO> signals;

    /** 是否触发了调整 */
    private boolean adjusted;

    /** 提示信息 */
    private String message;

    public static AdjustTriggerVO noAdjustment() {
        return AdjustTriggerVO.builder()
                .adjusted(false)
                .message("暂无需调整")
                .build();
    }

    public static AdjustTriggerVO of(Long diffId, List<TriggerSignalDTO> signals) {
        return AdjustTriggerVO.builder()
                .diffId(diffId)
                .signals(signals)
                .adjusted(true)
                .message("已生成调整方案")
                .build();
    }
}
