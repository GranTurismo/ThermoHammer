"""Where the site lives and where its data comes from. Everything can be overridden from the environment."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Config:
    origin: str          # scheme + host the pages are served from, no trailing slash
    base_path: str       # "" at the root of a host, or "/something" when the site lives in a folder
    api: str             # the leaderboard API
    out: Path            # where the finished site is written
    cache: Path          # API answers and rendered share images
    noindex: bool        # a preview copy: every page asks search engines to stay away
    max_pairs: int       # most comparison pages to make

    @property
    def site_url(self) -> str:
        return self.origin + self.base_path

    def url(self, path: str = "/") -> str:
        """A link inside the site, e.g. url('/leaderboard/')."""
        if not path.startswith("/"):
            path = "/" + path
        return self.base_path + path

    def abs_url(self, path: str = "/") -> str:
        return self.origin + self.url(path)

    # links that do not depend on where the site is hosted
    play_url = "https://play.google.com/store/apps/details?id=com.granturismo.thermohammer"
    appstore_url = "https://apps.apple.com/app/thermohammer/id6791439615"
    appstore_id = "6791439615"
    privacy_url = "https://gtgroup.dev/thpolicy.html"
    support_url = "https://gtgroup.dev/thermohammer.html"
    support_email = "kupatadze2000@outlook.com"
    publisher = "GranTurismo Group"


def load() -> Config:
    env = os.environ
    return Config(
        origin=env.get("TH_SITE_ORIGIN", "https://thermohammer.gtgroup.dev").rstrip("/"),
        base_path=env.get("TH_BASE_PATH", "").rstrip("/"),
        api=env.get("THAPI_URL", "https://thapi.gtgroup.dev").rstrip("/"),
        out=Path(env.get("TH_OUT", ROOT / "dist")),
        cache=Path(env.get("TH_CACHE", ROOT / ".cache")),
        noindex=env.get("TH_NOINDEX", "") not in ("", "0", "false"),
        max_pairs=int(env.get("TH_MAX_PAIRS", "5000")),
    )
