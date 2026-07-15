import{n as e}from"./rolldown-runtime-QTnfLwEv.js";import{$n as t,Bn as n,Cn as r,Ct as i,Et as a,G as o,Jn as s,Kn as c,Ln as l,Lt as u,N as d,Nt as f,Qn as p,Sn as m,Tt as h,U as g,Vt as _,Wt as v,Zn as y,_ as b,_n as x,bn as S,ct as C,dt as w,er as T,et as ee,g as E,h as D,lt as te,nn as ne,p as O,st as re,tn as ie,tr as ae,u as oe,ut as se,vn as ce,wt as k}from"./three.module-N1hBQQ4j.js";var le=0,ue=1,de=2,fe=0,pe=1,me=2,he=1.25,ge=65535,_e=ge<<16,ve=2**-24,ye=Symbol(`SKIP_GENERATION`),be={strategy:0,maxDepth:40,maxLeafSize:10,useSharedArrayBuffer:!1,setBoundingBox:!0,onProgress:null,indirect:!1,verbose:!0,range:null,[ye]:!1};function A(e,t,n){return n.min.x=t[e],n.min.y=t[e+1],n.min.z=t[e+2],n.max.x=t[e+3],n.max.y=t[e+4],n.max.z=t[e+5],n}function xe(e){let t=-1,n=-1/0;for(let r=0;r<3;r++){let i=e[r+3]-e[r];i>n&&(n=i,t=r)}return t}function Se(e,t){t.set(e)}function Ce(e,t,n){let r,i;for(let a=0;a<3;a++){let o=a+3;r=e[a],i=t[a],n[a]=r<i?r:i,r=e[o],i=t[o],n[o]=r>i?r:i}}function we(e,t,n){for(let r=0;r<3;r++){let i=t[e+2*r],a=t[e+2*r+1],o=i-a,s=i+a;o<n[r]&&(n[r]=o),s>n[r+3]&&(n[r+3]=s)}}function Te(e){let t=e[3]-e[0],n=e[4]-e[1],r=e[5]-e[2];return 2*(t*n+n*r+r*t)}function j(e,t){return t[e+15]===ge}function M(e,t){return t[e+6]}function N(e,t){return t[e+14]}function P(e){return e+8}function F(e,t){return e+t[e+6]*8}function Ee(e,t){return t[e+7]}function I(e){return e}function De(e,t,n,r,i){let a=1/0,o=1/0,s=1/0,c=-1/0,l=-1/0,u=-1/0,d=1/0,f=1/0,p=1/0,m=-1/0,h=-1/0,g=-1/0,_=e.offset||0;for(let r=(t-_)*6,i=(t+n-_)*6;r<i;r+=6){let t=e[r+0],n=e[r+1],i=t-n,_=t+n;i<a&&(a=i),_>c&&(c=_),t<d&&(d=t),t>m&&(m=t);let v=e[r+2],y=e[r+3],b=v-y,x=v+y;b<o&&(o=b),x>l&&(l=x),v<f&&(f=v),v>h&&(h=v);let S=e[r+4],C=e[r+5],w=S-C,T=S+C;w<s&&(s=w),T>u&&(u=T),S<p&&(p=S),S>g&&(g=S)}r[0]=a,r[1]=o,r[2]=s,r[3]=c,r[4]=l,r[5]=u,i[0]=d,i[1]=f,i[2]=p,i[3]=m,i[4]=h,i[5]=g}var L=32,Oe=(e,t)=>e.candidate-t.candidate,R=Array(L).fill().map(()=>({count:0,bounds:new Float32Array(6),rightCacheBounds:new Float32Array(6),leftCacheBounds:new Float32Array(6),candidate:0})),ke=new Float32Array(6);function Ae(e,t,n,r,i,a){let o=-1,s=0;if(a===0)o=xe(t),o!==-1&&(s=(t[o]+t[o+3])/2);else if(a===1)o=xe(e),o!==-1&&(s=je(n,r,i,o));else if(a===2){let a=Te(e),c=he*i,l=n.offset||0,u=(r-l)*6,d=(r+i-l)*6;for(let e=0;e<3;e++){let r=t[e],l=(t[e+3]-r)/L;if(i<L/4){let t=[...R];t.length=i;let r=0;for(let i=u;i<d;i+=6,r++){let a=t[r];a.candidate=n[i+2*e],a.count=0;let{bounds:o,leftCacheBounds:s,rightCacheBounds:c}=a;for(let e=0;e<3;e++)c[e]=1/0,c[e+3]=-1/0,s[e]=1/0,s[e+3]=-1/0,o[e]=1/0,o[e+3]=-1/0;we(i,n,o)}t.sort(Oe);let l=i;for(let e=0;e<l;e++){let n=t[e];for(;e+1<l&&t[e+1].candidate===n.candidate;)t.splice(e+1,1),l--}for(let r=u;r<d;r+=6){let i=n[r+2*e];for(let e=0;e<l;e++){let a=t[e];i>=a.candidate?we(r,n,a.rightCacheBounds):(we(r,n,a.leftCacheBounds),a.count++)}}for(let n=0;n<l;n++){let r=t[n],l=r.count,u=i-r.count,d=r.leftCacheBounds,f=r.rightCacheBounds,p=0;l!==0&&(p=Te(d)/a);let m=0;u!==0&&(m=Te(f)/a);let h=1+he*(p*l+m*u);h<c&&(o=e,c=h,s=r.candidate)}}else{for(let e=0;e<L;e++){let t=R[e];t.count=0,t.candidate=r+l+e*l;let n=t.bounds;for(let e=0;e<3;e++)n[e]=1/0,n[e+3]=-1/0}for(let t=u;t<d;t+=6){let i=~~((n[t+2*e]-r)/l);i>=L&&(i=L-1);let a=R[i];a.count++,we(t,n,a.bounds)}let t=R[L-1];Se(t.bounds,t.rightCacheBounds);for(let e=L-2;e>=0;e--){let t=R[e],n=R[e+1];Ce(t.bounds,n.rightCacheBounds,t.rightCacheBounds)}let f=0;for(let t=0;t<L-1;t++){let n=R[t],r=n.count,l=n.bounds,u=R[t+1].rightCacheBounds;r!==0&&(f===0?Se(l,ke):Ce(l,ke,ke)),f+=r;let d=0,p=0;f!==0&&(d=Te(ke)/a);let m=i-f;m!==0&&(p=Te(u)/a);let h=1+he*(d*f+p*m);h<c&&(o=e,c=h,s=n.candidate)}}}}else console.warn(`BVH: Invalid build strategy value ${a} used.`);return{axis:o,pos:s}}function je(e,t,n,r){let i=0,a=e.offset;for(let o=t,s=t+n;o<s;o++)i+=e[(o-a)*6+r*2];return i/n}var Me=class{constructor(){this.boundingData=new Float32Array(6)}};function Ne(e,t,n,r,i,a){let o=r,s=r+i-1,c=a.pos,l=a.axis*2,u=n.offset||0;for(;;){for(;o<=s&&n[(o-u)*6+l]<c;)o++;for(;o<=s&&n[(s-u)*6+l]>=c;)s--;if(o<s){for(let n=0;n<t;n++){let r=e[o*t+n];e[o*t+n]=e[s*t+n],e[s*t+n]=r}for(let e=0;e<6;e++){let t=o-u,r=s-u,i=n[t*6+e];n[t*6+e]=n[r*6+e],n[r*6+e]=i}o++,s--}else return o}}var Pe,Fe,Ie,Le,Re=2**32;function ze(e){return`count`in e?1:1+ze(e.left)+ze(e.right)}function Be(e,t,n){return Pe=new Float32Array(n),Fe=new Uint32Array(n),Ie=new Uint16Array(n),Le=new Uint8Array(n),Ve(e,t)}function Ve(e,t){let n=e/4,r=e/2,i=`count`in t,a=t.boundingData;for(let e=0;e<6;e++)Pe[n+e]=a[e];if(i)return t.buffer?(Le.set(new Uint8Array(t.buffer),e),e+t.buffer.byteLength):(Fe[n+6]=t.offset,Ie[r+14]=t.count,Ie[r+15]=ge,e+32);{let{left:r,right:i,splitAxis:a}=t,o=Ve(e+32,r),s=e/32,c=o/32-s;if(c>Re)throw Error(`MeshBVH: Cannot store relative child node offset greater than 32 bits.`);return Fe[n+6]=c,Fe[n+7]=a,Ve(o,i)}}function He(e,t,n,r,i,a){let{maxDepth:o,verbose:s,maxLeafSize:c,strategy:l,onProgress:u}=i,d=e.primitiveBuffer,f=e.primitiveBufferStride,p=new Float32Array(6),m=!1,h=new Me;return De(t,n,r,h.boundingData,p),_(h,n,r,p),h;function g(e){u&&u((e-a.offset)/a.count)}function _(e,n,r,i=null,a=0){if(!m&&a>=o&&(m=!0,s&&console.warn(`BVH: Max depth of ${o} reached when generating BVH. Consider increasing maxDepth.`)),r<=c||a>=o)return g(n+r),e.offset=n,e.count=r,e;let u=Ae(e.boundingData,i,t,n,r,l);if(u.axis===-1)return g(n+r),e.offset=n,e.count=r,e;let h=Ne(d,f,t,n,r,u);if(h===n||h===n+r)g(n+r),e.offset=n,e.count=r;else{e.splitAxis=u.axis;let i=new Me,o=n,s=h-n;e.left=i,De(t,o,s,i.boundingData,p),_(i,o,s,p,a+1);let c=new Me,l=h,d=r-s;e.right=c,De(t,l,d,c.boundingData,p),_(c,l,d,p,a+1)}return e}}function Ue(e,t){let n=t.useSharedArrayBuffer?SharedArrayBuffer:ArrayBuffer,r=e.getRootRanges(t.range),i=r[0],a=r[r.length-1],o={offset:i.offset,count:a.offset+a.count-i.offset},s=new Float32Array(6*o.count);s.offset=o.offset,e.computePrimitiveBounds(o.offset,o.count,s),e._roots=r.map(r=>{let i=He(e,s,r.offset,r.count,t,o),a=ze(i),c=new n(32*a);return Be(0,i,c),c})}var We=class{constructor(e){this._getNewPrimitive=e,this._primitives=[]}getPrimitive(){let e=this._primitives;return e.length===0?this._getNewPrimitive():e.pop()}releasePrimitive(e){this._primitives.push(e)}},z=new class{constructor(){this.float32Array=null,this.uint16Array=null,this.uint32Array=null;let e=[],t=null;this.setBuffer=n=>{t&&e.push(t),t=n,this.float32Array=new Float32Array(n),this.uint16Array=new Uint16Array(n),this.uint32Array=new Uint32Array(n)},this.clearBuffer=()=>{t=null,this.float32Array=null,this.uint16Array=null,this.uint32Array=null,e.length!==0&&this.setBuffer(e.pop())}}},B,Ge,Ke=[],qe=new We(()=>new O);function Je(e,t,n,r,i,a){B=qe.getPrimitive(),Ge=qe.getPrimitive(),Ke.push(B,Ge),z.setBuffer(e._roots[t]);let o=Ye(0,e.geometry,n,r,i,a);z.clearBuffer(),qe.releasePrimitive(B),qe.releasePrimitive(Ge),Ke.pop(),Ke.pop();let s=Ke.length;return s>0&&(Ge=Ke[s-1],B=Ke[s-2]),o}function Ye(e,t,n,r,i=null,a=0,o=0){let{float32Array:s,uint16Array:c,uint32Array:l}=z,u=e*2;if(j(u,c)){let t=M(e,l),n=N(u,c);return A(I(e),s,B),r(t,n,!1,o,a+e/8,B)}else{let u=P(e),d=F(e,l),f=u,p=d,m,h,g,_;if(i&&(g=B,_=Ge,A(I(f),s,g),A(I(p),s,_),m=i(g),h=i(_),h<m)){f=d,p=u;let e=m;m=h,h=e,g=_}g||(g=B,A(I(f),s,g));let v=j(f*2,c),y=n(g,v,m,o+1,a+f/8),b;if(y===2){let e=w(f);b=r(e,T(f)-e,!0,o+1,a+f/8,g)}else b=y&&Ye(f,t,n,r,i,a,o+1);if(b)return!0;_=Ge,A(I(p),s,_);let x=j(p*2,c),S=n(_,x,h,o+1,a+p/8),C;if(S===2){let e=w(p);C=r(e,T(p)-e,!0,o+1,a+p/8,_)}else C=S&&Ye(p,t,n,r,i,a,o+1);if(C)return!0;return!1;function w(e){let{uint16Array:t,uint32Array:n}=z,r=e*2;for(;!j(r,t);)e=P(e),r=e*2;return M(e,n)}function T(e){let{uint16Array:t,uint32Array:n}=z,r=e*2;for(;!j(r,t);)e=F(e,n),r=e*2;return M(e,n)+N(r,t)}}}var Xe=new z.constructor,Ze=new z.constructor,Qe=new We(()=>new O),$e=new O,et=new O,tt=new O,nt=new O,rt=!1;function it(e,t,n,r){if(rt)throw Error(`MeshBVH: Recursive calls to bvhcast not supported.`);rt=!0;let i=e._roots,a=t._roots,o,s=0,c=0,l=new k().copy(n).invert();for(let e=0,t=i.length;e<t;e++){Xe.setBuffer(i[e]),c=0;let t=Qe.getPrimitive();A(I(0),Xe.float32Array,t),t.applyMatrix4(l);for(let e=0,i=a.length;e<i&&(Ze.setBuffer(a[e]),o=V(0,0,n,l,r,s,c,0,0,t),Ze.clearBuffer(),c+=a[e].byteLength/32,!o);e++);if(Qe.releasePrimitive(t),Xe.clearBuffer(),s+=i[e].byteLength/32,o)break}return rt=!1,o}function V(e,t,n,r,i,a=0,o=0,s=0,c=0,l=null,u=!1){let d,f;u?(d=Ze,f=Xe):(d=Xe,f=Ze);let p=d.float32Array,m=d.uint32Array,h=d.uint16Array,g=f.float32Array,_=f.uint32Array,v=f.uint16Array,y=e*2,b=t*2,x=j(y,h),S=j(b,v),C=!1;if(S&&x)C=u?i(M(t,_),N(t*2,v),M(e,m),N(e*2,h),c,o+t/8,s,a+e/8):i(M(e,m),N(e*2,h),M(t,_),N(t*2,v),s,a+e/8,c,o+t/8);else if(S){let l=Qe.getPrimitive();A(I(t),g,l),l.applyMatrix4(n);let d=P(e),f=F(e,m);A(I(d),p,$e),A(I(f),p,et);let h=l.intersectsBox($e),_=l.intersectsBox(et);C=h&&V(t,d,r,n,i,o,a,c,s+1,l,!u)||_&&V(t,f,r,n,i,o,a,c,s+1,l,!u),Qe.releasePrimitive(l)}else{let d=P(t),f=F(t,_);A(I(d),g,tt),A(I(f),g,nt);let h=l.intersectsBox(tt),v=l.intersectsBox(nt);if(h&&v)C=V(e,d,n,r,i,a,o,s,c+1,l,u)||V(e,f,n,r,i,a,o,s,c+1,l,u);else if(h)if(x)C=V(e,d,n,r,i,a,o,s,c+1,l,u);else{let t=Qe.getPrimitive();t.copy(tt).applyMatrix4(n);let l=P(e),f=F(e,m);A(I(l),p,$e),A(I(f),p,et);let h=t.intersectsBox($e),g=t.intersectsBox(et);C=h&&V(d,l,r,n,i,o,a,c,s+1,t,!u)||g&&V(d,f,r,n,i,o,a,c,s+1,t,!u),Qe.releasePrimitive(t)}else if(v)if(x)C=V(e,f,n,r,i,a,o,s,c+1,l,u);else{let t=Qe.getPrimitive();t.copy(nt).applyMatrix4(n);let l=P(e),d=F(e,m);A(I(l),p,$e),A(I(d),p,et);let h=t.intersectsBox($e),g=t.intersectsBox(et);C=h&&V(f,l,r,n,i,o,a,c,s+1,t,!u)||g&&V(f,d,r,n,i,o,a,c,s+1,t,!u),Qe.releasePrimitive(t)}}return C}var at=new O,ot=new Float32Array(6),st=class{constructor(){this._roots=null,this.primitiveBuffer=null,this.primitiveBufferStride=null}init(e){e={...be,...e},Ue(this,e)}getRootRanges(){throw Error(`BVH: getRootRanges() not implemented`)}writePrimitiveBounds(){throw Error(`BVH: writePrimitiveBounds() not implemented`)}writePrimitiveRangeBounds(e,t,n,r){let i=1/0,a=1/0,o=1/0,s=-1/0,c=-1/0,l=-1/0;for(let n=e,r=e+t;n<r;n++){this.writePrimitiveBounds(n,ot,0);let[e,t,r,u,d,f]=ot;e<i&&(i=e),u>s&&(s=u),t<a&&(a=t),d>c&&(c=d),r<o&&(o=r),f>l&&(l=f)}return n[r+0]=i,n[r+1]=a,n[r+2]=o,n[r+3]=s,n[r+4]=c,n[r+5]=l,n}computePrimitiveBounds(e,t,n){let r=n.offset||0;for(let i=e,a=e+t;i<a;i++){this.writePrimitiveBounds(i,ot,0);let[e,t,a,o,s,c]=ot,l=(e+o)/2,u=(t+s)/2,d=(a+c)/2,f=(o-e)/2,p=(s-t)/2,m=(c-a)/2,h=(i-r)*6;n[h+0]=l,n[h+1]=f+(Math.abs(l)+f)*ve,n[h+2]=u,n[h+3]=p+(Math.abs(u)+p)*ve,n[h+4]=d,n[h+5]=m+(Math.abs(d)+m)*ve}return n}shiftPrimitiveOffsets(e){let t=this._indirectBuffer;if(t)for(let n=0,r=t.length;n<r;n++)t[n]+=e;else{let t=this._roots;for(let n=0;n<t.length;n++){let r=t[n],i=new Uint32Array(r),a=new Uint16Array(r),o=r.byteLength/32;for(let t=0;t<o;t++){let n=8*t;j(2*n,a)&&(i[n+6]+=e)}}}}traverse(e,t=0){let n=this._roots[t],r=new Uint32Array(n),i=new Uint16Array(n);a(0);function a(t,o=0){let s=t*2,c=j(s,i);if(c){let a=r[t+6],l=i[s+14];e(o,c,new Float32Array(n,t*4,6),a,l)}else{let i=P(t),s=F(t,r),l=Ee(t,r);e(o,c,new Float32Array(n,t*4,6),l)||(a(i,o+1),a(s,o+1))}}}refit(){let e=this._roots;for(let t=0,n=e.length;t<n;t++){let n=e[t],r=new Uint32Array(n),i=new Uint16Array(n),a=new Float32Array(n),o=n.byteLength/32;for(let e=o-1;e>=0;e--){let t=e*8,n=t*2;if(j(n,i)){let e=M(t,r),o=N(n,i);this.writePrimitiveRangeBounds(e,o,ot,0),a.set(ot,t)}else{let e=P(t),n=F(t,r);for(let r=0;r<3;r++){let i=a[e+r],o=a[e+r+3],s=a[n+r],c=a[n+r+3];a[t+r]=i<s?i:s,a[t+r+3]=o>c?o:c}}}}}getBoundingBox(e){return e.makeEmpty(),this._roots.forEach(t=>{A(0,new Float32Array(t),at),e.union(at)}),e}shapecast(e){let{boundsTraverseOrder:t,intersectsBounds:n,intersectsRange:r,intersectsPrimitive:i,scratchPrimitive:a,iterate:o}=e;if(r&&i){let e=r;r=(t,n,r,s,c)=>e(t,n,r,s,c)?!0:o(t,n,this,i,r,s,a)}else r||=i?(e,t,n,r)=>o(e,t,this,i,n,r,a):(e,t,n)=>n;let s=!1,c=0,l=this._roots;for(let e=0,i=l.length;e<i;e++){let i=l[e];if(s=Je(this,e,n,r,t,c),s)break;c+=i.byteLength/32}return s}bvhcast(e,t,n){let{intersectsRanges:r}=n;return it(this,e,t,r)}};function ct(){return typeof SharedArrayBuffer<`u`}function lt(e){return e.index?e.index.count:e.attributes.position.count}function ut(e){return lt(e)/3}function dt(e,t=ArrayBuffer){return e>65535?new Uint32Array(new t(4*e)):new Uint16Array(new t(2*e))}function ft(e,t){if(!e.index){let n=e.attributes.position.count,r=dt(n,t.useSharedArrayBuffer?SharedArrayBuffer:ArrayBuffer);e.setIndex(new D(r,1));for(let e=0;e<n;e++)r[e]=e}}function pt(e,t,n){let r=lt(e)/n,i=t||e.drawRange,a=i.start/n,o=(i.start+i.count)/n,s=Math.max(0,a),c=Math.min(r,o)-s;return{offset:Math.floor(s),count:Math.floor(c)}}function mt(e,t){return e.groups.map(e=>({offset:e.start/t,count:e.count/t}))}function ht(e,t,n){let r=pt(e,t,n),i=mt(e,n);if(!i.length)return[r];let a=[],o=r.offset,s=r.offset+r.count,c=lt(e)/n,l=[];for(let e of i){let{offset:t,count:n}=e,r=t,i=t+(isFinite(n)?n:c-t);r<s&&i>o&&(l.push({pos:Math.max(o,r),isStart:!0}),l.push({pos:Math.min(s,i),isStart:!1}))}l.sort((e,t)=>e.pos===t.pos?e.type===`end`?-1:1:e.pos-t.pos);let u=0,d=null;for(let e of l){let t=e.pos;u!==0&&t!==d&&a.push({offset:d,count:t-d}),u+=e.isStart?1:-1,d=t}return a}function gt(e,t){let n=e[e.length-1],r=n.offset+n.count>2**16,i=e.reduce((e,t)=>e+t.count,0),a=r?4:2,o=t?new SharedArrayBuffer(i*a):new ArrayBuffer(i*a),s=r?new Uint32Array(o):new Uint16Array(o),c=0;for(let t=0;t<e.length;t++){let{offset:n,count:r}=e[t];for(let e=0;e<r;e++)s[c+e]=n+e;c+=r}return s}var _t=class extends st{get indirect(){return!!this._indirectBuffer}get primitiveStride(){return null}get primitiveBufferStride(){return this.indirect?1:this.primitiveStride}set primitiveBufferStride(e){}get primitiveBuffer(){return this.indirect?this._indirectBuffer:this.geometry.index.array}set primitiveBuffer(e){}constructor(e,t={}){if(!e.isBufferGeometry)throw Error(`BVH: Only BufferGeometries are supported.`);if(e.index&&e.index.isInterleavedBufferAttribute)throw Error(`BVH: InterleavedBufferAttribute is not supported for the index attribute.`);if(t.useSharedArrayBuffer&&!ct())throw Error(`BVH: SharedArrayBuffer is not available.`);super(),this.geometry=e,this.resolvePrimitiveIndex=t.indirect?e=>this._indirectBuffer[e]:e=>e,this.primitiveBuffer=null,this.primitiveBufferStride=null,this._indirectBuffer=null,t={...be,...t},t[ye]||this.init(t)}init(e){let{geometry:t,primitiveStride:n}=this;if(e.indirect){let r=gt(ht(t,e.range,n),e.useSharedArrayBuffer);this._indirectBuffer=r}else ft(t,e);super.init(e),!t.boundingBox&&e.setBoundingBox&&(t.boundingBox=this.getBoundingBox(new O))}getRootRanges(e){return this.indirect?[{offset:0,count:this._indirectBuffer.length}]:ht(this.geometry,e,this.primitiveStride)}raycastObject3D(){throw Error(`BVH: raycastObject3D() not implemented`)}},H=class{constructor(){this.min=1/0,this.max=-1/0}setFromPointsField(e,t){let n=1/0,r=-1/0;for(let i=0,a=e.length;i<a;i++){let a=e[i][t];n=a<n?a:n,r=a>r?a:r}this.min=n,this.max=r}setFromPoints(e,t){let n=1/0,r=-1/0;for(let i=0,a=t.length;i<a;i++){let a=t[i],o=e.dot(a);n=o<n?o:n,r=o>r?o:r}this.min=n,this.max=r}isSeparated(e){return this.min>e.max||e.min>this.max}};H.prototype.setFromBox=(function(){let e=new T;return function(t,n){let r=n.min,i=n.max,a=1/0,o=-1/0;for(let n=0;n<=1;n++)for(let s=0;s<=1;s++)for(let c=0;c<=1;c++){e.x=r.x*n+i.x*(1-n),e.y=r.y*s+i.y*(1-s),e.z=r.z*c+i.z*(1-c);let l=t.dot(e);a=Math.min(l,a),o=Math.max(l,o)}this.min=a,this.max=o}})(),(function(){let e=new H;return function(t,n){let r=t.points,i=t.satAxes,a=t.satBounds,o=n.points,s=n.satAxes,c=n.satBounds;for(let t=0;t<3;t++){let n=a[t],r=i[t];if(e.setFromPoints(r,o),n.isSeparated(e))return!1}for(let t=0;t<3;t++){let n=c[t],i=s[t];if(e.setFromPoints(i,r),n.isSeparated(e))return!1}}})();var vt=(function(){let e=new T,t=new T,n=new T;return function(r,i,a){let o=r.start,s=e,c=i.start,l=t;n.subVectors(o,c),e.subVectors(r.end,r.start),t.subVectors(i.end,i.start);let u=n.dot(l),d=l.dot(s),f=l.dot(l),p=n.dot(s),m=s.dot(s)*f-d*d,h,g;h=m===0?0:(u*d-p*f)/m,g=(u+h*d)/f,a.x=h,a.y=g}})(),yt=(function(){let e=new t,n=new T,r=new T;return function(t,i,a,o){vt(t,i,e);let s=e.x,c=e.y;if(s>=0&&s<=1&&c>=0&&c<=1){t.at(s,a),i.at(c,o);return}else if(s>=0&&s<=1){c<0?i.at(0,o):i.at(1,o),t.closestPointToPoint(o,!0,a);return}else if(c>=0&&c<=1){s<0?t.at(0,a):t.at(1,a),i.closestPointToPoint(a,!0,o);return}else{let e;e=s<0?t.start:t.end;let l;l=c<0?i.start:i.end;let u=n,d=r;if(t.closestPointToPoint(l,!0,n),i.closestPointToPoint(e,!0,r),u.distanceToSquared(l)<=d.distanceToSquared(e)){a.copy(u),o.copy(l);return}else{a.copy(e),o.copy(d);return}}}})(),bt=(function(){let e=new T,t=new T,n=new _,r=new C;return function(i,a){let{radius:o,center:s}=i,{a:c,b:l,c:u}=a;if(r.start=c,r.end=l,r.closestPointToPoint(s,!0,e).distanceTo(s)<=o||(r.start=c,r.end=u,r.closestPointToPoint(s,!0,e).distanceTo(s)<=o)||(r.start=l,r.end=u,r.closestPointToPoint(s,!0,e).distanceTo(s)<=o))return!0;let d=a.getPlane(n);if(Math.abs(d.distanceToPoint(s))<=o){let e=d.projectPoint(s,t);if(a.containsPoint(e))return!0}return!1}})(),xt=[`x`,`y`,`z`],U=1e-15,St=U*U;function W(e){return Math.abs(e)<U}var G=class extends c{constructor(...e){super(...e),this.isExtendedTriangle=!0,this.satAxes=[,,,,].fill().map(()=>new T),this.satBounds=[,,,,].fill().map(()=>new H),this.points=[this.a,this.b,this.c],this.plane=new _,this.isDegenerateIntoSegment=!1,this.isDegenerateIntoPoint=!1,this.degenerateSegment=new C,this.needsUpdate=!0}intersectsSphere(e){return bt(e,this)}update(){let e=this.a,t=this.b,n=this.c,r=this.points,i=this.satAxes,a=this.satBounds,o=i[0],s=a[0];this.getNormal(o),s.setFromPoints(o,r);let c=i[1],l=a[1];c.subVectors(e,t),l.setFromPoints(c,r);let u=i[2],d=a[2];u.subVectors(t,n),d.setFromPoints(u,r);let f=i[3],p=a[3];f.subVectors(n,e),p.setFromPoints(f,r);let m=c.length(),h=u.length(),g=f.length();this.isDegenerateIntoPoint=!1,this.isDegenerateIntoSegment=!1,m<U?h<U||g<U?this.isDegenerateIntoPoint=!0:(this.isDegenerateIntoSegment=!0,this.degenerateSegment.start.copy(e),this.degenerateSegment.end.copy(n)):h<U?g<U?this.isDegenerateIntoPoint=!0:(this.isDegenerateIntoSegment=!0,this.degenerateSegment.start.copy(t),this.degenerateSegment.end.copy(e)):g<U&&(this.isDegenerateIntoSegment=!0,this.degenerateSegment.start.copy(n),this.degenerateSegment.end.copy(t)),this.plane.setFromNormalAndCoplanarPoint(o,e),this.needsUpdate=!1}};G.prototype.closestPointToSegment=(function(){let e=new T,t=new T,n=new C;return function(r,i=null,a=null){let{start:o,end:s}=r,c=this.points,l,u=1/0;for(let o=0;o<3;o++){let s=(o+1)%3;n.start.copy(c[o]),n.end.copy(c[s]),yt(n,r,e,t),l=e.distanceToSquared(t),l<u&&(u=l,i&&i.copy(e),a&&a.copy(t))}return this.closestPointToPoint(o,e),l=o.distanceToSquared(e),l<u&&(u=l,i&&i.copy(e),a&&a.copy(o)),this.closestPointToPoint(s,e),l=s.distanceToSquared(e),l<u&&(u=l,i&&i.copy(e),a&&a.copy(s)),Math.sqrt(u)}})(),G.prototype.intersectsTriangle=(function(){let e=new G,n=new H,r=new H,i=new T,a=new T,o=new T,s=new T,c=new C,l=new C,u=new T,d=new t,f=new t;function p(e,t,a,o){let c=i;!e.isDegenerateIntoPoint&&!e.isDegenerateIntoSegment?c.copy(e.plane.normal):c.copy(t.plane.normal);let l=e.satBounds,u=e.satAxes;for(let i=1;i<4;i++){let a=l[i],o=u[i];if(n.setFromPoints(o,t.points),a.isSeparated(n)||(s.copy(c).cross(o),n.setFromPoints(s,e.points),r.setFromPoints(s,t.points),n.isSeparated(r)))return!1}let d=t.satBounds,f=t.satAxes;for(let i=1;i<4;i++){let a=d[i],o=f[i];if(n.setFromPoints(o,e.points),a.isSeparated(n)||(s.crossVectors(c,o),n.setFromPoints(s,e.points),r.setFromPoints(s,t.points),n.isSeparated(r)))return!1}return a&&(o||console.warn(`ExtendedTriangle.intersectsTriangle: Triangles are coplanar which does not support an output edge. Setting edge to 0, 0, 0.`),a.start.set(0,0,0),a.end.set(0,0,0)),!0}function m(e,t,n,r,i,a,o,s,c,l,u){let d=o/(o-s);l.x=r+(i-r)*d,u.start.subVectors(t,e).multiplyScalar(d).add(e),d=o/(o-c),l.y=r+(a-r)*d,u.end.subVectors(n,e).multiplyScalar(d).add(e)}function h(e,t,n,r,i,a,o,s,c,l,u){if(i>0)m(e.c,e.a,e.b,r,t,n,c,o,s,l,u);else if(a>0)m(e.b,e.a,e.c,n,t,r,s,o,c,l,u);else if(s*c>0||o!=0)m(e.a,e.b,e.c,t,n,r,o,s,c,l,u);else if(s!=0)m(e.b,e.a,e.c,n,t,r,s,o,c,l,u);else if(c!=0)m(e.c,e.a,e.b,r,t,n,c,o,s,l,u);else return!0;return!1}function g(e,t,n,r){let a=t.degenerateSegment,o=e.plane.distanceToPoint(a.start),s=e.plane.distanceToPoint(a.end);return W(o)?W(s)?p(e,t,n,r):(n&&(n.start.copy(a.start),n.end.copy(a.start)),e.containsPoint(a.start)):W(s)?(n&&(n.start.copy(a.end),n.end.copy(a.end)),e.containsPoint(a.end)):e.plane.intersectLine(a,i)==null?!1:(n&&(n.start.copy(i),n.end.copy(i)),e.containsPoint(i))}function _(e,t,n){let r=t.a;return W(e.plane.distanceToPoint(r))&&e.containsPoint(r)?(n&&(n.start.copy(r),n.end.copy(r)),!0):!1}function v(e,t,n){let r=e.degenerateSegment,a=t.a;return r.closestPointToPoint(a,!0,i),a.distanceToSquared(i)<St?(n&&(n.start.copy(a),n.end.copy(a)),!0):!1}function y(e,t,n,r){if(e.isDegenerateIntoSegment)if(t.isDegenerateIntoSegment){let r=e.degenerateSegment,s=t.degenerateSegment,c=a,l=o;r.delta(c),s.delta(l);let u=i.subVectors(s.start,r.start),d=c.x*l.y-c.y*l.x;if(W(d))return!1;let f=(u.x*l.y-u.y*l.x)/d,p=-(c.x*u.y-c.y*u.x)/d;return f<0||f>1||p<0||p>1?!1:W(r.start.z+c.z*f-(s.start.z+l.z*p))?(n&&(n.start.copy(r.start).addScaledVector(c,f),n.end.copy(r.start).addScaledVector(c,f)),!0):!1}else if(t.isDegenerateIntoPoint)return v(e,t,n);else return g(t,e,n,r);else if(e.isDegenerateIntoPoint)return t.isDegenerateIntoPoint?t.a.distanceToSquared(e.a)<St?(n&&(n.start.copy(e.a),n.end.copy(e.a)),!0):!1:t.isDegenerateIntoSegment?v(t,e,n):_(t,e,n);else if(t.isDegenerateIntoPoint)return _(e,t,n);else if(t.isDegenerateIntoSegment)return g(e,t,n,r)}return function(t,n=null,r=!1){this.needsUpdate&&this.update(),t.isExtendedTriangle?t.needsUpdate&&t.update():(e.copy(t),e.update(),t=e);let i=y(this,t,n,r);if(i!==void 0)return i;let s=this.plane,m=t.plane,g=m.distanceToPoint(this.a),_=m.distanceToPoint(this.b),v=m.distanceToPoint(this.c);W(g)&&(g=0),W(_)&&(_=0),W(v)&&(v=0);let b=g*_,x=g*v;if(b>0&&x>0)return!1;let S=s.distanceToPoint(t.a),C=s.distanceToPoint(t.b),w=s.distanceToPoint(t.c);W(S)&&(S=0),W(C)&&(C=0),W(w)&&(w=0);let T=S*C,ee=S*w;if(T>0&&ee>0)return!1;a.copy(s.normal),o.copy(m.normal);let E=a.cross(o),D=0,te=Math.abs(E.x),ne=Math.abs(E.y);ne>te&&(te=ne,D=1),Math.abs(E.z)>te&&(D=2);let O=xt[D],re=this.a[O],ie=this.b[O],ae=this.c[O],oe=t.a[O],se=t.b[O],ce=t.c[O];if(h(this,re,ie,ae,b,x,g,_,v,d,c)||h(t,oe,se,ce,T,ee,S,C,w,f,l))return p(this,t,n,r);if(d.y<d.x){let e=d.y;d.y=d.x,d.x=e,u.copy(c.start),c.start.copy(c.end),c.end.copy(u)}if(f.y<f.x){let e=f.y;f.y=f.x,f.x=e,u.copy(l.start),l.start.copy(l.end),l.end.copy(u)}return d.y<f.x||f.y<d.x?!1:(n&&(f.x>d.x?n.start.copy(l.start):n.start.copy(c.start),f.y<d.y?n.end.copy(l.end):n.end.copy(c.end)),!0)}})(),G.prototype.distanceToPoint=(function(){let e=new T;return function(t){return this.closestPointToPoint(t,e),t.distanceTo(e)}})(),G.prototype.distanceToTriangle=(function(){let e=new T,t=new T,n=[`a`,`b`,`c`],r=new C,i=new C;return function(a,o=null,s=null){let c=o||s?r:null;if(this.intersectsTriangle(a,c,!0))return(o||s)&&(o&&c.getCenter(o),s&&c.getCenter(s)),0;let l=1/0;for(let t=0;t<3;t++){let r,i=n[t],c=a[i];this.closestPointToPoint(c,e),r=c.distanceToSquared(e),r<l&&(l=r,o&&o.copy(e),s&&s.copy(c));let u=this[i];a.closestPointToPoint(u,e),r=u.distanceToSquared(e),r<l&&(l=r,o&&o.copy(u),s&&s.copy(e))}for(let c=0;c<3;c++){let u=n[c],d=n[(c+1)%3];r.set(this[u],this[d]);for(let c=0;c<3;c++){let u=n[c],d=n[(c+1)%3];i.set(a[u],a[d]),yt(r,i,e,t);let f=e.distanceToSquared(t);f<l&&(l=f,o&&o.copy(e),s&&s.copy(t))}}return Math.sqrt(l)}})();var K=class{constructor(e,t,n){this.isOrientedBox=!0,this.min=new T,this.max=new T,this.matrix=new k,this.invMatrix=new k,this.points=Array(8).fill().map(()=>new T),this.satAxes=[,,,].fill().map(()=>new T),this.satBounds=[,,,].fill().map(()=>new H),this.alignedSatBounds=[,,,].fill().map(()=>new H),this.needsUpdate=!1,e&&this.min.copy(e),t&&this.max.copy(t),n&&this.matrix.copy(n)}set(e,t,n){this.min.copy(e),this.max.copy(t),this.matrix.copy(n),this.needsUpdate=!0}copy(e){this.min.copy(e.min),this.max.copy(e.max),this.matrix.copy(e.matrix),this.needsUpdate=!0}};K.prototype.update=(function(){return function(){let e=this.matrix,t=this.min,n=this.max,r=this.points;for(let i=0;i<=1;i++)for(let a=0;a<=1;a++)for(let o=0;o<=1;o++){let s=r[1*i|2*a|4*o];s.x=i?n.x:t.x,s.y=a?n.y:t.y,s.z=o?n.z:t.z,s.applyMatrix4(e)}let i=this.satBounds,a=this.satAxes,o=r[0];for(let e=0;e<3;e++){let t=a[e],n=i[e],s=r[1<<e];t.subVectors(o,s),n.setFromPoints(t,r)}let s=this.alignedSatBounds;s[0].setFromPointsField(r,`x`),s[1].setFromPointsField(r,`y`),s[2].setFromPointsField(r,`z`),this.invMatrix.copy(this.matrix).invert(),this.needsUpdate=!1}})(),K.prototype.intersectsBox=(function(){let e=new H;return function(t){this.needsUpdate&&this.update();let n=t.min,r=t.max,i=this.satBounds,a=this.satAxes,o=this.alignedSatBounds;if(e.min=n.x,e.max=r.x,o[0].isSeparated(e)||(e.min=n.y,e.max=r.y,o[1].isSeparated(e))||(e.min=n.z,e.max=r.z,o[2].isSeparated(e)))return!1;for(let n=0;n<3;n++){let r=a[n],o=i[n];if(e.setFromBox(r,t),o.isSeparated(e))return!1}return!0}})(),K.prototype.intersectsTriangle=(function(){let e=new G,t=[,,,],n=new H,r=new H,i=new T;return function(a){this.needsUpdate&&this.update(),a.isExtendedTriangle?a.needsUpdate&&a.update():(e.copy(a),e.update(),a=e);let o=this.satBounds,s=this.satAxes;t[0]=a.a,t[1]=a.b,t[2]=a.c;for(let e=0;e<3;e++){let r=o[e],i=s[e];if(n.setFromPoints(i,t),r.isSeparated(n))return!1}let c=a.satBounds,l=a.satAxes,u=this.points;for(let e=0;e<3;e++){let t=c[e],r=l[e];if(n.setFromPoints(r,u),t.isSeparated(n))return!1}for(let e=0;e<3;e++){let a=s[e];for(let e=0;e<4;e++){let o=l[e];if(i.crossVectors(a,o),n.setFromPoints(i,t),r.setFromPoints(i,u),n.isSeparated(r))return!1}}return!0}})(),K.prototype.closestPointToPoint=(function(){return function(e,t){return this.needsUpdate&&this.update(),t.copy(e).applyMatrix4(this.invMatrix).clamp(this.min,this.max).applyMatrix4(this.matrix),t}})(),K.prototype.distanceToPoint=(function(){let e=new T;return function(t){return this.closestPointToPoint(t,e),t.distanceTo(e)}})(),K.prototype.distanceToBox=(function(){let e=[`x`,`y`,`z`],t=Array(12).fill().map(()=>new C),n=Array(12).fill().map(()=>new C),r=new T,i=new T;return function(a,o=0,s=null,c=null){if(this.needsUpdate&&this.update(),this.intersectsBox(a))return(s||c)&&(a.getCenter(i),this.closestPointToPoint(i,r),a.closestPointToPoint(r,i),s&&s.copy(r),c&&c.copy(i)),0;let l=o*o,u=a.min,d=a.max,f=this.points,p=1/0;for(let e=0;e<8;e++){let t=f[e];i.copy(t).clamp(u,d);let n=t.distanceToSquared(i);if(n<p&&(p=n,s&&s.copy(t),c&&c.copy(i),n<l))return Math.sqrt(n)}let m=0;for(let r=0;r<3;r++)for(let i=0;i<=1;i++)for(let a=0;a<=1;a++){let o=(r+1)%3,s=(r+2)%3,c=i<<o|a<<s,l=1<<r|i<<o|a<<s,p=f[c],h=f[l];t[m].set(p,h);let g=e[r],_=e[o],v=e[s],y=n[m],b=y.start,x=y.end;b[g]=u[g],b[_]=i?u[_]:d[_],b[v]=a?u[v]:d[_],x[g]=d[g],x[_]=i?u[_]:d[_],x[v]=a?u[v]:d[_],m++}for(let e=0;e<=1;e++)for(let t=0;t<=1;t++)for(let n=0;n<=1;n++){i.x=e?d.x:u.x,i.y=t?d.y:u.y,i.z=n?d.z:u.z,this.closestPointToPoint(i,r);let a=i.distanceToSquared(r);if(a<p&&(p=a,s&&s.copy(r),c&&c.copy(i),a<l))return Math.sqrt(a)}for(let e=0;e<12;e++){let a=t[e];for(let e=0;e<12;e++){let t=n[e];yt(a,t,r,i);let o=r.distanceToSquared(i);if(o<p&&(p=o,s&&s.copy(r),c&&c.copy(i),o<l))return Math.sqrt(o)}}return Math.sqrt(p)}})();var q=new class extends We{constructor(){super(()=>new G)}},Ct=new T,wt=new T;function Tt(e,t,n={},r=0,i=1/0){let a=r*r,o=i*i,s=1/0,c=null;if(e.shapecast({boundsTraverseOrder:e=>(Ct.copy(t).clamp(e.min,e.max),Ct.distanceToSquared(t)),intersectsBounds:(e,t,n)=>n<s&&n<o,intersectsTriangle:(e,n)=>{e.closestPointToPoint(t,Ct);let r=t.distanceToSquared(Ct);return r<s&&(wt.copy(Ct),s=r,c=n),r<a}}),s===1/0)return null;let l=Math.sqrt(s);return n.point?n.point.copy(wt):n.point=wt.clone(),n.distance=l,n.faceIndex=c,n}var Et=!0,Dt=new T,Ot=new T,kt=new T,At=new t,jt=new t,Mt=new t,Nt=new T,Pt=new T,Ft=new T,It=new T;function Lt(e,t,n,r,i,a,o,s){let c;if(c=a===1?e.intersectTriangle(r,n,t,!0,i):e.intersectTriangle(t,n,r,a!==2,i),c===null)return null;let l=e.origin.distanceTo(i);return l<o||l>s?null:{distance:l,point:i.clone()}}function Rt(e,n,r,i,a,o,s,l,u,d,f){Dt.fromBufferAttribute(n,o),Ot.fromBufferAttribute(n,s),kt.fromBufferAttribute(n,l);let p=Lt(e,Dt,Ot,kt,It,u,d,f);if(p){if(i){At.fromBufferAttribute(i,o),jt.fromBufferAttribute(i,s),Mt.fromBufferAttribute(i,l),p.uv=new t;let e=c.getInterpolation(It,Dt,Ot,kt,At,jt,Mt,p.uv);Et||(p.uv=e)}if(a){At.fromBufferAttribute(a,o),jt.fromBufferAttribute(a,s),Mt.fromBufferAttribute(a,l),p.uv1=new t;let e=c.getInterpolation(It,Dt,Ot,kt,At,jt,Mt,p.uv1);Et||(p.uv1=e)}if(r){Nt.fromBufferAttribute(r,o),Pt.fromBufferAttribute(r,s),Ft.fromBufferAttribute(r,l),p.normal=new T;let t=c.getInterpolation(It,Dt,Ot,kt,Nt,Pt,Ft,p.normal);p.normal.dot(e.direction)>0&&p.normal.multiplyScalar(-1),Et||(p.normal=t)}let n={a:o,b:s,c:l,normal:new T,materialIndex:0};if(c.getNormal(Dt,Ot,kt,n.normal),p.face=n,p.faceIndex=o,Et){let e=new T;c.getBarycoord(It,Dt,Ot,kt,e),p.barycoord=e}}return p}function zt(e){return e&&e.isMaterial?e.side:e}function Bt(e,t,n,r,i,a,o){let s=r*3,c=s+0,l=s+1,u=s+2,{index:d,groups:f}=e;e.index&&(c=d.getX(c),l=d.getX(l),u=d.getX(u));let{position:p,normal:m,uv:h,uv1:g}=e.attributes;if(Array.isArray(t)){let e=r*3;for(let s=0,d=f.length;s<d;s++){let{start:d,count:_,materialIndex:v}=f[s];if(e>=d&&e<d+_){let e=zt(t[v]),s=Rt(n,p,m,h,g,c,l,u,e,a,o);if(s)if(s.faceIndex=r,s.face.materialIndex=v,i)i.push(s);else return s}}}else{let e=zt(t),s=Rt(n,p,m,h,g,c,l,u,e,a,o);if(s)if(s.faceIndex=r,s.face.materialIndex=0,i)i.push(s);else return s}return null}function J(e,t,n,r){let i=e.a,a=e.b,o=e.c,s=t,c=t+1,l=t+2;n&&(s=n.getX(s),c=n.getX(c),l=n.getX(l)),i.x=r.getX(s),i.y=r.getY(s),i.z=r.getZ(s),a.x=r.getX(c),a.y=r.getY(c),a.z=r.getZ(c),o.x=r.getX(l),o.y=r.getY(l),o.z=r.getZ(l)}var Vt=new T,Ht=new T,Ut=new T,Wt=new t,Gt=new t,Kt=new t;function qt(e,n,r,i){let a=n.getIndex().array,o=n.getAttribute(`position`),s=n.getAttribute(`uv`),l=a[r*3],u=a[r*3+1],d=a[r*3+2];Vt.fromBufferAttribute(o,l),Ht.fromBufferAttribute(o,u),Ut.fromBufferAttribute(o,d);let f=0,p=n.groups,m=r*3;for(let e=0,t=p.length;e<t;e++){let t=p[e],{start:n,count:r}=t;if(m>=n&&m<n+r){f=t.materialIndex;break}}let h=i&&i.barycoord?i.barycoord:new T;c.getBarycoord(e,Vt,Ht,Ut,h);let g=null;return s&&(Wt.fromBufferAttribute(s,l),Gt.fromBufferAttribute(s,u),Kt.fromBufferAttribute(s,d),g=i&&i.uv?i.uv:new t,c.getInterpolation(e,Vt,Ht,Ut,Wt,Gt,Kt,g)),i?(i.face||={},i.face.a=l,i.face.b=u,i.face.c=d,i.face.materialIndex=f,i.face.normal||(i.face.normal=new T),c.getNormal(Vt,Ht,Ut,i.face.normal),g&&(i.uv=g),i.barycoord=h,i):{face:{a:l,b:u,c:d,materialIndex:f,normal:c.getNormal(Vt,Ht,Ut,new T)},uv:g,barycoord:h}}function Jt(e,t,n,r,i,a,o,s){let{geometry:c,_indirectBuffer:l}=e;for(let e=r,l=r+i;e<l;e++)Bt(c,t,n,e,a,o,s)}function Yt(e,t,n,r,i,a,o){let{geometry:s,_indirectBuffer:c}=e,l=1/0,u=null;for(let e=r,c=r+i;e<c;e++){let r;r=Bt(s,t,n,e,null,a,o),r&&r.distance<l&&(u=r,l=r.distance)}return u}function Xt(e,t,n,r,i,a,o){let{geometry:s}=n,{index:c}=s,l=s.attributes.position;for(let n=e,s=t+e;n<s;n++){let e;if(e=n,J(o,e*3,c,l),o.needsUpdate=!0,r(o,e,i,a))return!0}return!1}function Zt(e,t=null){t&&Array.isArray(t)&&(t=new Set(t));let n=e.geometry,r=n.index?n.index.array:null,i=n.attributes.position,a,o,s,c,l=0,u=e._roots;for(let e=0,t=u.length;e<t;e++)a=u[e],o=new Uint32Array(a),s=new Uint16Array(a),c=new Float32Array(a),d(0,l),l+=a.byteLength;function d(e,n,a=!1){let l=e*2;if(j(l,s)){let t=M(e,o),n=N(l,s),a=1/0,u=1/0,d=1/0,f=-1/0,p=-1/0,m=-1/0;for(let e=3*t,o=3*(t+n);e<o;e++){let t=r[e],n=i.getX(t),o=i.getY(t),s=i.getZ(t);n<a&&(a=n),n>f&&(f=n),o<u&&(u=o),o>p&&(p=o),s<d&&(d=s),s>m&&(m=s)}return c[e+0]!==a||c[e+1]!==u||c[e+2]!==d||c[e+3]!==f||c[e+4]!==p||c[e+5]!==m?(c[e+0]=a,c[e+1]=u,c[e+2]=d,c[e+3]=f,c[e+4]=p,c[e+5]=m,!0):!1}else{let r=P(e),i=F(e,o),s=a,l=!1,u=!1;if(t){if(!s){let e=r/8+n/32,a=i/8+n/32;l=t.has(e),u=t.has(a),s=!l&&!u}}else l=!0,u=!0;let f=s||l,p=s||u,m=!1;f&&(m=d(r,n,s));let h=!1;p&&(h=d(i,n,s));let g=m||h;if(g)for(let t=0;t<3;t++){let n=r+t,a=i+t,o=c[n],s=c[n+3],l=c[a],u=c[a+3];c[e+t]=o<l?o:l,c[e+t+3]=s>u?s:u}return g}}}function Qt(e,t,n,r,i){let a,o,s,c,l,u,d=1/n.direction.x,f=1/n.direction.y,p=1/n.direction.z,m=n.origin.x,h=n.origin.y,g=n.origin.z,_=t[e],v=t[e+3],y=t[e+1],b=t[e+3+1],x=t[e+2],S=t[e+3+2];return d>=0?(a=(_-m)*d,o=(v-m)*d):(a=(v-m)*d,o=(_-m)*d),f>=0?(s=(y-h)*f,c=(b-h)*f):(s=(b-h)*f,c=(y-h)*f),a>c||s>o||((s>a||isNaN(a))&&(a=s),(c<o||isNaN(o))&&(o=c),p>=0?(l=(x-g)*p,u=(S-g)*p):(l=(S-g)*p,u=(x-g)*p),a>u||l>o)?!1:((l>a||a!==a)&&(a=l),(u<o||o!==o)&&(o=u),a<=i&&o>=r)}function $t(e,t,n,r,i,a,o,s){let{geometry:c,_indirectBuffer:l}=e;for(let e=r,u=r+i;e<u;e++)Bt(c,t,n,l?l[e]:e,a,o,s)}function en(e,t,n,r,i,a,o){let{geometry:s,_indirectBuffer:c}=e,l=1/0,u=null;for(let e=r,d=r+i;e<d;e++){let r;r=Bt(s,t,n,c?c[e]:e,null,a,o),r&&r.distance<l&&(u=r,l=r.distance)}return u}function tn(e,t,n,r,i,a,o){let{geometry:s}=n,{index:c}=s,l=s.attributes.position;for(let s=e,u=t+e;s<u;s++){let e;if(e=n.resolveTriangleIndex(s),J(o,e*3,c,l),o.needsUpdate=!0,r(o,e,i,a))return!0}return!1}function nn(e,t,n,r,i,a,o){z.setBuffer(e._roots[t]),rn(0,e,n,r,i,a,o),z.clearBuffer()}function rn(e,t,n,r,i,a,o){let{float32Array:s,uint16Array:c,uint32Array:l}=z,u=e*2;if(j(u,c))Jt(t,n,r,M(e,l),N(u,c),i,a,o);else{let c=P(e);Qt(c,s,r,a,o)&&rn(c,t,n,r,i,a,o);let u=F(e,l);Qt(u,s,r,a,o)&&rn(u,t,n,r,i,a,o)}}var an=[`x`,`y`,`z`];function on(e,t,n,r,i,a){z.setBuffer(e._roots[t]);let o=sn(0,e,n,r,i,a);return z.clearBuffer(),o}function sn(e,t,n,r,i,a){let{float32Array:o,uint16Array:s,uint32Array:c}=z,l=e*2;if(j(l,s))return Yt(t,n,r,M(e,c),N(l,s),i,a);{let s=Ee(e,c),l=an[s],u=r.direction[l]>=0,d,f;u?(d=P(e),f=F(e,c)):(d=F(e,c),f=P(e));let p=Qt(d,o,r,i,a)?sn(d,t,n,r,i,a):null;if(p){let e=p.point[l];if(u?e<=o[f+s]:e>=o[f+s+3])return p}let m=Qt(f,o,r,i,a)?sn(f,t,n,r,i,a):null;return p&&m?p.distance<=m.distance?p:m:p||m||null}}var cn=new O,ln=new G,un=new G,dn=new k,fn=new K,pn=new K;function mn(e,t,n,r){z.setBuffer(e._roots[t]);let i=hn(0,e,n,r);return z.clearBuffer(),i}function hn(e,t,n,r,i=null){let{float32Array:a,uint16Array:o,uint32Array:s}=z,c=e*2;if(i===null&&(n.boundingBox||n.computeBoundingBox(),fn.set(n.boundingBox.min,n.boundingBox.max,r),i=fn),j(c,o)){let i=t.geometry,l=i.index,u=i.attributes.position,d=n.index,f=n.attributes.position,p=M(e,s),m=N(c,o);if(dn.copy(r).invert(),n.boundsTree)return A(I(e),a,pn),pn.matrix.copy(dn),pn.needsUpdate=!0,n.boundsTree.shapecast({intersectsBounds:e=>pn.intersectsBox(e),intersectsTriangle:e=>{e.a.applyMatrix4(r),e.b.applyMatrix4(r),e.c.applyMatrix4(r),e.needsUpdate=!0;for(let t=p*3,n=(m+p)*3;t<n;t+=3)if(J(un,t,l,u),un.needsUpdate=!0,e.intersectsTriangle(un))return!0;return!1}});{let e=ut(n);for(let t=p*3,n=(m+p)*3;t<n;t+=3){J(ln,t,l,u),ln.a.applyMatrix4(dn),ln.b.applyMatrix4(dn),ln.c.applyMatrix4(dn),ln.needsUpdate=!0;for(let t=0,n=e*3;t<n;t+=3)if(J(un,t,d,f),un.needsUpdate=!0,ln.intersectsTriangle(un))return!0}}}else{let o=P(e),c=F(e,s);return A(I(o),a,cn),!!(i.intersectsBox(cn)&&hn(o,t,n,r,i)||(A(I(c),a,cn),i.intersectsBox(cn)&&hn(c,t,n,r,i)))}}var gn=new k,_n=new K,vn=new K,yn=new T,bn=new T,xn=new T,Sn=new T;function Cn(e,t,n,r={},i={},a=0,o=1/0){t.boundingBox||t.computeBoundingBox(),_n.set(t.boundingBox.min,t.boundingBox.max,n),_n.needsUpdate=!0;let s=e.geometry,c=s.attributes.position,l=s.index,u=t.attributes.position,d=t.index,f=q.getPrimitive(),p=q.getPrimitive(),m=yn,h=bn,g=null,_=null;i&&(g=xn,_=Sn);let v=1/0,y=null,b=null;return gn.copy(n).invert(),vn.matrix.copy(gn),e.shapecast({boundsTraverseOrder:e=>_n.distanceToBox(e),intersectsBounds:(e,t,n)=>n<v&&n<o?(t&&(vn.min.copy(e.min),vn.max.copy(e.max),vn.needsUpdate=!0),!0):!1,intersectsRange:(e,r)=>{if(t.boundsTree)return t.boundsTree.shapecast({boundsTraverseOrder:e=>vn.distanceToBox(e),intersectsBounds:(e,t,n)=>n<v&&n<o,intersectsRange:(t,i)=>{for(let o=t,s=t+i;o<s;o++){J(p,3*o,d,u),p.a.applyMatrix4(n),p.b.applyMatrix4(n),p.c.applyMatrix4(n),p.needsUpdate=!0;for(let t=e,n=e+r;t<n;t++){J(f,3*t,l,c),f.needsUpdate=!0;let e=f.distanceToTriangle(p,m,g);if(e<v&&(h.copy(m),_&&_.copy(g),v=e,y=t,b=o),e<a)return!0}}}});{let i=ut(t);for(let t=0,o=i;t<o;t++){J(p,3*t,d,u),p.a.applyMatrix4(n),p.b.applyMatrix4(n),p.c.applyMatrix4(n),p.needsUpdate=!0;for(let n=e,i=e+r;n<i;n++){J(f,3*n,l,c),f.needsUpdate=!0;let e=f.distanceToTriangle(p,m,g);if(e<v&&(h.copy(m),_&&_.copy(g),v=e,y=n,b=t),e<a)return!0}}}}}),q.releasePrimitive(f),q.releasePrimitive(p),v===1/0?null:(r.point?r.point.copy(h):r.point=h.clone(),r.distance=v,r.faceIndex=y,i&&(i.point?i.point.copy(_):i.point=_.clone(),i.point.applyMatrix4(gn),h.applyMatrix4(gn),i.distance=h.sub(i.point).length(),i.faceIndex=b),r)}function wn(e,t=null){t&&Array.isArray(t)&&(t=new Set(t));let n=e.geometry,r=n.index?n.index.array:null,i=n.attributes.position,a,o,s,c,l=0,u=e._roots;for(let e=0,t=u.length;e<t;e++)a=u[e],o=new Uint32Array(a),s=new Uint16Array(a),c=new Float32Array(a),d(0,l),l+=a.byteLength;function d(n,a,l=!1){let u=n*2;if(j(u,s)){let t=M(n,o),a=N(u,s),l=1/0,d=1/0,f=1/0,p=-1/0,m=-1/0,h=-1/0;for(let n=t,o=t+a;n<o;n++){let t=3*e.resolveTriangleIndex(n);for(let e=0;e<3;e++){let n=t+e;n=r?r[n]:n;let a=i.getX(n),o=i.getY(n),s=i.getZ(n);a<l&&(l=a),a>p&&(p=a),o<d&&(d=o),o>m&&(m=o),s<f&&(f=s),s>h&&(h=s)}}return c[n+0]!==l||c[n+1]!==d||c[n+2]!==f||c[n+3]!==p||c[n+4]!==m||c[n+5]!==h?(c[n+0]=l,c[n+1]=d,c[n+2]=f,c[n+3]=p,c[n+4]=m,c[n+5]=h,!0):!1}else{let e=P(n),r=F(n,o),i=l,s=!1,u=!1;if(t){if(!i){let n=e/8+a/32,o=r/8+a/32;s=t.has(n),u=t.has(o),i=!s&&!u}}else s=!0,u=!0;let f=i||s,p=i||u,m=!1;f&&(m=d(e,a,i));let h=!1;p&&(h=d(r,a,i));let g=m||h;if(g)for(let t=0;t<3;t++){let i=e+t,a=r+t,o=c[i],s=c[i+3],l=c[a],u=c[a+3];c[n+t]=o<l?o:l,c[n+t+3]=s>u?s:u}return g}}}function Tn(e,t,n,r,i,a,o){z.setBuffer(e._roots[t]),En(0,e,n,r,i,a,o),z.clearBuffer()}function En(e,t,n,r,i,a,o){let{float32Array:s,uint16Array:c,uint32Array:l}=z,u=e*2;if(j(u,c))$t(t,n,r,M(e,l),N(u,c),i,a,o);else{let c=P(e);Qt(c,s,r,a,o)&&En(c,t,n,r,i,a,o);let u=F(e,l);Qt(u,s,r,a,o)&&En(u,t,n,r,i,a,o)}}var Dn=[`x`,`y`,`z`];function On(e,t,n,r,i,a){z.setBuffer(e._roots[t]);let o=kn(0,e,n,r,i,a);return z.clearBuffer(),o}function kn(e,t,n,r,i,a){let{float32Array:o,uint16Array:s,uint32Array:c}=z,l=e*2;if(j(l,s))return en(t,n,r,M(e,c),N(l,s),i,a);{let s=Ee(e,c),l=Dn[s],u=r.direction[l]>=0,d,f;u?(d=P(e),f=F(e,c)):(d=F(e,c),f=P(e));let p=Qt(d,o,r,i,a)?kn(d,t,n,r,i,a):null;if(p){let e=p.point[l];if(u?e<=o[f+s]:e>=o[f+s+3])return p}let m=Qt(f,o,r,i,a)?kn(f,t,n,r,i,a):null;return p&&m?p.distance<=m.distance?p:m:p||m||null}}var An=new O,jn=new G,Mn=new G,Nn=new k,Pn=new K,Fn=new K;function In(e,t,n,r){z.setBuffer(e._roots[t]);let i=Ln(0,e,n,r);return z.clearBuffer(),i}function Ln(e,t,n,r,i=null){let{float32Array:a,uint16Array:o,uint32Array:s}=z,c=e*2;if(i===null&&(n.boundingBox||n.computeBoundingBox(),Pn.set(n.boundingBox.min,n.boundingBox.max,r),i=Pn),j(c,o)){let i=t.geometry,l=i.index,u=i.attributes.position,d=n.index,f=n.attributes.position,p=M(e,s),m=N(c,o);if(Nn.copy(r).invert(),n.boundsTree)return A(I(e),a,Fn),Fn.matrix.copy(Nn),Fn.needsUpdate=!0,n.boundsTree.shapecast({intersectsBounds:e=>Fn.intersectsBox(e),intersectsTriangle:e=>{e.a.applyMatrix4(r),e.b.applyMatrix4(r),e.c.applyMatrix4(r),e.needsUpdate=!0;for(let n=p,r=m+p;n<r;n++)if(J(Mn,3*t.resolveTriangleIndex(n),l,u),Mn.needsUpdate=!0,e.intersectsTriangle(Mn))return!0;return!1}});{let e=ut(n);for(let n=p,r=m+p;n<r;n++){J(jn,3*t.resolveTriangleIndex(n),l,u),jn.a.applyMatrix4(Nn),jn.b.applyMatrix4(Nn),jn.c.applyMatrix4(Nn),jn.needsUpdate=!0;for(let t=0,n=e*3;t<n;t+=3)if(J(Mn,t,d,f),Mn.needsUpdate=!0,jn.intersectsTriangle(Mn))return!0}}}else{let o=P(e),c=F(e,s);return A(I(o),a,An),!!(i.intersectsBox(An)&&Ln(o,t,n,r,i)||(A(I(c),a,An),i.intersectsBox(An)&&Ln(c,t,n,r,i)))}}var Rn=new k,zn=new K,Bn=new K,Vn=new T,Hn=new T,Un=new T,Wn=new T;function Gn(e,t,n,r={},i={},a=0,o=1/0){t.boundingBox||t.computeBoundingBox(),zn.set(t.boundingBox.min,t.boundingBox.max,n),zn.needsUpdate=!0;let s=e.geometry,c=s.attributes.position,l=s.index,u=t.attributes.position,d=t.index,f=q.getPrimitive(),p=q.getPrimitive(),m=Vn,h=Hn,g=null,_=null;i&&(g=Un,_=Wn);let v=1/0,y=null,b=null;return Rn.copy(n).invert(),Bn.matrix.copy(Rn),e.shapecast({boundsTraverseOrder:e=>zn.distanceToBox(e),intersectsBounds:(e,t,n)=>n<v&&n<o?(t&&(Bn.min.copy(e.min),Bn.max.copy(e.max),Bn.needsUpdate=!0),!0):!1,intersectsRange:(r,i)=>{if(t.boundsTree){let s=t.boundsTree;return s.shapecast({boundsTraverseOrder:e=>Bn.distanceToBox(e),intersectsBounds:(e,t,n)=>n<v&&n<o,intersectsRange:(t,o)=>{for(let x=t,S=t+o;x<S;x++){let t=s.resolveTriangleIndex(x);J(p,3*t,d,u),p.a.applyMatrix4(n),p.b.applyMatrix4(n),p.c.applyMatrix4(n),p.needsUpdate=!0;for(let t=r,n=r+i;t<n;t++){let n=e.resolveTriangleIndex(t);J(f,3*n,l,c),f.needsUpdate=!0;let r=f.distanceToTriangle(p,m,g);if(r<v&&(h.copy(m),_&&_.copy(g),v=r,y=t,b=x),r<a)return!0}}}})}else{let o=ut(t);for(let t=0,s=o;t<s;t++){J(p,3*t,d,u),p.a.applyMatrix4(n),p.b.applyMatrix4(n),p.c.applyMatrix4(n),p.needsUpdate=!0;for(let n=r,o=r+i;n<o;n++){let r=e.resolveTriangleIndex(n);J(f,3*r,l,c),f.needsUpdate=!0;let i=f.distanceToTriangle(p,m,g);if(i<v&&(h.copy(m),_&&_.copy(g),v=i,y=n,b=t),i<a)return!0}}}}}),q.releasePrimitive(f),q.releasePrimitive(p),v===1/0?null:(r.point?r.point.copy(h):r.point=h.clone(),r.distance=v,r.faceIndex=y,i&&(i.point?i.point.copy(_):i.point=_.clone(),i.point.applyMatrix4(Rn),h.applyMatrix4(Rn),i.distance=h.sub(i.point).length(),i.faceIndex=b),r)}function Kn(e,t,n){return e===null?null:(e.point.applyMatrix4(t.matrixWorld),e.distance=e.point.distanceTo(n.ray.origin),e.object=t,e)}var qn=new K,Jn=new S,Yn=new T,Xn=new k,Zn=new T,Qn=[`getX`,`getY`,`getZ`],$n=class e extends _t{static serialize(e,t={}){t={cloneBuffers:!0,...t};let n=e.geometry,r=e._roots,i=e._indirectBuffer,a=n.getIndex(),o={version:1,roots:null,index:null,indirectBuffer:null};return t.cloneBuffers?(o.roots=r.map(e=>e.slice()),o.index=a?a.array.slice():null,o.indirectBuffer=i?i.slice():null):(o.roots=r,o.index=a?a.array:null,o.indirectBuffer=i),o}static deserialize(t,n,r={}){r={setIndex:!0,indirect:!!t.indirectBuffer,...r};let{index:i,roots:a,indirectBuffer:o}=t;t.version||(console.warn(`MeshBVH.deserialize: Serialization format has been changed and will be fixed up. It is recommended to regenerate any stored serialized data.`),c(a));let s=new e(n,{...r,[ye]:!0});if(s._roots=a,s._indirectBuffer=o||null,r.setIndex){let e=n.getIndex();if(e===null){let e=new D(t.index,1,!1);n.setIndex(e)}else e.array!==i&&(e.array.set(i),e.needsUpdate=!0)}return s;function c(e){for(let t=0;t<e.length;t++){let n=e[t],r=new Uint32Array(n),i=new Uint16Array(n);for(let e=0,t=n.byteLength/32;e<t;e++){let t=8*e;j(2*t,i)||(r[t+6]=r[t+6]/8-e)}}}}get primitiveStride(){return 3}get resolveTriangleIndex(){return this.resolvePrimitiveIndex}constructor(e,t={}){t.maxLeafTris&&(console.warn(`MeshBVH: "maxLeafTris" option has been deprecated. Use maxLeafSize, instead.`),t={...t,maxLeafSize:t.maxLeafTris}),super(e,t)}shiftTriangleOffsets(e){return super.shiftPrimitiveOffsets(e)}writePrimitiveBounds(e,t,n){let r=this.geometry,i=this._indirectBuffer,a=r.attributes.position,o=r.index?r.index.array:null,s=(i?i[e]:e)*3,c=s+0,l=s+1,u=s+2;o&&(c=o[c],l=o[l],u=o[u]);for(let e=0;e<3;e++){let r=a[Qn[e]](c),i=a[Qn[e]](l),o=a[Qn[e]](u),s=r;i<s&&(s=i),o<s&&(s=o);let d=r;i>d&&(d=i),o>d&&(d=o),t[n+e]=s,t[n+e+3]=d}return t}computePrimitiveBounds(e,t,n){let r=this.geometry,i=this._indirectBuffer,a=r.attributes.position,o=r.index?r.index.array:null,s=a.normalized;if(e<0||t+e-n.offset>n.length/6)throw Error(`MeshBVH: compute triangle bounds range is invalid.`);let c=a.array,l=a.offset||0,u=3;a.isInterleavedBufferAttribute&&(u=a.data.stride);let d=[`getX`,`getY`,`getZ`],f=n.offset;for(let r=e,p=e+t;r<p;r++){let e=(i?i[r]:r)*3,t=(r-f)*6,p=e+0,m=e+1,h=e+2;o&&(p=o[p],m=o[m],h=o[h]),s||(p=p*u+l,m=m*u+l,h=h*u+l);for(let e=0;e<3;e++){let r,i,o;s?(r=a[d[e]](p),i=a[d[e]](m),o=a[d[e]](h)):(r=c[p+e],i=c[m+e],o=c[h+e]);let l=r;i<l&&(l=i),o<l&&(l=o);let u=r;i>u&&(u=i),o>u&&(u=o);let f=(u-l)/2,g=e*2;n[t+g+0]=l+f,n[t+g+1]=f+(Math.abs(l)+f)*ve}}return n}raycastObject3D(e,t,n=[]){let{material:r}=e;if(r===void 0)return;Xn.copy(e.matrixWorld).invert(),Jn.copy(t.ray).applyMatrix4(Xn),Zn.setFromMatrixScale(e.matrixWorld),Yn.copy(Jn.direction).multiply(Zn);let i=Yn.length(),a=t.near/i,o=t.far/i;if(t.firstHitOnly===!0){let i=this.raycastFirst(Jn,r,a,o);i=Kn(i,e,t),i&&n.push(i)}else{let i=this.raycast(Jn,r,a,o);for(let r=0,a=i.length;r<a;r++){let a=Kn(i[r],e,t);a&&n.push(a)}}return n}refit(e=null){return(this.indirect?wn:Zt)(this,e)}raycast(e,t=0,n=0,r=1/0){let i=this._roots,a=[],o=this.indirect?Tn:nn;for(let s=0,c=i.length;s<c;s++)o(this,s,t,e,a,n,r);return a}raycastFirst(e,t=0,n=0,r=1/0){let i=this._roots,a=null,o=this.indirect?On:on;for(let s=0,c=i.length;s<c;s++){let i=o(this,s,t,e,n,r);i!=null&&(a==null||i.distance<a.distance)&&(a=i)}return a}intersectsGeometry(e,t){let n=!1,r=this._roots,i=this.indirect?In:mn;for(let a=0,o=r.length;a<o&&(n=i(this,a,e,t),!n);a++);return n}shapecast(e){let t=q.getPrimitive(),n=super.shapecast({...e,intersectsPrimitive:e.intersectsTriangle,scratchPrimitive:t,iterate:this.indirect?tn:Xt});return q.releasePrimitive(t),n}bvhcast(t,n,r){let{intersectsRanges:i,intersectsTriangles:a}=r,o=q.getPrimitive(),s=this.geometry.index,c=this.geometry.attributes.position,l=this.indirect?e=>{let t=this.resolveTriangleIndex(e);J(o,t*3,s,c)}:e=>{J(o,e*3,s,c)},u=q.getPrimitive(),d=t.geometry.index,f=t.geometry.attributes.position,p=t.indirect?e=>{let n=t.resolveTriangleIndex(e);J(u,n*3,d,f)}:e=>{J(u,e*3,d,f)};if(a){if(!(t instanceof e))throw Error(`MeshBVH: "intersectsTriangles" callback can only be used with another MeshBVH.`);let r=(e,t,r,i,s,c,d,f)=>{for(let m=r,h=r+i;m<h;m++){p(m),u.a.applyMatrix4(n),u.b.applyMatrix4(n),u.c.applyMatrix4(n),u.needsUpdate=!0;for(let n=e,r=e+t;n<r;n++)if(l(n),o.needsUpdate=!0,a(o,u,n,m,s,c,d,f))return!0}return!1};if(i){let e=i;i=function(t,n,i,a,o,s,c,l){return e(t,n,i,a,o,s,c,l)?!0:r(t,n,i,a,o,s,c,l)}}else i=r}return super.bvhcast(t,n,{intersectsRanges:i})}intersectsBox(e,t){return qn.set(e.min,e.max,t),qn.needsUpdate=!0,this.shapecast({intersectsBounds:e=>qn.intersectsBox(e),intersectsTriangle:e=>qn.intersectsTriangle(e)})}intersectsSphere(e){return this.shapecast({intersectsBounds:t=>e.intersectsBox(t),intersectsTriangle:t=>t.intersectsSphere(e)})}closestPointToGeometry(e,t,n={},r={},i=0,a=1/0){return(this.indirect?Gn:Cn)(this,e,t,n,r,i,a)}closestPointToPoint(e,t={},n=0,r=1/0){return Tt(this,e,t,n,r)}},er=new k,tr=new S,nr=new We(()=>new C),rr=new T,ir=new T,ar=new O,or=[`getX`,`getY`,`getZ`],sr=class extends _t{get primitiveStride(){return 2}writePrimitiveBounds(e,t,n){let r=this._indirectBuffer,{geometry:i,primitiveStride:a}=this,o=i.attributes.position,s=i.index,c=s?s.count:o.count,l=(r?r[e]:e)*a,u=(l+1)%c;s&&(l=s.getX(l),u=s.getX(u));for(let e=0;e<3;e++){let r=o[or[e]](l),i=o[or[e]](u),a=r<i?r:i,s=r>i?r:i;t[n+e]=a,t[n+e+3]=s}return t}shapecast(e){let t=nr.getPrimitive(),n=super.shapecast({...e,intersectsPrimitive:e.intersectsLine,scratchPrimitive:t,iterate:ur});return nr.releasePrimitive(t),n}raycastObject3D(e,t,n=[]){let{matrixWorld:r}=e,{firstHitOnly:i}=t;er.copy(r).invert(),tr.copy(t.ray).applyMatrix4(er);let a=t.params.Line.threshold/((e.scale.x+e.scale.y+e.scale.z)/3),o=a*a,s=null,c=1/0;return this.shapecast({boundsTraverseOrder:e=>e.distanceToPoint(tr.origin),intersectsBounds:e=>(ar.copy(e).expandByScalar(Math.abs(a)),+!!tr.intersectsBox(ar)),intersectsLine:(a,l)=>{if(tr.distanceSqToSegment(a.start,a.end,rr,ir)>o)return;rr.applyMatrix4(e.matrixWorld);let u=t.ray.origin.distanceTo(rr);u<t.near||u>t.far||i&&u>=c||(c=u,l=this.resolvePrimitiveIndex(l),s={distance:u,point:ir.clone().applyMatrix4(r),index:l*this.primitiveStride,face:null,faceIndex:null,barycoord:null,object:e},i||n.push(s))}}),i&&s&&n.push(s),n}},cr=class extends sr{get primitiveStride(){return 1}constructor(e,t={}){t={...t,indirect:!0},super(e,t)}},lr=class extends cr{getRootRanges(...e){let t=super.getRootRanges(...e);return t.forEach(e=>e.count--),t}};function ur(e,t,n,r,i,a,o){let{geometry:s,primitiveStride:c}=n,{index:l}=s,u=s.attributes.position,d=l?l.count:u.count;for(let s=e,f=t+e;s<f;s++){let e=n.resolvePrimitiveIndex(s)*c,t=(e+1)%d;if(l&&(e=l.getX(e),t=l.getX(t)),o.start.fromBufferAttribute(u,e),o.end.fromBufferAttribute(u,t),r(o,s,i,a))return!0}return!1}var dr=new k,fr=new S,pr=new We(()=>new T),mr=new O,hr=class extends _t{get primitiveStride(){return 1}writePrimitiveBounds(e,t,n){let r=this._indirectBuffer,{geometry:i}=this,a=i.attributes.position,o=i.index,s=r?r[e]:e;o&&(s=o.getX(s));let c=a.getX(s),l=a.getY(s),u=a.getZ(s);return t[n+0]=c,t[n+1]=l,t[n+2]=u,t[n+3]=c,t[n+4]=l,t[n+5]=u,t}shapecast(e){let t=pr.getPrimitive(),n=super.shapecast({...e,intersectsPrimitive:e.intersectsPoint,scratchPrimitive:t,iterate:gr});return pr.releasePrimitive(t),n}raycastObject3D(e,t,n=[]){let{geometry:r}=this,{matrixWorld:i}=e,{firstHitOnly:a}=t;dr.copy(i).invert(),fr.copy(t.ray).applyMatrix4(dr);let o=t.params.Points.threshold/((e.scale.x+e.scale.y+e.scale.z)/3),s=o*o,c=null,l=1/0;return this.shapecast({boundsTraverseOrder:e=>e.distanceToPoint(fr.origin),intersectsBounds:e=>(mr.copy(e).expandByScalar(Math.abs(o)),+!!fr.intersectsBox(mr)),intersectsPoint:(o,u)=>{let d=fr.distanceSqToPoint(o);if(d<s){let s=new T;fr.closestPointToPoint(o,s),s.applyMatrix4(i);let f=t.ray.origin.distanceTo(s);if(f<t.near||f>t.far||a&&f>=l)return;l=f,u=this.resolvePrimitiveIndex(u),c={distance:f,distanceToRay:Math.sqrt(d),point:s,index:r.index?r.index.getX(u):u,face:null,faceIndex:null,barycoord:null,object:e},a||n.push(c)}}}),a&&c&&n.push(c),n}};function gr(e,t,n,r,i,a,o){let{geometry:s}=n,{index:c}=s,l=s.attributes.position;for(let s=e,u=t+e;s<u;s++){let e=n.resolvePrimitiveIndex(s),t=c?c.array[e]:e;if(o.fromBufferAttribute(l,t),r(o,s,i,a))return!0}return!1}var Y=new E,X=new k,_r=new k,vr=new O,yr=new n,Z=new T,br=new S,Q=new h,xr={},Sr=class extends st{constructor(e,t={}){t={precise:!1,includeInstances:!0,matrixWorld:Array.isArray(e)?new k:e.matrixWorld,maxLeafSize:1,...t},super();let n=new Set;Er(e,n);let r=Array.from(n),i=Math.ceil(Math.log2(r.length)),a=Cr(i);this.objects=r,this.idBits=i,this.idMask=a,this.primitiveBuffer=null,this.primitiveBufferStride=1,this.precise=t.precise,this.includeInstances=t.includeInstances,this.matrixWorld=t.matrixWorld,this.init(t)}getObjectFromId(e){let{idMask:t,objects:n}=this;return n[wr(e,t)]}getInstanceFromId(e){let{idMask:t,idBits:n}=this;return Tr(e,n,t)}init(e){let{objects:t,idBits:n}=this;this.primitiveBuffer=new Uint32Array(this._countPrimitives(t)),this._fillPrimitiveBuffer(t,n,this.primitiveBuffer),super.init(e)}writePrimitiveBounds(e,t,n){let{primitiveBuffer:r}=this;_r.copy(this.matrixWorld).invert(),this._getPrimitiveBoundingBox(r[e],_r,vr);let{min:i,max:a}=vr;t[n+0]=i.x,t[n+1]=i.y,t[n+2]=i.z,t[n+3]=a.x,t[n+4]=a.y,t[n+5]=a.z}getRootRanges(){return[{offset:0,count:this.primitiveBuffer.length}]}shapecast(e){return super.shapecast({...e,intersectsPrimitive:e.intersectsObject,scratchPrimitive:null,iterate:Or})}raycast(e,t=[]){let{matrixWorld:n,includeInstances:r}=this,{firstHitOnly:i}=e,a=[];_r.copy(n).invert(),br.copy(e.ray).applyMatrix4(_r);let o=1/0,s=null;return this.shapecast({boundsTraverseOrder:e=>e.distanceToPoint(br.origin),intersectsBounds:t=>i?br.intersectBox(t,Z)?(Z.applyMatrix4(n),+(e.ray.origin.distanceTo(Z)<o)):0:+!!br.intersectsBox(t),intersectsObject(n,c){if(n.visible){if(a.length=0,n.isInstancedMesh&&r)Q.geometry=n.geometry,Q.material=n.material,n.getMatrixAt(c,Q.matrixWorld),Q.matrixWorld.premultiply(n.matrixWorld),Q.raycast(e,a),a.forEach(e=>{e.object=n,e.instanceId=c}),Q.material=null;else if(n.isBatchedMesh&&r){if(!n.getVisibleAt(c))return;let t=n.getGeometryIdAt(c),r=n.getGeometryRangeAt(t,xr);Y.index=n.geometry.index,Y.attributes=n.geometry.attributes,Y.setDrawRange(r.start,r.count),Q.geometry=Y,Q.material=n.material,n.getMatrixAt(c,Q.matrixWorld),Q.matrixWorld.premultiply(n.matrixWorld),Q.raycast(e,a),a.forEach(e=>{e.object=n,e.batchId=c}),Q.material=null,Y.index=null,Y.attributes=null,Y.setDrawRange(0,1/0)}else n.raycast(e,a);i?a.forEach(e=>{e.distance<o&&(o=e.distance,s=e)}):t.push(...a)}}}),i&&s&&t.push(s),t}_getPrimitiveBoundingBox(e,t,n){let{objects:r,idMask:i,idBits:a,precise:o,includeInstances:s}=this,c=wr(e,i),l=Tr(e,a,i),u=r[c];if(!s&&(u.isInstancedMesh||u.isBatchedMesh))u.boundingBox||u.computeBoundingBox(),u.boundingSphere||u.computeBoundingSphere(),X.copy(u.matrixWorld).premultiply(t),yr.copy(u.boundingSphere).applyMatrix4(X),n.copy(u.boundingBox).applyMatrix4(X),kr(n,yr);else if(o)if(u.isInstancedMesh)u.getMatrixAt(l,X),X.premultiply(u.matrixWorld).premultiply(t),Dr(u.geometry,X,n);else if(u.isBatchedMesh){let e=u.getGeometryIdAt(l),r=u.getGeometryRangeAt(e,xr);Y.index=u.geometry.index,Y.attributes=u.geometry.attributes,Y.setDrawRange(r.start,r.count),u.getMatrixAt(l,X),X.premultiply(u.matrixWorld).premultiply(t),Dr(Y,X,n),Y.attributes=null}else X.copy(u.matrixWorld).premultiply(t),n.setFromObject(u,!0).applyMatrix4(t);else if(u.isInstancedMesh)u.geometry.boundingBox||u.geometry.computeBoundingBox(),u.geometry.boundingSphere||u.geometry.computeBoundingSphere(),u.getMatrixAt(l,X),X.premultiply(u.matrixWorld).premultiply(t),yr.copy(u.geometry.boundingSphere).applyMatrix4(X),n.copy(u.geometry.boundingBox).applyMatrix4(X),kr(n,yr);else if(u.isBatchedMesh){let e=u.getGeometryIdAt(l);u.getMatrixAt(l,X),X.premultiply(u.matrixWorld).premultiply(t),u.getBoundingSphereAt(e,yr).applyMatrix4(X),u.getBoundingBoxAt(e,n).applyMatrix4(X),kr(n,yr)}else n.setFromObject(u,!1).applyMatrix4(t)}_countPrimitives(e){let{includeInstances:t}=this,n=0;return e.forEach(e=>{if(e.isInstancedMesh&&t)n+=e.count;else if(e.isBatchedMesh&&t){if(!(`instanceCount`in e))throw Error(`ObjectBVH: Three.js revision >= r169 is required to use BatchedMesh.`);n+=e.instanceCount}else n++}),n}_fillPrimitiveBuffer(e,t,n){let{includeInstances:r}=this,i=0;e.forEach((e,a)=>{if(e.isInstancedMesh&&r){let r=e.count;for(let e=0;e<r;e++)n[i]=e<<t|a,i++}else if(e.isBatchedMesh&&r){let{instanceCount:r,maxInstanceCount:o}=e,s=0,c=0;for(;s<r&&c<o;){try{e.getVisibleAt(c),n[i]=c<<t|a,s++,i++}catch{}c++}}else n[i]=a,i++})}};function Cr(e){let t=0;for(let n=0;n<e;n++)t=t<<1|1;return t}function wr(e,t){return e&t}function Tr(e,t,n){return(e&~n)>>t}function Er(e,t=new Set){Array.isArray(e)?e.forEach(e=>Er(e,t)):e.traverse(e=>{(e.isMesh||e.isLine||e.isPoints)&&t.add(e)})}function Dr(e,t,n){n.makeEmpty();let r=e.drawRange,i=e.index,a=e.attributes.position,o=r.start,s=i?i.count:a.count,c=Math.min(s-o,r.count);for(let e=o,r=o+c;e<r;e++){let r=e;i&&(r=i.getX(r)),Z.fromBufferAttribute(a,r).applyMatrix4(t),n.expandByPoint(Z)}return n}function Or(e,t,n,r,i,a){let{primitiveBuffer:o,objects:s,idMask:c,idBits:l}=n;for(let n=e,u=t+e;n<u;n++){let e=o[n],t=wr(e,c),u=Tr(e,l,c),d=s[t];if(r(d,u,i,a))return!0}return!1}function kr(e,t){Z.copy(t.center).addScalar(-t.radius),e.min.max(Z),Z.copy(t.center).addScalar(t.radius),e.max.min(Z)}var Ar=new T,jr=new T,Mr=new T,Nr=new S,Pr=new k,Fr=new T,Ir=[`x`,`y`,`z`],Lr=!0,Rr=new t,zr=new t,Br=new t,Vr=new T,Hr=new T,Ur=new T,Wr=class extends _t{get primitiveStride(){return 3}constructor(e,t={}){if(!e.isMesh)throw Error(`SkinnedMeshBVH: First argument must be a Mesh.`);super(e.geometry,{...t,[ye]:!0}),this.mesh=e,t[ye]||this.init(t)}writePrimitiveBounds(e,t,n){let{mesh:r,geometry:i}=this,a=this._indirectBuffer,o=i.index?i.index.array:null,s=(a?a[e]:e)*3,c=s+0,l=s+1,u=s+2;o&&(c=o[c],l=o[l],u=o[u]),r.getVertexPosition(c,Ar),r.getVertexPosition(l,jr),r.getVertexPosition(u,Mr);for(let e=0;e<3;e++){let r=Ir[e],i=Ar[r],a=jr[r],o=Mr[r],s=i;a<s&&(s=a),o<s&&(s=o);let c=i;a>c&&(c=a),o>c&&(c=o),t[n+e]=s,t[n+e+3]=c}return t}shapecast(e){let t=new G;return super.shapecast({...e,intersectsPrimitive:e.intersectsTriangle,scratchPrimitive:t,iterate:Gr})}raycastObject3D(e,n,r=[]){let{material:i}=e;if(i===void 0)return;let{matrixWorld:a}=e,{firstHitOnly:o}=n;Pr.copy(a).invert(),Nr.copy(n.ray).applyMatrix4(Pr);let s=null,l=1/0;return this.shapecast({boundsTraverseOrder:e=>e.distanceToPoint(Nr.origin),intersectsBounds:e=>+!!Nr.intersectsBox(e),intersectsTriangle:(u,d)=>{let f=null;if(f=i.side===0?Nr.intersectTriangle(u.a,u.b,u.c,!0,Fr):i.side===1?Nr.intersectTriangle(u.c,u.b,u.a,!0,Fr):Nr.intersectTriangle(u.a,u.b,u.c,!1,Fr),!f)return;f=f.clone().applyMatrix4(a);let p=n.ray.origin.distanceTo(f);if(p>=n.near&&p<=n.far){if(o&&p>=l)return;let{geometry:n}=this,{index:i}=n,a=this.resolvePrimitiveIndex(d),m=a*3,h=m+0,g=m+1,_=m+2;i&&(h=i.array[h],g=i.array[g],_=i.array[_]);let v={distance:p,point:f.clone(),object:e,uv:null,uv1:null,normal:null,face:{a:h,b:g,c:_,normal:c.getNormal(u.a,u.b,u.c,new T),materialIndex:0},faceIndex:a};if(Lr){let e=new T;c.getBarycoord(Fr,u.a,u.b,u.c,e),v.barycoord=e}let y=n.attributes.uv,b=n.attributes.uv1,x=n.attributes.normal;if(y){Rr.fromBufferAttribute(y,h),zr.fromBufferAttribute(y,g),Br.fromBufferAttribute(y,_),v.uv=new t;let e=c.getInterpolation(Fr,u.a,u.b,u.c,Rr,zr,Br,v.uv);Lr||(v.uv=e)}if(b){Rr.fromBufferAttribute(b,h),zr.fromBufferAttribute(b,g),Br.fromBufferAttribute(b,_),v.uv1=new t;let e=c.getInterpolation(Fr,u.a,u.b,u.c,Rr,zr,Br,v.uv1);Lr||(v.uv1=e)}if(x){Vr.fromBufferAttribute(x,h),Hr.fromBufferAttribute(x,g),Ur.fromBufferAttribute(x,_),v.normal=new T;let e=c.getInterpolation(Fr,u.a,u.b,u.c,Vr,Hr,Ur,v.normal);v.normal.dot(Nr.direction)>0&&v.normal.multiplyScalar(-1),Lr||(v.normal=e)}l=v.distance,s=v,o||r.push(v)}}}),o&&s&&r.push(s),r}};function Gr(e,t,n,r,i,a,o){let{mesh:s,geometry:c}=n,l=c.index?c.index.array:null;for(let c=e,u=t+e;c<u;c++){let e=n.resolvePrimitiveIndex(c),t=3*e+0,u=3*e+1,d=3*e+2;if(l&&(t=l[t],u=l[u],d=l[d]),s.getVertexPosition(t,o.a),s.getVertexPosition(u,o.b),s.getVertexPosition(d,o.c),o.needsUpdate=!0,r(o,c,i,a))return!0}return!1}var Kr=new O,qr=new k,Jr=new T,Yr=class extends u{get isMesh(){return!this.displayEdges}get isLineSegments(){return this.displayEdges}get isLine(){return this.displayEdges}getVertexPosition(...e){return h.prototype.getVertexPosition.call(this,...e)}constructor(e,t,n=10,r=0){super(),this.material=t,this.geometry=new E,this.name=`BVHRootHelper`,this.depth=n,this.displayParents=!1,this.bvh=e,this.displayEdges=!0,this._group=r}raycast(){}update(){let e=this.bvh;this.geometry.dispose(),this.visible=!1,e&&(this.geometry=this.getGeometry(e),this.visible=!0)}getGeometry(e){let t=this._group,n=null;if(t!==-1)n=this.getBVHBoundPositions(e,t);else{let t=e._roots.map((t,n)=>this.getBVHBoundPositions(e,n)),r=t.reduce((e,t)=>e+t.length,0);n=new Float32Array(r);let i=0;t.forEach(e=>{n.set(e,i),i+=e.length})}let r=this.getBVHBoundIndices(n),i=new E;return i.setIndex(new D(r,1,!1)),i.setAttribute(`position`,new D(n,3,!1)),i}getBVHBoundIndices(e){let t=e.length/24,n,r;r=this.displayEdges?new Uint8Array([0,4,1,5,2,6,3,7,0,2,1,3,4,6,5,7,0,1,2,3,4,5,6,7]):new Uint8Array([0,1,2,2,1,3,4,6,5,6,7,5,1,4,5,0,4,1,2,3,6,3,7,6,0,2,4,2,6,4,1,5,3,3,5,7]),n=e.length>65535?new Uint32Array(r.length*t):new Uint16Array(r.length*t);let i=r.length;for(let e=0;e<t;e++){let t=e*8,a=e*i;for(let e=0;e<i;e++)n[a+e]=t+r[e]}return n}getBVHBoundPositions(e,t=0,n=null){let r=this.depth-1,i=this.displayParents,a=0;e.traverse((e,t)=>{if(e>=r||t)return a++,!0;i&&a++},t);let o=0,s=new Float32Array(24*a);return e.traverse((e,t,a)=>{let c=e>=r||t;if(c||i){A(0,a,Kr);let{min:e,max:t}=Kr;for(let r=-1;r<=1;r+=2){let i=r<0?e.x:t.x;for(let r=-1;r<=1;r+=2){let a=r<0?e.y:t.y;for(let r=-1;r<=1;r+=2){let c=r<0?e.z:t.z;Jr.set(i,a,c),n&&Jr.applyMatrix4(n),Jr.toArray(s,o),o+=3}}}return c}},t),s}},Xr=class e extends o{get color(){return this.edgeMaterial.color}get opacity(){return this.edgeMaterial.opacity}set opacity(e){this.edgeMaterial.opacity=e,this.meshMaterial.opacity=e}get objectIndex(){return console.warn(`BVHHelper: "objectIndex" has been renamed "instanceId".`),this.instanceId}set objectIndex(e){console.warn(`BVHHelper: "objectIndex" has been renamed "instanceId".`),this.instanceId=e}constructor(e=null,t=null,n=10){e instanceof $n&&(n=t||10,t=e,e=null),typeof t==`number`&&(n=t,t=null),super(),this.name=`BVHHelper`,this.depth=n,this.mesh=e,this.bvh=t,this.displayParents=!1,this.displayEdges=!0,this.instanceId=0,this._roots=[];let r=new te({color:65416,transparent:!0,opacity:.3,depthWrite:!1}),i=new a({color:65416,transparent:!0,opacity:.3,depthWrite:!1});i.color=r.color,this.edgeMaterial=r,this.meshMaterial=i,this.update()}update(){let e=this.mesh,t=this.instanceId,n=this.bvh||e.boundsTree||e.geometry&&e.geometry.boundsTree||null;if(e&&e.isBatchedMesh&&e.boundsTrees&&!n&&t>=0){let r=e._drawInfo[t];r&&(n=e.boundsTrees[r.geometryIndex]||n)}let r=n?n._roots.length:0;for(;this._roots.length>r;){let e=this._roots.pop();e.geometry.dispose(),this.remove(e)}for(let e=0;e<r;e++){let{depth:t,edgeMaterial:r,meshMaterial:i,displayParents:a,displayEdges:o}=this;if(e>=this._roots.length){let i=new Yr(n,r,t,e);this.add(i),this._roots.push(i)}let s=this._roots[e];s.bvh=n,s.depth=t,s.displayParents=a,s.displayEdges=o,s.material=o?r:i,s.update()}}updateMatrixWorld(...e){let t=this.mesh,n=this.parent,r=this.instanceId;t!==null&&(t.updateWorldMatrix(!0,!1),n?this.matrix.copy(n.matrixWorld).invert().multiply(t.matrixWorld):this.matrix.copy(t.matrixWorld),(t.isInstancedMesh||t.isBatchedMesh)&&r>=0&&(t.getMatrixAt(r,qr),this.matrix.multiply(qr)),this.matrix.decompose(this.position,this.quaternion,this.scale)),super.updateMatrixWorld(...e)}copy(e){this.depth=e.depth,this.mesh=e.mesh,this.bvh=e.bvh,this.opacity=e.opacity,this.color.copy(e.color)}clone(){return new e().copy(this)}dispose(){this.edgeMaterial.dispose(),this.meshMaterial.dispose();let e=this.children;for(let t=0,n=e.length;t<n;t++)e[t].geometry.dispose()}},Zr=class extends Xr{constructor(...e){console.warn(`MeshBVHHelper: Class has been deprecated. Use BVHHelper instead.`),super(...e)}},Qr=new O,$r=new O;function ei(e){switch(typeof e){case`number`:return 8;case`string`:return e.length*2;case`boolean`:return 4;default:return 0}}function ti(e){return/(Uint|Int|Float)(8|16|32)Array/.test(e.constructor.name)}function ni(e,t){let n={nodeCount:0,leafNodeCount:0,depth:{min:1/0,max:-1/0},primitives:{min:1/0,max:-1/0},splits:[0,0,0],surfaceAreaScore:0};return e.traverse((e,t,r,i,a)=>{let o=r[3]-r[0],s=r[4]-r[1],c=r[5]-r[2],l=2*(o*s+s*c+c*o);n.nodeCount++,t?(n.leafNodeCount++,n.depth.min=Math.min(e,n.depth.min),n.depth.max=Math.max(e,n.depth.max),n.primitives.min=Math.min(a,n.primitives.min),n.primitives.max=Math.max(a,n.primitives.max),n.surfaceAreaScore+=l*he*a):(n.splits[i]++,n.surfaceAreaScore+=l*1)},t),n.primitives.min===1/0&&(n.primitives.min=0,n.primitives.max=0),n.depth.min===1/0&&(n.depth.min=0,n.depth.max=0),n}function ri(e){return e._roots.map((t,n)=>ni(e,n))}function ii(e){let t=new Set,n=[e],r=0;for(;n.length;){let e=n.pop();if(!t.has(e)){t.add(e);for(let t in e){if(!Object.hasOwn(e,t))continue;r+=ei(t);let i=e[t];i&&(typeof i==`object`||typeof i==`function`)?ti(i)||ct()&&i instanceof SharedArrayBuffer||i instanceof ArrayBuffer?r+=i.byteLength:n.push(i):r+=ei(i)}}}return r}function ai(e){let t=[],n=new Float32Array(6),r=!0;return e.traverse((i,a,o,s,c)=>{t[i]={depth:i,isLeaf:a,boundingData:o,offset:s,count:c},A(0,o,Qr);let l=t[i-1];if(a){e.writePrimitiveRangeBounds(s,c,n,0),$r.min.set(n[0],n[1],n[2]),$r.max.set(n[3],n[4],n[5]);let t=Qr.containsBox($r);console.assert(t,`Leaf bounds does not fully contain primitives.`),r&&=t}if(l){A(0,l.boundingData,$r);let e=$r.containsBox(Qr);console.assert(e,`Parent bounds does not fully contain child.`),r&&=e}}),r}function oi(e){let t=[];return e.traverse((e,n,r,i,a)=>{let o={bounds:A(0,r,new O)};n?(o.count=a,o.offset=i):(o.left=null,o.right=null),t[e]=o;let s=t[e-1];s&&(s.left===null?s.left=o:s.right=o)}),t[0]}var si=!0,ci={Mesh:h.prototype.raycast,Line:re.prototype.raycast,LineSegments:w.prototype.raycast,LineLoop:se.prototype.raycast,Points:v.prototype.raycast,BatchedMesh:oe.prototype.raycast},$=new h,li=[];function ui(e,t){if(this.isBatchedMesh)di.call(this,e,t);else{let{geometry:n}=this;if(n.boundsTree)n.boundsTree.raycastObject3D(this,e,t);else{let n;if(this instanceof h)n=ci.Mesh;else if(this instanceof w)n=ci.LineSegments;else if(this instanceof se)n=ci.LineLoop;else if(this instanceof re)n=ci.Line;else if(this instanceof v)n=ci.Points;else throw Error(`BVH: Fallback raycast function not found.`);n.call(this,e,t)}}}function di(e,t){if(this.boundsTrees){let r=this.boundsTrees,i=this._drawInfo||this._instanceInfo,a=this._drawRanges||this._geometryInfo,o=this.matrixWorld;$.material=this.material,$.geometry=this.geometry;let s=$.geometry.boundsTree,c=$.geometry.drawRange;$.geometry.boundingSphere===null&&($.geometry.boundingSphere=new n);for(let n=0,s=i.length;n<s;n++){if(!this.getVisibleAt(n))continue;let s=i[n].geometryIndex;if($.geometry.boundsTree=r[s],this.getMatrixAt(n,$.matrixWorld).premultiply(o),!$.geometry.boundsTree){this.getBoundingBoxAt(s,$.geometry.boundingBox),this.getBoundingSphereAt(s,$.geometry.boundingSphere);let e=a[s];$.geometry.setDrawRange(e.start,e.count)}$.raycast(e,li);for(let e=0,r=li.length;e<r;e++){let r=li[e];r.object=this,r.batchId=n,t.push(r)}li.length=0}$.geometry.boundsTree=s,$.geometry.drawRange=c,$.material=null,$.geometry=null}else ci.BatchedMesh.call(this,e,t)}function fi(e={}){let{type:t=$n}=e;return this.boundsTree=new t(this,e),this.boundsTree}function pi(){this.boundsTree=null}function mi(e=-1,t={}){if(!si)throw Error(`BatchedMesh: Three r166+ is required to compute bounds trees.`);t={...t,range:null};let n=this._drawRanges||this._geometryInfo,r=this._geometryCount;this.boundsTrees||=Array(r).fill(null);let i=this.boundsTrees;for(;i.length<r;)i.push(null);if(e<0){for(let e=0;e<r;e++)t.range=n[e],i[e]=new $n(this.geometry,t);return i}else return e<n.length&&(t.range=n[e],i[e]=new $n(this.geometry,t)),i[e]||null}function hi(e=-1){e<0?this.boundsTrees.fill(null):e<this.boundsTrees.length&&(this.boundsTrees[e]=null)}function gi(e){switch(e){case 1:return`R`;case 2:return`RG`;case 3:return`RGBA`;case 4:return`RGBA`}throw Error()}function _i(e){switch(e){case 1:return m;case 2:return x;case 3:return ie;case 4:return ie}}function vi(e){switch(e){case 1:return r;case 2:return ce;case 3:return ne;case 4:return ne}}var yi=class extends d{constructor(){super(),this.minFilter=f,this.magFilter=f,this.generateMipmaps=!1,this.overrideItemSize=null,this._forcedType=null}updateFrom(e){let t=this.overrideItemSize,n=e.itemSize,r=e.count;if(t!==null){if(n*r%t!==0)throw Error(`VertexAttributeTexture: overrideItemSize must divide evenly into buffer length.`);e.itemSize=t,e.count=r*n/t}let i=e.itemSize,a=e.count,o=e.normalized,c=e.array.constructor,u=c.BYTES_PER_ELEMENT,d=this._forcedType,f=i;if(d===null)switch(c){case Float32Array:d=g;break;case Uint8Array:case Uint16Array:case Uint32Array:d=y;break;case Int8Array:case Int16Array:case Int32Array:d=ee;break}let m,h,_,v,x=gi(i);switch(d){case g:_=1,h=_i(i),o&&u===1?(v=c,x+=`8`,c===Uint8Array?m=s:(m=b,x+=`_SNORM`)):(v=Float32Array,x+=`32F`,m=g);break;case ee:x+=u*8+`I`,_=o?2**(c.BYTES_PER_ELEMENT*8-1):1,h=vi(i),u===1?(v=Int8Array,m=b):u===2?(v=Int16Array,m=l):(v=Int32Array,m=ee);break;case y:x+=u*8+`UI`,_=o?2**(c.BYTES_PER_ELEMENT*8-1):1,h=vi(i),u===1?(v=Uint8Array,m=s):u===2?(v=Uint16Array,m=p):(v=Uint32Array,m=y);break}f===3&&(h===1023||h===1033)&&(f=4);let S=Math.ceil(Math.sqrt(a))||1,C=f*S*S,w=new v(C),T=e.normalized;e.normalized=!1;for(let t=0;t<a;t++){let n=f*t;w[n]=e.getX(t)/_,i>=2&&(w[n+1]=e.getY(t)/_),i>=3&&(w[n+2]=e.getZ(t)/_,f===4&&(w[n+3]=1)),i>=4&&(w[n+3]=e.getW(t)/_)}e.normalized=T,this.internalFormat=x,this.format=h,this.type=m,this.image.width=S,this.image.height=S,this.image.data=w,this.needsUpdate=!0,this.dispose(),e.itemSize=n,e.count=r}},bi=class extends yi{constructor(){super(),this._forcedType=y}},xi=class extends yi{constructor(){super(),this._forcedType=ee}},Si=class extends yi{constructor(){super(),this._forcedType=g}},Ci=class{constructor(){this.index=new bi,this.position=new Si,this.bvhBounds=new d,this.bvhContents=new d,this._cachedIndexAttr=null,this.index.overrideItemSize=3}updateFrom(e){let{geometry:t}=e;if(Ti(e,this.bvhBounds,this.bvhContents),this.position.updateFrom(t.attributes.position),e.indirect){let n=e._indirectBuffer;if(this._cachedIndexAttr===null||this._cachedIndexAttr.count!==n.length)if(t.index)this._cachedIndexAttr=t.index.clone();else{let e=dt(lt(t));this._cachedIndexAttr=new D(e,1,!1)}wi(t,n,this._cachedIndexAttr),this.index.updateFrom(this._cachedIndexAttr)}else this.index.updateFrom(t.index)}dispose(){let{index:e,position:t,bvhBounds:n,bvhContents:r}=this;e&&e.dispose(),t&&t.dispose(),n&&n.dispose(),r&&r.dispose()}};function wi(e,t,n){let r=n.array,i=e.index?e.index.array:null;for(let e=0,n=t.length;e<n;e++){let n=3*e,a=3*t[e];for(let e=0;e<3;e++)r[n+e]=i?i[a+e]:a+e}}function Ti(e,t,n){let r=e._roots;if(r.length!==1)throw Error(`MeshBVHUniformStruct: Multi-root BVHs not supported.`);let i=r[0],a=new Uint16Array(i),o=new Uint32Array(i),s=new Float32Array(i),c=i.byteLength/32,l=2*Math.ceil(Math.sqrt(c/2)),u=new Float32Array(4*l*l),d=Math.ceil(Math.sqrt(c)),p=new Uint32Array(2*d*d);for(let e=0;e<c;e++){let t=e*32/4,n=t*2,r=I(t);for(let t=0;t<3;t++)u[8*e+0+t]=s[r+0+t],u[8*e+4+t]=s[r+3+t];if(j(n,a)){let r=N(n,a),i=M(t,o),s=_e|r;p[e*2+0]=s,p[e*2+1]=i}else{let n=o[t+6],r=Ee(t,o);p[e*2+0]=r,p[e*2+1]=n}}t.image.data=u,t.image.width=l,t.image.height=l,t.format=ie,t.type=g,t.internalFormat=`RGBA32F`,t.minFilter=f,t.magFilter=f,t.generateMipmaps=!1,t.needsUpdate=!0,t.dispose(),n.image.data=p,n.image.width=d,n.image.height=d,n.format=ce,n.type=y,n.internalFormat=`RG32UI`,n.minFilter=f,n.magFilter=f,n.generateMipmaps=!1,n.needsUpdate=!0,n.dispose()}var Ei=new T,Di=new T,Oi=new T,ki=new ae,Ai=new T,ji=new T,Mi=new ae,Ni=new ae,Pi=new k,Fi=new k;function Ii(e,t){if(!e&&!t)return;let n=e.count===t.count,r=e.normalized===t.normalized,i=e.array.constructor===t.array.constructor,a=e.itemSize===t.itemSize;if(!n||!r||!i||!a)throw Error()}function Li(e,t=null){let n=e.array.constructor,r=e.normalized,i=e.itemSize;return new D(new n(i*(t===null?e.count:t)),i,r)}function Ri(e,t,n=0){if(e.isInterleavedBufferAttribute){let r=e.itemSize;for(let i=0,a=e.count;i<a;i++){let a=i+n;t.setX(a,e.getX(i)),r>=2&&t.setY(a,e.getY(i)),r>=3&&t.setZ(a,e.getZ(i)),r>=4&&t.setW(a,e.getW(i))}}else{let r=t.array,i=r.constructor,a=r.BYTES_PER_ELEMENT*e.itemSize*n;new i(r.buffer,a,e.array.length).set(e.array)}}function zi(e,t,n){let r=e.elements,i=t.elements;for(let e=0,t=i.length;e<t;e++)r[e]+=i[e]*n}function Bi(e,t,n){let r=e.skeleton,i=e.geometry,a=r.bones,o=r.boneInverses;Mi.fromBufferAttribute(i.attributes.skinIndex,t),Ni.fromBufferAttribute(i.attributes.skinWeight,t),Pi.elements.fill(0);for(let e=0;e<4;e++){let t=Ni.getComponent(e);if(t!==0){let n=Mi.getComponent(e);Fi.multiplyMatrices(a[n].matrixWorld,o[n]),zi(Pi,Fi,t)}}return Pi.multiply(e.bindMatrix).premultiply(e.bindMatrixInverse),n.transformDirection(Pi),n}function Vi(e,t,n,r,i){Ai.set(0,0,0);for(let a=0,o=e.length;a<o;a++){let o=t[a],s=e[a];o!==0&&(ji.fromBufferAttribute(s,r),n?Ai.addScaledVector(ji,o):Ai.addScaledVector(ji.sub(i),o))}i.add(Ai)}function Hi(e,t={useGroups:!1,updateIndex:!1,skipAttributes:[]},n=new E){let r=e[0].index!==null,{useGroups:i=!1,updateIndex:a=!1,skipAttributes:o=[]}=t,s=new Set(Object.keys(e[0].attributes)),c={},l=0;n.clearGroups();for(let t=0;t<e.length;++t){let a=e[t],o=0;if(r!==(a.index!==null))throw Error(`StaticGeometryGenerator: All geometries must have compatible attributes; make sure index attribute exists among all geometries, or in none of them.`);for(let e in a.attributes){if(!s.has(e))throw Error(`StaticGeometryGenerator: All geometries must have compatible attributes; make sure "`+e+`" attribute exists among all geometries, or in none of them.`);c[e]===void 0&&(c[e]=[]),c[e].push(a.attributes[e]),o++}if(o!==s.size)throw Error(`StaticGeometryGenerator: Make sure all geometries have the same number of attributes.`);if(i){let e;if(r)e=a.index.count;else if(a.attributes.position!==void 0)e=a.attributes.position.count;else throw Error(`StaticGeometryGenerator: The geometry must have either an index or a position attribute`);n.addGroup(l,e,t),l+=e}}if(r){let t=!1;if(!n.index){let r=0;for(let t=0;t<e.length;++t)r+=e[t].index.count;n.setIndex(new D(new Uint32Array(r),1,!1)),t=!0}if(a||t){let t=n.index,r=0,i=0;for(let n=0;n<e.length;++n){let a=e[n],s=a.index;if(o[n]!==!0)for(let e=0;e<s.count;++e)t.setX(r,s.getX(e)+i),r++;i+=a.attributes.position.count}}}for(let e in c){let t=c[e];if(!(e in n.attributes)){let r=0;for(let e in t)r+=t[e].count;n.setAttribute(e,Li(c[e][0],r))}let r=n.attributes[e],i=0;for(let e=0,n=t.length;e<n;e++){let n=t[e];o[e]!==!0&&Ri(n,r,i),i+=n.count}}return n}function Ui(e,t){if(e===null||t===null)return e===t;if(e.length!==t.length)return!1;for(let n=0,r=e.length;n<r;n++)if(e[n]!==t[n])return!1;return!0}function Wi(e){let{index:t,attributes:n}=e;if(t)for(let e=0,n=t.count;e<n;e+=3){let n=t.getX(e),r=t.getX(e+2);t.setX(e,r),t.setX(e+2,n)}else for(let e in n){let t=n[e],r=t.itemSize;for(let e=0,n=t.count;e<n;e+=3)for(let n=0;n<r;n++){let r=t.getComponent(e,n),i=t.getComponent(e+2,n);t.setComponent(e,n,i),t.setComponent(e+2,n,r)}}return e}var Gi=class{constructor(e){this.matrixWorld=new k,this.geometryHash=null,this.boneMatrices=null,this.primitiveCount=-1,this.mesh=e,this.update()}update(){let e=this.mesh,t=e.geometry,n=e.skeleton,r=(t.index?t.index.count:t.attributes.position.count)/3;if(this.matrixWorld.copy(e.matrixWorld),this.geometryHash=t.attributes.position.version,this.primitiveCount=r,n){n.boneTexture||n.computeBoneTexture(),n.update();let e=n.boneMatrices;!this.boneMatrices||this.boneMatrices.length!==e.length?this.boneMatrices=e.slice():this.boneMatrices.set(e)}else this.boneMatrices=null}didChange(){let e=this.mesh,t=e.geometry,n=(t.index?t.index.count:t.attributes.position.count)/3;return!(this.matrixWorld.equals(e.matrixWorld)&&this.geometryHash===t.attributes.position.version&&Ui(e.skeleton&&e.skeleton.boneMatrices||null,this.boneMatrices)&&this.primitiveCount===n)}},Ki=class{constructor(e){Array.isArray(e)||(e=[e]);let t=[];e.forEach(e=>{e.traverseVisible(e=>{e.isMesh&&t.push(e)})}),this.meshes=t,this.useGroups=!0,this.applyWorldTransforms=!0,this.attributes=[`position`,`normal`,`color`,`tangent`,`uv`,`uv2`],this._intermediateGeometry=Array(t.length).fill().map(()=>new E),this._diffMap=new WeakMap}getMaterials(){let e=[];return this.meshes.forEach(t=>{Array.isArray(t.material)?e.push(...t.material):e.push(t.material)}),e}generate(e=new E){let t=[],{meshes:n,useGroups:r,_intermediateGeometry:i,_diffMap:a}=this;for(let e=0,r=n.length;e<r;e++){let r=n[e],o=i[e],s=a.get(r);!s||s.didChange(r)?(this._convertToStaticGeometry(r,o),t.push(!1),s?s.update():a.set(r,new Gi(r))):t.push(!0)}if(i.length===0){e.setIndex(null);let t=e.attributes;for(let n in t)e.deleteAttribute(n);for(let t in this.attributes)e.setAttribute(this.attributes[t],new D(new Float32Array,4,!1))}else Hi(i,{useGroups:r,skipAttributes:t},e);for(let t in e.attributes)e.attributes[t].needsUpdate=!0;return e}_convertToStaticGeometry(e,t=new E){let n=e.geometry,r=this.applyWorldTransforms,a=this.attributes.includes(`normal`),o=this.attributes.includes(`tangent`),s=n.attributes,c=t.attributes;!t.index&&n.index&&(t.index=n.index.clone()),c.position||t.setAttribute(`position`,Li(s.position)),a&&!c.normal&&s.normal&&t.setAttribute(`normal`,Li(s.normal)),o&&!c.tangent&&s.tangent&&t.setAttribute(`tangent`,Li(s.tangent)),Ii(n.index,t.index),Ii(s.position,c.position),a&&Ii(s.normal,c.normal),o&&Ii(s.tangent,c.tangent);let l=s.position,u=a?s.normal:null,d=o?s.tangent:null,f=n.morphAttributes.position,p=n.morphAttributes.normal,m=n.morphAttributes.tangent,h=n.morphTargetsRelative,g=e.morphTargetInfluences,_=new i;_.getNormalMatrix(e.matrixWorld),n.index&&t.index.array.set(n.index.array);for(let t=0,n=s.position.count;t<n;t++)Ei.fromBufferAttribute(l,t),u&&Di.fromBufferAttribute(u,t),d&&(ki.fromBufferAttribute(d,t),Oi.fromBufferAttribute(d,t)),g&&(f&&Vi(f,g,h,t,Ei),p&&Vi(p,g,h,t,Di),m&&Vi(m,g,h,t,Oi)),e.isSkinnedMesh&&(e.applyBoneTransform(t,Ei),u&&Bi(e,t,Di),d&&Bi(e,t,Oi)),r&&Ei.applyMatrix4(e.matrixWorld),c.position.setXYZ(t,Ei.x,Ei.y,Ei.z),u&&(r&&Di.applyNormalMatrix(_),c.normal.setXYZ(t,Di.x,Di.y,Di.z)),d&&(r&&Oi.transformDirection(e.matrixWorld),c.tangent.setXYZW(t,Oi.x,Oi.y,Oi.z,ki.w));for(let e in this.attributes){let n=this.attributes[e];n===`position`||n===`tangent`||n===`normal`||!(n in s)||(c[n]||t.setAttribute(n,Li(s[n])),Ii(s[n],c[n]),Ri(s[n],c[n]))}return e.matrixWorld.determinant()<0&&Wi(t),t}},qi=`

// A stack of uint32 indices can can store the indices for
// a perfectly balanced tree with a depth up to 31. Lower stack
// depth gets higher performance.
//
// However not all trees are balanced. Best value to set this to
// is the trees max depth.
#ifndef BVH_STACK_DEPTH
#define BVH_STACK_DEPTH 60
#endif

#ifndef INFINITY
#define INFINITY 1e20
#endif

// Utilities
uvec4 uTexelFetch1D( usampler2D tex, uint index ) {

	uint width = uint( textureSize( tex, 0 ).x );
	uvec2 uv;
	uv.x = index % width;
	uv.y = index / width;

	return texelFetch( tex, ivec2( uv ), 0 );

}

ivec4 iTexelFetch1D( isampler2D tex, uint index ) {

	uint width = uint( textureSize( tex, 0 ).x );
	uvec2 uv;
	uv.x = index % width;
	uv.y = index / width;

	return texelFetch( tex, ivec2( uv ), 0 );

}

vec4 texelFetch1D( sampler2D tex, uint index ) {

	uint width = uint( textureSize( tex, 0 ).x );
	uvec2 uv;
	uv.x = index % width;
	uv.y = index / width;

	return texelFetch( tex, ivec2( uv ), 0 );

}

vec4 textureSampleBarycoord( sampler2D tex, vec3 barycoord, uvec3 faceIndices ) {

	return
		barycoord.x * texelFetch1D( tex, faceIndices.x ) +
		barycoord.y * texelFetch1D( tex, faceIndices.y ) +
		barycoord.z * texelFetch1D( tex, faceIndices.z );

}

void ndcToCameraRay(
	vec2 coord, mat4 cameraWorld, mat4 invProjectionMatrix,
	out vec3 rayOrigin, out vec3 rayDirection
) {

	// get camera look direction and near plane for camera clipping
	vec4 lookDirection = cameraWorld * vec4( 0.0, 0.0, - 1.0, 0.0 );
	vec4 nearVector = invProjectionMatrix * vec4( 0.0, 0.0, - 1.0, 1.0 );
	float near = abs( nearVector.z / nearVector.w );

	// get the camera direction and position from camera matrices
	vec4 origin = cameraWorld * vec4( 0.0, 0.0, 0.0, 1.0 );
	vec4 direction = invProjectionMatrix * vec4( coord, 0.5, 1.0 );
	direction /= direction.w;
	direction = cameraWorld * direction - origin;

	// slide the origin along the ray until it sits at the near clip plane position
	origin.xyz += direction.xyz * near / dot( direction, lookDirection );

	rayOrigin = origin.xyz;
	rayDirection = direction.xyz;

}
`,Ji=`

float dot2( vec3 v ) {

	return dot( v, v );

}

// https://www.shadertoy.com/view/ttfGWl
vec3 closestPointToTriangle( vec3 p, vec3 v0, vec3 v1, vec3 v2, out vec3 barycoord ) {

    vec3 v10 = v1 - v0;
    vec3 v21 = v2 - v1;
    vec3 v02 = v0 - v2;

	vec3 p0 = p - v0;
	vec3 p1 = p - v1;
	vec3 p2 = p - v2;

    vec3 nor = cross( v10, v02 );

    // method 2, in barycentric space
    vec3  q = cross( nor, p0 );
    float d = 1.0 / dot2( nor );
    float u = d * dot( q, v02 );
    float v = d * dot( q, v10 );
    float w = 1.0 - u - v;

	if( u < 0.0 ) {

		w = clamp( dot( p2, v02 ) / dot2( v02 ), 0.0, 1.0 );
		u = 0.0;
		v = 1.0 - w;

	} else if( v < 0.0 ) {

		u = clamp( dot( p0, v10 ) / dot2( v10 ), 0.0, 1.0 );
		v = 0.0;
		w = 1.0 - u;

	} else if( w < 0.0 ) {

		v = clamp( dot( p1, v21 ) / dot2( v21 ), 0.0, 1.0 );
		w = 0.0;
		u = 1.0 - v;

	}

	barycoord = vec3( u, v, w );
    return u * v1 + v * v2 + w * v0;

}

float distanceToTriangles(
	// geometry info and triangle range
	sampler2D positionAttr, usampler2D indexAttr, uint offset, uint count,

	// point and cut off range
	vec3 point, float closestDistanceSquared,

	// outputs
	inout uvec4 faceIndices, inout vec3 faceNormal, inout vec3 barycoord, inout float side, inout vec3 outPoint
) {

	bool found = false;
	vec3 localBarycoord;
	for ( uint i = offset, l = offset + count; i < l; i ++ ) {

		uvec3 indices = uTexelFetch1D( indexAttr, i ).xyz;
		vec3 a = texelFetch1D( positionAttr, indices.x ).rgb;
		vec3 b = texelFetch1D( positionAttr, indices.y ).rgb;
		vec3 c = texelFetch1D( positionAttr, indices.z ).rgb;

		// get the closest point and barycoord
		vec3 closestPoint = closestPointToTriangle( point, a, b, c, localBarycoord );
		vec3 delta = point - closestPoint;
		float sqDist = dot2( delta );
		if ( sqDist < closestDistanceSquared ) {

			// set the output results
			closestDistanceSquared = sqDist;
			faceIndices = uvec4( indices.xyz, i );
			faceNormal = normalize( cross( a - b, b - c ) );
			barycoord = localBarycoord;
			outPoint = closestPoint;
			side = sign( dot( faceNormal, delta ) );

		}

	}

	return closestDistanceSquared;

}

float distanceSqToBounds( vec3 point, vec3 boundsMin, vec3 boundsMax ) {

	vec3 clampedPoint = clamp( point, boundsMin, boundsMax );
	vec3 delta = point - clampedPoint;
	return dot( delta, delta );

}

float distanceSqToBVHNodeBoundsPoint( vec3 point, sampler2D bvhBounds, uint currNodeIndex ) {

	uint cni2 = currNodeIndex * 2u;
	vec3 boundsMin = texelFetch1D( bvhBounds, cni2 ).xyz;
	vec3 boundsMax = texelFetch1D( bvhBounds, cni2 + 1u ).xyz;
	return distanceSqToBounds( point, boundsMin, boundsMax );

}

// use a macro to hide the fact that we need to expand the struct into separate fields
#define	bvhClosestPointToPoint(		bvh,		point, maxDistance, faceIndices, faceNormal, barycoord, side, outPoint	)	_bvhClosestPointToPoint(		bvh.position, bvh.index, bvh.bvhBounds, bvh.bvhContents,		point, maxDistance, faceIndices, faceNormal, barycoord, side, outPoint	)

float _bvhClosestPointToPoint(
	// bvh info
	sampler2D bvh_position, usampler2D bvh_index, sampler2D bvh_bvhBounds, usampler2D bvh_bvhContents,

	// point to check
	vec3 point, float maxDistance,

	// output variables
	inout uvec4 faceIndices, inout vec3 faceNormal, inout vec3 barycoord,
	inout float side, inout vec3 outPoint
 ) {

	// stack needs to be twice as long as the deepest tree we expect because
	// we push both the left and right child onto the stack every traversal
	int pointer = 0;
	uint stack[ BVH_STACK_DEPTH ];
	stack[ 0 ] = 0u;

	float closestDistanceSquared = maxDistance * maxDistance;
	bool found = false;
	while ( pointer > - 1 && pointer < BVH_STACK_DEPTH ) {

		uint currNodeIndex = stack[ pointer ];
		pointer --;

		// check if we intersect the current bounds
		float boundsHitDistance = distanceSqToBVHNodeBoundsPoint( point, bvh_bvhBounds, currNodeIndex );
		if ( boundsHitDistance > closestDistanceSquared ) {

			continue;

		}

		uvec2 boundsInfo = uTexelFetch1D( bvh_bvhContents, currNodeIndex ).xy;
		bool isLeaf = bool( boundsInfo.x & 0xffff0000u );
		if ( isLeaf ) {

			uint count = boundsInfo.x & 0x0000ffffu;
			uint offset = boundsInfo.y;
			closestDistanceSquared = distanceToTriangles(
				bvh_position, bvh_index, offset, count, point, closestDistanceSquared,

				// outputs
				faceIndices, faceNormal, barycoord, side, outPoint
			);

		} else {

			uint leftIndex = currNodeIndex + 1u;
			uint splitAxis = boundsInfo.x & 0x0000ffffu;
			uint rightIndex = currNodeIndex + boundsInfo.y;
			bool leftToRight = distanceSqToBVHNodeBoundsPoint( point, bvh_bvhBounds, leftIndex ) < distanceSqToBVHNodeBoundsPoint( point, bvh_bvhBounds, rightIndex );//rayDirection[ splitAxis ] >= 0.0;
			uint c1 = leftToRight ? leftIndex : rightIndex;
			uint c2 = leftToRight ? rightIndex : leftIndex;

			// set c2 in the stack so we traverse it later. We need to keep track of a pointer in
			// the stack while we traverse. The second pointer added is the one that will be
			// traversed first
			pointer ++;
			stack[ pointer ] = c2;
			pointer ++;
			stack[ pointer ] = c1;

		}

	}

	return sqrt( closestDistanceSquared );

}
`,Yi=`

#ifndef TRI_INTERSECT_EPSILON
#define TRI_INTERSECT_EPSILON 1e-5
#endif

// Raycasting
bool intersectsBounds( vec3 rayOrigin, vec3 rayDirection, vec3 boundsMin, vec3 boundsMax, out float dist ) {

	// https://www.reddit.com/r/opengl/comments/8ntzz5/fast_glsl_ray_box_intersection/
	// https://tavianator.com/2011/ray_box.html
	vec3 invDir = 1.0 / rayDirection;

	// find intersection distances for each plane
	vec3 tMinPlane = invDir * ( boundsMin - rayOrigin );
	vec3 tMaxPlane = invDir * ( boundsMax - rayOrigin );

	// get the min and max distances from each intersection
	vec3 tMinHit = min( tMaxPlane, tMinPlane );
	vec3 tMaxHit = max( tMaxPlane, tMinPlane );

	// get the furthest hit distance
	vec2 t = max( tMinHit.xx, tMinHit.yz );
	float t0 = max( t.x, t.y );

	// get the minimum hit distance
	t = min( tMaxHit.xx, tMaxHit.yz );
	float t1 = min( t.x, t.y );

	// set distance to 0.0 if the ray starts inside the box
	dist = max( t0, 0.0 );

	return t1 >= dist;

}

bool intersectsTriangle(
	vec3 rayOrigin, vec3 rayDirection, vec3 a, vec3 b, vec3 c,
	out vec3 barycoord, out vec3 norm, out float dist, out float side
) {

	// https://stackoverflow.com/questions/42740765/intersection-between-line-and-triangle-in-3d
	vec3 edge1 = b - a;
	vec3 edge2 = c - a;
	norm = cross( edge1, edge2 );

	float det = - dot( rayDirection, norm );
	float invdet = 1.0 / det;

	vec3 AO = rayOrigin - a;
	vec3 DAO = cross( AO, rayDirection );

	vec4 uvt;
	uvt.x = dot( edge2, DAO ) * invdet;
	uvt.y = - dot( edge1, DAO ) * invdet;
	uvt.z = dot( AO, norm ) * invdet;
	uvt.w = 1.0 - uvt.x - uvt.y;

	// set the hit information
	barycoord = uvt.wxy; // arranged in A, B, C order
	dist = uvt.z;
	side = sign( det );
	norm = side * normalize( norm );

	// add an epsilon to avoid misses between triangles
	uvt += vec4( TRI_INTERSECT_EPSILON );

	return all( greaterThanEqual( uvt, vec4( 0.0 ) ) );

}

bool intersectTriangles(
	// geometry info and triangle range
	sampler2D positionAttr, usampler2D indexAttr, uint offset, uint count,

	// ray
	vec3 rayOrigin, vec3 rayDirection,

	// outputs
	inout float minDistance, inout uvec4 faceIndices, inout vec3 faceNormal, inout vec3 barycoord,
	inout float side, inout float dist
) {

	bool found = false;
	vec3 localBarycoord, localNormal;
	float localDist, localSide;
	for ( uint i = offset, l = offset + count; i < l; i ++ ) {

		uvec3 indices = uTexelFetch1D( indexAttr, i ).xyz;
		vec3 a = texelFetch1D( positionAttr, indices.x ).rgb;
		vec3 b = texelFetch1D( positionAttr, indices.y ).rgb;
		vec3 c = texelFetch1D( positionAttr, indices.z ).rgb;

		if (
			intersectsTriangle( rayOrigin, rayDirection, a, b, c, localBarycoord, localNormal, localDist, localSide )
			&& localDist < minDistance
		) {

			found = true;
			minDistance = localDist;

			faceIndices = uvec4( indices.xyz, i );
			faceNormal = localNormal;

			side = localSide;
			barycoord = localBarycoord;
			dist = localDist;

		}

	}

	return found;

}

bool intersectsBVHNodeBounds( vec3 rayOrigin, vec3 rayDirection, sampler2D bvhBounds, uint currNodeIndex, out float dist ) {

	uint cni2 = currNodeIndex * 2u;
	vec3 boundsMin = texelFetch1D( bvhBounds, cni2 ).xyz;
	vec3 boundsMax = texelFetch1D( bvhBounds, cni2 + 1u ).xyz;
	return intersectsBounds( rayOrigin, rayDirection, boundsMin, boundsMax, dist );

}

// use a macro to hide the fact that we need to expand the struct into separate fields
#define	bvhIntersectFirstHit(		bvh,		rayOrigin, rayDirection, faceIndices, faceNormal, barycoord, side, dist	)	_bvhIntersectFirstHit(		bvh.position, bvh.index, bvh.bvhBounds, bvh.bvhContents,		rayOrigin, rayDirection, faceIndices, faceNormal, barycoord, side, dist	)

bool _bvhIntersectFirstHit(
	// bvh info
	sampler2D bvh_position, usampler2D bvh_index, sampler2D bvh_bvhBounds, usampler2D bvh_bvhContents,

	// ray
	vec3 rayOrigin, vec3 rayDirection,

	// output variables split into separate variables due to output precision
	inout uvec4 faceIndices, inout vec3 faceNormal, inout vec3 barycoord,
	inout float side, inout float dist
) {

	// stack needs to be twice as long as the deepest tree we expect because
	// we push both the left and right child onto the stack every traversal
	int pointer = 0;
	uint stack[ BVH_STACK_DEPTH ];
	stack[ 0 ] = 0u;

	float triangleDistance = INFINITY;
	bool found = false;
	while ( pointer > - 1 && pointer < BVH_STACK_DEPTH ) {

		uint currNodeIndex = stack[ pointer ];
		pointer --;

		// check if we intersect the current bounds
		float boundsHitDistance;
		if (
			! intersectsBVHNodeBounds( rayOrigin, rayDirection, bvh_bvhBounds, currNodeIndex, boundsHitDistance )
			|| boundsHitDistance > triangleDistance
		) {

			continue;

		}

		uvec2 boundsInfo = uTexelFetch1D( bvh_bvhContents, currNodeIndex ).xy;
		bool isLeaf = bool( boundsInfo.x & 0xffff0000u );

		if ( isLeaf ) {

			uint count = boundsInfo.x & 0x0000ffffu;
			uint offset = boundsInfo.y;

			found = intersectTriangles(
				bvh_position, bvh_index, offset, count,
				rayOrigin, rayDirection, triangleDistance,
				faceIndices, faceNormal, barycoord, side, dist
			) || found;

		} else {

			uint leftIndex = currNodeIndex + 1u;
			uint splitAxis = boundsInfo.x & 0x0000ffffu;
			uint rightIndex = currNodeIndex + boundsInfo.y;

			bool leftToRight = rayDirection[ splitAxis ] >= 0.0;
			uint c1 = leftToRight ? leftIndex : rightIndex;
			uint c2 = leftToRight ? rightIndex : leftIndex;

			// set c2 in the stack so we traverse it later. We need to keep track of a pointer in
			// the stack while we traverse. The second pointer added is the one that will be
			// traversed first
			pointer ++;
			stack[ pointer ] = c2;

			pointer ++;
			stack[ pointer ] = c1;

		}

	}

	return found;

}
`,Xi=`
struct BVH {

	usampler2D index;
	sampler2D position;

	sampler2D bvhBounds;
	usampler2D bvhContents;

};
`,Zi=e({bvh_distance_functions:()=>Ji,bvh_ray_functions:()=>Yi,bvh_struct_definitions:()=>Xi,common_functions:()=>qi}),Qi=Xi,$i=Ji,ea=`
	${qi}
	${Yi}
`;export{ue as AVERAGE,st as BVH,Xr as BVHHelper,Zi as BVHShaderGLSL,le as CENTER,me as CONTAINED,G as ExtendedTriangle,Si as FloatVertexAttributeTexture,_t as GeometryBVH,pe as INTERSECTED,xi as IntVertexAttributeTexture,lr as LineBVH,cr as LineLoopBVH,sr as LineSegmentsBVH,$n as MeshBVH,Zr as MeshBVHHelper,Ci as MeshBVHUniformStruct,fe as NOT_INTERSECTED,Sr as ObjectBVH,K as OrientedBox,hr as PointsBVH,de as SAH,ye as SKIP_GENERATION,Wr as SkinnedMeshBVH,Ki as StaticGeometryGenerator,bi as UIntVertexAttributeTexture,yi as VertexAttributeTexture,ui as acceleratedRaycast,mi as computeBatchedBoundsTree,fi as computeBoundsTree,hi as disposeBatchedBoundsTree,pi as disposeBoundsTree,ii as estimateMemoryInBytes,gt as generateIndirectBuffer,ri as getBVHExtremes,oi as getJSONStructure,qt as getTriangleHitPointInfo,$i as shaderDistanceFunction,ea as shaderIntersectFunction,Qi as shaderStructs,ai as validateBounds};