# -*- coding: utf-8 -*-
"""Turn the article into a standalone site for GitHub Pages.

dokkan-article.html is written to be publishable as a Claude artifact,
where the runtime supplies the <!doctype>, <html> and <head> wrapper. On
Pages nothing does, so this adds one. In particular it adds the viewport
meta, without which phones render the page at desktop width, and the
social-preview tags that decide what a pasted link looks like.

Output goes to _site/, which is what the Pages workflow uploads.

    python article/build_site.py
"""
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "_site")

SITE_URL = "https://okfaygo.github.io/dokkan-translation/"
DESCRIPTION = (
    "An Android app that reads JP Dokkan Battle cards off your screen and "
    "shows what they do in English, without translating a single word."
)
ASSETS = ["demo_clip.mp4", "poster.jpg", "preview.jpg",
          "example-vegeta-card.jpg", "example-vegeta-kit.jpg",
          "example-goku-card.jpg", "example-goku-kit.jpg"]

src = open(os.path.join(HERE, "dokkan-article.html"), encoding="utf-8").read()

# The leading comment is a note to whoever edits the file, not to readers.
src = re.sub(r"^<!--.*?-->\s*", "", src, count=1, flags=re.S)

m = re.search(r"<title>(.*?)</title>\s*", src, flags=re.S)
if not m:
    sys.exit("no <title> found in dokkan-article.html")
title = m.group(1).strip()
body = src[: m.start()] + src[m.end():]

m = re.search(r"<style>.*?</style>", body, flags=re.S)
if not m:
    sys.exit("no <style> block found")
style = m.group(0)
body = (body[: m.start()] + body[m.end():]).strip()

# The video gets a poster so the first paint is the app, not a black box.
if body.count("<video ") != 1:
    sys.exit("expected exactly one <video>")
body = body.replace("<video ", '<video poster="poster.jpg" ', 1)

page = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{desc}">
<meta name="color-scheme" content="light dark">
<link rel="canonical" href="{url}">

<meta property="og:type" content="article">
<meta property="og:site_name" content="Dokkan Translate">
<meta property="og:title" content="{title}">
<meta property="og:description" content="{desc}">
<meta property="og:url" content="{url}">
<meta property="og:image" content="{url}preview.jpg">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="{title}">
<meta name="twitter:description" content="{desc}">
<meta name="twitter:image" content="{url}preview.jpg">

{style}
</head>
<body>
{body}
</body>
</html>
""".format(title=title, desc=DESCRIPTION, url=SITE_URL, style=style, body=body)

# Clear the contents rather than the directory itself: on Windows a
# rmtree of _site fails with "access is denied" whenever anything still
# holds the folder open, which leaves a half-deleted build behind.
os.makedirs(OUT, exist_ok=True)
for name in os.listdir(OUT):
    stale = os.path.join(OUT, name)
    if os.path.isdir(stale):
        shutil.rmtree(stale, ignore_errors=True)
    else:
        os.remove(stale)
open(os.path.join(OUT, "index.html"), "w", encoding="utf-8", newline="").write(page)

missing = [a for a in ASSETS if not os.path.exists(os.path.join(HERE, a))]
if missing:
    sys.exit("missing asset(s): %s" % ", ".join(missing))
for a in ASSETS:
    shutil.copyfile(os.path.join(HERE, a), os.path.join(OUT, a))

print("built %s" % OUT)
for f in sorted(os.listdir(OUT)):
    print("  %-16s %8.1f KB" % (f, os.path.getsize(os.path.join(OUT, f)) / 1024))
