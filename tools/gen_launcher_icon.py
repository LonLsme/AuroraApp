"""
为 AuroraApp 生成圆角图标。

输入：logo/aurora-logo.png (940×940 RGB, 白色背景, 人物在右下角)
输出：
  - drawable/ic_launcher_foreground.png  (透明 + 居中人物, 432×432 = 108dp @xxxhdpi)
  - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png     (圆角矩形 + 居中人物, 48/72/96/144/192)
  - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_round.png (同上)
"""

import os
import numpy as np
from PIL import Image, ImageFilter
from collections import deque

SRC = r'E:\Personal-website\XIAOMITOOL\logo\aurora-logo.png'
DST = r'E:\Personal-website\XIAOMITOOL\AuroraApp\app\src\main\res'

# 圆角矩形：108 单位画布, 圆角 r=24 (iOS 同款 22%)
BG_SIZE = 108
BG_RADIUS = 24
# adaptive icon foreground 安全区 66dp = 61% of 108
FG_SAFE = 66

# 白色背景: 与 drawable/ic_launcher_background.xml 的渐变一致, 上白下极浅灰
BG_TOP = (255, 255, 255)     # #FFFFFF
BG_BOTTOM = (244, 246, 249)  # #F4F6F9

# mipmap 尺寸
MIPMAP_SIZES = {
    'mdpi':    48,
    'hdpi':    72,
    'xhdpi':   96,
    'xxhdpi':  144,
    'xxxhdpi': 192,
}

# ----------------------------------------------------------------------
# Step 1: 读取原图, 转 RGBA, 用 4 角 flood fill 抠白底 (抗锯齿边缘干净)
# ----------------------------------------------------------------------
print('[1] 读取并 4 角 flood fill 抠白底...')
src = Image.open(SRC).convert('RGB')
arr = np.array(src)
H, W = arr.shape[:2]
# 与"白"差距: max(|255-R|, |255-G|, |255-B|) 越小越白
dist_white = np.maximum(np.abs(255 - arr[..., 0].astype(np.int16)),
                        np.abs(255 - arr[..., 1].astype(np.int16)),
                        np.abs(255 - arr[..., 2].astype(np.int16)))
# 阈值 18 视为白 (含抗锯齿浅色)
WHITE_TH = 18
is_white = dist_white < WHITE_TH
# BFS 从四个角: 只把"连接到边界的白色区域"判为背景
visited = np.zeros_like(is_white, dtype=bool)
q = deque()
for x0, y0 in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]:
    if is_white[y0, x0] and not visited[y0, x0]:
        visited[y0, x0] = True
        q.append((x0, y0))
while q:
    x, y = q.popleft()
    for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
        if 0 <= nx < W and 0 <= ny < H and is_white[ny, nx] and not visited[ny, nx]:
            visited[ny, nx] = True
            q.append((nx, ny))
bg_mask = visited  # 与四角连通的白色 = 背景
# 主体 alpha: 主体=255, 背景=0
alpha = np.where(bg_mask, 0, 255).astype(np.uint8)
# 边缘羽化: 0.5px
alpha_img = Image.fromarray(alpha, mode='L').filter(ImageFilter.GaussianBlur(radius=0.4))
alpha = np.array(alpha_img)
# ----------------------------------------------------------------------
# Step 2: 找主体 bounding box, 居中缩放
# ----------------------------------------------------------------------
print('[2] 主体定位与缩放...')
mask = alpha > 16
ys, xs = np.where(mask)
if len(xs) == 0:
    raise RuntimeError('未检测到主体')
x0, x1 = xs.min(), xs.max()
y0, y1 = ys.min(), ys.max()
w, h = x1 - x0 + 1, y1 - y0 + 1
print(f'    主体 bbox: ({x0},{y0})-({x1},{y1}) = {w}x{h}')

# 主体裁出来
subject = Image.fromarray(np.dstack([arr, alpha])).crop((x0, y0, x1 + 1, y1 + 1))
sw, sh = subject.size
# 缩放到 66dp 安全区
scale = min(FG_SAFE / sw, FG_SAFE / sh)
new_w = max(1, int(round(sw * scale)))
new_h = max(1, int(round(sh * scale)))
subject_resized = subject.resize((new_w, new_h), Image.LANCZOS)
print(f'    缩放: {sw}x{sh} -> {new_w}x{new_h} (scale={scale:.3f})')

# ----------------------------------------------------------------------
# Step 3: 合成 foreground (透明 108×108 画布 + 居中主体)
# ----------------------------------------------------------------------
print('[3] 合成 adaptive-icon foreground...')
fg_full = Image.new('RGBA', (BG_SIZE, BG_SIZE), (0, 0, 0, 0))
ox = (BG_SIZE - new_w) // 2
oy = (BG_SIZE - new_h) // 2
fg_full.paste(subject_resized, (ox, oy), subject_resized)

# ----------------------------------------------------------------------
# Step 4: 合成 mipmap legacy PNG (圆角矩形底 + 居中主体)
# ----------------------------------------------------------------------
print('[4] 合成 mipmap-*dpi/ic_launcher.png ...')
def make_rounded_bg(size, radius):
    """生成对角渐变圆角矩形 (numpy 向量化)"""
    # 渐变背景
    ratio = np.linspace(0.0, 1.0, size, dtype=np.float32)[:, None]   # (size,1)
    top = np.array(BG_TOP, dtype=np.float32)
    bot = np.array(BG_BOTTOM, dtype=np.float32)
    color = top * (1 - ratio) + bot * ratio                          # (size,1,3)
    color = np.broadcast_to(color, (size, size, 3)).copy()
    # 圆角蒙版
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    dx = np.maximum.reduce([radius - xx, xx - (size - 1 - radius), np.zeros_like(xx)])
    dy = np.maximum.reduce([radius - yy, yy - (size - 1 - radius), np.zeros_like(yy)])
    d = np.sqrt(dx * dx + dy * dy)
    # 1px 抗锯齿
    a = np.clip((radius + 0.5 - d) * 255.0, 0, 255).astype(np.uint8)
    rgba = np.dstack([color.astype(np.uint8), a])
    return Image.fromarray(rgba, 'RGBA')

for ddir, sz in MIPMAP_SIZES.items():
    # 圆角半径按比例缩放
    radius = max(2, round(BG_RADIUS / BG_SIZE * sz))
    bg = make_rounded_bg(sz, radius)
    # 主体缩放到目标尺寸的安全区
    target_safe = int(round(sz * FG_SAFE / BG_SIZE))
    sub_scale = min(target_safe / sw, target_safe / sh)
    sub_w = max(1, int(round(sw * sub_scale)))
    sub_h = max(1, int(round(sh * sub_scale)))
    sub_resized = subject.resize((sub_w, sub_h), Image.LANCZOS)
    ox2 = (sz - sub_w) // 2
    oy2 = (sz - sub_h) // 2
    # 主体粘贴时用自身 alpha, 不受圆角限制 (因为主体本身是透明的)
    bg.paste(sub_resized, (ox2, oy2), sub_resized)
    out = os.path.join(DST, f'mipmap-{ddir}', 'ic_launcher.png')
    bg.convert('RGBA').save(out, 'PNG', optimize=True)
    out2 = os.path.join(DST, f'mipmap-{ddir}', 'ic_launcher_round.png')
    bg.convert('RGBA').save(out2, 'PNG', optimize=True)
    print(f'    -> mipmap-{ddir}/ ({sz}x{sz}, r={radius})')

# ----------------------------------------------------------------------
# Step 5: 输出 adaptive-icon foreground (432x432 透明)
# ----------------------------------------------------------------------
print('[5] 输出 drawable/ic_launcher_foreground.png (432x432)...')
fg_out = fg_full.resize((432, 432), Image.LANCZOS)
fg_out.save(os.path.join(DST, 'drawable', 'ic_launcher_foreground.png'), 'PNG', optimize=True)

# ----------------------------------------------------------------------
# Step 6: 删除我自己写错的矢量前景 (ic_launcher_foreground.xml)
# ----------------------------------------------------------------------
xml_path = os.path.join(DST, 'drawable', 'ic_launcher_foreground.xml')
if os.path.exists(xml_path):
    os.remove(xml_path)
    print(f'[6] 删除 {xml_path}')

print('\n全部完成。')
