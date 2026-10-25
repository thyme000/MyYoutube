package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.common.media.AudioPreparationContracts;
import cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException;
import cn.diaojiahong.myyoutube.common.media.StructuredJson;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException.Reason.*;

@Component
@RequiredArgsConstructor
public class PreparedAudioReader {
    private static final Pattern TIME = Pattern.compile("(?m)^out_time_us=(\\d+)\\r?$");
    private final AudioPreparationProperties properties;

    public record Probe(long durationMs, String mime) {}

    public Probe read(Path source) throws Exception {
        String mime;
        try (var input = Files.newInputStream(source)) { mime = new Tika().detect(input.readNBytes(65536)); }
        if (!mime.startsWith("audio/") && !Set.of("application/ogg", "application/mp4", "video/mp4").contains(mime))
            throw new InvalidMediaInputException(UNSUPPORTED_AUDIO);
        var timeout = Duration.ofSeconds(properties.getCommandTimeoutSeconds());
        var probe = FFmpegUtil.runBoundedCommand(List.of("ffprobe", "-v", "error", "-show_format", "-show_streams",
                "-of", "json", source.toString()), timeout);
        requireSuccess(probe, "音频探测", INVALID_AUDIO);
        String actualMime = validateProbe(probe.output());
        var decoded = FFmpegUtil.runBoundedCommand(List.of("ffmpeg", "-nostdin", "-v", "error", "-xerror", "-threads", "1",
                "-i", source.toString(), "-map", "0:a:0", "-vn", "-sn", "-dn", "-af", "asetpts=N/SR/TB", "-t", "601",
                "-progress", "pipe:1", "-f", "null", "-"), timeout);
        requireSuccess(decoded, "音频解码", AUDIO_DECODE_FAILED);
        return new Probe(decodedDuration(decoded.output()), actualMime);
    }

    String validateProbe(String text) {
        var json = StructuredJson.object(text);
        var streams = json.get("streams");
        if (streams == null || !streams.isJsonArray()) throw new InvalidMediaInputException(INVALID_AUDIO);
        int audioTracks = 0;
        String codec = null;
        for (var value : streams.getAsJsonArray()) {
            if (!value.isJsonObject()) throw new InvalidMediaInputException(INVALID_AUDIO);
            var stream = value.getAsJsonObject();
            String type = text(stream, "codec_type");
            if ("audio".equals(type)) {
                audioTracks++;
                codec = text(stream, "codec_name");
                int rate = integer(stream, "sample_rate"), channels = integer(stream, "channels");
                if (rate <= 0 || channels < 1 || channels > 8) throw new InvalidMediaInputException(INVALID_AUDIO);
            } else if (!"video".equals(type) || !stream.has("disposition") || !stream.get("disposition").isJsonObject()
                    || integer(stream.getAsJsonObject("disposition"), "attached_pic") != 1)
                throw new InvalidMediaInputException(INVALID_AUDIO);
        }
        if (audioTracks != 1) throw new InvalidMediaInputException(INVALID_AUDIO);
        var format = json.get("format");
        if (format == null || !format.isJsonObject()) throw new InvalidMediaInputException(INVALID_AUDIO);
        var formats = Arrays.stream(text(format.getAsJsonObject(), "format_name").split(",")).collect(Collectors.toSet());
        if (formats.contains("mp3") && "mp3".equals(codec)) return "audio/mpeg";
        if (formats.contains("mp4") && "aac".equals(codec)) return "audio/mp4";
        if (formats.contains("aac") && "aac".equals(codec)) return "audio/aac";
        if (formats.contains("flac") && "flac".equals(codec)) return "audio/flac";
        if (formats.contains("ogg") && Set.of("vorbis", "opus", "flac").contains(codec)) return "audio/ogg";
        if (formats.contains("wav") && codec != null && codec.startsWith("pcm_")) return "audio/wav";
        throw new InvalidMediaInputException(UNSUPPORTED_AUDIO);
    }

    private static String text(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean() || value.getAsString().isBlank())
            throw new InvalidMediaInputException(INVALID_AUDIO);
        return value.getAsString();
    }

    private static int integer(JsonObject object, String field) {
        try { return Integer.parseInt(text(object, field)); }
        catch (NumberFormatException e) { throw new InvalidMediaInputException(INVALID_AUDIO, e); }
    }

    long decodedDuration(String output) {
        if (!output.contains("progress=end")) throw new IllegalStateException("音频解码进程没有返回完整进度");
        var matcher = TIME.matcher(output);
        long micros = 0;
        while (matcher.find()) micros = Math.max(micros, Long.parseLong(matcher.group(1)));
        long duration = micros / 1000 + (micros % 1000 == 0 ? 0 : 1);
        if (duration <= 0) throw new InvalidMediaInputException(EMPTY_MEDIA);
        if (duration > AudioPreparationContracts.MAX_DURATION_MS) throw new InvalidMediaInputException(AUDIO_TOO_LONG);
        return duration;
    }

    private static void requireSuccess(FFmpegUtil.CommandOutput result, String operation, InvalidMediaInputException.Reason reason) {
        if (result.exitCode() == 0) return;
        String detail = result.output() == null ? "" : result.output();
        String lower = detail.toLowerCase(Locale.ROOT);
        if (result.exitCode() > 0 && List.of("invalid data found when processing input", "error while decoding stream",
                "header missing", "invalid frame size", "failed to read frame size", "moov atom not found").stream().anyMatch(lower::contains))
            throw new InvalidMediaInputException(reason);
        throw new IllegalStateException(operation + "进程失败: exit=" + result.exitCode() + ", "
                + detail.substring(Math.max(0, detail.length() - 1800)));
    }
}
