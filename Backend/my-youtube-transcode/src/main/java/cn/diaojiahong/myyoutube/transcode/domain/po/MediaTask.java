package cn.diaojiahong.myyoutube.transcode.domain.po;

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
@TableName("media_task")
public class MediaTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long draftId;

    private String taskType;

    private Boolean requiredForReady;

    private String status;

    private Integer attempt;

    private Integer retryCount;

    private String resultJson;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
