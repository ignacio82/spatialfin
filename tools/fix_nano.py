from PIL import Image

# 1. Fix Banner
banner_in = "/home/ignacio/.gemini/antigravity-cli/brain/6e8e6918-a4d0-43a1-b06f-016dfd784be9/nano_banana_banner_1781553332668.jpg"
b = Image.open(banner_in).convert("RGBA")
# Target 320x180
bw, bh = b.size
target_ratio = 320/180
b_ratio = bw/bh
if b_ratio > target_ratio:
    new_h = bh
    new_w = int(new_h * target_ratio)
else:
    new_w = bw
    new_h = int(new_w / target_ratio)
left = (bw - new_w)//2
top = (bh - new_h)//2
b_cropped = b.crop((left, top, left+new_w, top+new_h))
b_resized = b_cropped.resize((320, 180), Image.Resampling.LANCZOS)
b_resized.save("/home/ignacio/SpatialFin/core/src/main/res/drawable-xhdpi/ic_banner.png", "PNG")
print("Saved banner")

# 2. Fix Icon
icon_in = "/home/ignacio/.gemini/antigravity-cli/brain/6e8e6918-a4d0-43a1-b06f-016dfd784be9/nano_banana_icon_1781553341695.jpg"
i = Image.open(icon_in).convert("RGBA")
iw, ih = i.size
# Crop central 65% to avoid the white rounded corners
c = 0.65
cw, ch = int(iw * c), int(ih * c)
left = (iw - cw)//2
top = (ih - ch)//2
i_cropped = i.crop((left, top, left+cw, top+ch))
i_resized = i_cropped.resize((512, 512), Image.Resampling.LANCZOS)
i_resized.save("/home/ignacio/SpatialFin/store_icon_512.png", "PNG")
print("Saved icon")

