# -*- coding: utf-8 -*-
"""把审计出的可疑样本按类分组拼图，供批量人工标记。

用法: python tools/make_review.py
输出: tools/out/review_color.png / review_empty_fp.png / review_shape.png
编号 = dataset_audit.csv 的 idx 列。
"""
from __future__ import annotations

import csv
from collections import defaultdict

from PIL import Image, ImageDraw

CSV = "tools/out/dataset_audit.csv"
CELL = 110
COLS = 10


def load_rows() -> list[dict]:
    return list(csv.DictReader(open(CSV, encoding="utf-8-sig")))


def sheet(rows: list[dict], path: str, title: str) -> None:
    if not rows:
        return
    nrows = (len(rows) + COLS - 1) // COLS
    img = Image.new("RGB", (COLS * CELL, 22 + nrows * (CELL + 14)), (255, 255, 255))
    d = ImageDraw.Draw(img)
    d.text((4, 4), title, fill=(0, 0, 0))
    for k, r in enumerate(rows):
        im = Image.open(f"samples/dataset/{r['label']}/{r['name']}")
        im = im.convert("RGB").resize((CELL, CELL), Image.BILINEAR)
        x, y = (k % COLS) * CELL, 22 + (k // COLS) * (CELL + 14)
        img.paste(im, (x, y))
        d.text((x + 2, y + CELL + 1), f"{r['idx']} {r['label'][:2]}", fill=(0, 0, 0))
    img.save(path)
    print(f"{path}: {len(rows)} 张")


def main() -> None:
    rows = load_rows()
    sus = [r for r in rows if r["reasons"]]
    # 同类近重复只保留一张
    seen: set[tuple[str, str]] = set()
    uniq = []
    for r in sus:
        key = (r["label"], r["dup_of"] or r["name"])
        if key in seen:
            continue
        seen.add(key)
        uniq.append(r)
    color = [r for r in uniq if "glyph" in r["reasons"]]
    shape = [r for r in uniq if r["label"] != "EMPTY" and "model=" in r["reasons"]
             and "glyph" not in r["reasons"]]
    empty = [r for r in uniq if r["label"] == "EMPTY"]
    sheet(color, "tools/out/review_color.png", "COLOR mismatch (label vs glyph)")
    sheet(shape, "tools/out/review_shape.png", "PIECE: model != label")
    sheet(empty, "tools/out/review_empty_fp.png", "EMPTY predicted as piece")
    # 给用户的简表
    with open("tools/out/review_list.txt", "w", encoding="utf-8") as f:
        for group, rs in (("COLOR", color), ("SHAPE", shape), ("EMPTY_FP", empty)):
            f.write(f"## {group}\n")
            for r in rs:
                f.write(f"{r['idx']}\t{r['label']}\tpred={r['pred']}\t"
                        f"{r['name']}\n")
    print("清单: tools/out/review_list.txt")


if __name__ == "__main__":
    main()
