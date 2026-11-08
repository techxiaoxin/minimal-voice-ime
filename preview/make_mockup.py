#!/usr/bin/env python3
# 1dp=2px 按真实布局参数渲染，色值取自 res/values/colors.xml
import math
from PIL import Image, ImageDraw, ImageFont

W, H = 760, 1560
PANEL = 140
BG = (23, 25, 30)
PANEL_BG = (20, 22, 26)
BTN = (35, 39, 46)
ACCENT = (76, 141, 255)
RECORD = (255, 77, 79)
WHITE = (255, 255, 255)
GREY = (155, 161, 172)
BUBBLE = (42, 46, 54)
BUBBLE_ME = (46, 88, 160)

FONT = "/System/Library/Fonts/Hiragino Sans GB.ttc"


def f(size):
    return ImageFont.truetype(FONT, size, index=0)


def txt(d, xy, s, size, color, anchor="la"):
    d.text(xy, s, font=f(size), fill=color, anchor=anchor)


def mic(d, cx, cy, s, color):
    d.rounded_rectangle([cx - s * .26, cy - s * .95, cx + s * .26, cy + s * .12], radius=s * .26, fill=color)
    d.arc([cx - s * .52, cy - s * .45, cx + s * .52, cy + s * .55], 0, 180, color, int(s * .13))
    d.line([(cx, cy + s * .5), (cx, cy + s * .85)], fill=color, width=int(s * .13))


def backspace(d, cx, cy, s, fg, bg):
    d.polygon([(cx - s * .95, cy), (cx - s * .34, cy - s * .66), (cx + s * .95, cy - s * .66),
               (cx + s * .95, cy + s * .66), (cx - s * .34, cy + s * .66)], fill=fg)
    d.line([(cx - s * .02, cy - s * .26), (cx + s * .5, cy + s * .26)], fill=bg, width=max(3, int(s * .13)))
    d.line([(cx + s * .5, cy - s * .26), (cx - s * .02, cy + s * .26)], fill=bg, width=max(3, int(s * .13)))


def send(d, cx, cy, s, fg):
    d.polygon([(cx - s * .85, cy - s * .8), (cx + s * .95, cy), (cx - s * .85, cy + s * .8), (cx - s * .45, cy)], fill=fg)


def gear(d, cx, cy, s, fg, bg):
    for i in range(8):
        a = math.radians(i * 45)
        r1, r2, hh = s * .6, s * .95, math.radians(15)
        d.polygon([(cx + r1 * math.cos(a - hh), cy + r1 * math.sin(a - hh)),
                   (cx + r2 * math.cos(a - hh), cy + r2 * math.sin(a - hh)),
                   (cx + r2 * math.cos(a + hh), cy + r2 * math.sin(a + hh)),
                   (cx + r1 * math.cos(a + hh), cy + r1 * math.sin(a + hh))], fill=fg)
    d.ellipse([cx - s * .62, cy - s * .62, cx + s * .62, cy + s * .62], fill=fg)
    d.ellipse([cx - s * .26, cy - s * .26, cx + s * .26, cy + s * .26], fill=bg)


def grid(d, cx, cy, s, fg):
    for i in range(3):
        for j in range(3):
            x = cx - s * .8 + i * s * .8
            y = cy - s * .8 + j * s * .8
            d.rectangle([x, y, x + s * .45, y + s * .45], fill=fg)


def draw_phone(mode):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    txt(d, (28, 30), "9:41", 26, WHITE)
    for i in range(4):
        hh = 8 + i * 3
        d.rectangle([W - 170 + i * 12, 40 - hh, W - 162 + i * 12, 40], fill=GREY)
    d.rounded_rectangle([W - 76, 25, W - 42, 41], radius=3, outline=(120, 124, 132), width=2)

    txt(d, (W / 2, 78), "微信", 30, WHITE, "ma")
    d.line([(30, 96), (30, 78)], fill=GREY, width=3)
    d.line([(30, 78), (20, 87)], fill=GREY, width=3)
    d.line([(30, 96), (20, 87)], fill=GREY, width=3)

    msgs = [("在吗", False), ("明天下午三点我过去吧", True), ("好，等你", False),
            ("带上那个增距镜", True), ("行，我记得", False), ("到时候给你电话", True)]
    y = 170
    for body, mine in msgs:
        tw = d.textlength(body, font=f(24))
        bw, hh = tw + 44, 66
        x0 = W - 40 - bw if mine else 40
        d.rounded_rectangle([x0, y, x0 + bw, y + hh], radius=18, fill=BUBBLE_ME if mine else BUBBLE)
        txt(d, (x0 + 22, y + 19), body, 24, WHITE)
        y += hh + 26

    num_h = 90 if mode == "numbers" else 0
    box_y = H - 60 - PANEL - num_h - 116
    d.rounded_rectangle([40, box_y, W - 40, box_y + 82], radius=20, fill=BUBBLE)
    txt(d, (66, box_y + 26), "明天下午三点我过去", 22, WHITE)
    d.rectangle([266, box_y + 24, 268, box_y + 58], fill=ACCENT)

    # 数字九宫格（仅第三屏展开）
    if mode == "numbers":
        top = H - 60 - PANEL - num_h
        d.rectangle([0, top, W, top + num_h], fill=PANEL_BG)
        keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"]
        cell = (W - 12) / 10
        for c, k in enumerate(keys):
            x0 = 6 + c * cell + 4
            x1 = 6 + c * cell + cell - 4
            y0 = top + 4
            y1 = top + 84
            d.rounded_rectangle([x0, y0, x1, y1], radius=12, fill=BTN)
            txt(d, ((x0 + x1) / 2, (y0 + y1) / 2 - 2), k, 32, WHITE, "mm")

    # 面板
    py = H - 60 - PANEL
    d.rectangle([0, py, W, py + PANEL], fill=PANEL_BG)

    rec = mode == "recording"
    btn_y = py + (PANEL - 80) / 2
    voice_y0 = py + (PANEL - 88) / 2

    # 最左：设置 38dp
    d.rounded_rectangle([16, btn_y + 4, 92, btn_y + 80], radius=18, fill=BTN)
    gear(d, 54, btn_y + 42, 20, WHITE, BTN)
    # 左：中/数 40dp
    d.rounded_rectangle([104, btn_y, 184, btn_y + 80], radius=18, fill=BTN)
    txt(d, (144, btn_y + 40), "数" if mode == "numbers" else "中", 30, WHITE, "mm")
    # 中：语音条，占满中间
    d.rounded_rectangle([220, voice_y0, 540, voice_y0 + 88], radius=16, fill=RECORD if rec else BTN)
    label = "松开结束" if rec else "按住说话"
    content_color = WHITE
    tw = d.textlength(label, font=f(24))
    total = 24 + 8 + tw
    mic(d, 380 - total / 2 + 12, voice_y0 + 44, 24, content_color)
    txt(d, (380 - total / 2 + 32, voice_y0 + 44), label, 24, content_color, "lm")
    # 右：删除 40dp
    d.rounded_rectangle([564, btn_y, 644, btn_y + 80], radius=18, fill=BTN)
    backspace(d, 604, btn_y + 40, 21, WHITE, BTN)
    # 最右：发送 44dp
    d.rounded_rectangle([656, btn_y - 4, 744, btn_y + 84], radius=20, fill=BTN)
    send(d, 700, btn_y + 40, 24, WHITE)

    # 导航栏（跟面板同色，不再留白条）
    d.rectangle([0, H - 60, W, H], fill=PANEL_BG)
    d.rounded_rectangle([W / 2 - 26, H - 34, W / 2 + 26, H - 24], radius=5, fill=(90, 96, 108))
    return img


def main():
    modes = [("idle", "默认，按住说话"), ("recording", "按住不放，录音中"), ("numbers", "点中/数，横向数字行")]
    margin, gap = 70, 80
    title_h, cap_h = 132, 92
    cw = margin * 2 + W * 3 + gap * 2
    ch = title_h + H + cap_h + 30
    canvas = Image.new("RGB", (cw, ch), (245, 246, 248))
    d = ImageDraw.Draw(canvas)

    txt(d, (cw / 2, 30), "极简语音输入法  ·  新版面板", 40, (24, 26, 31), "ma")
    txt(d, (cw / 2, 82), "设置、中英数、语音、删除、发送，语音按住才启动", 22, (118, 124, 136), "ma")

    for i, (mode, cap) in enumerate(modes):
        x = margin + i * (W + gap)
        canvas.paste(draw_phone(mode), (x, title_h))
        txt(d, (x + W / 2, title_h + H + 34), cap, 24, (40, 44, 52), "ma")

    canvas.save("手机界面预览.png", "PNG")
    print("saved", canvas.size)


if __name__ == "__main__":
    main()
