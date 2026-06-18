import os
from PIL import Image

def process_image_fit(input_path, output_path, target_size, bg_color, padding_factor=0.85):
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
        
    target_w, target_h = target_size
    out_img = Image.new("RGBA", target_size, bg_color)
    
    # Resize aspect fit
    img_ratio = img.width / img.height
    target_ratio = target_w / target_h
    
    fit_w = int(target_w * padding_factor)
    fit_h = int(target_h * padding_factor)
    
    if img_ratio > target_ratio:
        new_w = fit_w
        new_h = int(new_w / img_ratio)
    else:
        new_h = fit_h
        new_w = int(new_h * img_ratio)
        
    img_resized = img.resize((new_w, new_h), Image.Resampling.LANCZOS)
    
    left = (target_w - new_w) // 2
    top = (target_h - new_h) // 2
    
    out_img.paste(img_resized, (left, top), img_resized)
    
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    out_img.save(output_path, "PNG")
    print(f"Saved: {output_path}")

input_img = "/home/ignacio/SpatialFin/docs/images/icon.png"

# Generate 1280x720 Store Banner
process_image_fit(
    input_img,
    "/home/ignacio/SpatialFin/store_tv_banner_1280x720.png",
    (1280, 720),
    (17, 19, 24, 255), # #111318
    padding_factor=0.9
)
