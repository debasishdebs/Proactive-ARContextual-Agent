#!/usr/bin/env python3
"""Bake-off client. usage: bake.py <label> <port> <server_pid>  (server already running)."""
import json, sys, base64, time, random, os, re, csv, subprocess, math, urllib.request
D = os.path.dirname(os.path.abspath(__file__))
label, port, pid = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
SEED = open(os.path.join(D, '../../../android-companion/services/ai/src/main/assets/prompts/seeds/place_match.txt')).read()
# (key, true, kind, lookalike(kind), d1, d2, extra_for_absent)
O = 'object'
T = {
 'charminar': (('Charminar','monument'), ('Gulzar Houz','monument'), ('Golconda Fort','fort'), ('Hussain Sagar Buddha','statue'), ('Birla Mandir','temple')),
 'golconda': (('Golconda Fort','fort'), ('Warangal Fort','fort'), ('Charminar','monument'), ('Salar Jung Museum','museum'), ('Mecca Masjid','mosque')),
 'qutb': (('Qutb Shahi Tombs','tomb complex'), ('Paigah Tombs','tomb complex'), ('Mecca Masjid','mosque'), ('Birla Mandir','temple'), ('Charminar','monument')),
 'chowmahalla': (('Chowmahalla Palace','palace'), ('King Kothi Palace','palace'), ('Salar Jung Museum','museum'), ('Charminar','monument'), ('Golconda Fort','fort')),
 'mecca': (('Mecca Masjid','mosque'), ('Toli Masjid','mosque'), ('Charminar','monument'), ('Golconda Fort','fort'), ('Falaknuma Palace','palace')),
 'falaknuma': (('Falaknuma Palace','palace'), ('Chowmahalla Palace','palace'), ('Taj Krishna','hotel'), ('Salar Jung Museum','museum'), ('Qutb Shahi Tombs','tomb complex')),
 'buddha': (('Hussain Sagar Buddha','statue'), ('Statue of Equality','statue'), ('Birla Mandir','temple'), ('Necklace Road','road'), ('Charminar','monument')),
 'ramoji': (('Ramoji Film City','film studio'), ('Annapurna Studios','film studio'), ('Prasads IMAX','cinema'), ('Wonderla Hyderabad','amusement park'), ('Golconda Fort','fort')),
 'rumtek': (('Rumtek Monastery','monastery'), ('Enchey Monastery','monastery'), ('Pemayangtse Monastery','monastery'), ('Do-Drul Chorten','stupa'), ('Batasia Loop','railway loop')),
 'batasia': (('Batasia Loop','railway loop'), ('Ghum Railway Station','railway station'), ('Peace Pagoda Darjeeling','pagoda'), ('Darjeeling Zoo','zoo'), ('Rumtek Monastery','monastery')),
 'tigerhill': (('Tiger Hill','viewpoint'), ('Observatory Hill Darjeeling','hill'), ('Happy Valley Tea Estate','tea estate'), ('Tsomgo Lake','lake'), ('Rumtek Monastery','monastery')),
 'kanch': (('Kanchenjunga','mountain'), ('Mount Pandim','mountain'), ('Mount Siniolchu','mountain'), ('Tsomgo Lake','lake'), ('Batasia Loop','railway loop')),
 'sigiriya': (('Sigiriya','rock fortress'), ('Pidurangala Rock','rock'), ('Dambulla Cave Temple','cave temple'), ('Yapahuwa','rock fortress'), ('Galle Fort','fort')),
 'tooth': (('Temple of the Tooth','temple'), ('Gangaramaya Temple','temple'), ('Dambulla Cave Temple','cave temple'), ('Kelaniya Raja Maha Vihara','temple'), ('Sigiriya','rock fortress')),
 'galle': (('Galle Fort','fort'), ('Matara Star Fort','fort'), ('Jaffna Fort','fort'), ('Colombo Fort','district'), ('Nine Arch Bridge','bridge')),
 'ninearch': (('Nine Arch Bridge','bridge'), ('Demodara Loop','railway loop'), ('Pamban Bridge','bridge'), ('Ella Rock','hill'), ('Temple of the Tooth','temple')),
 'purifier': (('Air purifier','appliance'), ('Desk fan','appliance'), ('Air fryer','appliance'), ('Smart TV','appliance'), ('Kettle','appliance')),
 'ps5': (('PlayStation 5','game console'), ('Xbox Series X','game console'), ('Nintendo Switch','game console'), ('Soundbar','appliance'), ('Sofa','furniture')),
 'hotwheels': (('Hot Wheels toy cars','toy'), ('LEGO Technic set','toy'), ('Nerf blaster','toy'), ('Barbie Dreamhouse','toy'), ('Bookshelf','furniture')),
 'sofa': (('Sofa','furniture'), ('Armchair','furniture'), ('Dining table','furniture'), ('Double bed','furniture'), ('Bookshelf','furniture')),
 'tv': (('Television','appliance'), ('Computer monitor','appliance'), ('Microwave oven','appliance'), ('Soundbar','appliance'), ('Kettle','appliance')),
 'kettle': (('Electric kettle','appliance'), ('Coffee machine','appliance'), ('Toaster','appliance'), ('Rice cooker','appliance'), ('Air purifier','appliance')),
 'toycar': (('Toy car','toy'), ('Toy train','toy'), ('Toy truck','toy'), ('Hot Wheels race track','toy'), ('Sofa','furniture')),
 'bookshelf': (('Bookshelf','furniture'), ('Wardrobe','furniture'), ('TV unit','furniture'), ('Shoe rack','furniture'), ('Sofa','furniture')),
}
ABSENT = {'golconda','mecca','ramoji','kanch','galle','purifier','sofa','toycar'}
GRAMMAR = r'''root ::= "{\"choice\": \"" ("1" | "2" | "3" | "4" | "NONE") "\", \"confidence\": " ("1" | "0" | "0." [0-9] [0-9]?) "}"'''
rnd = random.Random(7)

def build(key):
    t = T[key]
    if key in ABSENT:
        cands = [t[1], t[2], t[3], t[4]]; truth = 'NONE'
    else:
        cands = [t[0], t[1], t[2], t[3]]; truth = None
    rnd.shuffle(cands)
    if truth is None: truth = str(cands.index(t[0]) + 1)
    lines = [f"{i+1}. {n} | {k}" for i, (n, k) in enumerate(cands)]
    block = "BEGIN_DATA candidates\n" + "\n".join("- " + l for l in lines) + "\nEND_DATA candidates"
    return block, truth, [n for n, _ in cands]

def post(body, timeout=900):
    r = urllib.request.Request(f'http://127.0.0.1:{port}/v1/chat/completions', json.dumps(body).encode(), {'Content-Type': 'application/json'})
    t0 = time.time(); j = json.load(urllib.request.urlopen(r, timeout=timeout)); return j, time.time() - t0

def rss():
    try: return int(subprocess.check_output(['ps', '-o', 'rss=', '-p', str(pid)]).strip()) / 1024
    except Exception: return 0

def parse(txt):
    m = re.search(r'"choice"\s*:\s*"?(NONE|[1-9])"?', txt or '')
    c = re.search(r'"confidence"\s*:\s*([0-9.]+)', txt or '')
    return (m.group(1) if m else None), (float(c.group(1)) if c else None)

rows = []; peak = 0
OUT = f'{D}/results_{label}.csv'
def flush():
    w = csv.DictWriter(open(OUT, 'w'), fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
ORDER=['charminar','purifier','qutb','sofa','golconda','ps5','rumtek','galle','tv','sigiriya','kanch','kettle','mecca','hotwheels','ninearch','toycar','chowmahalla','bookshelf','tooth','ramoji','batasia','tigerhill','falaknuma','buddha']
TXT=None
for key in ORDER:
    block, truth, names = build(key)
    prompt = SEED.replace('{{recent_context}}', block)
    b64 = base64.b64encode(open(f'{D}/img/{key}.jpg', 'rb').read()).decode()
    img = {'type': 'image_url', 'image_url': {'url': 'data:image/jpeg;base64,' + b64}}
    # A: PLACE_MATCH as shipped (JSON), grammar-constrained, temp 0
    msgs = [{'role': 'user', 'content': [img, {'type': 'text', 'text': prompt}]}]
    jA, tA = post({'messages': msgs, 'temperature': 0, 'max_tokens': 40, 'grammar': GRAMMAR, 'cache_prompt': False})
    peak = max(peak, rss())
    txt = jA['choices'][0]['message']['content']; ch, conf = parse(txt)
    tm = jA.get('timings', {})
    # text-only token count (same prompt, no image) -> image tokens
    if TXT is None:
        jT, _ = post({'messages': [{'role': 'user', 'content': prompt}], 'temperature': 0, 'max_tokens': 1, 'cache_prompt': False}); TXT = jT['usage']['prompt_tokens']
    ptoks = jA['usage']['prompt_tokens']; ttoks = TXT
    # B: single-token scoring (no grammar), logprobs over first token
    promptB = prompt.split('Reply only with JSON')[0] + 'Reply with only the number from the list, or NONE.\n' + block
    jB, tB = post({'messages': [{'role': 'user', 'content': [img, {'type': 'text', 'text': promptB}]}], 'temperature': 0, 'max_tokens': 1, 'logprobs': True, 'top_logprobs': 12, 'cache_prompt': False})
    tops = jB['choices'][0]['logprobs']['content'][0]['top_logprobs']
    agg = {}
    for e in tops:
        s = e['token'].strip().upper(); k = 'NONE' if s.startswith('N') else (s[:1] if s[:1] in '1234' and s else None)
        if k: agg[k] = agg.get(k, 0) + math.exp(e['logprob'])
    tot = sum(agg.values()) or 1; pb = {k: v / tot for k, v in agg.items()}
    bch = max(pb, key=pb.get) if pb else None; bconf = pb.get(bch, 0) if bch else None
    peak = max(peak, rss())
    rows.append(dict(model=label, key=key, truth=truth, candidates=' / '.join(f'{i+1}={n}' for i, n in enumerate(names)), raw=txt.strip(), choice=ch, conf=conf,
                     lat_s=round(tA, 2), prompt_ms=round(tm.get('prompt_ms', 0)), pred_ms=round(tm.get('predicted_ms', 0)), prompt_tokens=ptoks, text_tokens=ttoks,
                     image_tokens=ptoks - ttoks, B_choice=bch, B_p=round(bconf, 3) if bconf else None, B_lat_s=round(tB, 2), B_raw=jB['choices'][0]['message']['content'], rss_mb=round(rss())))
    flush()
    print(label, key, 'truth', truth, 'A', ch, conf, f'{tA:.1f}s', 'B', bch, rows[-1]['B_p'], f'{tB:.1f}s', flush=True)
out = f'{D}/results_{label}.csv'
w = csv.DictWriter(open(out, 'w'), fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
print('peak_rss_mb', round(peak))
open(f'{D}/peak_{label}.txt', 'w').write(str(round(peak)))
