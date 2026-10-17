#!/usr/bin/env python3
"""Generate approximate previews for OPPO NFC animation study.
NOT the original OPPO renderer. All outputs are approximations for study only.
"""
import json, math, os, csv
from PIL import Image, ImageDraw, ImageFont, ImageFilter

BASE = os.path.dirname(os.path.abspath(__file__))
OPPO = os.path.dirname(BASE)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONTB = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONTM = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"

def font(size, bold=False, mono=False):
    path = FONTM if mono else (FONTB if bold else FONT)
    return ImageFont.truetype(path, size)

def lerp(a,b,t): return a+(b-a)*t
def clamp(x,a=0,b=1): return max(a,min(b,x))
def smooth(t): t=clamp(t); return t*t*(3-2*t)
def ease_out(t): t=clamp(t); return 1-(1-t)**2
def keyframe(keys, t):
    if not keys: return 0
    if t <= keys[0][0]: return keys[0][1]
    for (t0,v0),(t1,v1) in zip(keys,keys[1:]):
        if t <= t1:
            u = 0 if t1==t0 else (t-t0)/(t1-t0)
            return lerp(v0,v1,smooth(u))
    return keys[-1][1]

# ---------- contact sheet ----------
def contact_sheet():
    items = [
        ("2130734260", "EasyLight texture\n1080x1080 PNG (colormap)\ncard top glow, LightOS Easy mode"),
        ("2130734263", "EasyWave texture\n1080x1080 PNG (colormap)\nwave ring, LightOS Easy mode"),
        ("2130743833", "Shadow texture\n360x472 PNG RGBA\ncard drop shadow"),
    ]
    cell_w, cell_h = 430, 560
    img = Image.new("RGB", (cell_w*3, cell_h+70), (18,18,22))
    d = ImageDraw.Draw(img)
    d.text((20,18), "CardRotate0424.coz / blend.coz textures (extracted from APK assets)", font=font(24,True), fill=(238,238,245))
    for i,(name,desc) in enumerate(items):
        p = os.path.join(OPPO,"CardRotate0424",name)
        im = Image.open(p).convert("RGBA")
        im.thumbnail((390,390))
        # checkerboard
        sq=20
        bg=Image.new("RGBA",(400,400),(38,38,45,255))
        bd=ImageDraw.Draw(bg)
        for y in range(0,400,sq):
            for x in range(0,400,sq):
                if (x//sq+y//sq)%2==0: bd.rectangle([x,y,x+sq-1,y+sq-1],fill=(52,52,60,255))
        bg.alpha_composite(im, ((400-im.width)//2,(400-im.height)//2))
        x0=i*cell_w+20
        img.paste(bg.convert("RGB"),(x0,70))
        d.text((x0,480),name,font=font(22,True),fill=(255,255,255))
        d.multiline_text((x0,512),desc,font=font(16),fill=(190,190,200),spacing=4)
    img.save(os.path.join(BASE,"textures_contact_sheet.png"))

# ---------- timeline charts ----------
PALETTE=[(255,99,132),(54,162,235),(255,206,86),(75,192,192),(153,102,255),(255,159,64),(120,220,120),(230,120,230),(160,160,255),(255,255,255)]
def nice(v):
    if abs(v)>=100: return f"{v:.0f}"
    if abs(v)>=10: return f"{v:.1f}"
    return f"{v:.2f}"

def plot_animator(a, path, w=1500, h_per=220):
    lines=[ln for ln in a['animLines'] if ln['type'] in ('float','Vec2','Vec3','Vec4','Vec')]
    # choose lines: all float; for Vec take x/y
    series=[]
    for ln in lines:
        for axis, arr in ln['animKeys'].items():
            if axis=='value':
                keys=[(float(k['time']),float(k['value'])) for k in arr]
                series.append((ln['key'],keys))
            elif axis in ('x','y','z','w'):
                keys=[(float(k['time']),float(k['value'])) for k in arr]
                series.append((ln['key']+'.'+axis,keys))
    # limit to 10 most interesting: prefer non-plugin, non-particle
    pref=[s for s in series if not s[0].startswith('plugin.') and not s[0].startswith('Particle') and s[0] not in ('viewPositionOffset.x','viewPositionOffset.y','viewPositionOffset.z','emitterInfo.x','emitterInfo.y','emitterInfo.z','emitterInfo.w')]
    if len(pref)>=4: series=pref[:10]
    else: series=series[:10]
    dur=max([ln['lastTime'] for ln in a['animLines']]+[a['eventLine']['lastTime'],0.001])
    margin_l,margin_r,margin_t,margin_b=240,40,58,50
    W=w; H=max(200,h_per+margin_t+margin_b)
    img=Image.new("RGB",(W,H),(22,22,28)); d=ImageDraw.Draw(img)
    d.text((18,14),f"{a['name']}  (duration {dur:.3f}s)",font=font(24,True),fill=(245,245,250))
    d.text((W-500,20),"approx. linear visualisation of baked keyframes",font=font(15),fill=(150,150,165))
    x0,x1=margin_l,W-margin_r; y0,y1=margin_t,H-margin_b
    d.rectangle([x0,y0,x1,y1],outline=(70,70,85))
    # normalize each line to its own min/max; note in label
    for idx,(name,keys) in enumerate(series):
        if not keys: continue
        lo=min(v for _,v in keys); hi=max(v for _,v in keys)
        if hi==lo: hi=lo+1
        col=PALETTE[idx%len(PALETTE)]
        pts=[]
        for k in range(0,241):
            tt=dur*k/240
            vv=keyframe(keys,tt)
            px=x0+(x1-x0)*(tt/dur); py=y1-(y1-y0)*((vv-lo)/(hi-lo))
            pts.append((px,py))
        d.line(pts,fill=col,width=3)
        for t,v in keys:
            px=x0+(x1-x0)*(t/dur); py=y1-(y1-y0)*((v-lo)/(hi-lo))
            r=4; d.ellipse([px-r,py-r,px+r,py+r],fill=col)
        label=f"{name}  [{nice(lo)}..{nice(hi)}]"
        d.text((10,y0+idx*18),label[:44],font=font(13,mono=True),fill=col)
    for k in range(0,5):
        tt=dur*k/4; px=x0+(x1-x0)*k/4
        d.line([(px,y1),(px,y1+5)],fill=(100,100,115)); d.text((px-14,y1+8),f"{tt:.2f}s",font=font(12),fill=(170,170,180))
    d.text((10,y1+22),"time",font=font(13),fill=(170,170,180))
    img.save(path)

def timelines():
    d=json.load(open(os.path.join(OPPO,"CardRotate0424","scene.json")))
    want=['Admission','Card appear','Card send','Card receive','Card respire','Card vanish','respire','All light disappears','Top light disappears','Edge light disappears']
    panels=[]
    for a in d['animators']:
        if a['name'] in want:
            p=os.path.join(BASE,"timelines",a['name'].replace(' ','_')+".png")
            plot_animator(a,p)
            panels.append(p)
    # combined sheet
    ims=[Image.open(p) for p in panels[:8]]
    cw=max(i.width for i in ims); ch=sum(i.height for i in ims[:8])
    sheet=Image.new("RGB",(cw,ch),(18,18,22)); y=0
    for im in ims:
        sheet.paste(im,(0,y)); y+=im.height
    sheet.save(os.path.join(BASE,"keyframe_timelines.png"))
    # csv
    with open(os.path.join(BASE,"keyframes.csv"),"w",newline="") as f:
        w=csv.writer(f); w.writerow(["animator","duration_s","key","axis","time_s","value","interpolation","bezier"])
        for a in d['animators']:
            dur=max([l['lastTime'] for l in a['animLines']]+[a['eventLine']['lastTime']])
            for ln in a['animLines']:
                for axis,arr in ln['animKeys'].items():
                    if axis in ('x','y','z','w'):
                        for k in arr:
                            w.writerow([a['name'],f"{dur:.3f}",ln['key'],axis,k.get('time',0),k.get('value'),k.get('ipol'),k.get('bezier')])
                    elif axis=='value':
                        for k in arr:
                            w.writerow([a['name'],f"{dur:.3f}",ln['key'],'',k.get('time',0),k.get('value'),k.get('ipol'),k.get('bezier')])

# ---------- approximate water + edge light animation ----------
def rounded_mask(size, box, radius):
    m=Image.new("L",size,0); d=ImageDraw.Draw(m)
    d.rounded_rectangle(box,radius=radius,fill=255)
    return m

def make_background(W,H):
    img=Image.new("RGB",(W,H),(10,13,22)); d=ImageDraw.Draw(img)
    # soft vertical gradient
    for y in range(H):
        t=y/H
        c=(int(12+18*t),int(16+22*t),int(30+35*t))
        d.line([(0,y),(W,y)],fill=c)
    # fake app screen: rounded panel + icon grid
    panel=(int(W*0.06),int(H*0.10),int(W*0.94),int(H*0.90))
    d.rounded_rectangle(panel,radius=26,fill=(24,28,42),outline=(52,58,78),width=2)
    for r in range(4):
        for c in range(4):
            x=int(W*0.13+c*W*0.20); y=int(H*0.18+r*H*0.13)
            col=(60+c*22,70+r*18,110+c*10)
            d.rounded_rectangle([x,y,x+int(W*0.10),y+int(W*0.10)],radius=10,fill=col)
    d.text((int(W*0.09),int(H*0.93)),"synthetic screen (approximation)",font=font(max(10,int(W*0.025))),fill=(120,125,140))
    return img

def water_overlay(W,H,phase):
    """Stylised ripple rings. Approximation of water_0812.coz animator1."""
    amp=keyframe([(0,0),(0.5,260),(1,0)],phase)
    ripples=keyframe([(0,12),(0.5,14),(1,23.35)],phase)
    radius=keyframe([(0,0),(1,360)],phase)
    minr=keyframe([(0,0),(1,10)],phase)
    fade=keyframe([(0,0),(0.6,4)],phase)
    if amp<=1 or radius<=1: return Image.new("RGBA",(W,H),(0,0,0,0))
    sc=W/360.0
    ov=Image.new("RGBA",(W,H),(0,0,0,0)); d=ImageDraw.Draw(ov)
    sx,sy=W*0.5,H*0.02
    r0=radius*sc
    wl=max(2.2, ripples*sc*0.95)
    base=amp/260.0
    for k in range(9):
        rk=r0-k*wl*1.55
        if rk<minr*sc: continue
        a=int(190*base*math.exp(-k*0.38)*min(1.0,fade/4.0))
        if a<3: continue
        # angular modulation to fake the 3 sampled ripple trains
        for j in range(3):
            rj=rk + (j-1)*wl*0.28
            if rj<=0: continue
            aa=int(a*(0.55+0.45*math.cos(j*2.1+phase*4.0)))
            if aa<2: continue
            d.ellipse([sx-rj,sy-rj,sx+rj,sy+rj],outline=(205,245,255,aa),width=max(2,int(W*0.006)))
    # faint refraction fill
    if fade>0:
        r=int(r0*0.95)
        if r>0:
            d.ellipse([sx-r,sy-r,sx+r,sy+r],fill=(120,210,255,int(10*base*min(1.0,fade/4.0))))
    return ov

def add_glow_layer(base, W,H, frame_t, phase):
    """Rounded edge glow + cast glow + water ripples."""
    overlay=Image.new("RGBA",(W,H),(0,0,0,0))
    od=ImageDraw.Draw(overlay)
    # ---- edge light (Light layer approximation) ----
    if phase < 1.04:
        p=clamp(phase/1.04)
        edge=smooth(p); alpha=smooth(clamp(phase/0.30))*0.95
        cast=smooth(p)
    elif phase < 3.4:
        q=(phase-1.04)/2.36
        edge=1.0-0.10*math.sin(math.pi*clamp(q)*2)
        alpha=0.92-0.05*math.sin(math.pi*clamp(q)*2)
        cast=0.9
    else:
        p=clamp((phase-3.4)/0.6)
        edge=1-smooth(p); alpha=(1-smooth(p))*0.92; cast=1-smooth(p)
    inset=max(2,int(W*0.018))
    box=[inset,inset,W-inset,H-inset]
    for i,ww in enumerate([max(1,int(W*0.003)),max(2,int(W*0.008)),max(4,int(W*0.018))]):
        a=int(alpha*(230-70*i)*edge)
        if a<=0: continue
        od.rounded_rectangle([box[0]-i*2,box[1]-i*2,box[2]+i*2,box[3]+i*2],radius=int(W*0.10)+i*2,outline=(150,228,255,a),width=ww)
    t=phase
    for side in range(4):
        cx,cy=[(W*0.5,inset),(W-inset,H*0.5),(W*0.5,H-inset),(inset,H*0.5)][side]
        span=[W,H,W,H][side]
        off=math.sin(t*5.2+side*1.7)*span*0.28
        for k in range(7):
            frac=(k-3)/3
            x=cx+off+frac*span*0.10 if side in (0,2) else cx
            y=cy if side in (0,2) else cy+off+frac*span*0.10
            r=int(W*0.075*(1-abs(frac)*0.5))
            a=int(alpha*edge*62*(1-abs(frac)*0.8))
            if a>0:
                od.ellipse([x-r,y-r,x+r,y+r],fill=(90,200,255,a))
    if cast>0.01:
        gx,gy=W*0.5,H*0.98
        gr=max(2,int(W*0.55*cast))
        for k in range(4,0,-1):
            r=int(gr*(0.35+0.18*k)); a=int(cast*20)
            od.ellipse([gx-r,gy-r*0.55,gx+r,gy+r*0.55],fill=(70,170,255,a))
    overlay=Image.alpha_composite(overlay,water_overlay(W,H,phase))
    overlay=overlay.filter(ImageFilter.GaussianBlur(radius=max(1,int(W*0.010))))
    out=Image.alpha_composite(base.convert("RGBA"),overlay)
    # crisp edge line on top (the blurred layer alone disappears on dark backgrounds)
    if edge>0.02:
        line=Image.new("RGBA",(W,H),(0,0,0,0)); ld=ImageDraw.Draw(line)
        for i,ww in enumerate([max(1,int(W*0.004)),max(2,int(W*0.010))]):
            a=int(alpha*edge*(210-80*i))
            if a>0:
                ld.rounded_rectangle([box[0]-i*2,box[1]-i*2,box[2]+i*2,box[3]+i*2],radius=int(W*0.10)+i*2,outline=(160,232,255,a),width=ww)
        out=Image.alpha_composite(out,line)
    return out

def card_frame(base, phase, W,H):
    """Card appear/receive + respire + send/vanish approximation."""
    img=base.convert("RGBA")
    shadow=Image.open(os.path.join(OPPO,"CardRotate0424","2130743833")).convert("RGBA")
    # phase mapping: 0.0-1.04 admission, 1.04-1.5 respire, 1.5-2.24 card appear, 2.24-3.4 respire
    if phase < 1.35: return img
    # card receive from bottom 1.35-2.09
    t=clamp((phase-1.35)/0.736)
    if t<=0: return img
    scale=lerp(0.1,0.7777,smooth(t))
    yfrac=lerp(1.05,0.564,smooth(t))
    alpha=smooth(clamp(t/0.5))
    target_w=W*scale*1.05
    cw=int(target_w); ch=int(cw*472/360)
    sh=shadow.resize((cw,ch),Image.LANCZOS)
    # split alpha and color; shadow texture is already a shadow silhouette
    cx=int(W*0.5); cy=int(H*(1.0-yfrac))
    # glow behind card
    glow=Image.new("RGBA",(W,H),(0,0,0,0)); gd=ImageDraw.Draw(glow)
    r=int(cw*0.62)
    for k in range(3):
        gd.ellipse([cx-r//2,cy-r//3,cx+r//2,cy+r//3],fill=(80,170,255,int(12*alpha)))
    img=Image.alpha_composite(img,glow.filter(ImageFilter.GaussianBlur(18)))
    sh.putalpha(sh.getchannel('A').point(lambda v:int(v*alpha)))
    img.alpha_composite(sh,(cx-cw//2,cy-ch//2))
    # card body placeholder
    body=Image.new("RGBA",(cw,ch),(0,0,0,0)); bd=ImageDraw.Draw(body)
    bd.rounded_rectangle([0,0,cw-1,ch-1],radius=int(cw*0.06),fill=(235,238,245,int(235*alpha)))
    bd.rounded_rectangle([int(cw*0.08),int(ch*0.08),cw-int(cw*0.08),ch-int(ch*0.08)],radius=int(cw*0.04),outline=(160,170,190,int(120*alpha)),width=2)
    bd.text((int(cw*0.14),int(ch*0.42)),"preview card",font=font(max(10,int(cw*0.07)),True),fill=(60,70,90,int(230*alpha)))
    img.alpha_composite(body,(cx-cw//2,cy-ch//2))
    return img

def animate():
    W,H=360,640; fps=12; total=4.4
    n=int(total*fps)
    frames=[]
    bg=make_background(W,H)
    base_dark=Image.new("RGB",(W,H),(10,13,22))
    for i in range(n):
        ph=i/fps
        # during first 1.35s use synthetic screen; after card, dim
        base=bg if ph<1.35 else Image.blend(bg,base_dark,clamp((ph-1.35)/0.4)*0.55)
        im=add_glow_layer(base,W,H,ph,ph)
        im=card_frame(im,ph,W,H)
        d=ImageDraw.Draw(im)
        # phase label
        if ph<0.35: label="1) touch: water ripple"
        elif ph<1.04: label="2) Admission light"
        elif ph<1.35: label="3) Respire loop"
        elif ph<2.09: label="4) Card receive (approx.)"
        elif ph<3.4: label="5) Card respire / wait"
        else: label="6) All light disappears"
        d.text((14,H-58),label,font=font(17,True),fill=(240,245,255))
        d.text((14,H-32),"APPROXIMATE PREVIEW - NOT OPPO ORIGINAL RENDERING",font=font(11,True),fill=(255,190,90))
        d.text((14,14),f"t={ph:.2f}s",font=font(15,mono=True),fill=(200,210,225))
        frames.append(im.convert("P",palette=Image.ADAPTIVE,colors=128))
    frames[0].save(os.path.join(BASE,"water_edge_light_approx.gif"),save_all=True,append_images=frames[1:],duration=int(1000/fps),loop=0,optimize=True)

def water_only():
    W=H=360; fps=20; total=1.05; n=int(total*fps)
    frames=[]
    bg=make_background(W,H)
    for i in range(n):
        ph=i/fps
        im=Image.alpha_composite(bg.convert("RGBA"),water_overlay(W,H,ph)).convert("RGB")
        dd=ImageDraw.Draw(im)
        dd.text((12,H-56),f"t={ph:.2f}s",font=font(14,mono=True),fill=(220,230,245))
        dd.text((12,H-36),"water_0812.coz animator1 - approximate, 1.0s",font=font(12,True),fill=(240,245,255))
        dd.text((12,H-20),"NOT OPPO ORIGINAL RENDERING",font=font(10,True),fill=(255,190,90))
        frames.append(im.convert("P",palette=Image.ADAPTIVE,colors=128))
    frames[0].save(os.path.join(BASE,"water_ripple_approx.gif"),save_all=True,append_images=frames[1:],duration=50,loop=0,optimize=False)

if __name__=="__main__":
    contact_sheet()
    timelines()
    water_only()
    animate()
    print("done")
