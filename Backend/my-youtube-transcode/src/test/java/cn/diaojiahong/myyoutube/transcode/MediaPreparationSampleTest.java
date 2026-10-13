package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.preparation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="media.samples",matches=".+")
class MediaPreparationSampleTest {
    @Test void preparesProvidedSamplesAtThreeBudgetsWithoutTouchingOriginals() throws Exception {
        var root=Path.of(System.getProperty("media.samples"));
        var output=Path.of("target/media-preparation-samples"); Files.createDirectories(output);
        List<Path> sources;
        try(var paths=Files.list(root)) { sources=paths.filter(Files::isRegularFile).sorted().toList(); }
        var results=new ArrayList<Map<String,Object>>();
        for(int edge:List.of(1280,1600,1920)) {
            var properties=new MediaPreparationProperties(); properties.setLongEdge(edge);
            properties.setOutputPixels(Math.round(1_600_000*Math.pow(edge/1600.0,2))); properties.validate();
            var writer=new PreparedImageWriter(properties);
            var videos=new PreparedVideoReader(properties,writer);
            int imageIndex=0,videoIndex=0;
            for(var source:sources) {
                String name=source.getFileName().toString().toLowerCase(Locale.ROOT);
                long start=System.nanoTime();
                if(name.endsWith(".jpeg")||name.endsWith(".jpg")||name.endsWith(".png")) {
                    var destination=output.resolve(edge+"-image-"+imageIndex+".jpg");
                    var image=writer.write(source,destination);
                    results.add(Map.of("kind","image","index",imageIndex++,"edge",edge,"sourceBytes",Files.size(source),
                            "width",image.width(),"height",image.height(),"bytes",image.bytes(),"elapsedMs",(System.nanoTime()-start)/1_000_000));
                } else if(name.endsWith(".mp4")) {
                    byte[] head; try(var input=Files.newInputStream(source)) { head=input.readNBytes(65536); }
                    var probe=videos.probe(source.toString(),head);
                    var frames=new ArrayList<Map<String,Object>>();
                    int id=videoIndex++;
                    videos.frames(source.toString(),probe,output.resolve("work-"+edge+"-"+id),frame -> {
                        assertEquals(frames.size(),frame.index());
                        var destination=output.resolve(edge+"-video-"+id+"-frame-"+frame.index()+".jpg");
                        Files.copy(frame.path(),destination,StandardCopyOption.REPLACE_EXISTING);
                        frames.add(Map.of("index",frame.index(),"timeMs",frame.timeMs(),"width",frame.image().width(),
                                "height",frame.image().height(),"bytes",frame.image().bytes()));
                    });
                    assertEquals(8,frames.size());
                    results.add(Map.of("kind","video","index",id,"edge",edge,"durationMs",probe.durationMs(),
                            "frames",frames,"elapsedMs",(System.nanoTime()-start)/1_000_000));
                }
            }
            assertTrue(imageIndex>0); assertTrue(videoIndex>0);
        }
        Files.writeString(output.resolve("preparation-results.json"),JsonUtil.toJson(results));
    }
}
