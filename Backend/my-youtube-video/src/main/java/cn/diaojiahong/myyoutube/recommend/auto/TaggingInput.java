package cn.diaojiahong.myyoutube.recommend.auto;

import java.util.List;

public record TaggingInput(String text, List<Image> images) {
    public TaggingInput { images = List.copyOf(images); }
    public static TaggingInput ofText(String text) { return new TaggingInput(text, List.of()); }
    public record Image(String url) {}
}
