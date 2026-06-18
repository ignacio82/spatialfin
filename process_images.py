import os
from PIL import Image

def process_image(input_path, output_path, target_size, bg_color):
    if not os.path.exists(input_path):
        print(f"File not found: {input_path}")
        return
        
    img = Image.open(input_path).convert("RGBA")
    
    # Trim transparency
    bg = Image.new("RGBA", img.size, (0,0,0,0))
    diff = Image.composite(img, bg, img)
    bbox = diff.getbbox()
    if bbox:
        img = img.crop(bbox)
        
    # Create background
    target_w, target_h = target_size
    out_img = Image.new("RGBA", target_size, bg_color)
    
    # Resize aspect fill
    img_ratio = img.width / img.height
    target_ratio = target_w / target_h
    
    if img_ratio > target_ratio:
        # Image is wider, scale by height
        new_h = target_h
        new_w = int(new_h * img_ratio)
    else:
        # Image is taller, scale by width
        new_w = target_w
        new_h = int(new_w / img_ratio)
        
    img_resized = img.resize((new_w, new_h), Image.Resampling.LANCZOS)
    
    # Center crop
    left = (new_w - target_w) // 2
    top = (new_h - target_h) // 2
    img_cropped = img_resized.crop((left, top, left + target_w, top + target_h))
    
    # Paste on background
    out_img.paste(img_cropped, (0, 0), img_cropped)
    
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    # Save as PNG
    out_img.save(output_path, "PNG")
    print(f"Saved: {output_path}")

process_image(
    "/home/ignacio/SpatialFin/core/src/main/res/drawable/ic_banner.png",
    "/home/ignacio/SpatialFin/core/src/main/res/drawable-xhdpi/ic_banner.png",
    (320, 180),
    (17, 19, 24, 255) # #111318
)

process_image(
    "/home/ignacio/SpatialFin/app/unified/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
    "/home/ignacio/SpatialFin/store_icon_512.png",
    (512, 512),
    (26, 26, 46, 255) # #1A1A2E
)
