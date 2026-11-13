#!/usr/bin/env python3
"""Burns phase captions into a screen recording (ffmpeg overlay; needs Pillow).
Usage: caption-video.py <in.mp4> <out.mp4> "<start>,<end>,<text>" ...
"""
import subprocess, sys, tempfile, os
from PIL import Image, ImageDraw, ImageFont

FONT = "/System/Library/Fonts/Supplemental/Arial.ttf"
WIDTH = 720
src, dst, *caps = sys.argv[1:]
tmp = tempfile.mkdtemp()
font = ImageFont.truetype(FONT, 26)
inputs, chain, last = [], [f"[0]scale={WIDTH}:-2[v0]"], "v0"
for i, cap in enumerate(caps):
    start, end, text = cap.split(",", 2)
    l, t, r, b = font.getbbox(text)
    img = Image.new("RGBA", (r - l + 32, b - t + 24), (0, 0, 0, 200))
    ImageDraw.Draw(img).text((16 - l, 12 - t), text, font=font, fill="white")
    path = os.path.join(tmp, f"cap{i}.png")
    img.save(path)
    inputs += ["-i", path]
    chain.append(f"[{last}][{i + 1}]overlay=x=(W-w)/2:y=H-h-130:enable='between(t,{start},{end})'[v{i + 1}]")
    last = f"v{i + 1}"
subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, *inputs, "-filter_complex", ";".join(chain),
                "-map", f"[{last}]", "-c:v", "libx264", "-crf", "28", "-preset", "slow",
                "-pix_fmt", "yuv420p", "-an", dst], check=True)
