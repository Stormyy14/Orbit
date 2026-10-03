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

    /** evaluateJavascript returns a JSON-encoded value; unwrap a string result. */
    fun unwrap(result: String?): String {
        if (result.isNullOrEmpty() || result == "null") return ""
        return runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("")
    }
}
