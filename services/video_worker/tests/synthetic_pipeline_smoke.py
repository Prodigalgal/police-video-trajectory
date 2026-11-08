"""Verify generated archive -> worker -> fake model -> outbox -> ES -> key frame."""
import base64
import http.client
import json
import os
from pathlib import Path
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request


es_tls = ssl.create_default_context(cafile="/es-ca/ca.crt")
core_tls = ssl.create_default_context(cafile="/core-ca/ca.crt")
es_password = Path("/es-token/reader-password").read_text().strip()
search_token = Path("/core-token/search-token").read_text().strip()
auth = "Basic " + base64.b64encode(("police_video_reader:" + es_password).encode()).decode()
base = "https://elasticsearch.police-video-trial.svc:9200"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=es_tls))


def es(path, body=None):
    req = urllib.request.Request(base + path, headers={"Authorization": auth, "Content-Type": "application/json"},
                                 method="POST" if body is not None else "GET",
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with opener.open(req, timeout=15) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as exc:
        return exc.code, {}


class SyntheticCore(http.client.HTTPSConnection):
    def connect(self):
        raw = socket.create_connection(("core-api-synthetic.police-video-trial.svc", 8444), self.timeout)
        self.sock = core_tls.wrap_socket(raw, server_hostname="core-api.police-video-trial.svc")


def main():
    mode = os.environ.get("SYNTHETIC_MODE", "archive")
    if mode not in {"archive", "rtsp"}:
        raise ValueError("invalid synthetic mode")
    expected_origin = "unknown" if mode == "archive" else "simulated"
    filters = [{"term": {"model_id": "synthetic-fake"}}, {"term": {"source_time_origin": expected_origin}}]
    if mode == "archive":
        # Old test documents predate replay metadata; do not mistake those for this run.
        filters.append({"term": {"source_type": "archive"}})
        filters.append({"exists": {"field": "media_offset_ms"}})
    # The box and uncertainty metadata distinguish this test from earlier test runs.
    filters.append({"exists": {"field": "bbox_xyxy"}})
    query = {"size": 5, "sort": [{"received_at": "desc"}], "query": {"bool": {"filter": filters}}}
    for _ in range(90):
        code, result = es("/police-video-observations-synthetic/_search", query)
        hits = result.get("hits", {}).get("hits", [])
        if code == 200 and hits:
            break
        time.sleep(2)
    else:
        raise RuntimeError("synthetic worker output not indexed")
    hit = hits[0]
    doc = hit["_source"]
    assert doc["target_type"] == "person" and doc["upper_color"] == "蓝色" and doc["carrying"] == ["背包"]
    assert doc["source_time_origin"] == expected_origin and doc.get("source_time") is None
    assert doc["bbox_xyxy"] == [10.0, 20.0, 60.0, 100.0] and doc["uncertain_fields"] == ["lower_color"]
    assert "lower_color" not in doc  # Unknown values must not become exact attribute matches.
    assert es("/police-video-observations/_doc/" + hit["_id"])[0] == 404
    core = SyntheticCore("core-api.police-video-trial.svc", 8444, timeout=30, context=core_tls)
    core.request("GET", "/api/v1/frames/" + doc["frame_id"] + "/image", headers={"Authorization": "Bearer " + search_token})
    response = core.getresponse()
    image = response.read(8 * 1024 * 1024 + 1)
    assert response.status == 200 and image.startswith(b"\xff\xd8\xff") and len(image) <= 8 * 1024 * 1024
    if mode == "archive":
        assert doc["source_type"] == "archive" and doc["media_offset_ms"] == 0
        path = "/api/v1/frames/" + doc["frame_id"] + "/archive"
        core.request("GET", path, headers={"Authorization": "Bearer " + search_token, "Range": "bytes=0-63"})
        replay = core.getresponse()
        first_bytes = replay.read()
        assert replay.status == 206 and len(first_bytes) == 64
        assert replay.getheader("Content-Range", "").startswith("bytes 0-63/")
        assert replay.getheader("Content-Type", "").startswith("video/mp4") and first_bytes[4:8] == b"ftyp"
        core.request("GET", path, headers={"Authorization": "Bearer " + search_token, "Range": "bytes=999999999-"})
        invalid = core.getresponse()
        invalid.read()
        assert invalid.status == 416 and invalid.getheader("Content-Range", "").startswith("bytes */")
        core.request("GET", path, headers={"Range": "bytes=0-63"})
        unauthorized = core.getresponse()
        unauthorized.read()
        assert unauthorized.status == 401
    core.close()
    suffix = ", byte-range replay, invalid range and authorization" if mode == "archive" else ""
    print("Synthetic " + mode + " decoded, queued, analyzed by local test double, indexed and retrieved as a key frame" + suffix + ".")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        tb = exc.__traceback__
        while tb and tb.tb_next:
            tb = tb.tb_next
        print("Synthetic video pipeline failed:", type(exc).__name__, "line", tb.tb_lineno if tb else "unknown", flush=True)
        sys.exit(1)
