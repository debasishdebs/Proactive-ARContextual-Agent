import json,urllib.request,urllib.parse,os,sys,csv,time,re
S=os.path.dirname(os.path.abspath(__file__))
UA={'User-Agent':'pca-vlm-bakeoff/1.0 (offline model research script)'}
def api(**p):
    p['format']='json'
    u='https://commons.wikimedia.org/w/api.php?'+urllib.parse.urlencode(p)
    for a in range(8):
        try: return json.load(urllib.request.urlopen(urllib.request.Request(u,headers=UA)))
        except Exception as e:
            print('retry',e); time.sleep(20*(a+1))
    raise SystemExit('api fail')
items=[("charminar","place","Charminar","Charminar Hyderabad"),
("golconda","place","Golconda Fort","Golconda Fort Hyderabad"),
("qutb","place","Qutb Shahi Tombs","Qutb Shahi Tombs Hyderabad"),
("chowmahalla","place","Chowmahalla Palace","Chowmahalla Palace Hyderabad"),
("mecca","place","Mecca Masjid","Mecca Masjid Hyderabad"),
("falaknuma","place","Falaknuma Palace","Falaknuma Palace Hyderabad"),
("buddha","place","Hussain Sagar Buddha","Buddha statue Hussain Sagar"),
("ramoji","place","Ramoji Film City","Ramoji Film City"),
("rumtek","place","Rumtek Monastery","Rumtek Monastery Sikkim"),
("batasia","place","Batasia Loop","Batasia Loop Darjeeling"),
("tigerhill","place","Tiger Hill sunrise view","Tiger Hill Darjeeling sunrise"),
("kanch","place","Kanchenjunga","Kanchenjunga mountain"),
("sigiriya","place","Sigiriya","Sigiriya rock fortress"),
("tooth","place","Temple of the Tooth","Temple of the Tooth Kandy"),
("galle","place","Galle Fort","Galle Fort Sri Lanka"),
("ninearch","place","Nine Arch Bridge","Nine Arch Bridge Ella"),
("purifier","object","air purifier","air purifier"),
("ps5","object","PlayStation 5 console (closest to box)","PlayStation 5 and DualSense"),
("hotwheels","object","Hot Wheels toys (closest to race track)","Hot Wheels display Safeway"),
("sofa","object","sofa","sofa living room"),
("tv","object","television","flat screen television"),
("kettle","object","electric kettle","electric kettle"),
("toycar","object","toy car","toy car die-cast"),
("bookshelf","object","bookshelf","bookshelf books")]
skip=set(sys.argv[1:])
out=[]
for key,kind,name,q in items:
    r=api(action='query',generator='search',gsrsearch=q+' filetype:bitmap',gsrnamespace=6,gsrlimit=8,prop='imageinfo',iiprop='url|extmetadata|size|mime',iiurlwidth=800)
    pages=sorted(r.get('query',{}).get('pages',{}).values(),key=lambda p:p['index'])
    cands=[]
    for p in pages:
        ii=p['imageinfo'][0]
        if ii['mime']!='image/jpeg' or ii['width']<ii['height']*0.7: continue
        em=ii['extmetadata']; lic=em.get('LicenseShortName',{}).get('value','')
        if not re.match(r'(CC0|CC BY|CC-BY|Public domain|PD)',lic): continue
        cands.append((p['title'],ii,em,lic))
    print(key,[c[0] for c in cands])
    pick=[c for c in cands if c[0] not in skip]
    if not pick: print("NONE for",key); continue
    t,ii,em,lic=pick[0]
    fn=f"{S}/img/{key}.jpg"
    req=urllib.request.Request(ii['thumburl'],headers=UA)
    for a in range(5):
        try: open(fn,'wb').write(urllib.request.urlopen(req).read());break
        except Exception as e: time.sleep(20*(a+1))
    auth=re.sub('<[^>]+>','',em.get('Artist',{}).get('value',''))[:80]
    out.append([key,kind,name,t,lic,auth,ii['descriptionurl']])
    time.sleep(4)
w=csv.writer(open(S+'/images.csv','w'));w.writerow(['key','kind','true_name','commons_title','licence','author','url']);w.writerows(out)
