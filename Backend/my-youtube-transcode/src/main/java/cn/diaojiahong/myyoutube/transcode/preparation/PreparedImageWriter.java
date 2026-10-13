package cn.diaojiahong.myyoutube.transcode.preparation;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class PreparedImageWriter {
    private final MediaPreparationProperties properties;

    public record Result(int sourceWidth, int sourceHeight, int width, int height, int orientation, long bytes) {}

    public Result write(Path source, Path output) throws Exception {
        long started = System.nanoTime();
        if (source.equals(output) || Files.size(source) <= 0 || Files.size(source) > properties.getSourceBytes())
            throw new IllegalArgumentException("图片文件大小超限或路径无效");
        String mime;
        try (var input = Files.newInputStream(source)) {
            mime = new Tika().detect(input.readNBytes(65536));
        }
        if (!mime.startsWith("image/")) throw new IllegalArgumentException("素材不是受支持的图片");
        try (var input = ImageIO.createImageInputStream(source.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片无法解码");
            var reader = readers.next();
            BufferedImage decoded = null;
            BufferedImage result = null;
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > properties.getSourcePixels()
                        || Math.max(width, height) > properties.getSourceLongEdge())
                    throw new IllegalArgumentException("图片源尺寸超限");
                int orientation = orientation(source);
                int orientedWidth = orientation >= 5 ? height : width;
                int orientedHeight = orientation >= 5 ? width : height;
                var dimensions = dimensions(orientedWidth, orientedHeight);
                var parameters = reader.getDefaultReadParam();
                if ("JPEG".equalsIgnoreCase(reader.getFormatName())) {
                    int sample = Math.max(1, Math.min(orientedWidth / dimensions[0], orientedHeight / dimensions[1]));
                    parameters.setSourceSubsampling(sample, sample, 0, 0);
                }
                decoded = reader.read(0, parameters);
                if (decoded == null) throw new IOException("图片解码失败");
                result = new BufferedImage(dimensions[0], dimensions[1], BufferedImage.TYPE_INT_RGB);
                var graphics = result.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    int dw = decoded.getWidth(), dh = decoded.getHeight();
                    double ow = orientation >= 5 ? dh : dw, oh = orientation >= 5 ? dw : dh;
                    var transform = AffineTransform.getScaleInstance(result.getWidth() / ow, result.getHeight() / oh);
                    transform.concatenate(orientationTransform(orientation, dw, dh));
                    graphics.drawImage(decoded, transform, null);
                } finally { graphics.dispose(); }
                jpeg(result, output);
                long bytes = Files.size(output);
                log.info("Media image prepared source={}x{} output={}x{} orientation={} bytes={} elapsedMs={}",
                        width, height, result.getWidth(), result.getHeight(), orientation, bytes,
                        (System.nanoTime() - started) / 1_000_000);
                return new Result(width, height, result.getWidth(), result.getHeight(), orientation, bytes);
            } finally {
                if (decoded != null) decoded.flush();
                if (result != null) result.flush();
                reader.dispose();
            }
        }
    }

    private int[] dimensions(int width, int height) {
        double ratio = Math.min(1, Math.min(properties.getLongEdge() / (double) Math.max(width, height),
                Math.sqrt(properties.getOutputPixels() / ((double) width * height))));
        return new int[]{Math.max(1, (int) Math.floor(width * ratio)), Math.max(1, (int) Math.floor(height * ratio))};
    }

    private static int orientation(Path source) throws Exception {
        var metadata = ImageMetadataReader.readMetadata(source.toFile());
        var exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        Integer value = exif == null ? null : exif.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
        if (value != null && (value < 1 || value > 8)) throw new IllegalArgumentException("图片方向非法");
        return value == null ? 1 : value;
    }

    private static AffineTransform orientationTransform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }

    private void jpeg(BufferedImage image, Path output) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("JPEG").next();
        try (var target = ImageIO.createImageOutputStream(output.toFile())) {
            writer.setOutput(target);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(properties.getJpegQuality());
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
        } finally { writer.dispose(); }
    }
}
