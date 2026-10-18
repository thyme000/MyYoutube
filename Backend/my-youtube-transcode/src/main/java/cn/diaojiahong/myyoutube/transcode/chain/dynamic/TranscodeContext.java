package cn.diaojiahong.myyoutube.transcode.chain.dynamic;

import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import lombok.Getter;

import java.nio.file.Path;
import java.util.List;

/**
 * 动态转码管线上下文: 各阶段产出(临时目录/素材路径/成片/上传结果)沉淀在此,
 * 失败兜底与 finally 清理都要从这里取。
 */
@Getter
public class TranscodeContext {

    private final TranscodeTask task;
    /** 建上下文即链执行起点, 供完成日志算端到端耗时 */
    private final long startedAtMillis = System.currentTimeMillis();

    private TranscodeSubmitDTO params;
    private Path workRoot;
    private Path workDir;
    private Path output;
    private List<Path> imagePaths;
    private Path audioPath;
    private String objectKey;
    private long mp4Size;

    private TranscodeContext(TranscodeTask task) {
        this.task = task;
    }

    public static TranscodeContext of(TranscodeTask task) {
        return new TranscodeContext(task);
    }

    public void setParams(TranscodeSubmitDTO params) {
        this.params = params;
    }

    public void setWorkspace(Path workRoot, Path workDir, Path output) {
        this.workRoot = workRoot;
        this.workDir = workDir;
        this.output = output;
    }

    public void setMaterials(List<Path> imagePaths, Path audioPath) {
        this.imagePaths = imagePaths;
        this.audioPath = audioPath;
    }

    public void setResult(String objectKey, long mp4Size) {
        this.objectKey = objectKey;
        this.mp4Size = mp4Size;
    }
}
