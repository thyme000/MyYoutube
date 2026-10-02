import { describe, it, expect } from "vitest";
import { videoDownloadName } from "../downloadFile";

describe("videoDownloadName", () => {
  it("用条目 id 组名，扩展名从直链推断", () => {
    expect(videoDownloadName({ id: "12", videoUrl: "https://cdn/a/b.mov" })).toBe("video-12.mov");
  });

  it("预签名地址带 query 时也能取到扩展名", () => {
    expect(videoDownloadName({ id: "12", videoUrl: "https://cdn/a.webm?X-Amz-Signature=abc" })).toBe(
      "video-12.webm"
    );
  });

  it("白名单外的扩展名一律回落 mp4（m3u8 不是可直接保存的文件）", () => {
    expect(videoDownloadName({ id: "12", videoUrl: "https://cdn/a.m3u8" })).toBe("video-12.mp4");
    expect(videoDownloadName({ id: "12", videoUrl: "https://cdn/a.exe" })).toBe("video-12.mp4");
  });

  it("没有地址或没有 id 时仍给出可用文件名", () => {
    expect(videoDownloadName({ id: "9" })).toBe("video-9.mp4");
    expect(videoDownloadName(null)).toBe("video-unknown.mp4");
  });
});
