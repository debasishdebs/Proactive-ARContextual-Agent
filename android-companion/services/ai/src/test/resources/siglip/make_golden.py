import json, sys
from transformers import AutoTokenizer
import sentencepiece as spm
import transformers
print("transformers", transformers.__version__, "sp", spm.__version__, file=sys.stderr)
D = "."
strings = [
 "ps5 box","air purifier","hot wheels track","my desk","MI air purifier","PS5 box","Hot Wheels race track",
 "Charminar","Ramoji Film City","Golconda Fort","Tiger Hill, Darjeeling","Rumtek Monastery","Sigiriya",
 "Temple of the Tooth, Kandy","Galle Fort","चारमीनार","गोलकोंडा किला","చార్మినార్","రామోజీ ఫిల్మ్ సిటీ",
 "గోల్కొండ కోట","සීගිරිය","දළදා මාළිගාව","ගාල්ල කොටුව","দার্জিলিং","দার্জিলিং-এর টাইগার হিল","दार्जिलिङ",
 "","Gate 22B","a  b   c"," leading and trailing "," \ttab\nnewline ","😀","coffee ☕ time 🚀","<mask>","hello <start_of_turn> world",
 "x"*200, ("This is a very long description of a place name that goes on and on " * 12).strip(),
 "Charminar चारमीनार","Café Niloufer","This is a photo of charminar.","This is a photo of Charminar.",
 "Hello, World! (test) [brackets] {braces} \"quotes\" 'single' a/b\\c #hash @at $dollar 100% ~tilde^ & | = + - _ *",
 "12345 6789 3.14159 1,000,000","ÀÉÎÕÜ ß İstanbul ǅ","日本語のテキスト 中文文本 한국어","Ünïcödé NAÏVE ÇA","a\u0000b","IKEA Billy bookcase 80x28x202 cm",
 "ab","The Quick Brown Fox Jumps Over The Lazy Dog","   ","\n","  double  space  inside  ","foo<unk>bar","<eos>","<pad>","<bos>x",
 "mi air purifier 4 pro","ΑΒΓ αβγ ΣΑΣ","İ","ǅ","ﬁ ligature ① ½ ㎏", "Ａｂｃ fullwidth",
 "​ zero width   nbsp","ᏣᎳᎩ","𐍈 astral 𝒳",
 " "*40+"end","a\t\t\tb","line1\n\n\nline2","\t"*40,"<0x41> <0xFF>","price: €5 ¥300 £2","Rokid Glasses (2nd gen)",
 "tiger hill, darjeeling — sunrise view","ǆ ǈ ǋ ᾈ","x"*70,"ab "*40,"गोलकोंडा किला और चारमीनार, हैदराबाद का प्रसिद्ध स्थल","<mask><mask>","a<mask>b <unused5> c",
]
slow = AutoTokenizer.from_pretrained(D, use_fast=False)
fast = AutoTokenizer.from_pretrained(D, use_fast=True)
sp = spm.SentencePieceProcessor(model_file=D + "/tokenizer.model")
print(type(slow).__name__, type(fast).__name__, file=sys.stderr)
out = []
bad = 0
kw = dict(padding="max_length", max_length=64, truncation=True)
for s in strings:
    low = s.lower()
    a = slow(low, **kw)["input_ids"]
    b = fast(low, **kw)["input_ids"]
    a0 = slow(s, **kw)["input_ids"]
    spids = (sp.encode(low) + [1])[:64]
    spids += [0] * (64 - len(spids))
    flag = []
    if a != b: flag.append("slow!=fast")
    if a != spids: flag.append("hf!=sp")
    if a0 != a: flag.append("case-sensitive-hf")
    if flag:
        bad += 1
        print(repr(s), flag, file=sys.stderr)
    out.append({"text": s, "lower": low, "ids": a, "fast": b, "sp": spids, "hf_lowercases_itself": a0 == a})
gold=[{"text":e["text"],"ids":e["ids"]} for e in out]
json.dump(gold, open("golden_min.json","w"), ensure_ascii=False, separators=(",",":"))
json.dump(out, open("golden_full.json", "w"), ensure_ascii=False)
print(len(out), "strings; flagged", bad, file=sys.stderr)
