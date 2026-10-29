package cn.diaojiahong.myyoutube.user.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户计数调整项; field ∈ fans/attentionCount
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CounterAdjust {
    private Long userId;
    private String field;
    private int delta;
}
