"""Golden data for SigLipImageEmbedder (milestone V). Usage (venv with transformers, pillow, numpy; onnxruntime optional):
  python make_vision_golden.py <dir with preprocessor_config.json> [vision_model_quantized.onnx] > siglip2_vision_golden.json
For each synthetic source image: the HF SiglipImageProcessor (PIL path, resample=2 bilinear) tensor, stored LOSSLESSLY as the
224x224x3 uint8 grid it came from (tensor = (u8/255 - 0.5)/0.5 exactly; asserted here), gzip+base64. If the ONNX file is given, also
the reference embeddings: python onnxruntime on the HF tensors, output `pooler_output`, L2-normalised."""
import sys, json, gzip, base64
import numpy as np
from PIL import Image
from transformers import SiglipImageProcessor

D = sys.argv[1]
onnx_path = sys.argv[2] if len(sys.argv) > 2 else None
proc = SiglipImageProcessor.from_pretrained(D)
rng = np.random.RandomState(7)


def solid(w, h, rgb):
    return np.tile(np.array(rgb, np.uint8), (h, w, 1))


def gradient(w, h):
    x = np.linspace(0, 255, w)[None, :].repeat(h, 0)
    y = np.linspace(0, 255, h)[:, None].repeat(w, 1)
    return np.stack([x, y, (x + y) / 2], -1).round().astype(np.uint8)


def checker(w, h, s):
    yy, xx = np.mgrid[0:h, 0:w]
    m = (((xx // s) + (yy // s)) % 2).astype(np.uint8) * 255
    return np.stack([m, m, 255 - m], -1)


def stripes(w, h):
    a = np.zeros((h, w, 3), np.uint8)
    a[:, ::2] = 255
    a[h // 2:, :, 1] = 255 - a[h // 2:, :, 1]
    return a


cases = {
    "solid_red_32x32": solid(32, 32, (255, 0, 0)),
    "solid_grey_50x40": solid(50, 40, (128, 128, 128)),
    "gradient_96x64": gradient(96, 64),
    "nonsquare_64x48": gradient(64, 48)[:, ::-1].copy(),
    "checker_high_contrast_300x200": checker(300, 200, 3),
    "stripes_1px_128x96": stripes(128, 96),
    "tiny_upscale_8x8": rng.randint(0, 256, (8, 8, 3)).astype(np.uint8),
    "noise_tall_40x120": rng.randint(0, 256, (120, 40, 3)).astype(np.uint8),
}


def b64gz(a):
    return base64.b64encode(gzip.compress(a.tobytes(), 9, mtime=0)).decode()


sess = None
if onnx_path:
    import onnxruntime as ort
    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
out = {"cases": []}
for name, arr in cases.items():
    h, w, _ = arr.shape
    t = proc(images=Image.fromarray(arr), return_tensors="np")["pixel_values"][0].astype(np.float32)  # 3,224,224
    assert t.shape == (3, 224, 224), t.shape
    u8 = np.rint((t * 0.5 + 0.5) * 255).astype(np.uint8)
    back = (u8.astype(np.float32) / 255 - 0.5) / 0.5
    assert np.abs(back - t).max() < 1e-5, np.abs(back - t).max()  # lossless: the tensor IS a uint8 grid
    pil = np.asarray(Image.fromarray(arr).resize((224, 224), Image.BILINEAR))  # cross-check against bare PIL
    print(name, "HF vs bare PIL max diff", int(np.abs(pil.transpose(2, 0, 1).astype(int) - u8).max()), file=sys.stderr)
    c = {"name": name, "w": w, "h": h, "src_rgb_gz_b64": b64gz(arr), "tensor_u8_chw_gz_b64": b64gz(u8)}
    if sess:
        e = sess.run(["pooler_output"], {"pixel_values": t[None]})[0][0].astype(np.float64)
        c["embedding_l2"] = [float(x) for x in (e / np.linalg.norm(e))]
    out["cases"].append(c)
print(json.dumps(out, separators=(",", ":")))
