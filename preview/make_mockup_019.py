#!/usr/bin/env python3
# 按 1dp=2px 画 v0.1.9 的键盘预览，浅色和深色各一张
from PIL import Image, ImageDraw, ImageFont

W = 760
PANEL_H = 126          # 4 + 50 + 6 dp 左右的键盘主体
NUM_H = 76             # 展开的数字行
FONT = "/System/Library/Fonts/Hiragino Sans GB.ttc"

LIGHT = dict(panel=(242, 244, 248), btn=(255, 255, 255), stroke=(226, 230, 237),
             text=(23, 25, 28), sub=(112, 120, 133), bar=(255, 255, 255),
             accent=(46, 124, 246), page=(238, 240, 245))
DARK = dict(panel=(20, 22, 26), btn=(35, 39, 46), stroke=(42, 47, 55),
            text=(255, 255, 255), sub=(155, 161, 172), bar=(35, 39, 46),
            accent=(76, 141, 255), page=(28, 30, 35))


def f(size):
    return ImageFont.truetype(FONT, size, index=0)


def txt(d, xy, s, size, color, anchor="mm"):
    d.text(xy, s, font=f(size), fill=color, anchor=anchor)


def mic(d, cx, cy, s, color, width=4):
    d.rounded_rectangle([cx - s * .26, cy - s * .95, cx + s * .26, cy + s * .12],
                        radius=s * .26, fill=color)
    d.arc([cx - s * .52, cy - s * .50, cx + s * .52, cy + s * .48], 0, 180, color, width)
    d.line([(cx, cy + s * .46), (cx, cy + s * .86)], fill=color, width=width)
    d.line([(cx - s * .34, cy + s * .86), (cx + s * .34, cy + s * .86)], fill=color, width=width)


def settings_icon(d, cx, cy, s, color):
    # 三条带滑块的横线，比齿轮好画也更像“设置”
    for i, off in enumerate((-s * .55, 0, s * .55)):
        y = cy + off
        d.line([(cx - s * .8, y), (cx + s * .8, y)], fill=color, width=4)
        knob_x = cx + (-s * .35 if i == 1 else s * .35)
        d.ellipse([knob_x - 6, y - 6, knob_x + 6, y + 6], fill=color)


def enter_icon(d, cx, cy, s, color):
    d.line([(cx + s * .8, cy - s * .55), (cx + s * .8, cy + s * .1)], fill=color, width=4)
    d.line([(cx + s * .8, cy + s * .1), (cx - s * .75, cy + s * .1)], fill=color, width=4)
    d.polygon([(cx - s * .78, cy + s * .1), (cx - s * .3, cy - s * .28),
               (cx - s * .3, cy + s * .48)], fill=color)


def backspace(d, cx, cy, s, fg, bg):
    d.polygon([(cx - s * .95, cy), (cx - s * .34, cy - s * .66),
               (cx + s * .95, cy - s * .66), (cx + s * .95, cy + s * .66),
               (cx - s * .34, cy + s * .66)], fill=fg)
    w = 5
    d.line([(cx + s * .02, cy - s * .26), (cx + s * .46, cy + s * .24)], fill=bg, width=w)
    d.line([(cx + s * .46, cy - s * .26), (cx + s * .02, cy + s * .24)], fill=bg, width=w)


def draw_key(d, box, c, radius=24):
    d.rounded_rectangle(box, radius=radius, fill=c["btn"], outline=c["stroke"], width=2)


def keyboard(with_numbers=True, theme="light", out="preview/keyboard_light.png"):
    c = LIGHT if theme == "light" else DARK
    total_h = PANEL_H + (NUM_H if with_numbers else 0)
    H = 300 + total_h
    img = Image.new("RGB", (W, H), c["page"])
    d = ImageDraw.Draw(img)

    # 上半部分假装是聊天输入区，方便看键盘贴在页面上的效果
    d.rounded_rectangle([20, 240 - 60, W - 20, 240], radius=16,
                        fill=(255, 255, 255) if theme == "light" else (54, 58, 66))
    txt(d, (W // 2, 210), "按住下方语音条说话", 22,
        (120, 126, 138) if theme == "light" else (150, 156, 168))

    top = H - total_h
    d.rectangle([0, top, W, H], fill=c["panel"])
    d.line([(0, top), (W, top)], fill=c["stroke"], width=2)

    y = top + 8
    if with_numbers:
        keys = list("1234567890")
        kw = (W - 32 - 9 * 8) // 10
        for i, k in enumerate(keys):
            x = 16 + i * (kw + 8)
            draw_key(d, [x, y + 4, x + kw, y + 68], c, 20)
            txt(d, (x + kw // 2, y + 38), k, 30, c["text"])
        y += NUM_H

    row_top = y
    row_h = 100

    # 左组
    draw_key(d, [20, row_top + 14, 92, row_top + 86], c)
    settings_icon(d, 56, row_top + 50, 16, c["text"])
    draw_key(d, [104, row_top + 14, 176, row_top + 86], c)
    txt(d, (140, row_top + 50), "123", 26, c["text"])

    # 语音条
    bar = [200, row_top + 10, W - 200, row_top + 90]
    d.rounded_rectangle(bar, radius=44, fill=c["bar"], outline=c["stroke"], width=2)
    mic(d, 236, row_top + 50, 20, c["text"], 4)
    txt(d, (268, row_top + 50), "按住说话", 24, c["text"], anchor="lm")

    # 右组
    draw_key(d, [W - 184, row_top + 14, W - 108, row_top + 86], c)
    d2 = ImageDraw.Draw(img)
    backspace(d, W - 146, row_top + 50, 22, c["text"], c["btn"])
    draw_key(d, [W - 96, row_top + 14, W - 20, row_top + 86], c)
    enter_icon(d, W - 58, row_top + 50, 18, c["text"])

    img.save(out)
    print(out)


keyboard(False, "light", "preview/keyboard_light.png")
keyboard(True, "dark", "preview/keyboard_dark.png")
