"""Exercise only the isolated synthetic DB and index; never read user video."""
import base64
import hashlib
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
import uuid


HOST = "core-api.police-video-trial.svc"
DESTINATION = "core-api-synthetic.police-video-trial.svc"
ES = "https://elasticsearch.police-video-trial.svc:9200"
INDEX = "police-video-observations-synthetic"
core_tls = ssl.create_default_context(cafile="/core-ca/ca.crt")
es_tls = ssl.create_default_context(cafile="/es-ca/ca.crt")
worker = Path("/tokens/worker-token").read_text().strip()
search = Path("/tokens/search-token").read_text().strip()
es_password = Path("/es-token/reader-password").read_text().strip()
es_auth = "Basic " + base64.b64encode(("police_video_reader:" + es_password).encode()).decode()


class SyntheticCore(http.client.HTTPSConnection):
    def connect(self):
        raw = socket.create_connection((DESTINATION, 8444), self.timeout)
        self.sock = core_tls.wrap_socket(raw, server_hostname=HOST)


def api(path, body=None, token=None, timeout=30):
    connection = SyntheticCore(HOST, 8444, timeout=timeout, context=core_tls)
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    connection.request("POST" if body is not None else "GET", path,
                       body=json.dumps(body, ensure_ascii=False).encode() if body is not None else None, headers=headers)
    response = connection.getresponse()
    data = response.read(2 * 1024 * 1024 + 1)
    connection.close()
    if len(data) > 2 * 1024 * 1024:
        raise RuntimeError("response too large")
    return response.status, json.loads(data)


def es(path, body=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=es_tls))
    req = urllib.request.Request(ES + path, method="POST" if body is not None else "GET",
                                 headers={"Authorization": es_auth, "Content-Type": "application/json"},
                                 data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None)
    try:
        with opener.open(req, timeout=15) as result:
            return result.status, json.loads(result.read())
    except urllib.error.HTTPError as exc:
        return exc.code, {}


def observation_id(frame, ordinal):
    digest = hashlib.md5((str(frame) + ":" + str(ordinal)).encode(), usedforsecurity=False).digest()
    return str(uuid.UUID(bytes=digest, version=3))


def main():
    for _ in range(90):
        try:
            if api("/healthz", timeout=3)[0] == 200:
                break
        except (OSError, ssl.SSLError, http.client.HTTPException):
            time.sleep(2)
    else:
        raise RuntimeError("synthetic API did not become ready")
    assert api("/internal/v1/tasks/claim", {"workerId": "synthetic-worker", "sourceType": "rtsp", "leaseSeconds": 600})[0] == 401
    code, body = api("/internal/v1/tasks/claim", {"workerId": "synthetic-worker", "sourceType": "rtsp", "leaseSeconds": 600}, worker)
    assert code == 200 and "task" in body
    task = body["task"]
    assert task["sourceRef"] == "synthetic:geometry" and task["sourceTimeOrigin"] == "simulated"
    frame_id = uuid.uuid4()
    frame = {"frameId": str(frame_id), "taskId": task["taskId"], "leaseOwner": "synthetic-worker", "generation": task["generation"],
             "evidenceRef": "evidence:" + frame_id.hex, "mediaOffsetMs": 1000, "sourceTime": None,
             "modelId": "synthetic-model", "promptVersion": "synthetic-v1", "objects": [
                 {"objectType": "person", "attributes": {"upper_color": "黑色", "carrying": []}, "description": "穿黑色上衣的人"},
                 {"objectType": "person", "attributes": {"upper_color": "红色", "carrying": ["背包"]}, "description": "穿红色上衣背包的人"},
                 {"objectType": "person", "attributes": {"upper_color": "黑色", "carrying": ["背包"]}, "description": "穿黑色上衣背包的人"}]}
    assert api("/internal/v1/frames", frame, worker) == (200, {"objects": 3})
    assert api("/internal/v1/frames", frame, worker) == (200, {"objects": 3})
    changed = json.loads(json.dumps(frame))
    changed["objects"][0]["attributes"]["upper_color"] = "白色"
    assert api("/internal/v1/frames", changed, worker)[0] == 409
    stale = {**frame, "generation": frame["generation"] + 1}
    assert api("/internal/v1/frames", stale, worker)[0] == 409
    print("TLS, authentication, task lease, atomic ingestion, replay and stale-lease rejection passed.", flush=True)

    queued_frame = uuid.uuid4()
    submission = {"frameId": str(queued_frame), "taskId": task["taskId"], "workerId": "synthetic-worker",
                  "generation": task["generation"], "evidenceRef": "evidence:" + queued_frame.hex,
                  "mediaOffsetMs": 2000, "sourceTime": None}
    code, enqueued = api("/internal/v1/frames/enqueue", submission, worker)
    assert code == 200 and api("/internal/v1/frames/enqueue", submission, worker) == (200, enqueued)
    code, claimed = api("/internal/v1/analysis/claim", {"workerId": "synthetic-analyzer", "leaseSeconds": 600}, worker)
    assert code == 200 and claimed["job"]["jobId"] == enqueued["jobId"]
    job = claimed["job"]
    failure = {"workerId": "synthetic-analyzer", "generation": job["generation"], "errorCode": "model_unavailable"}
    assert api("/internal/v1/analysis/" + job["jobId"] + "/fail", failure, worker)[0] == 200
    time.sleep(6)
    code, claimed_again = api("/internal/v1/analysis/claim", {"workerId": "synthetic-analyzer", "leaseSeconds": 600}, worker)
    assert code == 200 and claimed_again["job"]["jobId"] == job["jobId"] and claimed_again["job"]["generation"] > job["generation"]
    assert api("/internal/v1/analysis/" + job["jobId"] + "/fail", failure, worker)[0] == 409
    completion = {"jobId": job["jobId"], "workerId": "synthetic-analyzer", "generation": claimed_again["job"]["generation"],
                  "modelId": "synthetic-model", "promptVersion": "synthetic-v1", "objects": [
                      {"objectType": "vehicle", "attributes": {"transport_type": "汽车", "carrying": []}, "description": "合成汽车"}]}
    assert api("/internal/v1/analysis/complete", completion, worker) == (200, {"objects": 1})
    assert api("/internal/v1/analysis/complete", completion, worker) == (200, {"objects": 1})
    print("Durable analysis queue, delayed retry, idempotent completion and obsolete analyzer rejection passed.", flush=True)

    target_id = observation_id(frame_id, 2)
    for _ in range(60):
        code, document = es(f"/{INDEX}/_doc/{target_id}")
        if code == 200 and document.get("_source", {}).get("deleted") is False:
            break
        time.sleep(2)
    else:
        raise RuntimeError("outbox did not reach synthetic index")
    assert es(f"/police-video-observations/_doc/{target_id}")[0] == 404
    query = {"query": {"bool": {"filter": [{"term": {"frame_id": str(frame_id)}}, {"term": {"deleted": False}},
                                                   {"match": {"upper_color": "黑色"}}, {"match": {"carrying": "背包"}}]}}}
    for _ in range(10):
        code, result = es(f"/{INDEX}/_search", query)
        hits = result.get("hits", {}).get("hits", [])
        if code == 200 and len(hits) == 1:
            break
        time.sleep(1)
    assert len(hits) == 1 and hits[0]["_id"] == target_id
    print("Outbox indexing, synthetic isolation and same-object ES matching passed.", flush=True)

    code, found = api("/api/v1/search", {"query": "穿黑色上衣背包的人"}, search, timeout=370)
    print("Synthetic natural-language API status:", code, "result:", found.get("status", "error"), "code:", found.get("code", "none"), flush=True)
    if code == 200 and found.get("status") == "ok":
        print("Synthetic plan:", json.dumps(found.get("plan"), ensure_ascii=False), "result_count:", len(found.get("results", [])), flush=True)
    assert code == 200 and found.get("status") == "ok"
    assert any(item.get("observation_id") == target_id for item in found.get("results", []))
    assert found.get("coverage", {}).get("status") == "unknown"
    print("Real streaming text model -> validated plan -> Elasticsearch search passed on synthetic records.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        tb = exc.__traceback__
        while tb and tb.tb_next:
            tb = tb.tb_next
        print("Synthetic backend test failed:", type(exc).__name__, "line", tb.tb_lineno if tb else "unknown", flush=True)
        sys.exit(1)
