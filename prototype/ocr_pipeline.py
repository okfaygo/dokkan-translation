"""OCR pipeline: Dokkan Battle (JP) screenshot -> Japanese text candidates.

Stage 1 of the prototype. Takes a screenshot, runs several preprocessing
variants through Tesseract (jpn), and emits deduplicated candidate lines
ranked by confidence. Downstream, candidates are fuzzy-matched against a
JP-title -> card-id index (see match.py), then the English kit is fetched
from the dokkan.wiki API (see dokkan_api.py).

Usage:
    python ocr_pipeline.py screenshot.png [--tessdata PATH] [--debug-dir DIR]
"""

import argparse
import json
import os
import re
import sys

import cv2
import numpy as np
import pytesseract

# Lines must contain at least one kana/kanji char to be a candidate.
JP_CHAR = re.compile(r"[぀-ヿ一-鿿]")


def preprocess_variants(img):
    """Yield (name, image) variants. Game text over busy art needs multiple
    binarization strategies; we OCR all of them and merge results."""
    h, w = img.shape[:2]
    if w < 1500:  # upscale small screenshots; Tesseract likes ~30px+ glyphs
        scale = 1500 / w
        img = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    yield "gray", gray
    # White outlined text on dark/busy background -> invert helps
    yield "inverted", cv2.bitwise_not(gray)
    # Adaptive threshold isolates high-contrast UI text
    yield "adaptive", cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 31, 11
    )
    # Otsu on a blurred copy for large title lettering
    blur = cv2.GaussianBlur(gray, (3, 3), 0)
    _, otsu = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    yield "otsu", otsu


def ocr_lines(image, lang="jpn", psm=6):
    """Run Tesseract, return [(line_text, mean_confidence)]."""
    data = pytesseract.image_to_data(
        image, lang=lang, config=f"--psm {psm}", output_type=pytesseract.Output.DICT
    )
    lines = {}
    for i, txt in enumerate(data["text"]):
        txt = txt.strip()
        if not txt or int(data["conf"][i]) < 0:
            continue
        key = (data["block_num"][i], data["par_num"][i], data["line_num"][i])
        words, confs = lines.setdefault(key, ([], []))
        words.append(txt)
        confs.append(int(data["conf"][i]))
    out = []
    for words, confs in lines.values():
        # Japanese has no spaces; Tesseract splits glyphs into "words"
        text = "".join(words)
        if JP_CHAR.search(text):
            out.append((text, sum(confs) / len(confs)))
    return out


def extract_candidates(image_path, tessdata=None, debug_dir=None):
    if tessdata:
        os.environ["TESSDATA_PREFIX"] = tessdata
    img = cv2.imread(image_path)
    if img is None:
        sys.exit(f"could not read image: {image_path}")

    merged = {}  # text -> best confidence
    for name, variant in preprocess_variants(img):
        if debug_dir:
            os.makedirs(debug_dir, exist_ok=True)
            cv2.imwrite(os.path.join(debug_dir, f"{name}.png"), variant)
        for psm in (6, 11):  # 6 = uniform block, 11 = sparse text
            for text, conf in ocr_lines(variant, psm=psm):
                if conf > merged.get(text, -1):
                    merged[text] = conf

    return sorted(merged.items(), key=lambda kv: -kv[1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--tessdata", default=None, help="dir containing jpn.traineddata")
    ap.add_argument("--debug-dir", default=None, help="dump preprocessing variants")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args()

    candidates = extract_candidates(args.image, args.tessdata, args.debug_dir)
    if args.json:
        print(json.dumps(
            [{"text": t, "conf": round(c, 1)} for t, c in candidates],
            ensure_ascii=False, indent=2,
        ))
    else:
        for text, conf in candidates:
            print(f"{conf:5.1f}  {text}")


if __name__ == "__main__":
    main()
