package cn.diaojiahong.myyoutube.transcode.domain.enums;

import java.util.Set;

/**
 * 媒体任务类型注册处: required_for_ready 与依赖关系的定义源, 建任务行时物化到行上。
 * 注意: 向量化不做管线任务——search 模块已有消费侧异步补算+rebuild 兜底, 且草稿期无标题, 管线内算会向量空间不一致
 */
public enum MediaTaskType {

    /** tika/ffprobe 校验 + 标准化转码, 可看的必要条件 */
    TRANSCODE(true, Set.of()),
    /** 封面生成, 可看的必要条件 */
    COVER(true, Set.of()),
    /** LLM 视觉打标(原始标签名+类目), 增强任务: 失败只降级不挡 READY; 词库映射/落库在发布时由 video 服务做 */
    TAG(false, Set.of());

    private final boolean requiredForReady;
    private final Set<String> dependencies;

    MediaTaskType(boolean requiredForReady, Set<String> dependencies) {
        this.requiredForReady = requiredForReady;
        this.dependencies = dependencies;
    }

    public boolean isRequiredForReady() {
        return requiredForReady;
    }

    public Set<String> getDependencies() {
        return dependencies;
    }

    public static MediaTaskType fromCode(String code) {
        return valueOf(code.toUpperCase());
    }
}
