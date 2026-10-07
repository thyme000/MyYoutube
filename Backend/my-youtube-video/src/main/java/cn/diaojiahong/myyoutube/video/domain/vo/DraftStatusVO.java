package cn.diaojiahong.myyoutube.video.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 草稿处理状态: 前端上传页轮询用
 */
@Data
public class DraftStatusVO {
    private Long draftId;
    private String status;
    private String errorMessage;

    private List<TaskProgress> tasks;

    @Data
    public static class TaskProgress {
        private String taskType;
        private String status;
        private String errorMessage;
    }
}
