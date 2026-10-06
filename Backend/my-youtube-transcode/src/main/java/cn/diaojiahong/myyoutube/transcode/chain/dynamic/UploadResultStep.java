package cn.diaojiahong.myyoutube.transcode.chain.dynamic;

import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import org.springframework.stereotype.Component;

import java.nio.file.Files;

@Component
public class UploadResultStep extends TranscodeStep {

    private final S3StorageUtil storageUtil;

    public UploadResultStep(S3StorageUtil storageUtil) {
        super(40);
        this.storageUtil = storageUtil;
    }

    @Override
    protected void doApply(TranscodeContext ctx) throws Exception {
        byte[] mp4 = Files.readAllBytes(ctx.getOutput());
        String objectKey = storageUtil.uploadForObjectKey(
                mp4,
                "dynamic-" + ctx.getTask().getTaskKey().substring(0, 16) + ".mp4",
                ctx.getTask().getUserId(),
                "transcode"
        );
        ctx.setResult(objectKey, mp4.length);
    }
}
