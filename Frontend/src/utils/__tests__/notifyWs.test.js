import { describe, expect, it } from "vitest";
import {
  extractWsJsonDigitString,
  pickNotifyWsAvatarUrl,
  pickNotifyWsContent,
  pickNotifyWsConversationId,
  pickNotifyWsNickname,
  pickNotifyWsNotificationId,
  pickNotifyWsTypeCode,
  pickNotifyWsVideoId,
  previewChatIncomingWsBody,
} from "../notifyWs";

/** 超过 Number.MAX_SAFE_INTEGER 的雪花 id */
const BIG = "1234567890123456789";

describe("extractWsJsonDigitString", () => {
  it("从原文提取数字与字符串两种形态", () => {
    expect(extractWsJsonDigitString(`{"videoId":${BIG}}`, "videoId")).toBe(BIG);
    expect(extractWsJsonDigitString(`{"videoId":"${BIG}"}`, "videoId")).toBe(BIG);
  });

  it("非数字或缺失字段返回 null", () => {
    expect(extractWsJsonDigitString(`{"videoId":"abc"}`, "videoId")).toBeNull();
    expect(extractWsJsonDigitString("{}", "videoId")).toBeNull();
    expect(extractWsJsonDigitString(null, "videoId")).toBeNull();
    expect(extractWsJsonDigitString(`{"videoId":${BIG}}`, "")).toBeNull();
  });
});

describe("pickNotifyWsNotificationId", () => {
  it("优先从原文提取雪花 id，不受 JSON.parse 丢精度影响", () => {
    const raw = `{"notificationId":${BIG},"id":123}`;
    const parsed = JSON.parse(raw);
    expect(pickNotifyWsNotificationId(raw, parsed)).toBe(BIG);
  });

  it("原文缺失时回退到解析对象", () => {
    expect(pickNotifyWsNotificationId(null, { notificationId: "42" })).toBe("42");
    expect(pickNotifyWsNotificationId(null, { id: 42 })).toBe("42");
    expect(pickNotifyWsNotificationId(null, { notification_id: " 43 " })).toBe("43");
  });

  it("数字按 Math.trunc 收整后可用，非数字返回 null", () => {
    expect(pickNotifyWsNotificationId(null, { id: 1.5 })).toBe("1");
    expect(pickNotifyWsNotificationId(null, { id: "abc" })).toBeNull();
    expect(pickNotifyWsNotificationId(null, null)).toBeNull();
  });
});

describe("pickNotifyWsVideoId", () => {
  it("兼容驼峰与下划线字段", () => {
    expect(pickNotifyWsVideoId(`{"video_id":${BIG}}`, {})).toBe(BIG);
    expect(pickNotifyWsVideoId(null, { videoId: 7 })).toBe("7");
    expect(pickNotifyWsVideoId(null, {})).toBeNull();
  });
});

describe("pickNotifyWsConversationId", () => {
  it("会话 id 是 Mongo 字符串，不能用数字专用提取", () => {
    expect(pickNotifyWsConversationId(`{"conversationId":"conv-${BIG}"}`, {})).toBe(`conv-${BIG}`);
    expect(pickNotifyWsConversationId(null, { conversationId: "abc" })).toBe("abc");
    expect(pickNotifyWsConversationId(null, { conversation_id: " " })).toBeNull();
    expect(pickNotifyWsConversationId(null, {})).toBeNull();
  });
});

describe("pickNotifyWsContent", () => {
  it("按优先级取正文并支持嵌套对象", () => {
    expect(pickNotifyWsContent({ commentContent: "评论了你的视频" })).toBe("评论了你的视频");
    expect(pickNotifyWsContent({ data: { content: "nested" } })).toBe("nested");
    expect(pickNotifyWsContent({ msg: { body: "body-text" } })).toBe("body-text");
    expect(pickNotifyWsContent({ content: 0 })).toBe("0");
    expect(pickNotifyWsContent({ content: "  " })).toBe("");
    expect(pickNotifyWsContent(null)).toBe("");
  });
});

describe("pickNotifyWsNickname / AvatarUrl / TypeCode", () => {
  it("多候选字段取第一个非空值", () => {
    expect(pickNotifyWsNickname({ nickname: "thyme" })).toBe("thyme");
    expect(pickNotifyWsNickname({ senderName: "  " })).toBe("");
    expect(pickNotifyWsAvatarUrl({ sender_avatar_url: "http://a/b.png" })).toBe("http://a/b.png");
    expect(pickNotifyWsTypeCode({ type: { code: "LIKE" } })).toBe("LIKE");
    expect(pickNotifyWsTypeCode({ type: { name: "COMMENT" } })).toBe("COMMENT");
    expect(pickNotifyWsTypeCode({})).toBe("");
  });
});

describe("previewChatIncomingWsBody", () => {
  it("contentType 枚举名或对象均可识别", () => {
    expect(previewChatIncomingWsBody({ contentType: "TEXT", content: "hi" })).toBe("hi");
    expect(previewChatIncomingWsBody({ contentType: { name: "IMAGE" } })).toBe("[图片]");
    expect(previewChatIncomingWsBody({ contentType: "FILE" })).toBe("[文件]");
    expect(previewChatIncomingWsBody({ contentType: "SYSTEM" })).toBe("[系统消息]");
    expect(previewChatIncomingWsBody(null)).toBe("[消息]");
    expect(previewChatIncomingWsBody({ contentType: "AUDIO" })).toBe("[消息]");
  });
});
