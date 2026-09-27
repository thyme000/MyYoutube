import { describe, it, expect } from "vitest";
import { parseLyricsText, sniffLyricsFormat } from "../lyricsParser.js";

describe("parseLyricsText", () => {
  it("解析普通 LRC 并按时间排序", () => {
    const { lines } = parseLyricsText("[00:02.00]第一行\n[00:01.00]先出现");
    expect(lines).toHaveLength(2);
    expect(lines[0].timeMs).toBe(1000);
    expect(lines[0].text).toBe("先出现");
    expect(lines[1].timeMs).toBe(2000);
    expect(lines[1].text).toBe("第一行");
  });

  it("空行与注释行跳过, 非时间行忽略", () => {
    const { lines } = parseLyricsText("# 注释\n\n随便一段话\n[00:01.00]歌词");
    expect(lines).toHaveLength(1);
    expect(lines[0].text).toBe("歌词");
  });

  it("｜ 分隔的翻译拆为 main/translation", () => {
    const { lines } = parseLyricsText("[00:01.00]你好｜hello");
    expect(lines[0].text).toBe("你好");
    expect(lines[0].translation).toBe("hello");
  });

  it("增强 LRC 行内时间戳拆为 words", () => {
    const { lines } = parseLyricsText("[00:01.00]<00:01.00>你<00:02.50>好");
    expect(lines[0].words).toEqual([
      { timeMs: 1000, text: "你" },
      { timeMs: 2500, text: "好" },
    ]);
    expect(lines[0].text).toBe("你 好");
  });

  it("非字符串/空文本返回空 lines", () => {
    expect(parseLyricsText(null).lines).toEqual([]);
    expect(parseLyricsText("").lines).toEqual([]);
  });
});

describe("sniffLyricsFormat", () => {
  it("识别 lrc / unknown / empty", () => {
    expect(sniffLyricsFormat("[00:01.00]a")).toBe("lrc");
    expect(sniffLyricsFormat("纯文本没有标签")).toBe("unknown");
    expect(sniffLyricsFormat("")).toBe("empty");
  });

  it("长文本含控制字符判为 binary", () => {
    const binary = "\u0001\u0002\u0003\u0004\u0005" + "x".repeat(200);
    expect(sniffLyricsFormat(binary)).toBe("binary");
  });
});
