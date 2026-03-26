package cn.diaojiahong.myyoutube.video.domain.vo;

import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.hutool.core.bean.BeanUtil;

/**
 * 实体 {@link Video} → 对外 VO：与项目里用户侧 VO 一样，优先用 BeanUtil 拷贝，status 等与实体类型不一致的再手动设。
 */
public final class VideoVoConverter {

    private VideoVoConverter() {
    }

    /**
     * 列表 VO：实体上多出来的字段（如 videoUrl、isDelete）在目标 VO 上不存在，不会拷贝过去。
     */
    public static VideoListVO toListVo(Video v) {
        if (v == null) {
            return null;
        }
        VideoListVO vo = new VideoListVO();
        BeanUtil.copyProperties(v, vo);
        return vo;
    }

    public static VideoDetailVO toDetailVo(Video v) {
        if (v == null) {
            return null;
        }
        VideoDetailVO vo = new VideoDetailVO();
        BeanUtil.copyProperties(v, vo);
        return vo;
    }

    public static MyVideoListVO toMyListVo(Video v) {
        if (v == null) {
            return null;
        }
        MyVideoListVO vo = new MyVideoListVO();
        BeanUtil.copyProperties(v, vo);
        return vo;
    }

    public static ShortsVO toShortsVo(Video v) {
        if (v == null) {
            return null;
        }
        ShortsVO vo = new ShortsVO();
        BeanUtil.copyProperties(v, vo);
        return vo;
    }
}
