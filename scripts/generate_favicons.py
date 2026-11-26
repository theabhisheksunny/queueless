#!/usr/bin/env python3
"""
QueueLess — Brand Favicon & App Icon Generator
Generates:
1. frontend/public/favicon.svg (Scalable vector favicon)
2. frontend/public/favicon.ico (Multi-resolution: 16x16, 32x32, 48x48)
3. frontend/public/favicon-32x32.png (Standard desktop tab icon)
4. frontend/public/favicon-16x16.png (Small tab icon)
5. frontend/public/apple-touch-icon.png (180x180 iOS home screen)
6. frontend/public/icon-192.png (192x192 PWA standard)
7. frontend/public/icon-512.png (512x512 PWA splash/maskable)
"""

import os

import numpy as np
from PIL import Image, ImageDraw

SVG_CONTENT = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="100%" height="100%">
  <defs>
    <linearGradient id="queueless-grad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#8b5cf6" />
      <stop offset="100%" stop-color="#6d28d9" />
    </linearGradient>
  </defs>

  <!-- Background Squircle Badge -->
  <rect width="512" height="512" rx="112" fill="url(#queueless-grad)" />

  <!-- Top-Left Finder Pattern -->
  <rect x="96" y="96" width="128" height="128" rx="28" fill="none" stroke="#ffffff" stroke-width="24" />
  <rect x="136" y="136" width="48" height="48" rx="12" fill="#ffffff" />

  <!-- Top-Right Finder Pattern -->
  <rect x="288" y="96" width="128" height="128" rx="28" fill="none" stroke="#ffffff" stroke-width="24" />
  <rect x="328" y="136" width="48" height="48" rx="12" fill="#ffffff" />

  <!-- Bottom-Left Finder Pattern -->
  <rect x="96" y="288" width="128" height="128" rx="28" fill="none" stroke="#ffffff" stroke-width="24" />
  <rect x="136" y="328" width="48" height="48" rx="12" fill="#ffffff" />

  <!-- Center Timing & Token Elements -->
  <rect x="236" y="236" width="40" height="40" rx="10" fill="#ffffff" />
  <rect x="236" y="144" width="40" height="32" rx="8" fill="#ffffff" />
  <rect x="144" y="236" width="32" height="40" rx="8" fill="#ffffff" />

  <!-- Bottom-Right Token Data Grid -->
  <rect x="288" y="288" width="76" height="24" rx="8" fill="#ffffff" />
  <rect x="388" y="288" width="24" height="76" rx="8" fill="#ffffff" />
  <rect x="328" y="328" width="40" height="40" rx="10" fill="#ffffff" />
  <rect x="348" y="388" width="64" height="24" rx="8" fill="#ffffff" />
  <rect x="288" y="348" width="24" height="64" rx="8" fill="#ffffff" />
</svg>
"""


def generate_master_image(size=1024):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    c1 = (139, 92, 246)  # #8b5cf6
    c2 = (109, 40, 217)  # #6d28d9

    # 1. Gradient background
    x_coords, y_coords = np.meshgrid(np.arange(size), np.arange(size))
    t = (x_coords + y_coords) / (2.0 * size)
    r = (c1[0] + (c2[0] - c1[0]) * t).astype(np.uint8)
    g = (c1[1] + (c2[1] - c1[1]) * t).astype(np.uint8)
    b = (c1[2] + (c2[2] - c1[2]) * t).astype(np.uint8)
    a = np.full((size, size), 255, dtype=np.uint8)
    grad = Image.fromarray(np.dstack((r, g, b, a)), "RGBA")

    bg_mask = Image.new("L", (size, size), 0)
    # 22% squircle corner radius
    ImageDraw.Draw(bg_mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.218), fill=255)
    img.paste(grad, (0, 0), bg_mask)

    # 2. Foreground white mask
    scale = size / 512.0
    fg_mask = Image.new("L", (size, size), 0)
    fg_draw = ImageDraw.Draw(fg_mask)

    def r_rect(x, y, w, h, radius, fill):
        fg_draw.rounded_rectangle(
            [round(x * scale), round(y * scale), round((x + w) * scale), round((y + h) * scale)],
            radius=max(2, round(radius * scale)),
            fill=fill,
        )

    def draw_finder(x, y):
        # Outer finder box (128x128, outer stroke 24)
        r_rect(x, y, 128, 128, 28, 255)
        # Inner cutout
        r_rect(x + 24, y + 24, 80, 80, 10, 0)
        # Center dot
        r_rect(x + 40, y + 40, 48, 48, 12, 255)

    draw_finder(96, 96)
    draw_finder(288, 96)
    draw_finder(96, 288)

    # Center elements
    r_rect(236, 236, 40, 40, 10, 255)
    r_rect(236, 144, 40, 32, 8, 255)
    r_rect(144, 236, 32, 40, 8, 255)

    # Bottom-right token grid
    r_rect(288, 288, 76, 24, 8, 255)
    r_rect(388, 288, 24, 76, 8, 255)
    r_rect(328, 328, 40, 40, 10, 255)
    r_rect(348, 388, 64, 24, 8, 255)
    r_rect(288, 348, 24, 64, 8, 255)

    white = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    img.paste(white, (0, 0), fg_mask)

    return img


def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    public_dir = os.path.join(repo_root, "frontend", "public")
    os.makedirs(public_dir, exist_ok=True)

    print(f"Generating QueueLess favicons into {public_dir}...")

    # 1. Write SVG
    svg_path = os.path.join(public_dir, "favicon.svg")
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write(SVG_CONTENT.strip() + "\n")
    print(f"  [CREATED] {svg_path}")

    # 2. Render Master 1024 Image
    master = generate_master_image(1024)

    # 3. PNG Variants
    sizes = {
        "favicon-16x16.png": 16,
        "favicon-32x32.png": 32,
        "apple-touch-icon.png": 180,
        "icon-192.png": 192,
        "icon-512.png": 512,
    }

    for filename, sz in sizes.items():
        out_path = os.path.join(public_dir, filename)
        resized = master.resize((sz, sz), Image.Resampling.LANCZOS)
        resized.save(out_path, "PNG", optimize=True)
        print(f"  [CREATED] {out_path} ({sz}x{sz})")

    # 4. Multi-resolution favicon.ico (16x16, 32x32, 48x48)
    ico_path = os.path.join(public_dir, "favicon.ico")
    p48 = master.resize((48, 48), Image.Resampling.LANCZOS)
    p32 = master.resize((32, 32), Image.Resampling.LANCZOS)
    p16 = master.resize((16, 16), Image.Resampling.LANCZOS)

    # Save multi-size ICO
    p48.save(ico_path, format="ICO", sizes=[(16, 16), (32, 32), (48, 48)], append_images=[p32, p16])
    print(f"  [CREATED] {ico_path} (Multi-res: 16x16, 32x32, 48x48)")

    print("All favicon assets generated successfully!")


if __name__ == "__main__":
    main()
