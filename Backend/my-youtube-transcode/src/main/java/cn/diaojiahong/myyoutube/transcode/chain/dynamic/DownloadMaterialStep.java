package cn.diaojiahong.myyoutube.transcode.chain.dynamic;

import cn.diaojiahong.myyoutube.common.utils.DownLoadMP4ToTemp;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class DownloadMaterialStep extends TranscodeStep {

    private static final long STORAGE_SIGN_SECONDS = 3600L;

    private final S3StorageUtil storageUtil;

    public DownloadMaterialStep(S3StorageUtil storageUtil) {
        super(20);
        this.storageUtil = storageUtil;
    }

    @Override
    protected void doApply(TranscodeContext ctx) throws IOException, InterruptedException {
        List<Path> imagePaths = new ArrayList<>();
        for (var item : ctx.getParams().getItems()) {
            imagePaths.add(downloadTo(item.getImageObjectKey(), ctx.getWorkDir()));
        }
        Path audioPath = StringUtils.hasText(ctx.getParams().getAudioObjectKey())
                ? downloadTo(ctx.getParams().getAudioObjectKey(), ctx.getWorkDir()) : null;
        ctx.setMaterials(imagePaths, audioPath);
    }

    private Path downloadTo(String objectKey, Path workDir) throws IOException, InterruptedException {
        String url = storageUtil.signGetUrl(objectKey, STORAGE_SIGN_SECONDS);
        return DownLoadMP4ToTemp.downloadToFixedDir(url, workDir, suffixFromObjectKey(objectKey));
    }

    private static String suffixFromObjectKey(String key) {
        if (!StringUtils.hasText(key)) {
            return ".bin";
        }
        int dot = key.lastIndexOf('.');
        if (dot < 0 || dot >= key.length() - 1) {
            return ".img";
        }
        String ext = key.substring(dot).toLowerCase(Locale.ROOT);
        if (ext.matches("\\.(jpg|jpeg|png|webp|gif|bmp|mp3|m4a|aac|wav|flac|ogg)")) {
            return ext;
        }
        return ".bin";
    }
}
