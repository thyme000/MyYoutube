package cn.diaojiahong.myyoutube.common.utils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 将远程视频下载到本地临时文件，返回临时文件路径
 */
public class DownLoadMP4ToTemp {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /**
     * 下载到固定目录(resources/temp)
     */
    public static Path downloadToFixedDir(String url, Path baseDir, String suffix) throws IOException, InterruptedException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("下载URL不能为空");
        }
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir不能为空");
        }
        String ext = (suffix == null || suffix.isBlank()) ? ".mp4" : suffix;
        if (!ext.startsWith(".")) {
            ext = "." + ext;
        }
        // 按日期分目录，避免一个目录文件过多
        Path dayDir = baseDir.resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
        Files.createDirectories(dayDir);
        // 唯一文件名，防止并发覆盖
        String fileName = "video-src-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = dayDir.resolve(fileName);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<Path> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(target));
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            Files.deleteIfExists(target);
            throw new IOException("下载失败，HTTP状态码: " + code + ", url=" + url);
        }
        if (!Files.exists(target) || Files.size(target) <= 0) {
            Files.deleteIfExists(target);
            throw new IOException("下载结果为空文件, url=" + url);
        }
        return target;
    }
}
