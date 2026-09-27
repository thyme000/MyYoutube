package cn.diaojiahong.myyoutube.video.domain.po;

import cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 视频信息表
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("video")
public class Video implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 视频唯一ID（主键）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 视频标题
     */
    private String title;

    /**
     * 视频简介/描述
     */
    private String description;

    /**
     * 封面图地址
     */
    private String coverUrl;

    /**
     * 视频文件地址
     */
    private String videoUrl;

    /**
     * 视频时长（单位：秒）
     */
    private Integer duration;

    /**
     * 视频类型（short/long）
     */
    private String videoType;

    /**
     * 视频分类（如：游戏、生活）
     */
    private VideoCategoryEnum category;

    /**
     * 标签（JSON数组或逗号分隔字符串）
     */
    private String tags;

    /**
     * 作者ID（关联用户表）
     */
    private Long authorId;

    /**
     * 播放次数
     */
    private Long playCount;

    /**
     * 点赞数
     */
    private Long likeCount;

    /**
     * 收藏数
     */
    private Long collectCount;

    /**
     * 评论数
     */
    private Long commentCount;

    /**
     * 分享数
     */
    private Long shareCount;

    /**
     * 视频状态（draft/review/published/banned）
     */
    private VideoStatusEnum status;

    /**
     * 可见范围（public/private/unlisted）
     */
    private VideoVisibilityEnum visibility;

    /**
     * 是否允许评论（0-不允许，1-允许）
     */
    private Integer allowComment;

    /**
     * 发布地区（用于地域推荐）
     */
    private String region;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记（0-未删除，1-已删除）
     */
    private Integer isDelete;


}
