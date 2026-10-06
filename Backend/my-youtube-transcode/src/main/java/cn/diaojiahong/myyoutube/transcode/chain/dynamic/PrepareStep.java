package cn.diaojiahong.myyoutube.transcode.chain.dynamic;

import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PrepareStep extends TranscodeStep {

    private final ObjectMapper objectMapper;

    public PrepareStep(ObjectMapper objectMapper) {
        super(10);
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doApply(TranscodeContext ctx) throws Exception {
        TranscodeSubmitDTO params = objectMapper.readValue(ctx.getTask().getRequestJson(), TranscodeSubmitDTO.class);
        Path workRoot = Files.createTempDirectory("dynamic-" + ctx.getTask().getId() + "-");
        Path workDir = workRoot.resolve("work");
        Files.createDirectories(workDir);
        ctx.setWorkspace(workRoot, workDir, workDir.resolve("dynamic-out.mp4"));
        ctx.setParams(params);
    }
}
