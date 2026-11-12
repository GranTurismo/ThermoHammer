#!/usr/bin/env python3
"""Builds the ThermoHammer website from the public leaderboard API.

    .venv/bin/python build.py                 one build into ./dist
    .venv/bin/python build.py --serve 8088    build, then serve ./dist on http://localhost:8088
    .venv/bin/python build.py --loop 600      rebuild whenever the leaderboard changes, checking every 10 minutes

Where it reads from and writes to comes from the environment (see thweb/config.py): TH_SITE_ORIGIN, TH_BASE_PATH,
THAPI_URL, TH_OUT, TH_CACHE, TH_NOINDEX.
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import http.server
import sys
import time
import traceback
from datetime import datetime, timezone

from thweb import config
from thweb.api import Api
from thweb.model import load_site
from thweb.site import Builder


def log(*parts) -> None:
    print(datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ"), *parts, flush=True)


def build_once(cfg: config.Config) -> str:
    started = time.time()
    api = Api(cfg.api, cfg.cache)
    site = load_site(api, config.ROOT / "data", cfg.max_pairs, log=log)
    builder = Builder(cfg, log=log)
    made = builder.build(site)
    result = builder.publish()
    log(f"built {made['pages']} pages ({site.stats['devices']} devices, {site.stats['pairs']} comparisons, "
        f"{builder.cards.made} share images drawn) in {time.time() - started:.1f}s; "
        f"{result['written']} files written, {result['removed']} removed, {api.requests} API requests → {cfg.out}")
    return site.fingerprint


def boards_fingerprint(cfg: config.Config) -> str:
    """A cheap look at the two boards: has anything changed since the last build?"""
    api = Api(cfg.api, cfg.cache)
    return hashlib.sha1(repr((api.benchmark_board(), api.stress_board())).encode()).hexdigest()


def loop(cfg: config.Config, every: int) -> None:
    last, last_full = None, 0.0
    while True:
        try:
            now = time.time()
            current = boards_fingerprint(cfg)
            if current != last or now - last_full > 24 * 3600:       # once a day regardless: dates, renamed devices
                last = build_once(cfg)
                last_full = now
        except Exception:
            log("build failed; the published site stays as it is")
            traceback.print_exc()
        time.sleep(every)


def serve(cfg: config.Config, port: int) -> None:
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(cfg.out))
    log(f"serving {cfg.out} on http://localhost:{port}{cfg.base_path}/")
    http.server.ThreadingHTTPServer(("127.0.0.1", port), handler).serve_forever()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--loop", type=int, metavar="SECONDS", help="keep running; check the leaderboard this often")
    parser.add_argument("--serve", type=int, metavar="PORT", help="after building, serve the result locally")
    args = parser.parse_args()
    cfg = config.load()
    if args.loop:
        loop(cfg, max(60, args.loop))
        return 0
    build_once(cfg)
    if args.serve:
        serve(cfg, args.serve)
    return 0


if __name__ == "__main__":
    sys.exit(main())
