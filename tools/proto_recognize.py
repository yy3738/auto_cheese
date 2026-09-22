# -*- coding: utf-8 -*-
"""PC 端识别原型 v2：NCC 平移搜索匹配 + EMPTY 模板判空 + 留一验证"""
import base64
import io
import json
import os
import numpy as np
from PIL import Image

SCREEN = r"E:\learn\auto_cheese\.scratch_screen.png"
T = 24        # 模板尺寸
SHIFT = 3     # 平移搜索半径
MR = 0.72 * T / 2   # 中心圆掩码半径（像素）
CELL = 0.48   # 取样半径 = 格距 * CELL
W_SIDE = 0.05  # 红黑倾向惩罚权重（redness 差平方/100）
SD_MIN = 32.0  # 窗口灰度标准差低于该值判空（占用>=39.8 空位<=25.4）

BACK_BLACK = ['r', 'n', 'b', 'a', 'k', 'a', 'b', 'n', 'r']
BACK_RED = ['R', 'N', 'B', 'A', 'K', 'A', 'B', 'N', 'R']
CHAR2NAME = {}
CHAR2NAME = {
    'br': 'B_ROOK', 'bn': 'B_KNIGHT', 'bb': 'B_BISHOP', 'ba': 'B_ADVISOR',
    'bk': 'B_KING', 'bc': 'B_CANNON', 'bp': 'B_PAWN',
    'rR': 'R_ROOK', 'rN': 'R_KNIGHT', 'rB': 'R_BISHOP', 'rA': 'R_ADVISOR',
    'rK': 'R_KING', 'rC': 'R_CANNON', 'rp': 'R_PAWN',
}

YY, XX = np.mgrid[0:T, 0:T]
CC = (T - 1) / 2.0
MASK = ((YY - CC) ** 2 + (XX - CC) ** 2) <= (0.72 * T / 2) ** 2


def truth_board():
    b = [[None] * 9 for _ in range(10)]
    for c in range(9):
        b[0][c] = 'b' + BACK_BLACK[c]
        b[9][c] = 'r' + BACK_RED[c]
    for c in (1, 7):
        b[2][c] = 'bc'; b[7][c] = 'rC'
    for c in range(0, 9, 2):
        b[3][c] = 'bp'; b[6][c] = 'rp'
    return b


def load():
    img = Image.open(SCREEN).convert('RGB')
    a = np.asarray(img).astype(np.float32)
    gray = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
    return img, a, gray


def bilinear_grid(tl, tr, br, bl):
    pts = np.zeros((10, 9, 2), np.float32)
    tl, tr, br, bl = map(np.array, (tl, tr, br, bl))
    for r in range(10):
        v = r / 9.0
        for c in range(9):
            u = c / 8.0
            top = tl + (tr - tl) * u
            bot = bl + (br - bl) * u
            pts[r, c] = top + (bot - top) * v
    return pts


def refine_grid(gray, pts):
    """棋盘线比底色暗：间隙扫描暗度峰 + polyfit + 线线求交"""
    H, W = gray.shape
    col_fit = []
    for k in range(9):
        samples = []
        for i in range(9):
            y = (pts[i, k, 1] + pts[i + 1, k, 1]) / 2.0
            x0 = (pts[i, k, 0] + pts[i + 1, k, 0]) / 2.0
            xs = np.arange(int(x0) - 12, int(x0) + 13)
            xs = xs[(xs >= 2) & (xs < W - 2)]
            dark = 255.0 - np.array(
                [gray[int(y) - 1:int(y) + 2, x - 1:x + 2].mean() for x in xs])
            if len(dark) == 0:
                continue
            samples.append((y, float(xs[np.argmax(dark)])))
        if len(samples) >= 4:
            ys = np.array([s[0] for s in samples])
            xv = np.array([s[1] for s in samples])
            b, a = np.polyfit(ys, xv, 1)
            col_fit.append((a, b))
        else:
            col_fit.append(None)
    row_lines = []
    for r in range(10):
        samples = []
        for c in range(8):
            x = (pts[r, c, 0] + pts[r, c + 1, 0]) / 2.0
            yq = int(pts[r, c, 1])
            ys = np.arange(yq - 12, yq + 12)
            ys = ys[(ys >= 2) & (ys < H - 2)]
            dark = 255.0 - np.array(
                [gray[y - 1:y + 2, int(x) - 1:int(x) + 2].mean() for y in ys])
            samples.append((x, float(ys[np.argmax(dark)])))
        if len(samples) >= 5:
            xv = np.array([s[0] for s in samples])
            yv = np.array([s[1] for s in samples])
            d, c0 = np.polyfit(xv, yv, 1)
            row_lines.append((c0, d))
    out = np.zeros((10, 9, 2), np.float32)
    for r in range(10):
        for c in range(9):
            a, b = col_fit[c]
            c0, d = row_lines[r]
            y = c0 + d * pts[r, c, 0]
            x = a + b * y
            out[r, c] = (x, y)
    return out


def window24(img, cx, cy, radius):
    x0 = int(np.clip(cx - radius, 0, img.width - 1))
    y0 = int(np.clip(cy - radius, 0, img.height - 1))
    x1 = int(np.clip(cx + radius, 0, img.width))
    y1 = int(np.clip(cy + radius, 0, img.height))
    return img.crop((x0, y0, x1, y1)).resize((T, T), Image.BOX)


def norm24(g):
    v = g.flatten()
    q = (g - v.mean()) / max(8.0, v.std())
    return q.astype(np.float32), float(v.std())


def to_gray(a24):
    return 0.299 * a24[..., 0] + 0.587 * a24[..., 1] + 0.114 * a24[..., 2]


def ncc_dist(qn, tm, r1=0.0, r2=None):
    """±SHIFT 平移搜索取最大 NCC；r1/r2 为红黑倾向，加惩罚"""
    best = 1e9
    for dy in range(-SHIFT, SHIFT + 1):
        y0 = max(0, dy); y1 = min(T, T + dy)
        for dx in range(-SHIFT, SHIFT + 1):
            x0 = max(0, dx); x1 = min(T, T + dx)
            m = MASK[y0:y1, x0:x1]
            if m.sum() < 100:
                continue
            va = qn[y0:y1, x0:x1][m]
            vb = tm[y0 - dy:y1 - dy, x0 - dx:x1 - dx][m]
            den = float(np.sqrt(float(np.sum(va * va)) * float(np.sum(vb * vb))))
            if den < 1e-6:
                continue
            v = float(np.sum(va * vb)) / den
            d = 1.0 - v
            if r2 is not None:
                d += W_SIDE * (r1 - r2) * (r1 - r2) / 100.0
            if d < best:
                best = d
    return best


def redness(a24, g24):
    """暗像素的 (R-G) 均值，红字大正、黑字近 0"""
    med = float(np.median(g24))
    dark = (g24 < med - 30.0) & MASK
    n = int(dark.sum())
    if n == 0:
        return 0.0
    return float((a24[..., 0][dark] - a24[..., 1][dark]).mean())


def png_b64(a24):
    buf = io.BytesIO()
    Image.fromarray(a24.astype(np.uint8)).save(buf, format='PNG')
    return base64.b64encode(buf.getvalue()).decode('ascii')


def main():
    img, arr, gray = load()
    W, H = img.size
    corners = [(180, 595), (900, 595), (900, 1395), (180, 1395)]
    pts = refine_grid(gray, bilinear_grid(*corners))
    cell = 0.0
    n = 0
    for r in range(10):
        for c in range(8):
            cell += float(np.hypot(*(pts[r, c + 1] - pts[r, c]))); n += 1
    for r in range(9):
        for c in range(9):
            cell += float(np.hypot(*(pts[r + 1, c] - pts[r, c]))); n += 1
    cell /= n
    radius = cell * 0.48
    print(f"cell={cell:.1f}px radius={radius:.1f}px")

    truth = truth_board()
    queries = []   # (idx, name|None, qn, sd, rd)
    tpngs = {}     # (idx, name) -> a24 RGB
    for r in range(10):
        for c in range(9):
            idx = r * 9 + c
            a24 = np.asarray(window24(img, pts[r, c, 0], pts[r, c, 1], radius))
            g = to_gray(a24)
            qn, sd = norm24(g)
            name = truth[r][c]
            queries.append((idx, name, qn, sd, redness(a24, g)))
            if name is not None:
                tpngs[(idx, name)] = a24

    # 空点模板
    empty_idx = [(1, 0), (1, 4), (1, 8), (4, 0), (4, 8), (5, 2), (5, 6), (7, 3)]
    empties = []
    for (r, c) in empty_idx:
        idx = r * 9 + c
        a24 = np.asarray(window24(img, pts[r, c, 0], pts[r, c, 1], radius))
        g = to_gray(a24)
        qn, sd = norm24(g)
        empties.append((qn, sd, redness(a24, g)))
        tpngs[(idx, 'EMPTY')] = a24

    # 分布体检
    occ_v = [(nm, sd) for idx, nm, qn, sd, rd in queries if nm is not None]
    print("sd 占用:", min(v for _, v in occ_v), max(v for _, v in occ_v))
    print("sd 空位:", min(v for _, v, _ in empties), max(v for _, v, _ in empties))

    # 留一验证
    by_name = {}
    for idx, name, qn, sd, rd in queries:
        if name is not None:
            by_name.setdefault(name, []).append((idx, qn, rd))
    ok = 0
    errors = []
    for idx, name, qn, sd, rd in queries:
        if sd < SD_MIN:
            if name is None:
                ok += 1
            else:
                errors.append((idx, name, f'判空 sd={sd:.1f}'))
            continue
        tmpl = []
        for n2, lst in by_name.items():
            # 硬门：红方只配红方模板
            if rd > 40 and not n2.startswith('r'):
                continue
            if rd <= 40 and n2.startswith('r'):
                continue
            for i2, q2, r2 in lst:
                if i2 == idx:
                    continue
                tmpl.append((n2, q2, r2))
        if rd <= 40:
            for f2, sd2, rd2 in empties:
                tmpl.append(('EMPTY', f2, 0.0))
        best_name, best_d = None, 1e9
        for n2, f2, rd2 in tmpl:
            d = ncc_dist(qn, f2, rd, rd2)
            if d < best_d:
                best_d, best_name = d, n2
        if name is None:
            if best_name == 'EMPTY':
                ok += 1
            else:
                errors.append((idx, '空', f'{best_name} d={best_d:.3f}'))
        elif best_name == name:
            ok += 1
        else:
            errors.append((idx, name, f'→{best_name} d={best_d:.3f}'))

    print(f"\n留一验证: {ok}/90 正确")
    for idx, name, msg in errors:
        print(f"  ({idx // 9},{idx % 9}) 真值{name}: {msg}")

    # 全量匹配（App 实际场景：所有模板参与，无留一）
    all_t = []
    for n2, lst in by_name.items():
        for i2, q2, r2 in lst:
            all_t.append((n2, q2, r2))
    for f2, sd2, rd2 in empties:
        all_t.append(('EMPTY', f2, 0.0))
    ok2 = 0
    err2 = []
    for idx, name, qn, sd, rd in queries:
        if sd < SD_MIN:
            if name is None:
                ok2 += 1
            else:
                err2.append((idx, name, f'判空 sd={sd:.1f}'))
            continue
        cand = [(n2, f2, rd2) for n2, f2, rd2 in all_t
                if not (rd > 40 and not n2.startswith('r'))
                and not (rd <= 40 and n2.startswith('r'))]
        best_name, best_d = None, 1e9
        for n2, f2, rd2 in cand:
            d = ncc_dist(qn, f2, rd, rd2)
            if d < best_d:
                best_d, best_name = d, n2
        if name is None:
            if best_name == 'EMPTY':
                ok2 += 1
            else:
                err2.append((idx, '空', f'{best_name} d={best_d:.3f}'))
        elif best_name == name:
            ok2 += 1
        else:
            err2.append((idx, name, f'→{best_name} d={best_d:.3f}'))
    print(f"\n全量匹配（App 实际效果）: {ok2}/90 正确")
    for idx, name, msg in err2:
        print(f"  ({idx // 9},{idx % 9}) 真值{name}: {msg}")

    # 输出 JSON
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
    os.makedirs(out_dir, exist_ok=True)
    tarr = [{'piece': CHAR2NAME.get(nm, nm), 'png': png_b64(a)}
            for (idx, nm), a in tpngs.items()]
    with open(os.path.join(out_dir, 'templates_default.json'), 'w') as fp:
        json.dump({'templates': tarr}, fp)
    print(f"模板 {len(tarr)} 张 → tools/out/templates_default.json")

    def pt(x, y):
        return {'x': round(float(x), 1), 'y': round(float(y), 1)}
    calib = {
        'capturedWidth': W, 'capturedHeight': H,
        'tl': pt(*pts[0, 0]), 'tr': pt(*pts[0, 8]),
        'br': pt(*pts[9, 8]), 'bl': pt(*pts[9, 0]),
    }
    with open(os.path.join(out_dir, 'calib_default.json'), 'w') as fp:
        json.dump(calib, fp)
    print(f"校准角 {calib}")


if __name__ == '__main__':
    main()
