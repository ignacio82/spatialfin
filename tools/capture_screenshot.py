import subprocess
import os
import time
from PIL import Image
from io import BytesIO

def get_active_device():
    result = subprocess.run(['adb', 'devices'], capture_output=True, text=True)
    lines = result.stdout.strip().split('\n')[1:]
    for line in lines:
        if line.endswith('device'):
            return line.split()[0]
    return None

def capture_screenshot():
    device = get_active_device()
    if not device:
        print("No active adb device found.")
        return
    
    print(f"Capturing screenshot from {device}...")
    
    # Run adb shell screencap -p and read the raw PNG output
    cmd = ['adb', '-s', device, 'shell', 'screencap', '-p']
    result = subprocess.run(cmd, capture_output=True)
    
    if result.returncode != 0:
        print("Failed to capture screenshot:")
        print(result.stderr.decode('utf-8'))
        return
        
    try:
        # Load the image using Pillow
        # adb screencap on Windows can have \r\n issues but on Linux it's fine.
        img = Image.open(BytesIO(result.stdout))
        
        width, height = img.size
        print(f"Original resolution: {width}x{height}")
        
        # Target aspect ratio is 16:9
        target_ratio = 16.0 / 9.0
        current_ratio = width / height
        
        if abs(current_ratio - target_ratio) > 0.01:
            print("Cropping to exactly 16:9 aspect ratio...")
            if current_ratio > target_ratio:
                # Too wide, crop width
                new_width = int(height * target_ratio)
                left = (width - new_width) / 2
                top = 0
                right = (width + new_width) / 2
                bottom = height
            else:
                # Too tall, crop height
                new_height = int(width / target_ratio)
                left = 0
                top = (height - new_height) / 2
                right = width
                bottom = (height + new_height) / 2
                
            img = img.crop((left, top, right, bottom))
            print(f"Cropped resolution: {img.size[0]}x{img.size[1]}")
        else:
            print("Aspect ratio is already 16:9.")
            
        # Ensure sides are within Play Store limits (720px to 7680px)
        w, h = img.size
        if w > 7680 or h > 7680:
            print("Image exceeds 7680px, resizing...")
            img.thumbnail((7680, 7680), Image.Resampling.LANCZOS)
            print(f"Resized resolution: {img.size[0]}x{img.size[1]}")
            
        # Save the image
        out_dir = "fastlane/metadata/android/en-US/images"
        os.makedirs(out_dir, exist_ok=True)
        timestamp = time.strftime("%Y%m%d_%H%M%S")
        out_path = os.path.join(out_dir, f"screenshot_xr_{timestamp}.png")
        
        img.save(out_path, format="PNG")
        print(f"Screenshot saved successfully to {out_path}!")
        
    except Exception as e:
        print(f"Error processing screenshot: {e}")

if __name__ == "__main__":
    capture_screenshot()
