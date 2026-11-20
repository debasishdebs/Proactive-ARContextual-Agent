"""Golden data for SigLipTextEmbedder (milestone X). Usage (venv with transformers, numpy, onnxruntime):
  python make_text_golden.py <dir with tokenizer.json etc> <text_model_quantized.onnx> <vision_golden.json> <logit_scale.bin> <logit_bias.bin> > siglip2_text_golden.json
Per string: HF token ids (lowercased, padding=max_length 64, truncation) and the python-onnxruntime reference embedding (`pooler_output`, ONE text per run, L2).
Also: how far a batch of 8 drifts from the same texts one at a time (int8 batch dependence), and the cross-modal reference: image-text cosine and the SigLIP logits
(exp(logit_scale) * cos + logit_bias, the HF SiglipModel formula) for the 8 synthetic vision-golden images against 8 captions. NOTE: the reference towers are the SAME int8 ONNX files
(PyTorch/HF SiglipModel weights were not available offline), so this checks the Kotlin pipeline, not int8 vs fp32."""
import sys, json, struct
import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

D, text_onnx, vision_golden, scale_f, bias_f = sys.argv[1:6]
tok = AutoTokenizer.from_pretrained(D, use_fast=True)
sess = ort.InferenceSession(text_onnx, providers=["CPUExecutionProvider"])
STR = [
    "MI air purifier", "PS5 box", "Hot Wheels race track", "air purifier", "my desk", "Charminar", "Ramoji Film City", "Golconda Fort",
    "Tiger Hill, Darjeeling", "Rumtek Monastery", "Sigiriya", "Temple of the Tooth, Kandy", "Galle Fort", "This is a photo of Charminar.",
    "चारमीनार", "गोलकोंडा किला", "చార్మినార్", "రామోజీ ఫిల్మ్ సిటీ", "గోల్కొండ కోట", "සීගිරිය", "දළදා මාළිගාව", "ගාල්ල කොටුව", "দার্জিলিং", "দার্জিলিং-এর টাইগার হিল",
    "", "Gate 22B", "IKEA Billy bookcase 80x28x202 cm", "Café Niloufer", "😀", "Rokid Glasses (2nd gen)",
    "x" * 200, ("This is a very long description of a place name that goes on and on " * 12).strip(), "ab " * 40,
    "गोलकोंडा किला और चारमीनार, हैदराबाद का प्रसिद्ध स्थल",
]


def ids_of(s):
    return tok(s.lower(), padding="max_length", max_length=64, truncation=True)["input_ids"]


def run(id_rows):
    return sess.run(["pooler_output"], {"input_ids": np.array(id_rows, np.int64)})[0].astype(np.float64)


def l2(a):
    return a / np.linalg.norm(a, axis=-1, keepdims=True)


rows = [ids_of(s) for s in STR]
single = l2(np.concatenate([run([r]) for r in rows]))
# batch dependence: groups of 8 vs single
b8 = np.concatenate([l2(run(rows[i:i + 8])) for i in range(0, len(rows), 8)])
cos_b8 = (b8 * single).sum(-1)
big = l2(run(rows))
cos_all = (big * single).sum(-1)
print("batch8 vs single cos: min %.5f mean %.5f | batch-all(%d) vs single: min %.5f mean %.5f" % (cos_b8.min(), cos_b8.mean(), len(rows), cos_all.min(), cos_all.mean()), file=sys.stderr)

CAPS = ["a solid red square", "a plain grey picture", "a smooth colour gradient", "a mirrored colour gradient", "a black and white checkerboard",
        "thin vertical stripes", "a tiny blurry picture of random noise", "a tall picture of random noise"]
caps = l2(np.concatenate([run([ids_of(c)]) for c in CAPS]))
vg = json.load(open(vision_golden))["cases"]
img = np.array([c["embedding_l2"] for c in vg], np.float64)
scale = struct.unpack("<f", open(scale_f, "rb").read())[0]
bias = struct.unpack("<f", open(bias_f, "rb").read())[0]
cos = img @ caps.T
logits = np.exp(scale) * cos + bias
print("image order:", [c["name"] for c in vg], file=sys.stderr)
print("argmax caption per image:", logits.argmax(1).tolist(), "argmax image per caption:", logits.argmax(0).tolist(), file=sys.stderr)

out = {
    "texts": [{"text": s, "ids": r, "embedding_l2": [float(x) for x in e]} for s, r, e in zip(STR, rows, single)],
    "batch8_vs_single_cos": [float(x) for x in cos_b8],
    "batchAll_vs_single_cos": [float(x) for x in cos_all],
    "captions": [{"text": c, "embedding_l2": [float(x) for x in e]} for c, e in zip(CAPS, caps)],
    "image_names": [c["name"] for c in vg],
    "logit_scale": scale, "logit_bias": bias,
    "logits": [[float(x) for x in r] for r in logits],
}
print(json.dumps(out, ensure_ascii=False, separators=(",", ":")))
