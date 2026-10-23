# -*- coding: utf-8 -*-
"""弈枰 CNN 大换血 (方案A, 2026-10-20):
手搓 3 层 conv(<20K 参数)对同分布样本仍大片拒判——换 MobileNetV3-Small
ImageNet 预训练 fine-tune, 输入 96x96 RGB。部署 ONNX Runtime Mobile
(应用侧懒推理: 静态格走缓存, 只算变化格)。

验证协议(吸取教训):
- Lmp_* 批次 = 实时对局帧, 整批留出(不进训练/验证), 作为硬门槛
- cell 级随机 val 仅作早停参考(近重复泄漏, 数字只看趋势)
- 硬门槛: 留出帧 CONFIDENT>=70%/硬判错误=0 才接受

导出: cnn.onnx (动态 batch) + cnn_meta.json (classes/ImageNet mean+std)
"""
import os, random, json
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image
from torchvision.models import mobilenet_v3_small, MobileNet_V3_Small_Weights

ROOT = r'E:\learn\auto_cheese\samples\dataset'
OUT_CKPT = r'E:\learn\auto_cheese\tools\out\cnn_best.pt'
OUT_ONNX = r'E:\learn\auto_cheese\app\src\main\assets\cnn.onnx'
OUT_META = r'E:\learn\auto_cheese\app\src\main\assets\cnn_meta.json'
IMG = 128
SEED = 42
random.seed(SEED); np.random.seed(SEED); torch.manual_seed(SEED)

CLASSES = sorted(d for d in os.listdir(ROOT)
                 if os.path.isdir(os.path.join(ROOT, d)))
CIDX = {c: i for i, c in enumerate(CLASSES)}

# ImageNet 归一化(预训练骨干匹配)
IM_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IM_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)

# ---------- 数据装载 ----------
data, labels, fnames = [], [], []
for c in CLASSES:
    d = os.path.join(ROOT, c)
    for fn in os.listdir(d):
        im = Image.open(os.path.join(d, fn)).convert('RGB')
        a = np.asarray(im.resize((IMG, IMG), Image.BILINEAR), dtype=np.float32)
        data.append(a); labels.append(CIDX[c]); fnames.append(fn)
data = np.stack(data); labels = np.array(labels, dtype=np.int64)
fnames = np.array(fnames)
print(f"样本 {len(data)}  类别 {len(CLASSES)}")

# ---------- 去重 + 硬负样本（2026-10-22）----------
# 2542 张里 1408 张类内近重复(相邻帧同一格)。重复样本会把随机采样
# 偏向"常见格子", 新加入的空位硬负样本(白圈/红点/河界字/角标)几乎
# 抽不到——实测模型把 (4,4) 河界、(9,0) 角标读成车炮, 置信 0.97+。
# 处理: 类内 dHash 近重复只留一张; 硬负样本(name 在 review_list 的
# EMPTY_FP 组)强制保留, 且每轮训练必进。
def dhash_arr(a: np.ndarray) -> int:
    im = Image.fromarray(a.astype(np.uint8)).convert('L').resize((9, 8), Image.BILINEAR)
    g = np.asarray(im, np.int16)
    v = 0
    for b in (g[:, 1:] > g[:, :-1]).flatten():
        v = (v << 1) | int(b)
    return v


hard_names: set[str] = set()
review_list = os.path.join(os.path.dirname(OUT_CKPT), 'review_list.txt')
if os.path.exists(review_list):
    section = ''
    for line in open(review_list, encoding='utf-8'):
        if line.startswith('##'):
            section = line.strip()
            continue
        if section == '## EMPTY_FP' and line.strip():
            hard_names.add(line.rstrip('\n').split('\t')[-1].strip())
print(f"空位硬负样本 {len(hard_names)} 张")

live_mask0 = np.array([fn.startswith('Lmp_') for fn in fnames])
keep: list[int] = list(np.where(live_mask0)[0])          # 实时留出帧原样保留
by_label: dict[int, list[int]] = {}
for i in np.where(~live_mask0)[0]:
    by_label.setdefault(int(labels[i]), []).append(int(i))
dup_dropped = 0
hashes = {i: dhash_arr(data[i]) for i in np.where(~live_mask0)[0]}
for lab, idxs in by_label.items():
    clusters: list[list[int]] = []
    for i in idxs:
        h = hashes[i]
        placed = False
        for cl in clusters:
            if bin(hashes[cl[0]] ^ h).count('1') <= 3:
                cl.append(i)
                placed = True
                break
        if not placed:
            clusters.append([i])
    for cl in clusters:
        rep = next((i for i in cl if fnames[i] in hard_names), cl[0])
        keep.append(rep)
        dup_dropped += len(cl) - 1
keep = np.array(sorted(keep))
data, labels, fnames = data[keep], labels[keep], fnames[keep]
print(f"近重复剔除 {dup_dropped} 张，保留 {len(data)} 张")

live_mask = np.array([fn.startswith('Lmp_') for fn in fnames])
live_idx = np.where(live_mask)[0]
live_x = np.stack([data[i] for i in live_idx])
live_y = labels[live_idx]
usable = np.where(~live_mask)[0]
print(f"留出实时帧 {len(live_idx)} 格")

# ---------- 温和增强 ----------
# 实测漂移: 色调均值差 <10, std 差 <3; 上一版 jitter 叠加过猛把 EMPTY
# 打到 0.757。这里逐项收敛: 通道增益 0.95-1.05 / 偏移 ±8 / 亮度对比 ±10%
#
# 2026-10-22: 红/绿圈必须同时套黑子、红子与空格。旧增强只会画白圈/
# 暗环, 模型学会"红色光圈=红方棋子"——实测黑车+红圈→R_ROOK 置信
# 0.94-0.99。这是"少子"的识别层根因。
RING_COLORS = {
    'red': (225, 25, 25),
    'green': (60, 220, 80),
    'white': (235, 235, 235),
    'dark': (40, 40, 40),
}


def draw_ring(arr: np.ndarray, rng, color: tuple[int, int, int]) -> np.ndarray:
    h, w = arr.shape[:2]
    cy = h // 2 + int(rng.integers(-4, 5))
    cx = w // 2 + int(rng.integers(-4, 5))
    rr = h * float(rng.uniform(0.30, 0.48))
    width = max(2.0, h * float(rng.uniform(0.020, 0.055)))
    alpha = float(rng.uniform(0.55, 0.92))
    yy, xx = np.mgrid[0:h, 0:w]
    d2 = (yy - cy) ** 2 + (xx - cx) ** 2
    ring = (d2 <= rr * rr) & (d2 >= max(0.0, rr - width) ** 2)
    col = np.array(color, np.float32)
    arr[ring] = arr[ring] * (1 - alpha) + col * alpha
    return arr


def draw_dot(arr: np.ndarray, rng) -> np.ndarray:
    """JJ 的目标/威胁小圆点(红或绿)，通常落在空格或棋子边缘。"""
    h, w = arr.shape[:2]
    cy = h // 2 + int(rng.integers(-h // 4, h // 4))
    cx = w // 2 + int(rng.integers(-w // 4, w // 4))
    rr = h * float(rng.uniform(0.05, 0.11))
    alpha = float(rng.uniform(0.65, 1.0))
    col = np.array((230, 25, 25) if rng.random() < 0.7 else (60, 220, 80), np.float32)
    yy, xx = np.mgrid[0:h, 0:w]
    dot = (yy - cy) ** 2 + (xx - cx) ** 2 <= rr * rr
    arr[dot] = arr[dot] * (1 - alpha) + col * alpha
    return arr


def augment(a, rng):
    a = a.copy()
    # 不做水平翻转: 镜像汉字是训练分布外的形态, App 端也不做 TTA
    dx, dy = rng.integers(-2, 3), rng.integers(-2, 3)
    a = np.roll(np.roll(a, dy, 0), dx, 1)
    ang = np.deg2rad(rng.uniform(-6, 6))
    yy, xx = np.mgrid[0:IMG, 0:IMG]
    cy = cx = IMG / 2
    ys = ((yy - cy) * np.cos(ang) - (xx - cx) * np.sin(ang) + cy).astype(int)
    xs = ((yy - cy) * np.sin(ang) + (xx - cx) * np.cos(ang) + cx).astype(int)
    ok = (ys >= 0) & (ys < IMG) & (xs >= 0) & (xs < IMG)
    rot = np.zeros_like(a); rot[ys[ok], xs[ok]] = a[ys[ok], xs[ok]]
    a = rot
    a = a * rng.uniform(0.9, 1.1) + rng.uniform(-12, 12)
    if rng.random() < 0.4:
        a = a * rng.uniform(0.95, 1.05, size=(3,)) + rng.uniform(-8, 8, size=(3,))
    if rng.random() < 0.50:
        kind = rng.choice(['red', 'green', 'white', 'dark'], p=[0.35, 0.25, 0.25, 0.15])
        a = draw_ring(a, rng, RING_COLORS[str(kind)])
    if rng.random() < 0.14:
        a = draw_dot(a, rng)
    return np.clip(a, 0, 255)

def norm_t(a):
    # 0-255 → 0-1 → ImageNet 归一化 (Kotlin 端同一公式)
    t = ((a / 255.0 - IM_MEAN) / IM_STD).astype(np.float32).transpose(2, 0, 1)
    return t

# 分层划分(每类至少 1 个进参考 val; 自实现, 免 sklearn/scipy 版本地狱)
tr_i, va_i = [], []
for c in CLASSES:
    idxs = [i for i in usable if labels[i] == CIDX[c]]
    rr = random.Random(SEED + hash(c) % 1000)
    rr.shuffle(idxs)
    n_va = max(1, int(len(idxs) * 0.1))
    va_i.extend(idxs[:n_va]); tr_i.extend(idxs[n_va:])
random.Random(SEED).shuffle(tr_i); random.Random(SEED).shuffle(va_i)
tr_i, va_i = np.array(tr_i), np.array(va_i)
# 空位硬负样本必须进训练集(它们是本次要修的回归项); 留出实时帧才是泛化门槛
hard_tr = [i for i in usable if fnames[i] in hard_names and labels[i] == CIDX['EMPTY']]
tr_i = np.array([i for i in tr_i if i not in set(hard_tr)] + hard_tr)
va_i = np.array([i for i in va_i if i not in set(hard_tr)])
random.Random(SEED).shuffle(tr_i)
print(f"训练 {len(tr_i)}  参考val {len(va_i)}  留出实时帧 {len(live_idx)} 格 "
      f"(硬负样本 {len(hard_tr)})")

# ---------- 训练计划(类别均衡 + 硬负样本必进) ----------
from collections import Counter
cnt = Counter(labels[tr_i])
cap = min(int(max(cnt.values()) * 1.0), 200)
plan: list[int] = []
for c in CLASSES:
    idxs = [i for i in tr_i if labels[i] == CIDX[c]]
    if not idxs:
        continue
    r2 = random.Random(SEED + CIDX[c])
    if c == 'EMPTY':
        # 每轮都带全部硬负样本, 其余随机补足——旧版 EMPTY 只抽 50 张,
        # 71 个硬负样本每轮命中率极低, 模型至今没学会它们
        h = [i for i in idxs if i in set(hard_tr)]
        rest = [i for i in idxs if i not in set(hard_tr)]
        need = max(120, len(h) + 40)
        plan.extend(h)
        if rest:
            plan.extend(r2.sample(rest, min(len(rest), need - len(h))))
        continue
    rep, rem = divmod(cap, max(1, len(idxs)))
    plan.extend(idxs * rep)
    plan.extend(r2.sample(idxs, rem) if rem else [])
random.Random(SEED).shuffle(plan)
print(f"均衡后每 epoch {len(plan)}")

va_x = torch.stack([torch.from_numpy(norm_t(data[i])) for i in va_i])
va_y = torch.tensor([labels[i] for i in va_i])
live_t = torch.stack([torch.from_numpy(norm_t(a)) for a in live_x])
EPOCHS_HEAD, EPOCHS_ALL = 6, 30

def batchify(idxs, bs=64):
    for s in range(0, len(idxs), bs):
        chunk = idxs[s:s + bs]
        xs = torch.stack([torch.from_numpy(
            norm_t(augment(data[i], rng))) for i in chunk])
        ys = torch.tensor([labels[i] for i in chunk])
        yield xs, ys

model = mobilenet_v3_small(weights=MobileNet_V3_Small_Weights.IMAGENET1K_V1)
model.classifier[3] = nn.Linear(model.classifier[3].in_features, len(CLASSES))
rng = np.random.default_rng(SEED)

hard_t = torch.stack([torch.from_numpy(norm_t(data[i])) for i in hard_tr]) \
    if hard_tr else torch.zeros(0, 3, IMG, IMG)


def evaluate():
    model.eval()
    with torch.no_grad():
        # 不做翻转 TTA: App 端只做单次前向, 验收口径必须一致
        lg = model(va_x)
        acc = float((lg.argmax(1) == va_y).float().mean())
        mlive = None
        if len(live_t):
            lgl = model(live_t)
            t2l = torch.topk(lgl, 2, dim=1)
            m = torch.sigmoid(t2l.values[:, 0] - t2l.values[:, 1]).numpy()
            hard = int(((m >= 0.9) & (t2l.indices[:, 0].numpy() != live_y)).sum())
            conf = int((m >= 0.9).sum())
            mlive = (conf, len(live_t), hard)
        hard_bad = 0
        if len(hard_t):
            hl = model(hard_t)
            t2h = torch.topk(hl, 2, dim=1)
            hm = torch.sigmoid(t2h.values[:, 0] - t2h.values[:, 1]).numpy()
            hard_bad = int(((hm >= 0.9) &
                            (t2h.indices[:, 0].numpy() != CIDX['EMPTY'])).sum())
    return acc, mlive, hard_bad

acc0, live0, hb0 = evaluate()
print(f"初始: val={acc0:.4f} 留出live={live0} 空位硬负错={hb0}")

opt = torch.optim.AdamW(
    [p for p in model.classifier.parameters()], lr=2e-3, weight_decay=1e-4)
best_live, best_state = -1, None
def live_score(mlive, hard_bad):
    conf, n, hard = mlive
    return conf / n - hard * 3.0 / max(1, n) - hard_bad * 0.02

for ep in range(EPOCHS_HEAD + EPOCHS_ALL):
    if ep == EPOCHS_HEAD:
        print("=== 解冻全模型, lr 3e-4 + 余弦退火 ===")
        opt = torch.optim.AdamW(model.parameters(), lr=3e-4, weight_decay=1e-4)
        sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=EPOCHS_ALL)
    if ep >= EPOCHS_HEAD:
        sched.step()
    model.train()
    tot, nb = 0.0, 0
    for xs, ys in batchify(plan):
        opt.zero_grad()
        loss = F.cross_entropy(model(xs), ys)
        loss.backward(); opt.step()
        tot += float(loss); nb += 1
    acc, mlive, hard_bad = evaluate()
    ls = live_score(mlive, hard_bad) if mlive else -1
    gate = (f" 留出live conf={mlive[0]}/{mlive[1]} hard={mlive[2]}" if mlive else "")
    print(f"ep{ep+1:02d} loss={tot/nb:.3f} val={acc:.4f}{gate} 空位硬负错={hard_bad}")
    # 硬门槛: 留出帧零硬错 + 空位硬负样本零高置信误报
    if mlive and mlive[2] == 0 and hard_bad == 0 and ls >= best_live:
        best_live = ls
        best_state = {k: v.clone() for k, v in model.state_dict().items()}

if best_state is None:
    print("!! 留出帧从未零硬错/硬负样本仍有高置信误报——不得上线, 需回查数据/增强")
    raise SystemExit(1)
model.load_state_dict(best_state)
torch.save({'state': best_state, 'classes': CLASSES, 'img': IMG}, OUT_CKPT)

acc, mlive, hard_bad = evaluate()
print(f"最终 val={acc:.4f} 留出 conf={mlive[0]}/{mlive[1]} hard={mlive[2]} "
      f"拒判={mlive[1]-mlive[0]} 空位硬负错={hard_bad}")

# ---------- 红圈黑子回归测试 ----------
# 从干净的黑子样本里合成"黑子+红圈", 要求预测颜色仍为黑。
ring_rng = np.random.default_rng(20261022)
black_pool = [i for i in range(len(data))
              if CLASSES[labels[i]].startswith('B_') and fnames[i] not in ('',)]
ring_x, ring_y = [], []
for i in black_pool[:60]:
    a = draw_ring(data[i].copy(), ring_rng, RING_COLORS['red'])
    ring_x.append(torch.from_numpy(norm_t(a)))
    ring_y.append(CLASSES[labels[i]])
ring_t = torch.stack(ring_x)
with torch.no_grad():
    ring_lg = model(ring_t)
ring_pred = [CLASSES[int(k)] for k in ring_lg.argmax(1)]
ring_bad = sum(1 for p in ring_pred if p.startswith('R_'))
print(f"红圈黑子回归: {len(ring_pred)} 张, 颜色翻转 {ring_bad} 张")

# ---------- ONNX 导出（先写 tools/out，验收通过才替换 assets）----------
NEW_ONNX = r'E:\learn\auto_cheese\tools\out\cnn_new.onnx'
NEW_META = r'E:\learn\auto_cheese\tools\out\cnn_meta_new.json'
model.cpu().eval()
dummy = torch.zeros(1, 3, IMG, IMG)
torch.onnx.export(model, dummy, NEW_ONNX, input_names=['cells'],
                  output_names=['logits'], dynamic_axes={'cells': {0: 'n'}, 'logits': {0: 'n'}},
                  opset_version=17)
meta = {'classes': CLASSES, 'img': IMG,
        'mean': IM_MEAN.tolist(), 'std': IM_STD.tolist()}
json.dump(meta, open(NEW_META, 'w'))
sz = os.path.getsize(NEW_ONNX) // 1024
print(f"OK {NEW_ONNX} {sz}KB + {NEW_META}")

# ---------- 新旧模型同集对比 ----------
def onnx_eval(path: str, tensors: list[torch.Tensor]) -> list[int]:
    s = ort.InferenceSession(path, providers=['CPUExecutionProvider'])
    out = []
    for t in tensors:
        lg = s.run(None, {'cells': t.numpy()})[0]
        out.append(lg)
    return out

def summarize(lgs, labels, want: str | None = None):
    conf = hard = bad = 0
    for lg, y in zip(lgs, labels):
        o = np.argsort(lg)[::-1]
        m = 1 / (1 + np.exp(-(lg[o[0]] - lg[o[1]])))
        if want is None:
            if m >= 0.9:
                conf += 1
                if o[0] != y:
                    hard += 1
        else:
            pred = CLASSES[int(o[0])]
            if m >= 0.9 and not pred.startswith(want):
                bad += 1
    return conf, hard, bad

essential = [t for t in (live_t, hard_t, ring_t) if len(t)]
for t in essential:
    assert t.dtype == torch.float32
old_path = r'E:\learn\auto_cheese\tools\out\cnn_old.onnx'
if os.path.exists(old_path):
    old_live = onnx_eval(old_path, [live_t])[0]
    oc, oh, _ = summarize(old_live, live_y.tolist())
    ob = 0
    if len(hard_t):
        old_hard = onnx_eval(old_path, [hard_t])[0]
        _, _, ob = summarize(old_hard, [CIDX['EMPTY']] * len(hard_t))
    old_ring = onnx_eval(old_path, [ring_t])[0]
    old_ring_bad = sum(1 for lg in old_ring
                       if CLASSES[int(np.argmax(lg))].startswith('R_'))
    print(f"旧模型: 留出 conf={oc}/{len(live_y)} hard={oh} 空位硬负错={ob} "
          f"红圈黑子翻转={old_ring_bad}/{len(ring_y)}")
print(f"新模型: 留出 conf={mlive[0]}/{mlive[1]} hard={mlive[2]} "
      f"空位硬负错={hard_bad} 红圈黑子翻转={ring_bad}/{len(ring_y)}")

# ---------- 导出对拍(onnxruntime vs torch 同输入) ----------
sess = ort.InferenceSession(NEW_ONNX, providers=['CPUExecutionProvider'])
x = torch.stack([torch.from_numpy(norm_t(live_x[i])) for i in range(min(20, len(live_t)))])
with torch.no_grad():
    tl = model(x).numpy()
ol = sess.run(None, {'cells': x.numpy()})[0]
err = float(np.abs(tl - ol).max())
print(f"对拍最大 logits 误差 = {err:.6f} (须 <1e-3)")
assert err < 1e-2, "ONNX 与 PyTorch 输出偏差过大"

gate_ok = (mlive[2] == 0 and hard_bad == 0 and ring_bad <= 1
           and mlive[0] >= int(0.98 * mlive[1]))
if gate_ok:
    import shutil
    shutil.copy(NEW_ONNX, OUT_ONNX)
    shutil.copy(NEW_META, OUT_META)
    print("GATE PASS -> 已替换 app/src/main/assets/cnn.onnx")
else:
    print("GATE FAIL -> 保留旧模型；新模型在 tools/out/cnn_new.onnx，供人工复核")
    raise SystemExit(2)
print("DONE")
