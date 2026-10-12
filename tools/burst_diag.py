# -*- coding: utf-8 -*-
"""连拍诊断:复刻 App 端当前识别逻辑(白光圈剔除+GLOW 抬高阈值),定位 credibility fail 的假王"""
import base64
import io
import json
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, r'E:\learn\auto_cheese\tools')
import proto_recognize as pr
from proto_recognize import MASK, T, ncc_dist, refine_grid, bilinear_grid, redness, to_gray, window24

CORNERS = [(70, 455), (1010, 455), (1010, 1455), (70, 1455)]
SD_MIN = 32.0
GLOW_SD_MIN = 45.0
GLOW_RATIO_MIN = 0.10
REDNESS_GATE = 40.0
UNKNOWN_DIST = 0.75


def remove_white_overlay(win: Image.Image):
    """复刻 Features.removeWhiteOverlay:返回(处理后图, 掩码内白占比)"""
    a = np.asarray(win).astype(np.float32)
    gray = to_gray(a)
    r = a[..., 0]; g = a[..., 1]; b = a[..., 2]
    ov = (gray > 195) & (np.abs(r - g) < 35) & (np.abs(g - b) < 35)
    h, w = ov.shape
    if not ov.any():
        return win, 0.0
    cc = (w - 1) / 2.0
    yy, xx = np.mgrid[0:h, 0:w]
    mask = (xx - cc) ** 2 + (yy - cc) ** 2 <= (0.72 * w / 2.0) ** 2
    ratio = float((ov & mask).sum()) / max(1, int(mask.sum()))
    med = float(np.median(gray[~ov]))
    out = a.copy()
    out[ov] = med
    return Image.fromarray(out.astype(np.uint8)), ratio


def recognize(path: str, pts, cell, tmpls):
    img = Image.open(path).convert('RGB')
    radius = cell * 0.48
    board = {}
    details = {}
    for rr in range(10):
        for cc in range(9):
            x, y = pts[rr, cc]
            x0 = int(np.clip(x - radius, 0, img.width - 1))
            y0 = int(np.clip(y - radius, 0, img.height - 1))
            x1 = int(np.clip(x + radius, 0, img.width))
            y1 = int(np.clip(y + radius, 0, img.height))
            win = img.crop((x0, y0, x1, y1))
            win, wratio = remove_white_overlay(win)
            a24 = np.asarray(win.resize((T, T), Image.BOX)).astype(np.float32)
            g = to_gray(a24)
            q, sd = pr.norm24(g)
            rd = redness(a24, g)
            gate = GLOW_SD_MIN if wratio > GLOW_RATIO_MIN else SD_MIN
            if sd < gate:
                details[(rr, cc)] = ('empty', sd, wratio, 0.0)
                continue
            best, bd = '?', 1e9
            for name, q2, rd2 in tmpls:
                if rd > REDNESS_GATE and not name.startswith('R_'):
                    continue
                if rd <= REDNESS_GATE and name.startswith('R_'):
                    continue
                d = ncc_dist(q, q2, rd, rd2)
                if d < bd:
                    bd, best = d, name
            if bd > UNKNOWN_DIST:
                details[(rr, cc)] = ('?', sd, wratio, bd)
                continue
            details[(rr, cc)] = (best, sd, wratio, bd)
            if best != 'EMPTY':
                board[(rr, cc)] = best
    return board, details


def main():
    td = json.load(open(r'E:\learn\auto_cheese\tools\out\templates_default.json'))
    tmpls = []
    for t in td['templates']:
        im = Image.open(io.BytesIO(base64.b64decode(t['png']))).convert('RGB')
        a24 = np.asarray(im).astype(np.float32)
        g = to_gray(a24)
        q, _ = pr.norm24(g)
        tmpls.append((t['piece'], q, redness(a24, g)))

    # 网格只需细化一次(各帧棋盘静止)
    img0 = Image.open(r'E:\learn\auto_cheese\.scratch_burst_0.png').convert('RGB')
    gray0 = to_gray(np.asarray(img0).astype(np.float32))
    pts = refine_grid(gray0, bilinear_grid(*CORNERS))
    cell = float(np.mean([np.hypot(*(pts[r, c + 1] - pts[r, c])) for r in range(10) for c in range(8)]))

    for i in range(12):
        path = rf'E:\learn\auto_cheese\.scratch_burst_{i}.png'
        board, details = recognize(path, pts, cell, tmpls)
        kings = [(p, rc) for rc, p in board.items() if p in ('B_KING', 'R_KING')]
        bk = [rc for p, rc in kings if p == 'B_KING']
        rk = [rc for p, rc in kings if p == 'R_KING']
        tag = 'OK ' if (len(bk) == 1 and len(rk) == 1 and len(board) >= 10) else 'FAIL'
        print(f"[{i:2d}] {tag} pieces={len(board)} redK={len(rk)} blackK={len(bk)} "
              f"王位={kings}")
        if tag == 'FAIL':
            # 与相邻 OK 帧比对,列出可疑点(std/wr/d)
            for rc in sorted(board):
                sd, wr, d = details[rc][1], details[rc][2], details[rc][3]
                print(f"     ({rc[0]},{rc[1]}) {board[rc]:8s} std={sd:5.1f} wr={wr:.2f} d={d:.3f}")


if __name__ == '__main__':
    main()
