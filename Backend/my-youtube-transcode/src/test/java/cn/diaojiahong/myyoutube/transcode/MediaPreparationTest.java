package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.transcode.preparation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class MediaPreparationTest {
    @TempDir Path work;
    final MediaPreparationProperties properties = new MediaPreparationProperties();
    final PreparedImageWriter writer = new PreparedImageWriter(properties);
    Path image(String name, int width, int height, boolean transparent) throws Exception {
        var image = new BufferedImage(width,height,transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        if (!transparent) { graphics.setColor(Color.WHITE); graphics.fillRect(0,0,width,height); }
        graphics.dispose();
        var path = work.resolve(name);
        ImageIO.write(image,"png",path.toFile()); image.flush(); return path;
    }
    @Test void detectsBytesRatherThanExtensionAndAppliesBothBudgets() throws Exception {
        var result = writer.write(image("false.jpg",3840,2160,false),work.resolve("out.jpg"));
        assertEquals(3840,result.sourceWidth());
        assertTrue(result.width() <= 1600);
        assertTrue((long) result.width() * result.height() <= 1_600_000);
        assertEquals(result.width(),ImageIO.read(work.resolve("out.jpg").toFile()).getWidth());
    }
    @Test void smallImageIsNotUpscaledAndTransparencyIsWhite() throws Exception {
        var out = work.resolve("out.jpg");
        var result = writer.write(image("transparent.png",31,19,true),out);
        assertEquals(31,result.width()); assertEquals(19,result.height());
        var output = ImageIO.read(out.toFile());
        assertTrue(new Color(output.getRGB(10,10)).getRed() > 245); output.flush();
    }
    @Test void rejectsTypeCorruptionByteAndPixelLimits() throws Exception {
        var source = image("image.png",100,100,false);
        properties.setSourceBytes(1);
        assertThrows(IllegalArgumentException.class,()->writer.write(source,work.resolve("out.jpg")));
        properties.setSourceBytes(33554432); properties.setSourcePixels(9999);
        assertThrows(IllegalArgumentException.class,()->writer.write(source,work.resolve("out.jpg")));
        var fake = work.resolve("fake.png"); Files.writeString(fake,"not an image");
        assertThrows(IllegalArgumentException.class,()->writer.write(fake,work.resolve("out.jpg")));
        Files.write(source, java.util.Arrays.copyOf(Files.readAllBytes(source),35));
        properties.setSourcePixels(40_000_000);
        assertThrows(Exception.class,()->writer.write(source,work.resolve("out.jpg")));
    }
    @Test void exifRotationIsAppliedBeforeOutputDimensions() throws Exception {
        var source = work.resolve("rotated.jpg");
        var image = new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image,"jpg",source.toFile()); image.flush();
        byte[] jpeg = Files.readAllBytes(source);
        var exif = ByteBuffer.allocate(36);
        exif.put((byte)0xff).put((byte)0xe1).putShort((short)34);
        exif.put(new byte[]{'E','x','i','f',0,0,'M','M',0,42,0,0,0,8,0,1});
        exif.putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)6).putShort((short)0).putInt(0);
        var data = new java.io.ByteArrayOutputStream();
        data.write(jpeg,0,2); data.write(exif.array()); data.write(jpeg,2,jpeg.length-2);
        Files.write(source,data.toByteArray());
        var result = writer.write(source,work.resolve("out.jpg"));
        assertEquals(6,result.orientation()); assertEquals(40,result.width()); assertEquals(80,result.height());
    }
    String probe(String video, String audio) {
        return "{\"format\":{\"format_name\":\"mov,mp4,m4a,3gp,3g2,mj2\",\"duration\":\"1.0\",\"tags\":{\"major_brand\":\"isom\"}},\"streams\":["
                + "{\"codec_type\":\"video\",\"codec_name\":\""+video+"\",\"pix_fmt\":\"yuv420p\",\"profile\":\"High\",\"level\":40,\"width\":1920,\"height\":1080}"
                + (audio == null ? "" : ",{\"codec_type\":\"audio\",\"codec_name\":\""+audio+"\"}") + "]}";
    }
    @Test void videoAllowsNoAudioAndAacButRejectsIncompatibleInputs() {
        var videos = new PreparedVideoReader(properties,writer);
        assertFalse(videos.validateProbe(probe("h264",null)).hasAudio());
        assertTrue(videos.validateProbe(probe("h264","aac")).hasAudio());
        for (String input : java.util.List.of(probe("hevc",null),probe("h264","opus"),probe("h264",null).replace("isom","3gp5"),
                probe("h264",null).replace("yuv420p","yuv420p10le"),
                probe("h264",null).replace("\"High\"","\"High 10\""),
                probe("h264",null).replace("\"level\":40","\"level\":52"),
                probe("h264",null).replace("mov,mp4,m4a,3gp,3g2,mj2","matroska,webm")))
            assertThrows(IllegalArgumentException.class,()->videos.validateProbe(input));
    }
    @Test void videoAllowsLevelFiveAndFiveOneAndReportsConfiguredRejection() {
        var videos = new PreparedVideoReader(properties,writer);
        for (int level : java.util.List.of(50, 51))
            assertEquals(level, videos.validateProbe(probe("h264","aac")
                    .replace("\"level\":40", "\"level\":" + level)).level());
        properties.setVideoMaxLevel(42);
        var error = assertThrows(IllegalArgumentException.class, () -> videos.validateProbe(
                probe("h264",null).replace("\"level\":40", "\"level\":50")));
        assertTrue(error.getMessage().contains("level=50"));
        assertTrue(error.getMessage().contains("level≤42"));
        assertTrue(error.getMessage().contains("pix_fmt=\"yuv420p\""));
    }
    @Test void videoBrandAcceptsPaddingAndReportsActualConfiguredAllowlist() {
        var videos = new PreparedVideoReader(properties,writer);
        assertFalse(videos.validateProbe(probe("h264",null).replace("isom","M4V ")).hasAudio());
        var unsupported = assertThrows(IllegalArgumentException.class,
                () -> videos.validateProbe(probe("h264",null).replace("isom","qt  ")));
        assertTrue(unsupported.getMessage().contains("major_brand=\"qt  \""));
        assertTrue(unsupported.getMessage().contains("isom"));
        properties.setVideoBrands(java.util.Set.of("mp42"));
        var configured = assertThrows(IllegalArgumentException.class,
                () -> videos.validateProbe(probe("h264",null)));
        assertTrue(configured.getMessage().contains("允许值=[mp42]"));
    }
    @Test void videoBrandMissingOrMalformedHasDistinctDiagnostic() {
        var videos = new PreparedVideoReader(properties,writer);
        for (String tags : java.util.List.of("null", "{}", "{\"major_brand\":null}",
                "{\"major_brand\":\" \"}", "{\"major_brand\":[]}")) {
            var error = assertThrows(IllegalArgumentException.class, () -> videos.validateProbe(
                    probe("h264",null).replace("{\"major_brand\":\"isom\"}",tags)));
            assertTrue(error.getMessage().contains("缺少 major_brand"));
        }
        var error = assertThrows(IllegalArgumentException.class, () -> videos.validateProbe(
                probe("h264",null).replace(",\"tags\":{\"major_brand\":\"isom\"}","")));
        assertTrue(error.getMessage().contains("缺少 major_brand"));
    }
    @Test void videoBrandAllowsRepeatedMetadataButRejectsConflictingOrEmptyValues() {
        var videos = new PreparedVideoReader(properties,writer);
        for (String brand : java.util.List.of("isom;isom", "isom; isom ;isom", "mp42;mp42", "M4V ;M4V "))
            assertFalse(videos.validateProbe(probe("h264",null).replace("isom",brand)).hasAudio());
        for (String brand : java.util.List.of("qt  ;qt  ", "isom;qt  ", "qt  ;isom", "isom;mp42",
                "isom;", ";isom", "isom;;isom", "isom; ;isom"))
            assertThrows(IllegalArgumentException.class,
                    () -> videos.validateProbe(probe("h264",null).replace("isom",brand)));
        properties.setVideoBrands(java.util.Set.of("mp42"));
        assertThrows(IllegalArgumentException.class,
                () -> videos.validateProbe(probe("h264",null).replace("isom","isom;isom")));
    }
}
