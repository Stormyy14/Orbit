package app.orbit.core

import org.json.JSONArray
import org.json.JSONObject

/** JavaScript injected into pages. Kept small, defensive and dependency-free. */
object Scripts {

    /**
     * Applies [css] under a stable [id]. Prefers a constructable stylesheet, which a page's
     * Content-Security-Policy can't block; falls back to a <style> element.
     */
    fun style(id: String, css: String) =
        "(function(i,c){try{var k='__orbit_'+i,sh=window[k];" +
            "if(!sh&&window.CSSStyleSheet&&'adoptedStyleSheets' in document){try{sh=new CSSStyleSheet();" +
            "document.adoptedStyleSheets=document.adoptedStyleSheets.concat([sh]);window[k]=sh;}catch(x){sh=null;}}" +
            "if(sh){sh.replaceSync(c);return;}" +
            "var s=document.getElementById(i);if(!s){s=document.createElement('style');s.id=i;" +
            "(document.head||document.documentElement).appendChild(s);}s.textContent=c;}catch(e){}})(" +
            "${JSONObject.quote(id)},${JSONObject.quote(css)});"

    fun zapCss(selectors: List<String>) =
        if (selectors.isEmpty()) "" else selectors.joinToString(",") + "{display:none!important}"

    /** Returns the page's theme-color, or the computed background of body/html. */
    const val THEME = """
(function(){try{
var m=document.querySelector('meta[name="theme-color"]');
if(m&&m.content)return m.content;
function bg(e){if(!e)return '';var c=getComputedStyle(e).backgroundColor;return(c&&c!=='transparent'&&c!=='rgba(0, 0, 0, 0)')?c:'';}
var top=document.elementFromPoint(innerWidth/2,4);
return bg(top)||bg(document.body)||bg(document.documentElement)||'';
}catch(e){return ''}})()"""

    /**
     * Readability-lite: finds the densest text container of document D and flattens it into
     * blocks. Uses the article text that news sites put in their structured data (for search
     * engines) when it is longer than what the page shows, and reports signs of a paywall.
     */
    private const val READER_FN = """
function(D,live,U){
var BAD='nav,footer,aside,form,[role=navigation],[role=complementary],.comments,.related,.share,.social,.newsletter,.ad,.advert,[class*=share-],[class*=-share],[class*=sharing],[class*=social-]';
var WALL='[class*=paywall],[id*=paywall],[class*=Paywall],[class*=piano],[id*=piano],.tp-modal,[class*=regwall],[class*=subscribe-wall],[class*=subscription-wall],[class*=premium-wall],[class*=meter-wall]';
var NAG=/subscri|sign in|log in|already a member|continue reading|register|assin|subscrev|inicie sess|suscr/i;
var STOP=/continue reading|keep reading|subscribe (now|today|to)|already (a )?subscriber|continuar a ler|já (é )?assinante|assine (o|a|j)|seguir leyendo|suscríbete|weiterlesen|jetzt abonnieren|continuer à lire|abonnez-vous/i;
var body=D.body;if(!body)return JSON.stringify({error:'empty'});
function tx(e){return ((live&&e.innerText)||e.textContent||'').trim();}
function leaf(e){return e.tagName==='DIV'&&!e.querySelector('p,div,ul,ol,table,h1,h2,h3,h4,section,article,figure,blockquote,pre')&&(e.textContent||'').trim().length>=80;}
var P=[].slice.call(body.querySelectorAll('p'));
if(P.length<4){var dv=body.querySelectorAll('div');for(var i=0;i<dv.length&&i<6000;i++)if(leaf(dv[i]))P.push(dv[i]);}
var L=P.map(function(p){return (p.textContent||'').length;});
function ptext(el){var s=0;for(var j=0;j<P.length;j++)if(L[j]>50&&el.contains(P[j]))s+=L[j];return s;}
var total=ptext(body),best=null;
var pref=['[itemprop=articleBody]','.mw-parser-output','#mw-content-text','article','main','[role=main]','.post-content','.entry-content','.article-body','.article-content','.story-body','.story-content','#content'];
for(var i=0;i<pref.length&&!best;i++){var el=D.querySelector(pref[i]);if(el&&ptext(el)>=total*0.6)best=el;}
if(!best){var sc=new Map();
for(var j=0;j<P.length;j++){var t=L[j];if(t<50)continue;var p=P[j].parentElement;if(!p)continue;
sc.set(p,(sc.get(p)||0)+t);if(p.parentElement)sc.set(p.parentElement,(sc.get(p.parentElement)||0)+t/2);}
var bs=0;sc.forEach(function(v,k){if(v>bs){bs=v;best=k;}});
while(best&&best.parentElement&&best!==body&&ptext(best)<total*0.7&&ptext(best.parentElement)>=total*0.7)best=best.parentElement;}
if(!best)best=body;
var out=[],words=0,pw=0;var nodes=best.querySelectorAll('h1,h2,h3,h4,p,li,blockquote,pre,img,figcaption,div');
for(var k=0;k<nodes.length&&out.length<800;k++){var n=nodes[k];
var tag=n.tagName.toLowerCase();
if(tag==='div'&&!leaf(n))continue;
if(n.closest(BAD))continue;
if(tag==='img'){var src=n.getAttribute('data-src')||n.currentSrc||n.src;var w=n.naturalWidth||+n.getAttribute('width')||(live?0:999);
if(src&&src.indexOf('http')===0&&w>240)out.push({t:'img',v:src});continue;}
if(n.parentElement&&n.parentElement.closest('li,blockquote,pre')&&tag!=='pre')continue;
var txt=tx(n);if(!txt)continue;
if(tag==='li'&&txt.length<2)continue;
if(txt.length<600&&(n.closest(WALL)?NAG.test(txt):STOP.test(txt)))continue;
if(tag==='div')tag='p';
var c=txt.split(/\s+/).length;words+=c;if(tag==='p')pw+=c;out.push({t:tag,v:txt});}
var lb='',locked=false,ss=D.querySelectorAll('script[type="application/ld+json"]');
function walk(o,d){if(!o||typeof o!=='object'||d>6)return;
if(Array.isArray(o)){for(var i=0;i<o.length;i++)walk(o[i],d+1);return;}
if(typeof o.articleBody==='string'&&o.articleBody.length>lb.length)lb=o.articleBody;
var f=o.isAccessibleForFree;if(f===false||f==='False'||f==='false')locked=true;
for(var q in o)if(o[q]&&typeof o[q]==='object')walk(o[q],d+1);}
for(var i=0;i<ss.length;i++){try{walk(JSON.parse(ss[i].textContent),0);}catch(e){}}
if(lb){lb=lb.replace(/<br\s*\/?>|<\/(p|h\d|li|div|blockquote)>/gi,'\n');
try{lb=new DOMParser().parseFromString(lb,'text/html').body.textContent||'';}catch(e){}
var ps=lb.split(/\n+/).map(function(s){return s.trim();}).filter(function(s){return s.length>0;});
if(ps.length<3&&lb.length>1200){ps=[];var cur='',sn=lb.match(/[^.!?]+[.!?]+["'\u201d\u2019)]*\s*|[^.!?]+$/g)||[lb];
for(var i=0;i<sn.length;i++){cur+=sn[i];if(cur.length>500){ps.push(cur.trim());cur='';}}if(cur.trim())ps.push(cur.trim());}
var lw=0;for(var i=0;i<ps.length;i++)lw+=ps[i].split(/\s+/).length;
if(lw>pw*1.25+50){var keep=[];for(var i=0;i<out.length;i++){var b=out[i];if(b.t==='p')break;keep.push(b);}
out=keep.concat(ps.map(function(s){return {t:'p',v:s};}));words=words-pw+lw;pw=lw;}}
function meta(s){var e=D.querySelector(s);return e?(e.getAttribute('content')||''):'';}
var tier=meta('meta[property="article:content_tier"]').toLowerCase();
var wall=locked||tier==='locked'||tier==='metered'||!!D.querySelector(WALL);
var og=meta('meta[property="og:title"]'),sn=meta('meta[property="og:site_name"]').toLowerCase(),title='',hs=D.querySelectorAll('h1');
for(var i=0;i<hs.length&&!title;i++){var h=tx(hs[i]);if(h.length>3&&h.toLowerCase()!==sn&&(D.title.indexOf(h)>=0||og.indexOf(h)>=0)&&h!==D.title.trim())title=h;}
if(!title)title=og||D.title;
return JSON.stringify({title:title,
byline:meta('meta[name="author"]')||meta('meta[property="article:author"]'),
site:meta('meta[property="og:site_name"]')||(U.match(/^https?:\/\/([^\/?#]+)/)||[])[1]||'',
hero:meta('meta[property="og:image"]'),words:words,text:pw,paywall:wall,blocks:out});
}"""

    const val READER = "(function(){try{return ($READER_FN)(document,true,location.href);}catch(e){return JSON.stringify({error:String(e)})}})()"

    /** [READER] on fetched [html], parsed inertly (no scripts run, nothing loads). */
    fun readerOf(html: String, url: String) =
        "(function(){try{var D=new DOMParser().parseFromString(${JSONObject.quote(html)},'text/html');" +
            "return ($READER_FN)(D,false,${JSONObject.quote(url)});}catch(e){return JSON.stringify({error:String(e)})}})()"

    /** Zap mode: tap any element to hide it on this site forever. Posts the selector to OrbitBridge. */
    const val ZAP_ON = """
(function(){if(window.__orbitZap)return;window.__orbitZap=1;
var hl=document.createElement('div');
hl.style.cssText='position:fixed;pointer-events:none;z-index:2147483647;border:2px solid #FF7A6B;background:rgba(255,122,107,.18);border-radius:8px;transition:all .12s ease;box-shadow:0 0 0 9999px rgba(10,10,20,.25)';
document.documentElement.appendChild(hl);var cur=null;
function esc(s){return (window.CSS&&CSS.escape)?CSS.escape(s):s.replace(/([^\w-])/g,'\\$1');}
function uniq(q){try{return document.querySelectorAll(q).length===1;}catch(x){return false;}}
function sel(el){var parts=[];while(el&&el.nodeType===1&&el!==document.body&&parts.length<8){
if(el.id&&!/\d{3,}/.test(el.id)){parts.unshift('#'+esc(el.id));break;}
var p=el.tagName.toLowerCase();var cls=[].slice.call(el.classList).filter(function(c){return !/\d{3,}|active|hover|open|show/.test(c);}).slice(0,2);
if(cls.length)p+='.'+cls.map(esc).join('.');
var par=el.parentElement;if(par){var sib=[].filter.call(par.children,function(x){return x.tagName===el.tagName;});if(sib.length>1)p+=':nth-of-type('+(sib.indexOf(el)+1)+')';}
parts.unshift(p);if(uniq(parts.join(' > ')))break;el=el.parentElement;}return parts.join(' > ');}
function show(el){var r=el.getBoundingClientRect();hl.style.left=r.left+'px';hl.style.top=r.top+'px';hl.style.width=r.width+'px';hl.style.height=r.height+'px';}
function pick(e){var t=e.touches?e.touches[0]:e;var el=document.elementFromPoint(t.clientX,t.clientY);if(el&&el!==hl&&el!==document.body&&el!==document.documentElement){cur=el;show(el);}}
function click(e){e.preventDefault();e.stopPropagation();var el=cur||e.target;if(!el||el===document.body)return;
var s=sel(el);el.style.setProperty('display','none','important');hl.style.width='0';hl.style.height='0';cur=null;
try{OrbitBridge.postMessage(s);}catch(x){}}
document.addEventListener('touchstart',pick,true);document.addEventListener('click',click,true);
window.__orbitZapOff=function(){document.removeEventListener('touchstart',pick,true);document.removeEventListener('click',click,true);hl.remove();window.__orbitZap=0;};
})();"""

    const val ZAP_OFF = "(function(){if(window.__orbitZapOff)window.__orbitZapOff();})();"

    /**
     * Media: tells the app (via OrbitMedia) what is playing, so it can show media controls, and
     * takes play/pause/next/previous/seek commands back. While something audible is playing the
     * page is told it's still visible, so sites like YouTube don't pause when Orbit goes to the
     * background. Muted previews and short sounds are ignored.
     */
    const val MEDIA = """
(function(){var B=window.OrbitMedia;if(!B||window.__orbitMedia)return;window.__orbitMedia=1;
var cur=null,last=0,H={},seen=window.WeakSet?new WeakSet():null;
function audible(el){return el&&!el.paused&&!el.ended&&!el.muted&&el.volume>0&&!(isFinite(el.duration)&&el.duration<5);}
function playing(){return audible(cur);}
try{['hidden','webkitHidden','visibilityState','webkitVisibilityState'].forEach(function(k){
var d=Object.getOwnPropertyDescriptor(Document.prototype,k);if(!d||!d.get)return;
Object.defineProperty(Document.prototype,k,{configurable:true,enumerable:d.enumerable,get:function(){
if(playing())return k.indexOf('isibility')>0?'visible':false;return d.get.call(this);}});});}catch(e){}
['visibilitychange','webkitvisibilitychange'].forEach(function(t){window.addEventListener(t,function(e){if(playing())e.stopImmediatePropagation();},true);});
try{var ms=navigator.mediaSession;if(ms&&ms.setActionHandler){var sa=ms.setActionHandler.bind(ms);
ms.setActionHandler=function(a,h){H[a]=h;try{return sa(a,h);}catch(x){}};}}catch(e){}
function meta(){var m=null,art='';try{m=navigator.mediaSession&&navigator.mediaSession.metadata;}catch(e){}
try{if(m&&m.artwork&&m.artwork.length){var b=m.artwork[m.artwork.length-1];art=(b&&b.src)||'';}}catch(e){}
return{title:(m&&m.title)||document.title||'',artist:(m&&m.artist)||'',art:art};}
function send(force){var el=cur;if(!el)return;var now=Date.now();if(!force&&now-last<5000)return;last=now;
var d=meta();d.playing=playing();d.pos=Math.floor((el.currentTime||0)*1000);d.dur=isFinite(el.duration)?Math.floor(el.duration*1000):0;
d.next=!!H.nexttrack;d.prev=!!H.previoustrack;d.video=el.tagName==='VIDEO';
try{B.postMessage(JSON.stringify(d));}catch(e){}}
function ev(e){var el=e.target;
if(e.type==='emptied'&&el===cur&&!el.currentSrc){cur=null;try{B.postMessage('{"gone":true}');}catch(x){}return;}
if(el!==cur){if(!audible(el))return;cur=el;}
send(e.type!=='timeupdate');}
function track(el){if(!el||!(el instanceof HTMLMediaElement))return;if(seen){if(seen.has(el))return;seen.add(el);}else if(el.__orbitT)return;else el.__orbitT=1;
['play','playing','pause','ended','seeked','ratechange','durationchange','loadedmetadata','timeupdate','volumechange','emptied'].forEach(function(t){el.addEventListener(t,ev);});}
document.addEventListener('play',function(e){track(e.target);},true);
try{var P=HTMLMediaElement.prototype,op=P.play;P.play=function(){track(this);return op.apply(this,arguments);};}catch(e){}
B.onmessage=function(m){var c=String(m.data||''),el=cur;try{
if(c==='play'){if(el)el.play();else if(H.play)H.play({action:'play'});}
else if(c==='pause'){if(el)el.pause();else if(H.pause)H.pause({action:'pause'});}
else if(c==='next'){if(H.nexttrack)H.nexttrack({action:'nexttrack'});}
else if(c==='prev'){if(H.previoustrack)H.previoustrack({action:'previoustrack'});else if(el)el.currentTime=0;}
else if(c.indexOf('seek:')===0&&el){el.currentTime=parseFloat(c.slice(5))/1000;}
}catch(e){}};
})();"""

    /**
     * Shields, at document start in every frame. Asks the app (via OrbitShields) for the frame's
     * element-hiding CSS, then reports the class names and ids that appear on the page, so only
     * the generic rules that can match are applied (the lists have tens of thousands). The app
     * only ever answers with CSS built from the filter lists. Also signals Global Privacy Control.
     */
    const val SHIELDS = """
(function(){var B=window.OrbitShields;if(!B||window.__orbitShields)return;window.__orbitShields=1;
var D=document,P=JSON.parse,S=JSON.stringify,sheet=null,el=null,on=false,done=false,timer=0,n=0,qc=[],qi=[],sc=Object.create(null),si=Object.create(null),obs=null;
try{Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{configurable:true,enumerable:true,get:function(){return true;}});}catch(e){}
function attach(){try{if(sheet&&D.adoptedStyleSheets.indexOf(sheet)<0)D.adoptedStyleSheets=D.adoptedStyleSheets.concat([sheet]);}catch(e){}}
function add(css,first){if(!css)return;
try{if(!sheet&&!el&&window.CSSStyleSheet&&'adoptedStyleSheets' in D)sheet=new CSSStyleSheet();}catch(e){sheet=null;}
if(sheet){try{if(first)sheet.replaceSync(css);else{var r=css.split('\n');for(var i=0;i<r.length;i++)if(r[i])try{sheet.insertRule(r[i],sheet.cssRules.length);}catch(x){}}attach();return;}catch(e){sheet=null;}}
try{if(!el){el=D.createElement('style');el.id='orbit-shields';}el.textContent+=css;if(!el.parentNode)(D.head||D.documentElement).appendChild(el);}catch(e){}}
function note(e){var id=e.id;if(typeof id==='string'&&id&&!si[id]){si[id]=1;qi.push(id);n++;}
var cl=e.classList;if(cl)for(var i=0;i<cl.length;i++){var c=cl[i];if(!sc[c]){sc[c]=1;qc.push(c);n++;}}}
function scan(r){if(!r||r.nodeType!==1)return;note(r);var a=r.querySelectorAll('[id],[class]');for(var i=0;i<a.length;i++)note(a[i]);}
function queue(){if(on&&!timer&&(qc.length||qi.length))timer=setTimeout(flush,done?120:0);}
function flush(){timer=0;done=true;try{B.postMessage(S({t:'scan',c:qc.splice(0,1500),i:qi.splice(0,1500)}));}catch(e){}queue();}
function stop(){on=false;qc=[];qi=[];if(obs)obs.disconnect();obs=null;}
try{obs=new MutationObserver(function(ms){if(n>30000)return stop();
for(var i=0;i<ms.length;i++){var m=ms[i];if(m.type==='attributes'){if(m.target.nodeType===1)note(m.target);}else{var a=m.addedNodes;for(var j=0;j<a.length;j++)scan(a[j]);}}
queue();attach();});obs.observe(D,{childList:true,subtree:true,attributes:true,attributeFilter:['class','id']});}catch(e){}
B.onmessage=function(m){var d=String(m.data||'');
if(d.charAt(0)==='+'){add(d.slice(1),false);return;}
var o;try{o=P(d);}catch(e){return;}
add(o.css,true);if(o.scan){on=true;if(D.documentElement)scan(D.documentElement);queue();}else stop();};
D.addEventListener('DOMContentLoaded',function(){attach();if(on){scan(D.documentElement);queue();}});
try{B.postMessage(S({t:'init'}));}catch(e){}
})();"""

    /**
     * YouTube ads, which play from the same servers as the videos: the ad slots are removed from
     * the player's data before the player reads it, and an ad that still starts is muted and
     * skipped. Only added on YouTube's own origins, and only while ad blocking is on there.
     */
    const val YOUTUBE = """
(function(){if(window.__orbitYt)return;window.__orbitYt=1;
var K=['adPlacements','adSlots','playerAds','adBreakHeartbeatParams'];
function prune(o,d){if(!o||typeof o!=='object'||d>4)return o;try{
for(var i=0;i<K.length;i++)if(K[i] in o)delete o[K[i]];
if(o.playerResponse)prune(o.playerResponse,d+1);
if(Array.isArray(o)){for(var j=0;j<o.length&&j<8;j++)prune(o[j],d+1);}
else if(o.response)prune(o.response,d+1);}catch(e){}return o;}
try{JSON.parse=new Proxy(JSON.parse,{apply:function(t,s,a){return prune(Reflect.apply(t,s,a),0);}});}catch(e){}
try{var RP=Response.prototype;RP.json=new Proxy(RP.json,{apply:function(t,s,a){return Reflect.apply(t,s,a).then(function(r){return prune(r,0);});}});}catch(e){}
try{var v;Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,get:function(){return v;},set:function(x){v=prune(x,0);}});}catch(e){}
function skip(){try{var p=document.querySelector('.ad-showing video,.ad-interrupting video');
if(p){p.muted=true;if(isFinite(p.duration)&&p.duration>0&&p.currentTime<p.duration-0.1)p.currentTime=p.duration;}
var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button,button.ytp-ad-skip-button-slot');if(b)b.click();}catch(e){}}
setInterval(function(){if(!document.hidden||document.querySelector('.ad-showing'))skip();},400);
})();"""

    /**
     * While Orbit VPN is on: no WebRTC, which can reveal the real address by going around the
     * proxy. Same-origin child frames can be reached before their own copy of this runs, so they
     * are cleaned when touched, and after anything is inserted into the page.
     */
    const val NO_WEBRTC = """
(function(){if(window.__orbitNoRtc)return;
var N=['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection','RTCDataChannel','RTCRtpSender','RTCRtpReceiver','RTCRtpTransceiver','RTCIceCandidate','RTCSessionDescription','RTCDtlsTransport','RTCIceTransport','RTCSctpTransport','RTCCertificate','RTCPeerConnectionIceEvent','RTCDataChannelEvent','RTCTrackEvent','RTCEncodedAudioFrame','RTCEncodedVideoFrame','RTCIceCandidatePair'];
function off(w){try{if(!w||w.__orbitNoRtc)return;
for(var i=0;i<N.length;i++){try{Object.defineProperty(w,N[i],{value:undefined,writable:false,configurable:false,enumerable:false});}catch(e){try{delete w[N[i]];}catch(x){}}}
try{Object.defineProperty(w,'__orbitNoRtc',{value:1});}catch(e){}}catch(e){}}
off(window);
function sweep(){try{for(var i=0;i<window.frames.length;i++){try{off(window.frames[i]);}catch(e){}}}catch(e){}}
function wrap(o,k){try{var f=o[k];if(typeof f!=='function')return;o[k]=new Proxy(f,{apply:function(t,s,a){var r=Reflect.apply(t,s,a);sweep();return r;}});}catch(e){}}
['appendChild','insertBefore','replaceChild'].forEach(function(k){wrap(Node.prototype,k);});
['append','prepend','after','before','replaceWith','insertAdjacentElement','insertAdjacentHTML','replaceChildren','setHTMLUnsafe'].forEach(function(k){wrap(Element.prototype,k);});
['write','writeln','append','prepend'].forEach(function(k){wrap(Document.prototype,k);});
try{wrap(Range.prototype,'insertNode');}catch(e){}
function hook(C,p,view){try{var d=Object.getOwnPropertyDescriptor(C.prototype,p);if(!d||!d.get)return;var g=d.get;
Object.defineProperty(C.prototype,p,{configurable:true,enumerable:d.enumerable,get:function(){var r=g.call(this);try{off(view?(r&&r.defaultView):r);}catch(e){}return r;},set:d.set});}catch(e){}}
[window.HTMLIFrameElement,window.HTMLFrameElement,window.HTMLObjectElement,window.HTMLEmbedElement].forEach(function(C){if(!C)return;hook(C,'contentWindow',false);hook(C,'contentDocument',true);});
[Element,window.ShadowRoot].forEach(function(C){if(!C)return;['innerHTML','outerHTML'].forEach(function(p){try{var d=Object.getOwnPropertyDescriptor(C.prototype,p);if(!d||!d.set)return;var s=d.set;
Object.defineProperty(C.prototype,p,{configurable:true,enumerable:d.enumerable,get:d.get,set:function(v){s.call(this,v);sweep();}});}catch(e){}});});
})();"""

    /** evaluateJavascript returns a JSON-encoded value; unwrap a string result. */
    fun unwrap(result: String?): String {
        if (result.isNullOrEmpty() || result == "null") return ""
        return runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("")
    }
}
