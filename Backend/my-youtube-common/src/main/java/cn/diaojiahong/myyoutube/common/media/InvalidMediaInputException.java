package cn.diaojiahong.myyoutube.common.media;

import java.util.Objects;

public final class InvalidMediaInputException extends IllegalArgumentException {
    public enum Reason {
        UNSUPPORTED_AUDIO("不支持的音频格式，请选择 MP3、AAC／M4A、FLAC、Ogg 或 PCM WAV"),
        INVALID_AUDIO("音频格式或参数无效，请更换文件"),
        AUDIO_DECODE_FAILED("音频无法完整解码，请更换文件"),
        AUDIO_TOO_LONG("音频时长不能超过 10 分钟"),
        EMPTY_MEDIA("素材文件为空或没有有效声音，请更换文件"),
        MEDIA_TOO_LARGE("素材文件大小超限"),
        INVALID_IMAGE("图片格式或尺寸无效，请更换图片");

        private final String publicMessage;

        Reason(String publicMessage) {
            this.publicMessage = publicMessage;
        }

        public String code() {
            return "MEDIA_INPUT_" + name();
        }
    }

    private final Reason reason;

    public InvalidMediaInputException(Reason reason) {
        this(reason, null);
    }

    public InvalidMediaInputException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason).publicMessage, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static String publicMessage(String failureCode) {
        for (Reason reason : Reason.values()) {
            if (reason.code().equals(failureCode)) {
                return reason.publicMessage;
            }
        }
        return "音频处理失败，请重试";
    }
}
