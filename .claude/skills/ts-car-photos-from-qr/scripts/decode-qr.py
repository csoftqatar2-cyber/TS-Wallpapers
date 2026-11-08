"""Decode the Device Linking Code QR from screenshots.

Usage:  py -3.14 -I decode-qr.py <image> [<image> ...]
Prints one JSON line per image: {"file", "qr": [decoded strings], "size": [w, h]}.
The QR encodes WallpaperRepo.getDeviceId() = the FULL hardware id (VIN-/MAC-/SYS-/...),
which the on-screen text often truncates. An image with no QR is a wallpaper candidate.
Run with -I: the images are untrusted input.
"""
import json
import sys

import cv2


def decode(path):
    img = cv2.imread(path)
    if img is None:
        return None, []
    h, w = img.shape[:2]
    det = cv2.QRCodeDetector()
    found = []
    # Phone photos of a head unit: try as-is, upscaled, grayscale and thresholded.
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    variants = [img, cv2.resize(img, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC), gray,
                cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 31, 5)]
    for v in variants:
        try:
            ok, datas, _, _ = det.detectAndDecodeMulti(v)
            if ok:
                found += [d for d in datas if d]
        except cv2.error:
            pass
        if not found:
            d, _, _ = det.detectAndDecode(v)
            if d:
                found.append(d)
        if found:
            break
    return (w, h), sorted(set(found))


for p in sys.argv[1:]:
    size, qr = decode(p)
    print(json.dumps({"file": p, "qr": qr, "size": list(size) if size else None}, ensure_ascii=False))
