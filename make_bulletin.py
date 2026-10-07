"""
VC Bulletin: the "printing press".

Every morning this script:
  1. COLLECT  - reads the RSS feeds listed in sources.json
  2. FILTER   - keeps stories that look like VC news (funding, deals, exits...)
  3. PICK     - Africa-first: a few African stories, then a few global ones
  4. PUBLISH  - writes docs/bulletin.json (for the widget) and docs/index.html
                (a readable page you can open in your browser)

It only uses tools built into Python, so nothing needs installing.
"""

import html
import json
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

HERE = Path(__file__).parent
OUT = HERE / "docs"
NAIROBI = timezone(timedelta(hours=3))
MAX_PER_SOURCE = 2  # stops one busy site from filling the whole bulletin
MONEY = re.compile(r"(\$|usd|kes|ngn|€|£)\s?\d|\d+(\.\d+)?\s?(m|million|bn|billion)\b", re.I)


# ---------- 1. COLLECT ----------

BROWSER_HEADERS = {
    # Some sites turn away anything that doesn't look like a normal browser, so we look like one.
    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/129.0 Safari/537.36"),
    "Accept": "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5",
    "Accept-Language": "en-GB,en;q=0.9",
}


def fetch(url):
    last_error = None
    for attempt in range(2):  # one retry, in case the site just hiccupped
        try:
            req = urllib.request.Request(url, headers=BROWSER_HEADERS)
            with urllib.request.urlopen(req, timeout=25) as r:
                return r.read()
        except Exception as e:
            last_error = e
            time.sleep(3)
    raise last_error


def google_news_url(site):
    # Plan B: Google News keeps its own feed of each site's recent articles.
    q = urllib.parse.quote(f"site:{site} when:3d")
    return f"https://news.google.com/rss/search?q={q}&hl=en-KE&gl=KE&ceid=KE:en"


def fetch_stories(feed, fixture_dir=None):
    """Try the site's own feed first; if that fails, fall back to Google News for that site."""
    if fixture_dir:  # testing mode: read saved feed files instead of the internet
        return parse_feed((Path(fixture_dir) / f"{feed['name']}.xml").read_bytes()), "direct"
    try:
        stories = parse_feed(fetch(feed["url"]))
        if stories:
            return stories, "direct"
        raise ValueError("feed was empty")
    except Exception as e:
        print(f"   {feed['name']}: own feed failed ({e}), trying Google News", file=sys.stderr)
    site = feed.get("site") or urllib.parse.urlparse(feed["url"]).netloc.replace("www.", "")
    stories = parse_feed(fetch(google_news_url(site)))
    for s in stories:
        # Google adds " - Source Name" to every headline; trim it off.
        s["title"] = re.sub(r"\s+-\s+[^-]+$", "", s["title"]).strip()
    return stories, "via Google News"


def clean(text):
    text = re.sub(r"<[^>]+>", "", text or "")
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def parse_date(raw):
    if not raw:
        return None
    raw = raw.strip()
    try:
        d = parsedate_to_datetime(raw)          # RSS style: "Fri, 02 Oct 2026 09:00:03 +0000"
    except (TypeError, ValueError):
        try:
            d = datetime.fromisoformat(raw.replace("Z", "+00:00"))  # Atom style: "2026-10-02T09:00:03Z"
        except ValueError:
            return None
    return d if d.tzinfo else d.replace(tzinfo=timezone.utc)


def local(tag):
    return tag.rsplit("}", 1)[-1]  # strips XML namespaces like {http://www.w3.org/2005/Atom}


def parse_feed(xml_bytes):
    root = ET.fromstring(xml_bytes)
    stories = []
    for el in root.iter():
        if local(el.tag) not in ("item", "entry"):
            continue
        fields = {}
        for child in el:
            name = local(child.tag)
            if name == "link":
                fields.setdefault("link", (child.text or "").strip() or child.get("href", ""))
            elif name in ("title", "pubDate", "published", "updated", "date", "description", "summary"):
                fields.setdefault(name, child.text or "")
        title = clean(fields.get("title"))
        if not title:
            continue
        stories.append({
            "title": title,
            "link": fields.get("link", "").strip(),
            "summary": clean(fields.get("description") or fields.get("summary"))[:300],
            "published": parse_date(fields.get("pubDate") or fields.get("published")
                                    or fields.get("date") or fields.get("updated")),
        })
    return stories


# ---------- 2. FILTER ----------

def vc_score(story, words):
    text = f" {story['title']} {story['summary']} ".lower()
    title = story["title"].lower()
    score = 0.0
    for w in words:
        if re.search(rf"\b{re.escape(w)}\b", title):
            score += 2          # a VC word in the headline counts double
        elif re.search(rf"\b{re.escape(w)}\b", text):
            score += 1
    if MONEY.search(story["title"]):
        score += 2              # "$30.1m", "USD 8 M", etc.
    return score


def same_story(a, b):
    ta = set(re.findall(r"[a-z0-9]+", a.lower())) - {"the", "a", "to", "of", "in", "and", "for", "its"}
    tb = set(re.findall(r"[a-z0-9]+", b.lower())) - {"the", "a", "to", "of", "in", "and", "for", "its"}
    if not ta or not tb:
        return False
    return len(ta & tb) / len(ta | tb) > 0.5


# ---------- 3. PICK ----------

def pick(stories, region, how_many, lookback_hours, now):
    for hours in (lookback_hours, lookback_hours * 2, lookback_hours * 4):  # widen if it's a slow news day
        cutoff = now - timedelta(hours=hours)
        pool = [s for s in stories if s["region"] == region and s["published"] and s["published"] >= cutoff]
        if len(pool) >= how_many:
            break
    pool.sort(key=lambda s: (s["score"], s["published"]), reverse=True)
    chosen, per_source = [], {}
    for s in pool:
        if per_source.get(s["source"], 0) >= MAX_PER_SOURCE:
            continue
        if any(same_story(s["title"], c["title"]) for c in chosen):
            continue
        chosen.append(s)
        per_source[s["source"]] = per_source.get(s["source"], 0) + 1
        if len(chosen) == how_many:
            break
    chosen.sort(key=lambda s: s["published"], reverse=True)
    return chosen


def age_label(published, now):
    # A fixed time ("Fri 22:30") rather than "8h ago", which would go stale as the day goes on.
    return published.astimezone(NAIROBI).strftime("%a %H:%M")


# ---------- 4. PUBLISH ----------

def write_outputs(items, report, now):
    OUT.mkdir(exist_ok=True)
    stamp = now.astimezone(NAIROBI)
    slots = []
    for i in range(report["slots"]):          # fixed number of slots so the widget layout never breaks
        if i < len(items):
            s = items[i]
            slots.append({"title": s["title"], "source": s["source"], "link": s["link"],
                          "region": s["region"], "age": age_label(s["published"], now)})
        else:
            slots.append({"title": "", "source": "", "link": "", "region": "", "age": ""})

    digest = "\n".join(f"• {s['title']} — {s['source']}" for s in slots if s["title"])
    data = {
        "date_label": stamp.strftime("%a %d %b"),
        "updated": stamp.strftime("%H:%M EAT, %a %d %b %Y"),
        "count": len(items),
        "items": slots,
        "digest": digest or "No VC stories found today.",
        "sources_ok": report["ok"],
        "sources_failed": report["failed"],
    }
    (OUT / "bulletin.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUT / "index.html").write_text(render_page(data), encoding="utf-8")


def render_page(data):
    rows = []
    for region, label in (("africa", "Africa"), ("global", "Global")):
        group = [s for s in data["items"] if s["region"] == region]
        if not group:
            continue
        rows.append(f'<h2>{label}</h2><ol>')
        for s in group:
            rows.append(
                f'<li><a href="{html.escape(s["link"])}">{html.escape(s["title"])}</a>'
                f'<span>{html.escape(s["source"])} · {s["age"]}</span></li>')
        rows.append("</ol>")
    failed = ""
    if data["sources_failed"]:
        failed = f'<p class="warn">Couldn\'t reach: {html.escape(", ".join(data["sources_failed"]))}</p>'
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>VC Bulletin · {data['date_label']}</title>
<style>
  :root {{ --bg:#f7f5f0; --ink:#1c1b19; --muted:#6b675f; --line:#e2ddd2; --accent:#0f6b4f; }}
  @media (prefers-color-scheme: dark) {{ :root {{ --bg:#141412; --ink:#ecebe6; --muted:#9a968c; --line:#2a2925; --accent:#4cc79a; }} }}
  body {{ margin:0; background:var(--bg); color:var(--ink); font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }}
  main {{ max-width:640px; margin:0 auto; padding:24px 16px 48px; }}
  header p {{ margin:0; color:var(--muted); font-size:14px; }}
  h1 {{ margin:0 0 4px; font-size:26px; letter-spacing:-.01em; }}
  h2 {{ margin:28px 0 8px; font-size:13px; text-transform:uppercase; letter-spacing:.08em; color:var(--accent); }}
  ol {{ margin:0; padding:0; list-style:none; }}
  li {{ padding:12px 0; border-bottom:1px solid var(--line); }}
  li a {{ color:var(--ink); text-decoration:none; font-weight:600; display:block; }}
  li a:hover {{ text-decoration:underline; }}
  li span {{ color:var(--muted); font-size:13px; }}
  .warn {{ margin-top:24px; color:var(--muted); font-size:13px; }}
</style></head>
<body><main>
<header><h1>VC Bulletin</h1><p>{data['updated']} · {data['count']} stories</p></header>
{''.join(rows) or '<p>No VC stories found today.</p>'}
{failed}
</main></body></html>
"""


# ---------- RUN ----------

def main(fixture_dir=None):
    config = json.loads((HERE / "sources.json").read_text(encoding="utf-8"))
    now = datetime.now(timezone.utc)
    stories, ok, failed = [], [], []
    for feed in config["feeds"]:
        try:
            found, how = fetch_stories(feed, fixture_dir)
            ok.append(feed["name"])
        except Exception as e:  # one broken site shouldn't stop the whole bulletin
            print(f"!! {feed['name']}: {e}", file=sys.stderr)
            failed.append(feed["name"])
            continue
        for s in found:
            s["source"], s["region"] = feed["name"], feed["region"]
            s["score"] = vc_score(s, config["vc_words"])
            if feed.get("always_vc"):
                s["score"] += 1
            if s["score"] >= 1:
                stories.append(s)
        print(f"ok {feed['name']}: {len(found)} stories ({how})")

    items = (pick(stories, "africa", config["how_many_africa"], config["lookback_hours"], now)
             + pick(stories, "global", config["how_many_global"], config["lookback_hours"], now))
    report = {"ok": ok, "failed": failed, "slots": config["how_many_africa"] + config["how_many_global"]}
    write_outputs(items, report, now)
    print(f"\nWrote {len(items)} stories to docs/bulletin.json and docs/index.html")
    if not ok:
        sys.exit("Every feed failed - check your internet connection or the feed links.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else None)
