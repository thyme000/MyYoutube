package cn.diaojiahong.myyoutube.video.domain.po;

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
 * 视频核心表（包含长视频/短视频，分类：音乐/电影/新闻/时尚）
 * </p>
 *
 * @author thyme
 * @since 2026-03-18
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("video")
public class Video implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 视频主键ID，自增
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 视频标题
     */
    private String title;

    /**
     * 视频封面图片URL地址
     */
    private String coverUrl;

    /**
     * 视频播放URL地址
     */
    private String videoUrl;

    /**
     * 视频时长，单位：秒（长视频>60秒，短视频≤60秒）
     */
    private Integer duration;

    /**
     * 视频类型：0=长视频，1=短视频（Shorts）
     */
    private Integer videoType;

    /**
     * 视频分类：音乐/电影/新闻/时尚/其他
     */
    private String category;

    /**
     * 发布者ID（关联user表id，0表示未关联）
     */
    private Integer authorId;

    /**
     * 视频播放量，默认0
     */
    private Integer playCount;

    /**
     * 视频发布时间，默认当前时间
     */
    private LocalDateTime createTime;

    /**
     * 视频更新时间，自动刷新
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0=未删除，1=已删除
     */
    private Integer isDelete;


}
