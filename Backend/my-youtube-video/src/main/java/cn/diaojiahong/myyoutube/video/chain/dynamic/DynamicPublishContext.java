package cn.diaojiahong.myyoutube.video.chain.dynamic;

import cn.diaojiahong.myyoutube.video.domain.dto.DynamicPublishDTO;
import lombok.Getter;

import java.util.List;

/**
 * 动态发布链上下文: 校验步骤派生的画布风格/音频快照/逐项图片时长沉淀在此,
 * 发布主体直接取用, 不再重复计算。
 */
@Getter
public class DynamicPublishContext {

    private final DynamicPublishDTO dto;
    private final Long userId;

    private String canvasType;
    private int canvasW;
    private int canvasH;
    private String transition;
    private long fadeMs;

    private Long audioId;
    private String audioObjectKey;
    private String audioTitle;
    /** 已按上限截断的音频时长, 无音频为 0 */
    private long audioMs;

    private List<String> imageKeys;
    private long[] durations;

    private DynamicPublishContext(DynamicPublishDTO dto, Long userId) {
        this.dto = dto;
        this.userId = userId;
    }

    public static DynamicPublishContext of(DynamicPublishDTO dto, Long userId) {
        return new DynamicPublishContext(dto, userId);
    }

    public List<DynamicPublishDTO.DynamicItemDTO> getItems() {
        return dto.getItems();
    }

    public void setCanvasStyle(String canvasType, int canvasW, int canvasH, String transition, long fadeMs) {
        this.canvasType = canvasType;
        this.canvasW = canvasW;
        this.canvasH = canvasH;
        this.transition = transition;
        this.fadeMs = fadeMs;
    }

    public void setAudio(Long audioId, String audioObjectKey, String audioTitle, long audioMs) {
        this.audioId = audioId;
        this.audioObjectKey = audioObjectKey;
        this.audioTitle = audioTitle;
        this.audioMs = audioMs;
    }

    public void setItems(List<String> imageKeys, long[] durations) {
        this.imageKeys = imageKeys;
        this.durations = durations;
    }
}
