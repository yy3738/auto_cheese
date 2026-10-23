# -*- coding: utf-8 -*-
"""弈枰棋子分类器训练(阶段2):
数据: samples/dataset/<类>/*.png (用户逐张校正过的棋谱回放帧)
增强: 白圈/黑圈叠加+亮度抖动+平移旋转(模拟三类历史误读源)
模型: 3层conv小网络(<20K参数, 32x32灰度), CPU 数分钟
输出: tools/out/charcnn_best.pt (state_dict+元数据), 验证报告
"""
import os, random, json
import numpy as np
from PIL import Image, ImageDraw
import torch
import torch.nn as nn
import torch.nn.functional as F
from sklearn.model_selection import train_test_split

ROOT = r'E:\learn\auto_cheese\samples\dataset'
OUT = r'E:\learn\auto_cheese\tools\out\charcnn_best.pt'
IMG = 32
SEED = 42
random.seed(SEED); np.random.seed(SEED); torch.manual_seed(SEED)

CLASSES = sorted(d for d in os.listdir(ROOT)
                 if os.path.isdir(os.path.join(ROOT, d)))
CIDX = {c: i for i, c in enumerate(CLASSES)}

# ---------- 数据装载 ----------
# RGB 3 通道(2026-10-19):灰度下石纹空格与棋子字挤同一通道,同皮肤在集
# 样本仍大片拒判(m 0.5-0.9);颜色域红子/黑子/底盘三种颜色天然可分
data, labels, fnames = [], [], []
for c in CLASSES:
    for fn in os.listdir(os.path.join(ROOT, c)):
        im = Image.open(os.path.join(ROOT, c, fn)).convert('RGB')
        a = np.asarray(im.resize((IMG, IMG), Image.BILINEAR), dtype=np.float32)
        data.append(a); labels.append(CIDX[c]); fnames.append(fn)
data = np.stack(data); labels = np.array(labels, dtype=np.int64)
fnames = np.array(fnames)
print(f"样本 {len(data)}  类别 {len(CLASSES)}  通道 {data.shape[-1]}")

# 留出集: Lmp_* 批次 = 实时对局帧(2026-10-19), 不进训练/验证——
# cell 级随机划分会被近重复帧泄漏灌水(val 0.97 实测帧仍 48 拒判),
# 结构改动的真实判据必须是留出的实时帧 unknown 数
live_mask = np.array([fn.startswith('Lmp_') for fn in fnames])
live_idx = np.where(live_mask)[0]
live_x = np.stack([data[i] for i in live_idx])
live_y = labels[live_idx]
usable = np.where(~live_mask)[0]
tr_i, va_i = train_test_split(usable, test_size=0.1,
                              stratify=labels[usable], random_state=SEED)
print(f"留出实时帧: {len(live_idx)} 格 (tags={sorted(set(t[:14] for t in fnames[live_idx]))})")

def draw_ring(arr, rng, white, ring):
    """在棋子上叠污染: white=白色圆盘(行棋光圈), ring=暗环(将军特效)"""
    h, w = arr.shape[:2]
    cy, cx = h // 2 + rng.integers(-3, 4), w // 2 + rng.integers(-3, 4)
    rr = int(h * rng.uniform(0.28, 0.42))
    yy, xx = np.mgrid[0:h, 0:w]
    d2 = (yy - cy) ** 2 + (xx - cx) ** 2
    alpha = rng.uniform(0.35, 0.6)
    if white:
        m = d2 <= rr * rr
        arr[m] = arr[m] * (1 - alpha) + 235 * alpha
    if ring:
        band = (d2 <= rr * rr) & (d2 >= (rr * 0.78) ** 2)
        arr[band] = arr[band] * (1 - 0.5) + 40 * 0.5
    return arr

def augment(arr, rng):
    a = arr.copy()
    # 平移
    dx, dy = rng.integers(-3, 4), rng.integers(-3, 4)
    a = np.roll(np.roll(a, dy, 0), dx, 1)
    # 旋转 ±8°(最近邻, 绕中心)
    ang = np.deg2rad(rng.uniform(-8, 8))
    yy, xx = np.mgrid[0:IMG, 0:IMG]
    cy = cx = IMG / 2
    ys = ((yy - cy) * np.cos(ang) - (xx - cx) * np.sin(ang) + cy).astype(int)
    xs = ((yy - cy) * np.sin(ang) + (xx - cx) * np.cos(ang) + cx).astype(int)
    ok = (ys >= 0) & (ys < IMG) & (xs >= 0) & (xs < IMG)
    rotated = np.zeros_like(a); rotated[ys[ok], xs[ok]] = a[ys[ok], xs[ok]]
    a = rotated
    # 亮度/对比度
    a = a * rng.uniform(0.75, 1.25) + rng.uniform(-25, 25)
    # RGB 逐通道色彩抖动(2026-10-19): JJ 界面光照层/滤镜造成整盘色调
    # 漂移, 模型 val 0.97 但实时帧 48 格拒判——颜色域泛化必须抖通道
    if rng.random() < 0.6:
        gain = rng.uniform(0.85, 1.15, size=(3,))
        off = rng.uniform(-15, 15, size=(3,))
        a = a * gain + off
    # 污染叠加(白光圈/暗环, 可叠加)
    if rng.random() < 0.45: a = draw_ring(a, rng, white=True, ring=False)
    if rng.random() < 0.30: a = draw_ring(a, rng, white=False, ring=True)
    return np.clip(a, 0, 255)

# 类别均衡:每类每 epoch 采样数 = 2×最大类(限量)
from collections import Counter
cnt = Counter(labels)
cap = min(int(max(cnt.values()) * 1.0), 200)
plan = []
for c in CLASSES:
    idxs = [i for i in tr_i if labels[i] == CIDX[c]]
    need = min(cap, max(12, int(np.ceil(cap * 0.25)))) if c == 'EMPTY' else cap
    rng = random.Random(SEED + CIDX[c])
    rep, rem = divmod(need, max(1, len(idxs)))
    plan.extend(idxs * rep)
    plan.extend(rng.sample(idxs, rem) if rem else [])
random.Random(SEED).shuffle(plan)
print(f"均衡后每 epoch 训练样本 {len(plan)}")

MEAN = data[tr_i].mean(axis=(0, 1, 2))          # 逐通道均值 (3,)
STDV = data[tr_i].std(axis=(0, 1, 2))
STDV = np.maximum(STDV, 1e-6)

def to_tensor(a):
    # (IMG,IMG,3) → 归一化 → (3,IMG,IMG) 通道平面, 与 App 端 toRgb32 排列一致
    t = ((a - MEAN) / STDV).astype(np.float32).transpose(2, 0, 1)
    return torch.from_numpy(t)

class Net(nn.Module):
    def __init__(self, n):
        super().__init__()
        self.c1 = nn.Conv2d(3, 16, 3, padding=1)
        self.c2 = nn.Conv2d(16, 24, 3, padding=1)
        self.c3 = nn.Conv2d(24, 24, 3, padding=1)
        # GAP→2×2 空间池化(2026-10-19): 全局平均压扁全部空间结构,而
        # 石纹空格vs棋子字的判别恰恰在空间分布(中心字形vs平坦纹理)。
        # 8×8 特征图 → 2×2 maxpool → 24*4*4=384 维, 计算量零增加
        self.fc = nn.Linear(24 * 4 * 4, n)
    def forward(self, x):
        x = F.max_pool2d(F.relu(self.c1(x)), 2)
        x = F.max_pool2d(F.relu(self.c2(x)), 2)
        x = F.relu(self.c3(x))
        x = F.max_pool2d(x, 2).flatten(1)
        return self.fc(x)

model = Net(len(CLASSES))
opt = torch.optim.Adam(model.parameters(), 1e-3)
rng = np.random.default_rng(SEED)

def batch_eval(idxs):
    xs = torch.stack([to_tensor(data[i]) for i in idxs])
    ys = torch.tensor([labels[i] for i in idxs])
    return xs, ys

va_x, va_y = batch_eval(va_i)

# ---------- 回归关卡: 完美标签90格(replay_start, 未入库的独立基准) ----------
# 最佳模型硬性要求: 90格无 >=0.9 置信的硬判错误(高置信幻觉是实机"幽灵子"
# 与整帧毒化的来源, 宁可拒判)。不满足的 checkpoint 不接受为 best。
replay = []
REPLAY_DIR = r'E:\learn\auto_cheese\samples\replay_start'
R0 = ['R_ROOK','R_KNIGHT','R_BISHOP','R_ADVISOR','R_KING','R_ADVISOR','R_BISHOP','R_KNIGHT','R_ROOK']
REPLAY_TRUTH = [R0, ['EMPTY']*9,
    ['EMPTY','R_CANNON','EMPTY','EMPTY','EMPTY','EMPTY','EMPTY','R_CANNON','EMPTY'],
    ['R_PAWN','EMPTY','R_PAWN','EMPTY','R_PAWN','EMPTY','R_PAWN','EMPTY','R_PAWN'],
    ['EMPTY']*9, ['EMPTY']*9,
    ['B_PAWN','EMPTY','B_PAWN','EMPTY','B_PAWN','EMPTY','B_PAWN','EMPTY','B_PAWN'],
    ['EMPTY','B_CANNON','EMPTY','EMPTY','EMPTY','EMPTY','EMPTY','B_CANNON','EMPTY'],
    ['EMPTY']*9,
    ['B_ROOK','B_KNIGHT','B_BISHOP','B_ADVISOR','B_KING','B_ADVISOR','B_BISHOP','B_KNIGHT','B_ROOK']]
if os.path.isdir(REPLAY_DIR):
    rp_x, rp_y = [], []
    for r in range(10):
        for c in range(9):
            p = os.path.join(REPLAY_DIR, f'r{r}c{c}.png')
            if not os.path.exists(p):
                continue
            im = Image.open(p).convert('RGB').resize((IMG, IMG), Image.BILINEAR)
            rp_x.append(np.asarray(im, dtype=np.float32))
            rp_y.append(CIDX[REPLAY_TRUTH[r][c]])
    rp_x = np.stack(rp_x); rp_y = np.array(rp_y, dtype=np.int64)
    for i in range(len(rp_y)):
        replay.append(i)
    print(f"回归关卡基准(replay_start): {len(rp_y)} 格")

def replay_gate(verbose=False):
    """返回无高置信硬判错误则 True（逐格推理, 与 PC 验证脚本同构）"""
    if not replay:
        return True
    n_bad = 0
    model.eval()
    for j, i in enumerate(replay):
        with torch.no_grad():
            lg = model(to_tensor(rp_x[i]).unsqueeze(0))[0]
        t2 = torch.topk(lg, 2)
        m = torch.sigmoid(t2.values[0] - t2.values[1]).item()
        pred = CLASSES[int(t2.indices[0].item())]
        truth = CLASSES[int(rp_y[j])]
        if m >= 0.9 and pred != truth:
            n_bad += 1
            if verbose and n_bad <= 5:
                r, c = divmod(i, 9)
                print(f"    gate-block r{r}c{c}: 判{pred} 真{truth} m={m:.2f}")
    return n_bad == 0

best_acc, best_state = 0.0, None
for ep in range(120):
    model.train()
    random.shuffle(plan)
    tot_loss = 0.0; nb = 0
    for s in range(0, len(plan), 64):
        idxs = plan[s:s + 64]
        xs = torch.stack([to_tensor(augment(data[i], rng)) for i in idxs])
        ys = torch.tensor([labels[i] for i in idxs])
        opt.zero_grad()
        loss = F.cross_entropy(model(xs), ys)
        loss.backward(); opt.step()
        tot_loss += float(loss); nb += 1
    model.eval()
    with torch.no_grad():
        pred = model(va_x).argmax(1)
    acc = float((pred == va_y).float().mean())
    if acc >= best_acc and replay_gate(verbose=(ep >= 100)):
        best_acc, best_state = acc, {k: v.clone() for k, v in model.state_dict().items()}
    print(f"epoch {ep+1:2d}  loss={tot_loss/nb:.3f}  val_acc={acc:.4f}")

model.load_state_dict(best_state)
torch.save({'state': best_state, 'classes': CLASSES,
            'mean': MEAN.tolist(), 'std': STDV.tolist(), 'img': IMG}, OUT)

# ---------- 验证报告 ----------
model.eval()
with torch.no_grad():
    pred = model(va_x).argmax(1)
print("\n=== 每类验证准确率 ===")
worst = []
for i, c in enumerate(CLASSES):
    m = va_y == i
    if m.sum() == 0:
        worst.append((c, 0)); continue
    acc = float((pred[m] == va_y[m]).float().mean())
    worst.append((c, acc))
    print(f"  {c:12s} {acc:.3f}  (n={int(m.sum())})")

# ---------- 历史失败帧点检 ----------
print("\n=== 历史失败格点检(未参与训练的旧会话裁片) ===")
hist = r'E:\learn\auto_cheese\samples\game1'
chk = {'r9c4.png': 'R_KING(白圈罩红帥,曾误判空)'}
for fn, truth in chk.items():
    p = os.path.join(hist, fn)
    if not os.path.exists(p):
        continue
    im = Image.open(p).convert('RGB').resize((IMG, IMG), Image.BILINEAR)
    a = np.asarray(im, dtype=np.float32)
    with torch.no_grad():
        pr = model(to_tensor(a).unsqueeze(0)).argmax(1).item()
    print(f"  {fn}: 模型判 {CLASSES[pr]}  (真值 {truth})")
# ---------- 留出实时帧评估(真泛化判据) ----------
if len(live_idx):
    model.eval()
    live_t = torch.stack([to_tensor(a) for a in live_x])
    with torch.no_grad():
        lg = model(live_t)
    t2 = torch.topk(lg, 2, dim=1)
    margins = torch.sigmoid(t2.values[:, 0] - t2.values[:, 1]).numpy()
    pred_i = t2.indices[:, 1].numpy()
    conf = int((margins >= 0.9).sum())
    hard_wrong = int(((margins >= 0.9) & (pred_i != live_y)).sum())
    print(f"\n留出实时帧 {len(live_idx)} 格: CONFIDENT(m>=0.9)={conf} "
          f"硬判错误={hard_wrong} 拒判={len(live_idx)-conf}")

print(f"\n最佳 val_acc={best_acc:.4f} → {OUT}")
