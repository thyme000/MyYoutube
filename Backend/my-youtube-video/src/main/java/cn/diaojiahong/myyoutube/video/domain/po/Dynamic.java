package cn.diaojiahong.myyoutube.video.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("dynamic")
public class Dynamic implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String title;

    /** vertical/horizontal */
    private String canvasType;

    private Integer canvasW;

    private Integer canvasH;

    /** cut/fade */
    private String transition;

    private Integer fadeMs;

    /** 音频库条目ID */
    private Long audioId;

    private String audioObjectKey;

    /** 音频标题(快照) */
    private String audioTitle;

    private Integer audioDurationMs;

    private Integer totalDurationMs;

    /** 时间轴 JSON: [{imageObjectKey,startMs,durationMs,focusX,focusY}] */
    private String timelineJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDelete;
}
