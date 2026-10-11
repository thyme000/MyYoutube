"""Exercise final public contracts against the disposable, fully started services."""
import argparse
import json
from pathlib import Path
import struct
import subprocess
import signal
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

GATEWAY = "http://127.0.0.1:18080"


def api(path, token=None, body=None, method=None, ok=True):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(GATEWAY + path, data=None if body is None else json.dumps(body).encode(),
                                     headers=headers, method=method or ("POST" if body is not None else "GET"))
    try:
        with urllib.request.urlopen(request, timeout=150) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        result = {"code": error.code, "message": error.reason}
    if ok:
        if result.get("code") != 200:
            raise AssertionError(f"{path}: {result.get('code')} {result.get('message')}")
        return result["data"]
    return result


def until(description, function, timeout=240):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = function()
        if result:
            print(f"PASS {description}", flush=True)
            return result
        time.sleep(2)
    raise TimeoutError(description)


def png(color):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    width, height = 480, 640
    pixels = (b"\0" + bytes(color) * width) * height
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")


def upload(token, biz, name, data, mime):
    signed = api("/storage/presign?" + urllib.parse.urlencode({"bizType": biz, "filename": name}), token)
    if signed["uploadType"] == "put":
        request = urllib.request.Request(signed["url"], data=data, method="PUT", headers={"Content-Type": mime})
    else:
        boundary = uuid.uuid4().hex
        parts = []
        for key, value in {**signed["formFields"], "Content-Type": mime}.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: {mime}\r\n\r\n'.encode())
        parts.extend([data, f"\r\n--{boundary}--\r\n".encode()])
        request = urllib.request.Request(signed["url"], data=b"".join(parts), method="POST",
                                         headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(request, timeout=30) as response:
        assert response.status in (200, 201, 204)
    return signed


def main():
    def terminate(*_):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, terminate)
    parser = argparse.ArgumentParser()
    parser.add_argument("--speech", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    owner = until("gateway login", lambda: api("/login", body={"username": "thyme", "password": "password"}))["accessToken"]
    viewer = api("/login", body={"username": "test", "password": "password"})["accessToken"]
    assert api("/contents/mine", ok=False)["code"] == 401
    assert api("/internal/content/1", owner, ok=False)["code"] in (401, 404)
    api("/follow/1", viewer, body={})
    api("/follow/2", owner, body={})

    events = args.output / "sse-events.log"
    stream = events.open("w")
    connection = subprocess.Popen(["curl", "--silent", "--show-error", "--no-buffer", "--max-time", "600", "-K", "-"],
                                  stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.DEVNULL, text=True)
    connection.stdin.write(f'url = "{GATEWAY}/sse/notify"\nheader = "Authorization: Bearer {owner}"\n')
    connection.stdin.close()
    try:
        until("SSE connected through gateway", lambda: "CONNECTED" in events.read_text(), timeout=15)
        conversation = api("/chat/conversation/create", owner,
                           {"type": "SINGLE", "memberIds": ["2"], "name": "test", "avatar": ""})["id"]
        images = [upload(owner, "picture", f"image-{i}.png", png(color), "image/png")["objectKey"]
                  for i, color in enumerate([(70, 130, 190), (180, 120, 60)])]
        dynamic = api("/contents/drafts/dynamic", owner, {"title": "太阳系科普图集", "description": "介绍地球围绕太阳公转",
                "canvasType": "vertical", "transition": "fade", "visibility": "public", "allowComment": 1,
                "coverImageObjectKey": images[1], "items": [{"imageObjectKey": key, "durationMs": 3000} for key in images]})
        video_file = args.output / "speech.mp4"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=blue:s=320x240:r=10",
                        "-i", str(args.speech), "-t", "10", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", str(video_file)], check=True)
        video = upload(owner, "work", "speech.mp4", video_file.read_bytes(), "video/mp4")
        event = {"Records": [{"eventName": "s3:ObjectCreated:Post", "s3": {"object": {
                "key": video["objectKey"], "size": video_file.stat().st_size}}}]}
        # RustFS notification wiring is deployment-specific; replay its webhook payload into the real MQ bridge.
        request = urllib.request.Request("http://127.0.0.1:18086/internal/media/storage-event?token=content-it-only",
                                         data=json.dumps(event).encode(), headers={"Content-Type": "application/json"})
        urllib.request.urlopen(request, timeout=30).close()
        video_id = video["draftId"]
        until("video draft READY after real transcode", lambda: api(f"/contents/drafts/{video_id}", owner)["status"] == "ready")
        api(f"/contents/drafts/{video_id}/metadata", owner,
            {"title": "太阳系科普视频", "description": "介绍地球与太阳的关系", "visibility": "public", "allowComment": 1}, "PUT")
        published_ids = set()
        for kind, draft_id in [("dynamic", dynamic["draftId"]), ("video", video_id)]:
            submitted = api(f"/contents/drafts/{draft_id}/submit", owner, {})
            repeated = api(f"/contents/drafts/{draft_id}/submit", owner, {})
            assert submitted["contentId"] == repeated["contentId"] and submitted["pipelineId"] == repeated["pipelineId"]
            content_id = submitted["contentId"]
            published_ids.add(str(content_id))
            detail = until(f"{kind} processed, reviewed and published", lambda: published(content_id, viewer))
            assert detail["type"] == kind and detail["media"]["type"] == kind
            assert detail["summary"]
            if kind == "dynamic":
                assert detail["chapters"] == [] and len(detail["media"]["dynamic"]["items"]) == 2
                assert int(detail["media"]["dynamic"]["exportDurationMs"]) == 5200
            until(f"{kind} listed", lambda: any(str(row["id"]) == str(content_id)
                    for row in api("/contents/author/1?page=1&pageSize=10", viewer)["list"]))
            until(f"{kind} search document propagated", lambda: indexed(content_id, "public"))
            download = api(f"/contents/{content_id}/download", viewer, {})
            if download["taskId"]:
                download = until(f"{kind} download task DONE", lambda: completed(download["taskId"], viewer))
            with urllib.request.urlopen(download["downloadUrl"], timeout=30) as response:
                exported = args.output / f"{kind}-download.mp4"
                exported.write_bytes(response.read())
            duration = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=nw=1:nk=1", str(exported)], text=True))
            assert abs(duration - (5.2 if kind == "dynamic" else 10)) < 0.3
            forward = {"conversationId": conversation, "senderId": "1", "clientMsgId": str(uuid.uuid4()),
                       "contentType": "FORWARD_CONTENT", "content": {"contentId": content_id}}
            first = api("/chat/message/send", owner, forward)
            assert api("/chat/message/send", owner, forward)["messageId"] == first["messageId"]
            api(f"/contents/{content_id}/settings", owner, {"visibility": "private", "allowComment": 0}, "PUT")
            assert api(f"/contents/{content_id}", viewer, ok=False)["code"] != 200
            assert api(f"/contents/{content_id}/download", viewer, {}, ok=False)["code"] != 200
            forward["clientMsgId"] = str(uuid.uuid4())
            forward["senderId"] = "2"
            assert api("/chat/message/send", viewer, forward, ok=False)["code"] != 200
            assert api(f"/contents/{content_id}", owner)["allowComment"] == 0
            until(f"{kind} search privacy propagated", lambda: indexed(content_id, "private"))
            print(f"PASS {kind} detail/download/forward/retry/privacy", flush=True)
        until("author counter propagated", lambda: int(api("/user/info", owner)["contentCount"]) == 2)
        until("publication notifications stored for author", lambda: published_ids.issubset({str(row["refId"])
                for row in api("/notification/list", owner, {"pageNo": 1, "pageSize": 20}, "PUT")["list"]
                if row["refType"] == "CONTENT"}))
        until("publication SSE delivered", lambda: "NOTIFY" in events.read_text())
        print("WORKFLOW PASSED", flush=True)
    finally:
        connection.terminate()
        try:
            connection.wait(timeout=5)
        except subprocess.TimeoutExpired:
            connection.kill(); connection.wait()
        stream.close()


def published(content_id, token):
    result = api(f"/contents/{content_id}", token, ok=False)
    return result.get("data") if result.get("code") == 200 else None


def completed(task_id, token):
    result = api(f"/contents/download-tasks/{task_id}", token)
    if result["status"] == "FAILED":
        raise AssertionError(result["errorMessage"])
    return result if result["status"] == "DONE" else None


def indexed(content_id, visibility):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:19200/content/_doc/{content_id}", timeout=10) as response:
            source = json.load(response)["_source"]
            return source["visibility"] == visibility and source["status"] == "published"
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return False
        raise


if __name__ == "__main__":
    main()
