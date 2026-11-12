# ThermoHammer website

The public site for ThermoHammer: landing page, benchmark and throttling leaderboards, one page per device,
head-to-head comparisons, "how it works" and lab notes. Everything on it is generated from the public leaderboard
API (`thapi.gtgroup.dev`), so it shows what the apps show.

It is a static site: a Python script reads the API and writes plain HTML, CSS, a little JavaScript, SVG charts and
share images. There is no framework and no database, and the pages work without JavaScript.

## Build it locally

```
cd ThermoHammerWeb
python3 -m venv .venv && .venv/bin/pip install pillow jinja2 fonttools brotli segno     # once
.venv/bin/python build.py --serve 8088                                            # → http://localhost:8088
```

The first build fetches every stress run's samples (about a minute); after that a build takes a few seconds.
`dist/` is the finished site, `.cache/` holds API answers and share images. Neither is checked in.

## How it is put together

| Path | What it is |
| --- | --- |
| `build.py` | The command: build once, `--serve` locally, or `--loop` on the server |
| `thweb/api.py` | Read-only calls to the API, cached on disk; falls back to the last answer if the API is down |
| `thweb/model.py` | Devices, runs, rankings, comparisons (the apps' own comparison rules) |
| `thweb/charts.py` | The charts, as inline SVG: throughput ribbons, sparklines, radars |
| `thweb/og.py` | Share images (1200×630) for every page, drawn with Pillow |
| `thweb/site.py` | Renders pages, fingerprints assets, writes the sitemap, structured data, `llms.txt` |
| `thweb/content.py` | Words that don't come from the API: the test catalogue, the stress-test phases, the FAQ |
| `templates/` | One Jinja template per kind of page |
| `static/` | The stylesheet, the script, web fonts, icons, screenshots (served under `/assets/` with a hash in each name) |
| `data/device_aliases.tsv` | Readable names for devices stored under a code (`iPad (iPad14,1)` → `iPad mini (6th generation)`) |
| `data/soc_names.tsv` | Chip names for the codes devices report (`SM8750` → `Snapdragon 8 Elite`) |
| `assets-src/` | Source fonts, icon and screenshots; `tools/prepare_assets.py` turns them into `static/` |

The design uses the apps' own tokens (`ForgeTheme`): the same colours, the Thermal Ramp, JetBrains Mono and Space Grotesk.

### Pages for single runs (what the apps link to)

Every verified run has its own pages, so a link shared from an app lands on that exact run. They are left out of the
sitemap and marked `noindex` (hundreds of near-identical pages); the device pages are the ones meant for search.

| Path | What it is |
| --- | --- |
| `/run/{id}/` | A benchmark run: sections, every test, "Can your phone beat it?" — the apps' share link |
| `/stress/{id}/` | A stress run: its throughput ribbon and facts — the apps' share link for stress runs |
| `/cert/{id}/` | A health certificate for a benchmark run: the run against the model's median (the apps' Phone Health thresholds, at least 3 runs), a QR code to itself, and what a buyer should check. The apps show the same QR code on the device being sold |
| `/embed/run/{id}/`, `/embed/stress/{id}/` | The run alone, for reviewers' articles; the only pages any site may frame (see `deploy/Caddyfile`) |

A run submitted a moment ago has no page until the next build (the loop looks every 2 minutes); the 404 page
recognises these paths, says the run is on its way and checks again every 30 seconds. The stress pages' dates and OS
builds need the API's `createdAt`/`osBuild` fields on `GET /leaderboard` (added in the 2.1 API); without them those
lines are simply left out. Device pages also show **"Did an update change it?"**, the median score per OS version,
once a model has benchmark runs on two versions.

Tests for the derived numbers: `.venv/bin/python -m unittest discover -s tests`.

## Changing things

- **Copy**: page text is in `templates/`; the test list and FAQ are in `thweb/content.py`. The test descriptions
  mirror the apps' Diagnostics screen, so change them there first.
- **The lab note** (`templates/note_power_mode.html.j2`) reads its numbers from benchmark runs #4 and #5 and stress
  run #1039. If those runs ever disappear from the API, the note is left out rather than shown with wrong numbers.
- **A new screenshot**: put the PNG in `assets-src/screens/`, run `.venv/bin/python tools/prepare_assets.py`, and
  reference it in `templates/home.html.j2`.
- **A device name**: add a line to `data/device_aliases.tsv`. The stored name stays visible on the device's page.

## Where it lives

Settings come from the environment (`thweb/config.py`):

| Variable | Default | Meaning |
| --- | --- | --- |
| `TH_SITE_ORIGIN` | `https://thermohammer.gtgroup.dev` | Scheme and host, used for canonical links, the sitemap and share images |
| `TH_BASE_PATH` | empty | Set to `/folder` to host the site inside a folder of another site |
| `THAPI_URL` | `https://thapi.gtgroup.dev` | The leaderboard API |
| `TH_NOINDEX` | off | A preview copy: every page asks search engines to stay away |
| `TH_OUT`, `TH_CACHE` | `dist`, `.cache` | Output and cache folders |

Moving to another domain is a change of `TH_SITE_ORIGIN` plus a redirect from the old host.

## On the server

`deploy/deploy.sh` copies the generator to `~/docker/thweb` on `myserver` and starts two containers:

- `thweb-gen` checks the leaderboard every 10 minutes and rebuilds the site when it changed (and once a day anyway).
  If the API is down or a build fails, the published site stays as it is.
- `thweb` (Caddy) serves the result with compression, long-lived caching for `/assets/`, security headers and the 404 page.

The public proxy (the `caddy` container) needs the block in `deploy/edge-caddy-block.txt` once the host name points
at the server. Logs: `sudo docker logs thweb-gen`.

## Limits worth knowing

- Comparison pages are made for every pair of devices that share a test, up to `TH_MAX_PAIRS` (5,000, about 100
  devices). Beyond that the closest matches are kept; revisit before the board gets there.
- Stress runs carry no date in the API, so their pages show none.
- The site reads the API as deployed. Per-test winners on comparison pages appear once the API serves `number` and
  `better` for detail rows; until then sections and groups are compared and rows are shown side by side.
