package cn.diaojiahong.myyoutube.common.result;

import lombok.Getter;

@Getter
public enum ErrorCode {
    INVALID_ARGUMENT(40001, 400, "请求参数无效"),
    INVALID_REQUEST(40002, 400, "请求格式或参数绑定错误"),
    UNAUTHORIZED(40101, 401, "未登录或登录已过期"),
    FORBIDDEN(40301, 403, "没有操作权限"),
    NOT_FOUND(40401, 404, "资源不存在"),
    ENDPOINT_NOT_FOUND(40402, 404, "请求接口不存在"),
    METHOD_NOT_ALLOWED(40501, 405, "不支持该请求方法"),
    NOT_ACCEPTABLE(40601, 406, "不支持请求的响应格式"),
    STATE_CONFLICT(40901, 409, "当前状态不允许该操作"),
    DRAFT_PUBLICATION_LOCKED(40902, 409, "请先取消发布再编辑草稿"),
    DRAFT_PREPARATION_FAILED(40903, 409, "媒体准备失败，请先重试媒体准备"),
    DRAFT_VERSION_CHANGED(40904, 409, "草稿版本已变化，请刷新"),
    DRAFT_EXPIRED(40905, 409, "草稿已过期，请重新上传"),
    DRAFT_ALREADY_SUBMITTED(40906, 409, "草稿已提交，不能编辑或取消发布"),
    AUDIO_UPLOAD_EXPIRED(40907, 409, "本次上传已结束，请重新发起上传"),
    PAYLOAD_TOO_LARGE(41301, 413, "上传内容过大"),
    UNSUPPORTED_MEDIA_TYPE(41501, 415, "不支持该请求内容类型"),
    RATE_LIMITED(42901, 429, "操作过于频繁，请稍后再试"),
    INTERNAL_ERROR(50001, 500, "系统繁忙，请稍后再试"),
    DEPENDENCY_FAILURE(50201, 502, "依赖服务调用失败，请稍后再试"),
    MODEL_EXECUTION_FAILED(50202, 502, "模型执行失败，请稍后重试"),
    DEPENDENCY_UNAVAILABLE(50301, 503, "服务暂时不可用，请稍后再试"),
    MODEL_UNAVAILABLE(50302, 503, "模型暂时不可用，请稍后重试"),
    DEPENDENCY_TIMEOUT(50401, 504, "依赖服务响应超时，请稍后再试");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public static ErrorCode forHttpStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_ARGUMENT;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> STATE_CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            case 502 -> DEPENDENCY_FAILURE;
            case 503 -> DEPENDENCY_UNAVAILABLE;
            case 504 -> DEPENDENCY_TIMEOUT;
            default -> INTERNAL_ERROR;
        };
    }
}
