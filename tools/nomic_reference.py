#!/usr/bin/env python3
"""Writes the Nomic Embed v1.5 reference data used by the Android parity tests.

Output (under android-companion/services/ai/src/test/resources/nomic/):
  img0..img4.jpg   synthetic rectangular test images (640x480 / 480x640), generated here, no user photos
  reference.json   10 prefixed texts with their raw 768-dim mean-pooled outputs (before LayerNorm / shortening / L2),
                   and for each image the raw 768-dim CLS output plus the 3x224x224 pixel tensor produced by the official
                   processor (float16, little-endian, base64, CHW) for the Android preprocessing parity test.

Run (CPU, batch size 1, nothing else heavy running):
  python -m venv venv && venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cpu
  venv/bin/pip install "transformers<5" "sentence-transformers<4" einops pillow numpy
  (used: transformers 4.57.6, sentence-transformers 3.4.1, torch 2.14.1; transformers 5 breaks the nomic-bert remote code)
  nice -n 10 venv/bin/python tools/nomic_reference.py
Needs network for the public Hugging Face repos (no token). Models are pinned to the same commits as LocalModels.kt.
"""
import base64
import json
import pathlib
import random

import numpy as np
import torch
from PIL import Image, ImageDraw
from sentence_transformers import SentenceTransformer
from transformers import AutoImageProcessor, AutoModel

# Text: PyTorch repo whose weights the GGUF (nomic-ai/nomic-embed-text-v1.5-GGUF @ 0188c9bf) is converted from.
TEXT_REPO, TEXT_SHA = "nomic-ai/nomic-embed-text-v1.5", "e9b6763023c676ca8431644204f50c2b100d9aab"
VISION_REPO, VISION_SHA = "nomic-ai/nomic-embed-vision-v1.5", "e3a725bce72db07ca4adb1d83da08903f3ee02f8"

OUT = pathlib.Path(__file__).resolve().parent.parent / "android-companion/services/ai/src/test/resources/nomic"

TEXTS = [
    "search_document: The quarterly budget review is scheduled for Thursday at 10am in the Dublin office.",
    "search_document: Recipe notes: simmer the tomato sauce for forty minutes, then add fresh basil and a pinch of salt.",
    "search_document: Parking level 3, row C, next to the elevator. Ticket is in the blue jacket.",
    "search_document: Alice mentioned the new onboarding flow needs a second round of usability testing before launch.",
    "search_document: The red bicycle was locked to the railing outside the library on Mill Street.",
    "search_query: when is the budget review",
    "search_query: where did I park the car",
    "search_query: what did Alice say about onboarding",
    "search_query: tomato sauce recipe",
    "search_query: where is my bike",
]


def make_images():
    rnd = random.Random(7)
    specs = [(640, 480), (480, 640), (640, 480), (480, 640), (640, 480)]
    imgs = []
    for i, (w, h) in enumerate(specs):
        img = Image.new("RGB", (w, h))
        px = img.load()
        for y in range(h):  # diagonal colour gradient, different phase per image
            for x in range(w):
                px[x, y] = ((x * 255 // w + i * 40) % 256, (y * 255 // h + i * 25) % 256, ((x + y) * 255 // (w + h) + i * 60) % 256)
        d = ImageDraw.Draw(img)
        for _ in range(8):  # off-centre shapes so a centre crop vs. a squash would differ
            x0, y0 = rnd.randrange(0, w - 80), rnd.randrange(0, h - 80)
            x1, y1 = x0 + rnd.randrange(40, 160), y0 + rnd.randrange(40, 160)
            col = tuple(rnd.randrange(256) for _ in range(3))
            (d.ellipse if rnd.random() < 0.5 else d.rectangle)([x0, y0, x1, y1], fill=col)
        path = OUT / f"img{i}.jpg"
        img.save(path, quality=90)
        imgs.append(path)
    return imgs


def b16(a):
    return base64.b64encode(np.asarray(a, dtype="<f2").tobytes()).decode()


def main():
    torch.set_num_threads(2)
    OUT.mkdir(parents=True, exist_ok=True)
    text_model = SentenceTransformer(TEXT_REPO, revision=TEXT_SHA, trust_remote_code=True, device="cpu")
    texts = []
    with torch.no_grad():
        for t in TEXTS:  # batch size 1
            v = text_model.encode([t], batch_size=1, normalize_embeddings=False, convert_to_numpy=True)[0]
            assert v.shape == (768,)
            texts.append({"text": t, "raw768": [float(x) for x in v]})
    del text_model

    proc = AutoImageProcessor.from_pretrained(VISION_REPO, revision=VISION_SHA)
    vision = AutoModel.from_pretrained(VISION_REPO, revision=VISION_SHA, trust_remote_code=True).eval()
    images = []
    with torch.no_grad():
        for path in make_images():
            im = Image.open(path).convert("RGB")
            pix = proc(im, return_tensors="pt")["pixel_values"]  # (1,3,224,224)
            cls = vision(pixel_values=pix).last_hidden_state[:, 0][0]
            assert cls.shape == (768,)
            images.append({
                "file": path.name, "width": im.width, "height": im.height,
                "pixels_f16_b64": b16(pix[0].numpy()), "pixels_shape": [3, 224, 224],
                "raw768": [float(x) for x in cls],
            })
    ref = {
        "text_model": {"repo": TEXT_REPO, "commit": TEXT_SHA, "output": "mean-pooled 768 before LayerNorm/shorten/L2"},
        "vision_model": {"repo": VISION_REPO, "commit": VISION_SHA, "output": "CLS 768 before L2/shorten"},
        "texts": texts, "images": images,
    }
    (OUT / "reference.json").write_text(json.dumps(ref))
    print("texts", len(texts), "images", len(images))


if __name__ == "__main__":
    main()
