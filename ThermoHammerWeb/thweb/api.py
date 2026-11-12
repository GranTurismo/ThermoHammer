"""Reads the public leaderboard API (read-only GETs) and keeps the answers on disk.

A stress run's samples never change, so they are fetched once. Everything else is asked again after a while. If the
API can't be reached, the last answer is used, so one bad minute never takes the site down.
"""
from __future__ import annotations

import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

FOREVER = float("inf")


class ApiError(RuntimeError):
    pass


class Api:
    def __init__(self, base: str, cache: Path, timeout: float = 30.0):
        self.base = base.rstrip("/")
        self.dir = cache / "api"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.timeout = timeout
        self.requests = 0

    def _file(self, path: str) -> Path:
        return self.dir / (hashlib.sha1(path.encode()).hexdigest()[:20] + ".json")

    def get(self, path: str, ttl: float = 0.0, keep_missing: bool = False):
        """The JSON at `path`, or None for a 404. `ttl` seconds of age are fine; `keep_missing` remembers a 404 too."""
        file = self._file(path)
        cached = None
        if file.exists():
            try:
                cached = json.loads(file.read_text())
            except (OSError, ValueError):
                cached = None
        if cached is not None:
            age = time.time() - cached.get("at", 0)
            if cached.get("status") == 200 and age <= ttl:
                return cached["body"]
            if cached.get("status") == 404 and keep_missing and age <= ttl:
                return None
        last_error: Exception | None = None
        for attempt in range(3):
            try:
                self.requests += 1
                req = urllib.request.Request(self.base + path, headers={
                    "User-Agent": "ThermoHammerWeb/1.0 (site generator)", "Accept": "application/json"})
                with urllib.request.urlopen(req, timeout=self.timeout) as res:
                    body = json.loads(res.read().decode("utf-8"))
                file.write_text(json.dumps({"at": time.time(), "status": 200, "path": path, "body": body}))
                return body
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    file.write_text(json.dumps({"at": time.time(), "status": 404, "path": path, "body": None}))
                    return None
                last_error = e
            except (urllib.error.URLError, TimeoutError, ValueError, OSError) as e:
                last_error = e
            time.sleep(1.5 * (attempt + 1))
        if cached is not None and cached.get("status") == 200:
            return cached["body"]            # stale, but real
        raise ApiError(f"GET {path} failed: {last_error}")

    # ── the endpoints the site uses ──────────────────────────────────────────────────────────────────────────────────

    def benchmark_board(self):
        return self.get("/benchmark/leaderboard") or []

    def stress_board(self):
        return self.get("/leaderboard") or []

    def benchmark_run(self, run_id: int, ttl: float = 6 * 3600, keep_missing: bool = False):
        return self.get(f"/benchmark/{run_id}", ttl=ttl, keep_missing=keep_missing)

    def stamps(self, run_id: int):
        return self.get(f"/stamps/{run_id}", ttl=FOREVER) or []

    def model_stats(self, model: str, os_id: int, manufacturer: str, ttl: float = 0.0):
        q = urllib.parse.urlencode({"model": model, "os": os_id, "manufacturer": manufacturer})
        return self.get(f"/benchmark/model?{q}", ttl=ttl)
