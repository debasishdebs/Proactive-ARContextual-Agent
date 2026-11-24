import csv, sys, statistics as st, os
D = os.path.dirname(os.path.abspath(__file__))
def f(x):
    try: return float(x)
    except: return None
for label in sys.argv[1:]:
    R = list(csv.DictReader(open(f'{D}/results_{label}.csv')))
    n = len(R)
    pres = [r for r in R if r['truth'] != 'NONE']; absn = [r for r in R if r['truth'] == 'NONE']
    def stats(pick, conf):
        ok_p = sum(1 for r in pres if r[pick] == r['truth'])
        wrong_p = sum(1 for r in pres if r[pick] not in (r['truth'], 'NONE', '', None))
        miss_p = sum(1 for r in pres if r[pick] == 'NONE')
        ok_a = sum(1 for r in absn if r[pick] == 'NONE')
        fm_a = sum(1 for r in absn if r[pick] not in ('NONE', '', None))
        fail = sum(1 for r in R if r[pick] in ('', None))
        wrong_all = wrong_p + fm_a
        conf_wrong = {t: sum(1 for r in R if r[pick] not in ('', None, 'NONE') and r[pick] != r['truth'] and (f(r[conf]) or 0) >= t) for t in (0.5, 0.7, 0.9)}
        return dict(present_ok=f'{ok_p}/{len(pres)}', present_wrongpick=wrong_p, present_NONE=miss_p, absent_ok=f'{ok_a}/{len(absn)}', absent_falsematch=fm_a, fmt_fail=fail, wrongpick_rate=f'{wrong_all}/{n}', confwrong=conf_wrong)
    lat = [f(r['lat_s']) for r in R]
    print('==', label, 'n', n)
    print('A json  ', stats('choice', 'conf'))
    print('B logprob', stats('B_choice', 'B_p'))
    # latency: raw, and median excluding stalls
    print('latency A s: mean %.1f median %.1f min %.1f max %.1f' % (st.mean(lat), st.median(lat), min(lat), max(lat)))
    print('latency B s: mean %.1f median %.1f' % (st.mean(f(r['B_lat_s']) for r in R), st.median(f(r['B_lat_s']) for r in R)))
    print('prompt_ms median %.0f, prompt_tokens median %d, text_tokens median %d, image_tokens min/median/max %d/%d/%d' % (
        st.median(f(r['prompt_ms']) for r in R), st.median(int(r['prompt_tokens']) for r in R), st.median(int(r['text_tokens']) for r in R),
        min(int(r['image_tokens']) for r in R), st.median(int(r['image_tokens']) for r in R), max(int(r['image_tokens']) for r in R)))
    print('pred_ms median', st.median(f(r['pred_ms']) for r in R), 'max rss', max(f(r['rss_mb']) for r in R))
    # calibration of confidences
    for pick, conf, nm in (('choice', 'conf', 'A'), ('B_choice', 'B_p', 'B')):
        for t in (0.5, 0.7, 0.8, 0.9, 0.95, 0.99):
            acc = [r for r in R if r[pick] not in ('', None) and (f(r[conf]) or 0) >= t]
            if acc:
                good = sum(1 for r in acc if r[pick] == r['truth'])
                print(f'{nm} floor {t}: answers {len(acc)} correct {good} wrong-confident {len(acc)-good}')
