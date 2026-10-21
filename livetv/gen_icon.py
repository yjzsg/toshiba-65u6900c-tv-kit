# -*- coding: utf-8 -*-
"""Generate a simple launcher icon for the live TV app."""
from PIL import Image, ImageDraw
import os

S = 512
img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
# rounded background
d.rounded_rectangle([0, 0, S - 1, S - 1], radius=96, fill=(21, 101, 192, 255))
# screen
d.rounded_rectangle([72, 120, S - 72, S - 150], radius=28, fill=(255, 255, 255, 255))
# antenna
d.line([(S // 2, 120), (S // 2 - 70, 56)], fill=(255, 255, 255, 255), width=18)
d.line([(S // 2, 120), (S // 2 + 70, 56)], fill=(255, 255, 255, 255), width=18)
# play triangle
d.polygon([(S // 2 - 46, S // 2 - 52), (S // 2 - 46, S // 2 + 52), (S // 2 + 62, S // 2)],
          fill=(21, 101, 192, 255))
# base
d.rounded_rectangle([S // 2 - 90, S - 150, S // 2 + 90, S - 118], radius=14, fill=(255, 255, 255, 255))

out = r'D:\Temp\livetv\res\drawable-nodpi'
os.makedirs(out, exist_ok=True)
p = os.path.join(out, 'ic_launcher.png')
img.save(p)
print('saved', p, os.path.getsize(p))
