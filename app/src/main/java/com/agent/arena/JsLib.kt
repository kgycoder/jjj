package com.agent.arena

/** LMArena 페이지에 주입되는 DOM 헬퍼. 매 호출마다 앞에 붙고, window.__ag 는 한 번만 정의된다. */
object JsLib {
    val SRC: String = """
if(!window.__ag){window.__ag=(function(){
var TA='textarea[name="message"]';
var STOP_SEL='#chat-area > div.flex.min-h-0.flex-1.justify-center.overflow-hidden.overscroll-none > main > div > div > div.relative.flex.flex-col.items-center.pb-6.px-4 > div > div > div > div > form > div > div > div:nth-child(2) > button';
var FB_SEL='#chat-area div[class~="md:absolute"] div[class~="md:hidden"] div.justify-start button';
function norm(s){return (s||'').replace(/\s+/g,' ').trim();}
function isCode(e){return !!(e.getAttribute&&e.getAttribute('data-code-block')==='true');}
function findBtn(t){var b=document.querySelectorAll('button');for(var i=0;i<b.length;i++){if(norm(b[i].textContent).toLowerCase()===t){return b[i];}}return null;}
function codeMd(e){
  var l=e.querySelector('span.text-sm.font-medium');
  var lang=l?norm(l.textContent).toLowerCase():'';
  var code=e.querySelector('pre code');var t='';
  if(code){var ls=code.querySelectorAll('.line');
    if(ls.length){var a=[];for(var i=0;i<ls.length;i++){a.push(ls[i].textContent);}t=a.join('\n');}
    else{t=code.textContent;}}
  return '```'+lang+'\n'+t+'\n```';
}
function md(e){
  if(isCode(e)){return codeMd(e);}
  var o='';var n=e.childNodes;
  for(var i=0;i<n.length;i++){var c=n[i];
    if(c.nodeType===3){o+=c.textContent;}
    else if(c.nodeType===1){
      if(isCode(c)){o+='\n'+codeMd(c)+'\n';}
      else if(c.tagName==='BR'){o+='\n';}
      else if(/^(P|DIV|UL|OL|LI|H[1-6]|BLOCKQUOTE|TABLE|TR|PRE)$/.test(c.tagName)){o+='\n'+md(c)+'\n';}
      else{o+=md(c);}
    }}
  return o;
}
function tidy(s){return s.replace(/\n{3,}/g,'\n\n').trim();}
function segs(){
  var all=document.querySelectorAll('div.prose,[data-code-block="true"]');var arr=[];
  for(var i=0;i<all.length;i++){var e=all[i];var nested=false;
    for(var j=0;j<all.length;j++){if(j!==i&&all[j].contains(e)){nested=true;break;}}
    if(!nested){arr.push(e);}}
  return arr;
}
function alnum(x){return (x||'').toLowerCase().replace(/[^\p{L}\p{N}]/gu,'');}
function validMarkerNode(n){
  if(!n||!n.isConnected||!n.nodeValue){return false;}
  var pe=n.parentElement;
  return !(pe&&/^(TEXTAREA|INPUT|SCRIPT|STYLE|NOSCRIPT)$/.test(pe.tagName));
}
function findMarker(mk){
  var c=window.__agMN;
  if(validMarkerNode(c)&&c.nodeValue.indexOf(mk)>=0){return c;}
  var root=document.querySelector('#chat-area')||document.body;
  var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null);
  var n,found=null;
  while((n=w.nextNode())){if(validMarkerNode(n)&&n.nodeValue.indexOf(mk)>=0){found=n;break;}}
  window.__agMN=found;
  return found;
}
function generating(){
  if(document.querySelector('form button[aria-label*="stop" i]')){return true;}
  var f=document.querySelector(STOP_SEL);
  return !!(f&&f.getAttribute('aria-label')!=='Send message'&&f.type!=='submit');
}
return {
  hasComposer:function(){return !!document.querySelector(TA);},
  loginState:function(){
    if(findBtn('log in')||findBtn('continue with google')){return 'out';}
    return document.querySelector(TA)?'in':'unknown';
  },
  clickText:function(t){var b=findBtn(t);if(!b){return false;}b.click();return true;},
  clickFallback:function(){var b=document.querySelector(FB_SEL);if(!b){return false;}b.click();return true;},
  setPrompt:function(text){
    var ta=document.querySelector(TA);if(!ta){return false;}
    window.__agPN=alnum(text);window.__agMN=null;
    var bc={};var ss=segs();
    for(var q=0;q<ss.length;q++){var kk=alnum(tidy(md(ss[q])));bc[kk]=(bc[kk]||0)+1;}
    window.__agB=bc;
    ta.focus();
    var setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;
    setter.call(ta,text);
    ta.dispatchEvent(new Event('input',{bubbles:true}));
    return true;
  },
  clickSend:function(){
    var b=document.querySelector('button[aria-label="Send message"]');
    if(!b||b.disabled){return false;}b.click();return true;
  },
  clickStop:function(){
    var b=document.querySelector('form button[aria-label*="stop" i]')||document.querySelector(STOP_SEL);
    if(!b||b.getAttribute('aria-label')==='Send message'){return false;}b.click();return true;
  },
  segCount:function(){return segs().length;},
  // 답변 추출(순서/개수 가정에 의존하지 않음)
  //  a = 마커 뒤에 위치한 새 조각,  c = 전송 전에 없던 조각 중 내 텍스트가 아닌 모든 조각
  answer:function(mk){
    var m=findMarker(mk);var s=segs();
    var bc={};var src=window.__agB||{};for(var key in src){bc[key]=src[key];}
    var pn=window.__agPN||'';var A=[];var C=[];
    for(var i=0;i<s.length;i++){
      var e=s[i];var t=tidy(md(e));if(!t){continue;}
      var k=alnum(t);
      if(bc[k]>0){bc[k]--;continue;}
      if(t.indexOf(mk)>=0){continue;}
      var raw=isCode(e)?((e.querySelector('pre code')||e).textContent):t;
      var a=alnum(raw);
      if(a.length>=4&&pn.indexOf(a)>=0){continue;}
      C.push(t);
      if(m&&(m.compareDocumentPosition(e)&4)){A.push(t);}
    }
    return JSON.stringify({g:generating(),f:m?1:0,a:A.join('\n\n'),c:C.join('\n\n')});
  },
  // 폴백: baseline 이후 조각 중 "내가 보낸 텍스트의 일부"인 것을 제외
  snapshotFallback:function(base){
    var pn=window.__agPN||'';var s=segs().slice(base);var out=[];
    for(var i=0;i<s.length;i++){
      var e=s[i];var t=tidy(md(e));if(!t){continue;}
      var rawTxt=isCode(e)?((e.querySelector('pre code')||e).textContent):t;
      var a=alnum(rawTxt);
      if(a.length>=4&&pn.indexOf(a)>=0){continue;}
      out.push(t);
    }
    return JSON.stringify({g:generating(),t:out.join('\n\n'),f:0});
  },
  debug:function(mk){
    return JSON.stringify({segs:segs().length,marker:!!findMarker(mk),gen:generating(),composer:!!document.querySelector(TA),url:location.href});
  },
  // 내가 보낸 메시지 끝의 마커 "뒤"에 위치한 조각만 = 어시스턴트 답변
  snapshotAfter:function(mk){
    var m=findMarker(mk);
    if(!m){return JSON.stringify({g:generating(),t:'',f:0});}
    var s=segs();var out=[];
    for(var i=0;i<s.length;i++){
      if(m.compareDocumentPosition(s[i])&4){
        var t=tidy(md(s[i]));
        if(t&&t.indexOf(mk)<0){out.push(t);}
      }
    }
    return JSON.stringify({g:generating(),t:out.join('\n\n'),f:1});
  },
  snapshot:function(base,head){
    var s=segs().slice(base);var out=[];
    for(var i=0;i<s.length;i++){
      var t=tidy(md(s[i]));
      if(head&&norm(t).indexOf(head)===0){continue;}
      if(t){out.push(t);}
    }
    return JSON.stringify({g:generating(),t:out.join('\n\n')});
  }
};
})();}
"""
}
