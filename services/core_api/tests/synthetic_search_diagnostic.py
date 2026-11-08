"""Inspect the actual natural-language plan using only synthetic records."""
import json
from synthetic_backend_smoke import api, search

code, body = api("/api/v1/search", {"query": "穿黑色上衣背包的人"}, search, timeout=370)
result = {"http_status": code, "status": body.get("status"), "code": body.get("code"),
          "plan": body.get("plan"), "result_count": len(body.get("results", []))}
print(json.dumps(result, ensure_ascii=False), flush=True)
