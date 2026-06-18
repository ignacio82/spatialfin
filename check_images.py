import os
from PIL import Image

def analyze_image(path):
    if not os.path.exists(path):
        return
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    bg = Image.new("RGBA", img.size, (0,0,0,0))
    diff = Image.composite(img, bg, img)
    bbox = diff.getbbox()
    print(f"{path}: size={w}x{h}, bbox={bbox}")

files = [
    "/home/ignacio/SpatialFin/core/src/main/res/drawable/ic_banner.png",
    "/home/ignacio/SpatialFin/app/unified/src/main/res/mipmap-mdpi/ic_launcher.png",
    "/home/ignacio/SpatialFin/app/unified/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
]
for f in files:
    analyze_image(f)
