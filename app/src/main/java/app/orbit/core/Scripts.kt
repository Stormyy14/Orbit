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

    /** Readability-lite: finds the densest text container and flattens it into blocks. */
    const val READER = """
(function(){try{
function ptext(el){var s=0,ps=el.querySelectorAll('p');for(var j=0;j<ps.length;j++){var t=ps[j].textContent||'';if(t.length>50)s+=t.length;}return s;}
var total=ptext(document.body),best=null;
var pref=['[itemprop=articleBody]','.mw-parser-output','#mw-content-text','article','main','[role=main]','.post-content','.entry-content','.article-body','.story-body','#content'];
for(var i=0;i<pref.length&&!best;i++){var el=document.querySelector(pref[i]);if(el&&ptext(el)>=total*0.6)best=el;}
if(!best){var sc=new Map(),ps=document.querySelectorAll('p');
for(var j=0;j<ps.length;j++){var t=(ps[j].textContent||'').length;if(t<50)continue;var p=ps[j].parentElement;if(!p)continue;
sc.set(p,(sc.get(p)||0)+t);if(p.parentElement)sc.set(p.parentElement,(sc.get(p.parentElement)||0)+t/2);}
var bs=0;sc.forEach(function(v,k){if(v>bs){bs=v;best=k;}});
while(best&&best.parentElement&&best!==document.body&&ptext(best)<total*0.7&&ptext(best.parentElement)>=total*0.7)best=best.parentElement;}
if(!best)best=document.body;
var out=[],words=0;var nodes=best.querySelectorAll('h1,h2,h3,h4,p,li,blockquote,pre,img,figcaption');
for(var k=0;k<nodes.length&&out.length<800;k++){var n=nodes[k];
if(n.closest('nav,footer,aside,form,[role=navigation],[role=complementary],.comments,.related,.share,.social,.newsletter,.ad,.advert'))continue;
var tag=n.tagName.toLowerCase();
if(tag==='img'){var src=n.currentSrc||n.src;var w=n.naturalWidth||n.width;if(src&&src.indexOf('http')===0&&w>240)out.push({t:'img',v:src});continue;}
if(n.parentElement&&n.parentElement.closest('li,blockquote,pre')&&tag!=='pre')continue;
var txt=(n.innerText||n.textContent||'').trim();if(!txt)continue;
if(tag==='li'&&txt.length<2)continue;
words+=txt.split(/\s+/).length;out.push({t:tag,v:txt});}
function meta(s){var e=document.querySelector(s);return e?(e.content||''):'';}
var h1=document.querySelector('h1'),h=h1?(h1.innerText||'').trim():'';
var title=(h.length>3&&document.title.indexOf(h)>=0)?h:(meta('meta[property="og:title"]')||document.title);
return JSON.stringify({title:title,
byline:meta('meta[name="author"]')||meta('meta[property="article:author"]'),
site:meta('meta[property="og:site_name"]')||location.hostname,
hero:meta('meta[property="og:image"]'),words:words,blocks:out});
}catch(e){return JSON.stringify({error:String(e)})}})()"""

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
