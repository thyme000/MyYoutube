package cn.diaojiahong.myyoutube.common.constants;

public class RedisConstants {

    // 用户登录token凭证
    public static final String USER_TOKEN = "user:token:";
    public static final Long USER_TOKEN_EXPIRE = 24L;

    // 验证码
    public static final String CAPTCHA_LOGIN = "captcha:login:";
    public static final String CAPTCHA_REGISTER = "captcha:register:";
    public static final Long CAPTCHA_EXPIRE = 60L;
    public static final String CAPTCHA_FORGET = "captcha:forget:";

    // 视频播放量
    public static final String VIDEO_PLAY_COUNT = "video:play:count:";
    public static final Long VIDEO_PLAY_COUNT_EXPIRE = 3L;

    /** 分片数量（2^8），按 videoId 稳定路由，减轻单 Hash 过大 */
    public static final int VIDEO_PLAY_COUNT_SHARD_COUNT = 256;

    /**
     * 分片后的 Redis Hash key：video:play:count:0 .. video:play:count:{SHARD_COUNT-1}
     */
    public static String videoPlayCountShardKey(int shard) {
        if (shard < 0 || shard >= VIDEO_PLAY_COUNT_SHARD_COUNT) {
            throw new IllegalArgumentException("shard out of range: " + shard);
        }
        return VIDEO_PLAY_COUNT + shard;
    }

    // 用户看过的视频id
    public static final String USER_VIEWED = "user:viewed:";
    // 30天后过期
    public static final Long USER_VIEWED_EXPIRE = 30L;
    // 用户踩过的视频id
    public static final String USER_DISLIKE = "user:dislike:";


    // 用户看过的长视频时长记录 ->  {userid} : videoId , 当前时间戳
    public static final String USER_PLAYBACK_RESUME_TIMESTAMP = "user:playback:resume:timestamp:";
    // 30天后过期
    public static final Long USER_PLAYBACK_RESUME_EXPIRE = 30L;
    // 用户看过的视频时长记录 ->  {userid} : videoId , 当前时间戳
    public static final String USER_PLAYBACK_RESUME = "user:playback:resume:";


    // 用户观看的短视频记录
    public static final String USER_SHORTS_PLAY = "user:shorts:play:";
    // 30天后过期
    public static final Long USER_SHORTS_PLAY_EXPIRE = 30L;

}
