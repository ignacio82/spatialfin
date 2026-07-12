import{$ as e,A as t,B as n,C as r,Ct as i,D as a,E as o,G as s,J as c,L as l,O as u,R as d,W as f,X as p,_ as m,_t as h,at as g,c as _,et as v,ft as y,gt as b,ht as x,it as S,j as C,k as w,l as T,lt as ee,mt as te,nt as ne,o as re,ot as ie,s as E,tt as ae,u as oe,ut as se,vt as D,x as ce,yt as le,z as ue}from"./index-dRakOTvk.js";var de=0,fe=1,pe=2,me=0,he=1,ge=2,_e=1.25,ve=65535,ye=ve<<16,be=2**-24,xe=Symbol(`SKIP_GENERATION`),Se={strategy:0,maxDepth:40,maxLeafSize:10,useSharedArrayBuffer:!1,setBoundingBox:!0,onProgress:null,indirect:!1,verbose:!0,range:null,[xe]:!1};function O(e,t,n){return n.min.x=t[e],n.min.y=t[e+1],n.min.z=t[e+2],n.max.x=t[e+3],n.max.y=t[e+4],n.max.z=t[e+5],n}function Ce(e){let t=-1,n=-1/0;for(let r=0;r<3;r++){let i=e[r+3]-e[r];i>n&&(n=i,t=r)}return t}function we(e,t){t.set(e)}function Te(e,t,n){let r,i;for(let a=0;a<3;a++){let o=a+3;r=e[a],i=t[a],n[a]=r<i?r:i,r=e[o],i=t[o],n[o]=r>i?r:i}}function Ee(e,t,n){for(let r=0;r<3;r++){let i=t[e+2*r],a=t[e+2*r+1],o=i-a,s=i+a;o<n[r]&&(n[r]=o),s>n[r+3]&&(n[r+3]=s)}}function De(e){let t=e[3]-e[0],n=e[4]-e[1],r=e[5]-e[2];return 2*(t*n+n*r+r*t)}function k(e,t){return t[e+15]===ve}function A(e,t){return t[e+6]}function j(e,t){return t[e+14]}function M(e){return e+8}function N(e,t){return e+t[e+6]*8}function Oe(e,t){return t[e+7]}function P(e){return e}function ke(e,t,n,r,i){let a=1/0,o=1/0,s=1/0,c=-1/0,l=-1/0,u=-1/0,d=1/0,f=1/0,p=1/0,m=-1/0,h=-1/0,g=-1/0,_=e.offset||0;for(let r=(t-_)*6,i=(t+n-_)*6;r<i;r+=6){let t=e[r+0],n=e[r+1],i=t-n,_=t+n;i<a&&(a=i),_>c&&(c=_),t<d&&(d=t),t>m&&(m=t);let v=e[r+2],y=e[r+3],b=v-y,x=v+y;b<o&&(o=b),x>l&&(l=x),v<f&&(f=v),v>h&&(h=v);let S=e[r+4],C=e[r+5],w=S-C,T=S+C;w<s&&(s=w),T>u&&(u=T),S<p&&(p=S),S>g&&(g=S)}r[0]=a,r[1]=o,r[2]=s,r[3]=c,r[4]=l,r[5]=u,i[0]=d,i[1]=f,i[2]=p,i[3]=m,i[4]=h,i[5]=g}var F=32,Ae=(e,t)=>e.candidate-t.candidate,I=Array(F).fill().map(()=>({count:0,bounds:new Float32Array(6),rightCacheBounds:new Float32Array(6),leftCacheBounds:new Float32Array(6),candidate:0})),je=new Float32Array(6);function Me(e,t,n,r,i,a){let o=-1,s=0;if(a===0)o=Ce(t),o!==-1&&(s=(t[o]+t[o+3])/2);else if(a===1)o=Ce(e),o!==-1&&(s=Ne(n,r,i,o));else if(a===2){let a=De(e),c=_e*i,l=n.offset||0,u=(r-l)*6,d=(r+i-l)*6;for(let e=0;e<3;e++){let r=t[e],l=(t[e+3]-r)/F;if(i<F/4){let t=[...I];t.length=i;let r=0;for(let i=u;i<d;i+=6,r++){let a=t[r];a.candidate=n[i+2*e],a.count=0;let{bounds:o,leftCacheBounds:s,rightCacheBounds:c}=a;for(let e=0;e<3;e++)c[e]=1/0,c[e+3]=-1/0,s[e]=1/0,s[e+3]=-1/0,o[e]=1/0,o[e+3]=-1/0;Ee(i,n,o)}t.sort(Ae);let l=i;for(let e=0;e<l;e++){let n=t[e];for(;e+1<l&&t[e+1].candidate===n.candidate;)t.splice(e+1,1),l--}for(let r=u;r<d;r+=6){let i=n[r+2*e];for(let e=0;e<l;e++){let a=t[e];i>=a.candidate?Ee(r,n,a.rightCacheBounds):(Ee(r,n,a.leftCacheBounds),a.count++)}}for(let n=0;n<l;n++){let r=t[n],l=r.count,u=i-r.count,d=r.leftCacheBounds,f=r.rightCacheBounds,p=0;l!==0&&(p=De(d)/a);let m=0;u!==0&&(m=De(f)/a);let h=1+_e*(p*l+m*u);h<c&&(o=e,c=h,s=r.candidate)}}else{for(let e=0;e<F;e++){let t=I[e];t.count=0,t.candidate=r+l+e*l;let n=t.bounds;for(let e=0;e<3;e++)n[e]=1/0,n[e+3]=-1/0}for(let t=u;t<d;t+=6){let i=~~((n[t+2*e]-r)/l);i>=F&&(i=F-1);let a=I[i];a.count++,Ee(t,n,a.bounds)}let t=I[F-1];we(t.bounds,t.rightCacheBounds);for(let e=F-2;e>=0;e--){let t=I[e],n=I[e+1];Te(t.bounds,n.rightCacheBounds,t.rightCacheBounds)}let f=0;for(let t=0;t<F-1;t++){let n=I[t],r=n.count,l=n.bounds,u=I[t+1].rightCacheBounds;r!==0&&(f===0?we(l,je):Te(l,je,je)),f+=r;let d=0,p=0;f!==0&&(d=De(je)/a);let m=i-f;m!==0&&(p=De(u)/a);let h=1+_e*(d*f+p*m);h<c&&(o=e,c=h,s=n.candidate)}}}}else console.warn(`BVH: Invalid build strategy value ${a} used.`);return{axis:o,pos:s}}function Ne(e,t,n,r){let i=0,a=e.offset;for(let o=t,s=t+n;o<s;o++)i+=e[(o-a)*6+r*2];return i/n}var Pe=class{constructor(){this.boundingData=new Float32Array(6)}};function Fe(e,t,n,r,i,a){let o=r,s=r+i-1,c=a.pos,l=a.axis*2,u=n.offset||0;for(;;){for(;o<=s&&n[(o-u)*6+l]<c;)o++;for(;o<=s&&n[(s-u)*6+l]>=c;)s--;if(o<s){for(let n=0;n<t;n++){let r=e[o*t+n];e[o*t+n]=e[s*t+n],e[s*t+n]=r}for(let e=0;e<6;e++){let t=o-u,r=s-u,i=n[t*6+e];n[t*6+e]=n[r*6+e],n[r*6+e]=i}o++,s--}else return o}}var Ie,Le,Re,ze,Be=2**32;function Ve(e){return`count`in e?1:1+Ve(e.left)+Ve(e.right)}function He(e,t,n){return Ie=new Float32Array(n),Le=new Uint32Array(n),Re=new Uint16Array(n),ze=new Uint8Array(n),Ue(e,t)}function Ue(e,t){let n=e/4,r=e/2,i=`count`in t,a=t.boundingData;for(let e=0;e<6;e++)Ie[n+e]=a[e];if(i)return t.buffer?(ze.set(new Uint8Array(t.buffer),e),e+t.buffer.byteLength):(Le[n+6]=t.offset,Re[r+14]=t.count,Re[r+15]=ve,e+32);{let{left:r,right:i,splitAxis:a}=t,o=Ue(e+32,r),s=e/32,c=o/32-s;if(c>Be)throw Error(`MeshBVH: Cannot store relative child node offset greater than 32 bits.`);return Le[n+6]=c,Le[n+7]=a,Ue(o,i)}}function We(e,t,n,r,i,a){let{maxDepth:o,verbose:s,maxLeafSize:c,strategy:l,onProgress:u}=i,d=e.primitiveBuffer,f=e.primitiveBufferStride,p=new Float32Array(6),m=!1,h=new Pe;return ke(t,n,r,h.boundingData,p),_(h,n,r,p),h;function g(e){u&&u((e-a.offset)/a.count)}function _(e,n,r,i=null,a=0){if(!m&&a>=o&&(m=!0,s&&console.warn(`BVH: Max depth of ${o} reached when generating BVH. Consider increasing maxDepth.`)),r<=c||a>=o)return g(n+r),e.offset=n,e.count=r,e;let u=Me(e.boundingData,i,t,n,r,l);if(u.axis===-1)return g(n+r),e.offset=n,e.count=r,e;let h=Fe(d,f,t,n,r,u);if(h===n||h===n+r)g(n+r),e.offset=n,e.count=r;else{e.splitAxis=u.axis;let i=new Pe,o=n,s=h-n;e.left=i,ke(t,o,s,i.boundingData,p),_(i,o,s,p,a+1);let c=new Pe,l=h,d=r-s;e.right=c,ke(t,l,d,c.boundingData,p),_(c,l,d,p,a+1)}return e}}function Ge(e,t){let n=t.useSharedArrayBuffer?SharedArrayBuffer:ArrayBuffer,r=e.getRootRanges(t.range),i=r[0],a=r[r.length-1],o={offset:i.offset,count:a.offset+a.count-i.offset},s=new Float32Array(6*o.count);s.offset=o.offset,e.computePrimitiveBounds(o.offset,o.count,s),e._roots=r.map(r=>{let i=We(e,s,r.offset,r.count,t,o),a=Ve(i),c=new n(32*a);return He(0,i,c),c})}var Ke=class{constructor(e){this._getNewPrimitive=e,this._primitives=[]}getPrimitive(){let e=this._primitives;return e.length===0?this._getNewPrimitive():e.pop()}releasePrimitive(e){this._primitives.push(e)}},L=new class{constructor(){this.float32Array=null,this.uint16Array=null,this.uint32Array=null;let e=[],t=null;this.setBuffer=n=>{t&&e.push(t),t=n,this.float32Array=new Float32Array(n),this.uint16Array=new Uint16Array(n),this.uint32Array=new Uint32Array(n)},this.clearBuffer=()=>{t=null,this.float32Array=null,this.uint16Array=null,this.uint32Array=null,e.length!==0&&this.setBuffer(e.pop())}}},R,qe,Je=[],Ye=new Ke(()=>new E);function Xe(e,t,n,r,i,a){R=Ye.getPrimitive(),qe=Ye.getPrimitive(),Je.push(R,qe),L.setBuffer(e._roots[t]);let o=Ze(0,e.geometry,n,r,i,a);L.clearBuffer(),Ye.releasePrimitive(R),Ye.releasePrimitive(qe),Je.pop(),Je.pop();let s=Je.length;return s>0&&(qe=Je[s-1],R=Je[s-2]),o}function Ze(e,t,n,r,i=null,a=0,o=0){let{float32Array:s,uint16Array:c,uint32Array:l}=L,u=e*2;if(k(u,c)){let t=A(e,l),n=j(u,c);return O(P(e),s,R),r(t,n,!1,o,a+e/8,R)}else{let u=M(e),d=N(e,l),f=u,p=d,m,h,g,_;if(i&&(g=R,_=qe,O(P(f),s,g),O(P(p),s,_),m=i(g),h=i(_),h<m)){f=d,p=u;let e=m;m=h,h=e,g=_}g||(g=R,O(P(f),s,g));let v=k(f*2,c),y=n(g,v,m,o+1,a+f/8),b;if(y===2){let e=w(f);b=r(e,T(f)-e,!0,o+1,a+f/8,g)}else b=y&&Ze(f,t,n,r,i,a,o+1);if(b)return!0;_=qe,O(P(p),s,_);let x=k(p*2,c),S=n(_,x,h,o+1,a+p/8),C;if(S===2){let e=w(p);C=r(e,T(p)-e,!0,o+1,a+p/8,_)}else C=S&&Ze(p,t,n,r,i,a,o+1);if(C)return!0;return!1;function w(e){let{uint16Array:t,uint32Array:n}=L,r=e*2;for(;!k(r,t);)e=M(e),r=e*2;return A(e,n)}function T(e){let{uint16Array:t,uint32Array:n}=L,r=e*2;for(;!k(r,t);)e=N(e,n),r=e*2;return A(e,n)+j(r,t)}}}var Qe=new L.constructor,$e=new L.constructor,z=new Ke(()=>new E),et=new E,tt=new E,nt=new E,rt=new E,it=!1;function at(e,t,n,r){if(it)throw Error(`MeshBVH: Recursive calls to bvhcast not supported.`);it=!0;let i=e._roots,a=t._roots,o,s=0,c=0,l=new d().copy(n).invert();for(let e=0,t=i.length;e<t;e++){Qe.setBuffer(i[e]),c=0;let t=z.getPrimitive();O(P(0),Qe.float32Array,t),t.applyMatrix4(l);for(let e=0,i=a.length;e<i&&($e.setBuffer(a[e]),o=B(0,0,n,l,r,s,c,0,0,t),$e.clearBuffer(),c+=a[e].byteLength/32,!o);e++);if(z.releasePrimitive(t),Qe.clearBuffer(),s+=i[e].byteLength/32,o)break}return it=!1,o}function B(e,t,n,r,i,a=0,o=0,s=0,c=0,l=null,u=!1){let d,f;u?(d=$e,f=Qe):(d=Qe,f=$e);let p=d.float32Array,m=d.uint32Array,h=d.uint16Array,g=f.float32Array,_=f.uint32Array,v=f.uint16Array,y=e*2,b=t*2,x=k(y,h),S=k(b,v),C=!1;if(S&&x)C=u?i(A(t,_),j(t*2,v),A(e,m),j(e*2,h),c,o+t/8,s,a+e/8):i(A(e,m),j(e*2,h),A(t,_),j(t*2,v),s,a+e/8,c,o+t/8);else if(S){let l=z.getPrimitive();O(P(t),g,l),l.applyMatrix4(n);let d=M(e),f=N(e,m);O(P(d),p,et),O(P(f),p,tt);let h=l.intersectsBox(et),_=l.intersectsBox(tt);C=h&&B(t,d,r,n,i,o,a,c,s+1,l,!u)||_&&B(t,f,r,n,i,o,a,c,s+1,l,!u),z.releasePrimitive(l)}else{let d=M(t),f=N(t,_);O(P(d),g,nt),O(P(f),g,rt);let h=l.intersectsBox(nt),v=l.intersectsBox(rt);if(h&&v)C=B(e,d,n,r,i,a,o,s,c+1,l,u)||B(e,f,n,r,i,a,o,s,c+1,l,u);else if(h)if(x)C=B(e,d,n,r,i,a,o,s,c+1,l,u);else{let t=z.getPrimitive();t.copy(nt).applyMatrix4(n);let l=M(e),f=N(e,m);O(P(l),p,et),O(P(f),p,tt);let h=t.intersectsBox(et),g=t.intersectsBox(tt);C=h&&B(d,l,r,n,i,o,a,c,s+1,t,!u)||g&&B(d,f,r,n,i,o,a,c,s+1,t,!u),z.releasePrimitive(t)}else if(v)if(x)C=B(e,f,n,r,i,a,o,s,c+1,l,u);else{let t=z.getPrimitive();t.copy(rt).applyMatrix4(n);let l=M(e),d=N(e,m);O(P(l),p,et),O(P(d),p,tt);let h=t.intersectsBox(et),g=t.intersectsBox(tt);C=h&&B(f,l,r,n,i,o,a,c,s+1,t,!u)||g&&B(f,d,r,n,i,o,a,c,s+1,t,!u),z.releasePrimitive(t)}}return C}var ot=new E,st=new Float32Array(6),ct=class{constructor(){this._roots=null,this.primitiveBuffer=null,this.primitiveBufferStride=null}init(e){e={...Se,...e},Ge(this,e)}getRootRanges(){throw Error(`BVH: getRootRanges() not implemented`)}writePrimitiveBounds(){throw Error(`BVH: writePrimitiveBounds() not implemented`)}writePrimitiveRangeBounds(e,t,n,r){let i=1/0,a=1/0,o=1/0,s=-1/0,c=-1/0,l=-1/0;for(let n=e,r=e+t;n<r;n++){this.writePrimitiveBounds(n,st,0);let[e,t,r,u,d,f]=st;e<i&&(i=e),u>s&&(s=u),t<a&&(a=t),d>c&&(c=d),r<o&&(o=r),f>l&&(l=f)}return n[r+0]=i,n[r+1]=a,n[r+2]=o,n[r+3]=s,n[r+4]=c,n[r+5]=l,n}computePrimitiveBounds(e,t,n){let r=n.offset||0;for(let i=e,a=e+t;i<a;i++){this.writePrimitiveBounds(i,st,0);let[e,t,a,o,s,c]=st,l=(e+o)/2,u=(t+s)/2,d=(a+c)/2,f=(o-e)/2,p=(s-t)/2,m=(c-a)/2,h=(i-r)*6;n[h+0]=l,n[h+1]=f+(Math.abs(l)+f)*be,n[h+2]=u,n[h+3]=p+(Math.abs(u)+p)*be,n[h+4]=d,n[h+5]=m+(Math.abs(d)+m)*be}return n}shiftPrimitiveOffsets(e){let t=this._indirectBuffer;if(t)for(let n=0,r=t.length;n<r;n++)t[n]+=e;else{let t=this._roots;for(let n=0;n<t.length;n++){let r=t[n],i=new Uint32Array(r),a=new Uint16Array(r),o=r.byteLength/32;for(let t=0;t<o;t++){let n=8*t;k(2*n,a)&&(i[n+6]+=e)}}}}traverse(e,t=0){let n=this._roots[t],r=new Uint32Array(n),i=new Uint16Array(n);a(0);function a(t,o=0){let s=t*2,c=k(s,i);if(c){let a=r[t+6],l=i[s+14];e(o,c,new Float32Array(n,t*4,6),a,l)}else{let i=M(t),s=N(t,r),l=Oe(t,r);e(o,c,new Float32Array(n,t*4,6),l)||(a(i,o+1),a(s,o+1))}}}refit(){let e=this._roots;for(let t=0,n=e.length;t<n;t++){let n=e[t],r=new Uint32Array(n),i=new Uint16Array(n),a=new Float32Array(n),o=n.byteLength/32;for(let e=o-1;e>=0;e--){let t=e*8,n=t*2;if(k(n,i)){let e=A(t,r),o=j(n,i);this.writePrimitiveRangeBounds(e,o,st,0),a.set(st,t)}else{let e=M(t),n=N(t,r);for(let r=0;r<3;r++){let i=a[e+r],o=a[e+r+3],s=a[n+r],c=a[n+r+3];a[t+r]=i<s?i:s,a[t+r+3]=o>c?o:c}}}}}getBoundingBox(e){return e.makeEmpty(),this._roots.forEach(t=>{O(0,new Float32Array(t),ot),e.union(ot)}),e}shapecast(e){let{boundsTraverseOrder:t,intersectsBounds:n,intersectsRange:r,intersectsPrimitive:i,scratchPrimitive:a,iterate:o}=e;if(r&&i){let e=r;r=(t,n,r,s,c)=>e(t,n,r,s,c)?!0:o(t,n,this,i,r,s,a)}else r||=i?(e,t,n,r)=>o(e,t,this,i,n,r,a):(e,t,n)=>n;let s=!1,c=0,l=this._roots;for(let e=0,i=l.length;e<i;e++){let i=l[e];if(s=Xe(this,e,n,r,t,c),s)break;c+=i.byteLength/32}return s}bvhcast(e,t,n){let{intersectsRanges:r}=n;return at(this,e,t,r)}};function lt(){return typeof SharedArrayBuffer<`u`}function ut(e){return e.index?e.index.count:e.attributes.position.count}function dt(e){return ut(e)/3}function ft(e,t=ArrayBuffer){return e>65535?new Uint32Array(new t(4*e)):new Uint16Array(new t(2*e))}function pt(e,t){if(!e.index){let n=e.attributes.position.count,r=ft(n,t.useSharedArrayBuffer?SharedArrayBuffer:ArrayBuffer);e.setIndex(new _(r,1));for(let e=0;e<n;e++)r[e]=e}}function mt(e,t,n){let r=ut(e)/n,i=t||e.drawRange,a=i.start/n,o=(i.start+i.count)/n,s=Math.max(0,a),c=Math.min(r,o)-s;return{offset:Math.floor(s),count:Math.floor(c)}}function ht(e,t){return e.groups.map(e=>({offset:e.start/t,count:e.count/t}))}function gt(e,t,n){let r=mt(e,t,n),i=ht(e,n);if(!i.length)return[r];let a=[],o=r.offset,s=r.offset+r.count,c=ut(e)/n,l=[];for(let e of i){let{offset:t,count:n}=e,r=t,i=t+(isFinite(n)?n:c-t);r<s&&i>o&&(l.push({pos:Math.max(o,r),isStart:!0}),l.push({pos:Math.min(s,i),isStart:!1}))}l.sort((e,t)=>e.pos===t.pos?e.type===`end`?-1:1:e.pos-t.pos);let u=0,d=null;for(let e of l){let t=e.pos;u!==0&&t!==d&&a.push({offset:d,count:t-d}),u+=e.isStart?1:-1,d=t}return a}function _t(e,t){let n=e[e.length-1],r=n.offset+n.count>2**16,i=e.reduce((e,t)=>e+t.count,0),a=r?4:2,o=t?new SharedArrayBuffer(i*a):new ArrayBuffer(i*a),s=r?new Uint32Array(o):new Uint16Array(o),c=0;for(let t=0;t<e.length;t++){let{offset:n,count:r}=e[t];for(let e=0;e<r;e++)s[c+e]=n+e;c+=r}return s}var vt=class extends ct{get indirect(){return!!this._indirectBuffer}get primitiveStride(){return null}get primitiveBufferStride(){return this.indirect?1:this.primitiveStride}set primitiveBufferStride(e){}get primitiveBuffer(){return this.indirect?this._indirectBuffer:this.geometry.index.array}set primitiveBuffer(e){}constructor(e,t={}){if(!e.isBufferGeometry)throw Error(`BVH: Only BufferGeometries are supported.`);if(e.index&&e.index.isInterleavedBufferAttribute)throw Error(`BVH: InterleavedBufferAttribute is not supported for the index attribute.`);if(t.useSharedArrayBuffer&&!lt())throw Error(`BVH: SharedArrayBuffer is not available.`);super(),this.geometry=e,this.resolvePrimitiveIndex=t.indirect?e=>this._indirectBuffer[e]:e=>e,this.primitiveBuffer=null,this.primitiveBufferStride=null,this._indirectBuffer=null,t={...Se,...t},t[xe]||this.init(t)}init(e){let{geometry:t,primitiveStride:n}=this;if(e.indirect){let r=_t(gt(t,e.range,n),e.useSharedArrayBuffer);this._indirectBuffer=r}else pt(t,e);super.init(e),!t.boundingBox&&e.setBoundingBox&&(t.boundingBox=this.getBoundingBox(new E))}getRootRanges(e){return this.indirect?[{offset:0,count:this._indirectBuffer.length}]:gt(this.geometry,e,this.primitiveStride)}raycastObject3D(){throw Error(`BVH: raycastObject3D() not implemented`)}},V=class{constructor(){this.min=1/0,this.max=-1/0}setFromPointsField(e,t){let n=1/0,r=-1/0;for(let i=0,a=e.length;i<a;i++){let a=e[i][t];n=a<n?a:n,r=a>r?a:r}this.min=n,this.max=r}setFromPoints(e,t){let n=1/0,r=-1/0;for(let i=0,a=t.length;i<a;i++){let a=t[i],o=e.dot(a);n=o<n?o:n,r=o>r?o:r}this.min=n,this.max=r}isSeparated(e){return this.min>e.max||e.min>this.max}};V.prototype.setFromBox=(function(){let e=new D;return function(t,n){let r=n.min,i=n.max,a=1/0,o=-1/0;for(let n=0;n<=1;n++)for(let s=0;s<=1;s++)for(let c=0;c<=1;c++){e.x=r.x*n+i.x*(1-n),e.y=r.y*s+i.y*(1-s),e.z=r.z*c+i.z*(1-c);let l=t.dot(e);a=Math.min(l,a),o=Math.max(l,o)}this.min=a,this.max=o}})(),(function(){let e=new V;return function(t,n){let r=t.points,i=t.satAxes,a=t.satBounds,o=n.points,s=n.satAxes,c=n.satBounds;for(let t=0;t<3;t++){let n=a[t],r=i[t];if(e.setFromPoints(r,o),n.isSeparated(e))return!1}for(let t=0;t<3;t++){let n=c[t],i=s[t];if(e.setFromPoints(i,r),n.isSeparated(e))return!1}}})();var yt=(function(){let e=new D,t=new D,n=new D;return function(r,i,a){let o=r.start,s=e,c=i.start,l=t;n.subVectors(o,c),e.subVectors(r.end,r.start),t.subVectors(i.end,i.start);let u=n.dot(l),d=l.dot(s),f=l.dot(l),p=n.dot(s),m=s.dot(s)*f-d*d,h,g;h=m===0?0:(u*d-p*f)/m,g=(u+h*d)/f,a.x=h,a.y=g}})(),bt=(function(){let e=new h,t=new D,n=new D;return function(r,i,a,o){yt(r,i,e);let s=e.x,c=e.y;if(s>=0&&s<=1&&c>=0&&c<=1){r.at(s,a),i.at(c,o);return}else if(s>=0&&s<=1){c<0?i.at(0,o):i.at(1,o),r.closestPointToPoint(o,!0,a);return}else if(c>=0&&c<=1){s<0?r.at(0,a):r.at(1,a),i.closestPointToPoint(a,!0,o);return}else{let e;e=s<0?r.start:r.end;let l;l=c<0?i.start:i.end;let u=t,d=n;if(r.closestPointToPoint(l,!0,t),i.closestPointToPoint(e,!0,n),u.distanceToSquared(l)<=d.distanceToSquared(e)){a.copy(u),o.copy(l);return}else{a.copy(e),o.copy(d);return}}}})(),xt=(function(){let e=new D,t=new D,n=new c,r=new u;return function(i,a){let{radius:o,center:s}=i,{a:c,b:l,c:u}=a;if(r.start=c,r.end=l,r.closestPointToPoint(s,!0,e).distanceTo(s)<=o||(r.start=c,r.end=u,r.closestPointToPoint(s,!0,e).distanceTo(s)<=o)||(r.start=l,r.end=u,r.closestPointToPoint(s,!0,e).distanceTo(s)<=o))return!0;let d=a.getPlane(n);if(Math.abs(d.distanceToPoint(s))<=o){let e=d.projectPoint(s,t);if(a.containsPoint(e))return!0}return!1}})(),St=[`x`,`y`,`z`],H=1e-15,Ct=H*H;function U(e){return Math.abs(e)<H}var W=class extends y{constructor(...e){super(...e),this.isExtendedTriangle=!0,this.satAxes=[,,,,].fill().map(()=>new D),this.satBounds=[,,,,].fill().map(()=>new V),this.points=[this.a,this.b,this.c],this.plane=new c,this.isDegenerateIntoSegment=!1,this.isDegenerateIntoPoint=!1,this.degenerateSegment=new u,this.needsUpdate=!0}intersectsSphere(e){return xt(e,this)}update(){let e=this.a,t=this.b,n=this.c,r=this.points,i=this.satAxes,a=this.satBounds,o=i[0],s=a[0];this.getNormal(o),s.setFromPoints(o,r);let c=i[1],l=a[1];c.subVectors(e,t),l.setFromPoints(c,r);let u=i[2],d=a[2];u.subVectors(t,n),d.setFromPoints(u,r);let f=i[3],p=a[3];f.subVectors(n,e),p.setFromPoints(f,r);let m=c.length(),h=u.length(),g=f.length();this.isDegenerateIntoPoint=!1,this.isDegenerateIntoSegment=!1,m<H?h<H||g<H?this.isDegenerateIntoPoint=!0:(this.isDegenerateIntoSegment=!0,this.degenerateSegment.start.copy(e),this.degenerateSegment.end.copy(n)):h<H?g<H?this.isDegenerateIntoPoint=!0:(this.isDegenerateIntoSegment=!0,this.degenerateSegment.start.copy(t),this.degenerateSegment.end.copy(e)):g<H&&(this.isDegenerateIntoSegment=!0,this.degenerateSegment.start.copy(n),this.degenerateSegment.end.copy(t)),this.plane.setFromNormalAndCoplanarPoint(o,e),this.needsUpdate=!1}};W.prototype.closestPointToSegment=(function(){let e=new D,t=new D,n=new u;return function(r,i=null,a=null){let{start:o,end:s}=r,c=this.points,l,u=1/0;for(let o=0;o<3;o++){let s=(o+1)%3;n.start.copy(c[o]),n.end.copy(c[s]),bt(n,r,e,t),l=e.distanceToSquared(t),l<u&&(u=l,i&&i.copy(e),a&&a.copy(t))}return this.closestPointToPoint(o,e),l=o.distanceToSquared(e),l<u&&(u=l,i&&i.copy(e),a&&a.copy(o)),this.closestPointToPoint(s,e),l=s.distanceToSquared(e),l<u&&(u=l,i&&i.copy(e),a&&a.copy(s)),Math.sqrt(u)}})(),W.prototype.intersectsTriangle=(function(){let e=new W,t=new V,n=new V,r=new D,i=new D,a=new D,o=new D,s=new u,c=new u,l=new D,d=new h,f=new h;function p(e,i,a,s){let c=r;!e.isDegenerateIntoPoint&&!e.isDegenerateIntoSegment?c.copy(e.plane.normal):c.copy(i.plane.normal);let l=e.satBounds,u=e.satAxes;for(let r=1;r<4;r++){let a=l[r],s=u[r];if(t.setFromPoints(s,i.points),a.isSeparated(t)||(o.copy(c).cross(s),t.setFromPoints(o,e.points),n.setFromPoints(o,i.points),t.isSeparated(n)))return!1}let d=i.satBounds,f=i.satAxes;for(let r=1;r<4;r++){let a=d[r],s=f[r];if(t.setFromPoints(s,e.points),a.isSeparated(t)||(o.crossVectors(c,s),t.setFromPoints(o,e.points),n.setFromPoints(o,i.points),t.isSeparated(n)))return!1}return a&&(s||console.warn(`ExtendedTriangle.intersectsTriangle: Triangles are coplanar which does not support an output edge. Setting edge to 0, 0, 0.`),a.start.set(0,0,0),a.end.set(0,0,0)),!0}function m(e,t,n,r,i,a,o,s,c,l,u){let d=o/(o-s);l.x=r+(i-r)*d,u.start.subVectors(t,e).multiplyScalar(d).add(e),d=o/(o-c),l.y=r+(a-r)*d,u.end.subVectors(n,e).multiplyScalar(d).add(e)}function g(e,t,n,r,i,a,o,s,c,l,u){if(i>0)m(e.c,e.a,e.b,r,t,n,c,o,s,l,u);else if(a>0)m(e.b,e.a,e.c,n,t,r,s,o,c,l,u);else if(s*c>0||o!=0)m(e.a,e.b,e.c,t,n,r,o,s,c,l,u);else if(s!=0)m(e.b,e.a,e.c,n,t,r,s,o,c,l,u);else if(c!=0)m(e.c,e.a,e.b,r,t,n,c,o,s,l,u);else return!0;return!1}function _(e,t,n,i){let a=t.degenerateSegment,o=e.plane.distanceToPoint(a.start),s=e.plane.distanceToPoint(a.end);return U(o)?U(s)?p(e,t,n,i):(n&&(n.start.copy(a.start),n.end.copy(a.start)),e.containsPoint(a.start)):U(s)?(n&&(n.start.copy(a.end),n.end.copy(a.end)),e.containsPoint(a.end)):e.plane.intersectLine(a,r)==null?!1:(n&&(n.start.copy(r),n.end.copy(r)),e.containsPoint(r))}function v(e,t,n){let r=t.a;return U(e.plane.distanceToPoint(r))&&e.containsPoint(r)?(n&&(n.start.copy(r),n.end.copy(r)),!0):!1}function y(e,t,n){let i=e.degenerateSegment,a=t.a;return i.closestPointToPoint(a,!0,r),a.distanceToSquared(r)<Ct?(n&&(n.start.copy(a),n.end.copy(a)),!0):!1}function b(e,t,n,o){if(e.isDegenerateIntoSegment)if(t.isDegenerateIntoSegment){let o=e.degenerateSegment,s=t.degenerateSegment,c=i,l=a;o.delta(c),s.delta(l);let u=r.subVectors(s.start,o.start),d=c.x*l.y-c.y*l.x;if(U(d))return!1;let f=(u.x*l.y-u.y*l.x)/d,p=-(c.x*u.y-c.y*u.x)/d;return f<0||f>1||p<0||p>1?!1:U(o.start.z+c.z*f-(s.start.z+l.z*p))?(n&&(n.start.copy(o.start).addScaledVector(c,f),n.end.copy(o.start).addScaledVector(c,f)),!0):!1}else if(t.isDegenerateIntoPoint)return y(e,t,n);else return _(t,e,n,o);else if(e.isDegenerateIntoPoint)return t.isDegenerateIntoPoint?t.a.distanceToSquared(e.a)<Ct?(n&&(n.start.copy(e.a),n.end.copy(e.a)),!0):!1:t.isDegenerateIntoSegment?y(t,e,n):v(t,e,n);else if(t.isDegenerateIntoPoint)return v(e,t,n);else if(t.isDegenerateIntoSegment)return _(e,t,n,o)}return function(t,n=null,r=!1){this.needsUpdate&&this.update(),t.isExtendedTriangle?t.needsUpdate&&t.update():(e.copy(t),e.update(),t=e);let o=b(this,t,n,r);if(o!==void 0)return o;let u=this.plane,m=t.plane,h=m.distanceToPoint(this.a),_=m.distanceToPoint(this.b),v=m.distanceToPoint(this.c);U(h)&&(h=0),U(_)&&(_=0),U(v)&&(v=0);let y=h*_,x=h*v;if(y>0&&x>0)return!1;let S=u.distanceToPoint(t.a),C=u.distanceToPoint(t.b),w=u.distanceToPoint(t.c);U(S)&&(S=0),U(C)&&(C=0),U(w)&&(w=0);let T=S*C,ee=S*w;if(T>0&&ee>0)return!1;i.copy(u.normal),a.copy(m.normal);let te=i.cross(a),ne=0,re=Math.abs(te.x),ie=Math.abs(te.y);ie>re&&(re=ie,ne=1),Math.abs(te.z)>re&&(ne=2);let E=St[ne],ae=this.a[E],oe=this.b[E],se=this.c[E],D=t.a[E],ce=t.b[E],le=t.c[E];if(g(this,ae,oe,se,y,x,h,_,v,d,s)||g(t,D,ce,le,T,ee,S,C,w,f,c))return p(this,t,n,r);if(d.y<d.x){let e=d.y;d.y=d.x,d.x=e,l.copy(s.start),s.start.copy(s.end),s.end.copy(l)}if(f.y<f.x){let e=f.y;f.y=f.x,f.x=e,l.copy(c.start),c.start.copy(c.end),c.end.copy(l)}return d.y<f.x||f.y<d.x?!1:(n&&(f.x>d.x?n.start.copy(c.start):n.start.copy(s.start),f.y<d.y?n.end.copy(c.end):n.end.copy(s.end)),!0)}})(),W.prototype.distanceToPoint=(function(){let e=new D;return function(t){return this.closestPointToPoint(t,e),t.distanceTo(e)}})(),W.prototype.distanceToTriangle=(function(){let e=new D,t=new D,n=[`a`,`b`,`c`],r=new u,i=new u;return function(a,o=null,s=null){let c=o||s?r:null;if(this.intersectsTriangle(a,c,!0))return(o||s)&&(o&&c.getCenter(o),s&&c.getCenter(s)),0;let l=1/0;for(let t=0;t<3;t++){let r,i=n[t],c=a[i];this.closestPointToPoint(c,e),r=c.distanceToSquared(e),r<l&&(l=r,o&&o.copy(e),s&&s.copy(c));let u=this[i];a.closestPointToPoint(u,e),r=u.distanceToSquared(e),r<l&&(l=r,o&&o.copy(u),s&&s.copy(e))}for(let c=0;c<3;c++){let u=n[c],d=n[(c+1)%3];r.set(this[u],this[d]);for(let c=0;c<3;c++){let u=n[c],d=n[(c+1)%3];i.set(a[u],a[d]),bt(r,i,e,t);let f=e.distanceToSquared(t);f<l&&(l=f,o&&o.copy(e),s&&s.copy(t))}}return Math.sqrt(l)}})();var G=class{constructor(e,t,n){this.isOrientedBox=!0,this.min=new D,this.max=new D,this.matrix=new d,this.invMatrix=new d,this.points=Array(8).fill().map(()=>new D),this.satAxes=[,,,].fill().map(()=>new D),this.satBounds=[,,,].fill().map(()=>new V),this.alignedSatBounds=[,,,].fill().map(()=>new V),this.needsUpdate=!1,e&&this.min.copy(e),t&&this.max.copy(t),n&&this.matrix.copy(n)}set(e,t,n){this.min.copy(e),this.max.copy(t),this.matrix.copy(n),this.needsUpdate=!0}copy(e){this.min.copy(e.min),this.max.copy(e.max),this.matrix.copy(e.matrix),this.needsUpdate=!0}};G.prototype.update=(function(){return function(){let e=this.matrix,t=this.min,n=this.max,r=this.points;for(let i=0;i<=1;i++)for(let a=0;a<=1;a++)for(let o=0;o<=1;o++){let s=r[1*i|2*a|4*o];s.x=i?n.x:t.x,s.y=a?n.y:t.y,s.z=o?n.z:t.z,s.applyMatrix4(e)}let i=this.satBounds,a=this.satAxes,o=r[0];for(let e=0;e<3;e++){let t=a[e],n=i[e],s=r[1<<e];t.subVectors(o,s),n.setFromPoints(t,r)}let s=this.alignedSatBounds;s[0].setFromPointsField(r,`x`),s[1].setFromPointsField(r,`y`),s[2].setFromPointsField(r,`z`),this.invMatrix.copy(this.matrix).invert(),this.needsUpdate=!1}})(),G.prototype.intersectsBox=(function(){let e=new V;return function(t){this.needsUpdate&&this.update();let n=t.min,r=t.max,i=this.satBounds,a=this.satAxes,o=this.alignedSatBounds;if(e.min=n.x,e.max=r.x,o[0].isSeparated(e)||(e.min=n.y,e.max=r.y,o[1].isSeparated(e))||(e.min=n.z,e.max=r.z,o[2].isSeparated(e)))return!1;for(let n=0;n<3;n++){let r=a[n],o=i[n];if(e.setFromBox(r,t),o.isSeparated(e))return!1}return!0}})(),G.prototype.intersectsTriangle=(function(){let e=new W,t=[,,,],n=new V,r=new V,i=new D;return function(a){this.needsUpdate&&this.update(),a.isExtendedTriangle?a.needsUpdate&&a.update():(e.copy(a),e.update(),a=e);let o=this.satBounds,s=this.satAxes;t[0]=a.a,t[1]=a.b,t[2]=a.c;for(let e=0;e<3;e++){let r=o[e],i=s[e];if(n.setFromPoints(i,t),r.isSeparated(n))return!1}let c=a.satBounds,l=a.satAxes,u=this.points;for(let e=0;e<3;e++){let t=c[e],r=l[e];if(n.setFromPoints(r,u),t.isSeparated(n))return!1}for(let e=0;e<3;e++){let a=s[e];for(let e=0;e<4;e++){let o=l[e];if(i.crossVectors(a,o),n.setFromPoints(i,t),r.setFromPoints(i,u),n.isSeparated(r))return!1}}return!0}})(),G.prototype.closestPointToPoint=(function(){return function(e,t){return this.needsUpdate&&this.update(),t.copy(e).applyMatrix4(this.invMatrix).clamp(this.min,this.max).applyMatrix4(this.matrix),t}})(),G.prototype.distanceToPoint=(function(){let e=new D;return function(t){return this.closestPointToPoint(t,e),t.distanceTo(e)}})(),G.prototype.distanceToBox=(function(){let e=[`x`,`y`,`z`],t=Array(12).fill().map(()=>new u),n=Array(12).fill().map(()=>new u),r=new D,i=new D;return function(a,o=0,s=null,c=null){if(this.needsUpdate&&this.update(),this.intersectsBox(a))return(s||c)&&(a.getCenter(i),this.closestPointToPoint(i,r),a.closestPointToPoint(r,i),s&&s.copy(r),c&&c.copy(i)),0;let l=o*o,u=a.min,d=a.max,f=this.points,p=1/0;for(let e=0;e<8;e++){let t=f[e];i.copy(t).clamp(u,d);let n=t.distanceToSquared(i);if(n<p&&(p=n,s&&s.copy(t),c&&c.copy(i),n<l))return Math.sqrt(n)}let m=0;for(let r=0;r<3;r++)for(let i=0;i<=1;i++)for(let a=0;a<=1;a++){let o=(r+1)%3,s=(r+2)%3,c=i<<o|a<<s,l=1<<r|i<<o|a<<s,p=f[c],h=f[l];t[m].set(p,h);let g=e[r],_=e[o],v=e[s],y=n[m],b=y.start,x=y.end;b[g]=u[g],b[_]=i?u[_]:d[_],b[v]=a?u[v]:d[_],x[g]=d[g],x[_]=i?u[_]:d[_],x[v]=a?u[v]:d[_],m++}for(let e=0;e<=1;e++)for(let t=0;t<=1;t++)for(let n=0;n<=1;n++){i.x=e?d.x:u.x,i.y=t?d.y:u.y,i.z=n?d.z:u.z,this.closestPointToPoint(i,r);let a=i.distanceToSquared(r);if(a<p&&(p=a,s&&s.copy(r),c&&c.copy(i),a<l))return Math.sqrt(a)}for(let e=0;e<12;e++){let a=t[e];for(let e=0;e<12;e++){let t=n[e];bt(a,t,r,i);let o=r.distanceToSquared(i);if(o<p&&(p=o,s&&s.copy(r),c&&c.copy(i),o<l))return Math.sqrt(o)}}return Math.sqrt(p)}})();var K=new class extends Ke{constructor(){super(()=>new W)}},wt=new D,Tt=new D;function Et(e,t,n={},r=0,i=1/0){let a=r*r,o=i*i,s=1/0,c=null;if(e.shapecast({boundsTraverseOrder:e=>(wt.copy(t).clamp(e.min,e.max),wt.distanceToSquared(t)),intersectsBounds:(e,t,n)=>n<s&&n<o,intersectsTriangle:(e,n)=>{e.closestPointToPoint(t,wt);let r=t.distanceToSquared(wt);return r<s&&(Tt.copy(wt),s=r,c=n),r<a}}),s===1/0)return null;let l=Math.sqrt(s);return n.point?n.point.copy(Tt):n.point=Tt.clone(),n.distance=l,n.faceIndex=c,n}var Dt=!0,Ot=new D,kt=new D,At=new D,jt=new h,Mt=new h,Nt=new h,Pt=new D,Ft=new D,It=new D,Lt=new D;function Rt(e,t,n,r,i,a,o,s){let c;if(c=a===1?e.intersectTriangle(r,n,t,!0,i):e.intersectTriangle(t,n,r,a!==2,i),c===null)return null;let l=e.origin.distanceTo(i);return l<o||l>s?null:{distance:l,point:i.clone()}}function zt(e,t,n,r,i,a,o,s,c,l,u){Ot.fromBufferAttribute(t,a),kt.fromBufferAttribute(t,o),At.fromBufferAttribute(t,s);let d=Rt(e,Ot,kt,At,Lt,c,l,u);if(d){if(r){jt.fromBufferAttribute(r,a),Mt.fromBufferAttribute(r,o),Nt.fromBufferAttribute(r,s),d.uv=new h;let e=y.getInterpolation(Lt,Ot,kt,At,jt,Mt,Nt,d.uv);Dt||(d.uv=e)}if(i){jt.fromBufferAttribute(i,a),Mt.fromBufferAttribute(i,o),Nt.fromBufferAttribute(i,s),d.uv1=new h;let e=y.getInterpolation(Lt,Ot,kt,At,jt,Mt,Nt,d.uv1);Dt||(d.uv1=e)}if(n){Pt.fromBufferAttribute(n,a),Ft.fromBufferAttribute(n,o),It.fromBufferAttribute(n,s),d.normal=new D;let t=y.getInterpolation(Lt,Ot,kt,At,Pt,Ft,It,d.normal);d.normal.dot(e.direction)>0&&d.normal.multiplyScalar(-1),Dt||(d.normal=t)}let t={a,b:o,c:s,normal:new D,materialIndex:0};if(y.getNormal(Ot,kt,At,t.normal),d.face=t,d.faceIndex=a,Dt){let e=new D;y.getBarycoord(Lt,Ot,kt,At,e),d.barycoord=e}}return d}function Bt(e){return e&&e.isMaterial?e.side:e}function Vt(e,t,n,r,i,a,o){let s=r*3,c=s+0,l=s+1,u=s+2,{index:d,groups:f}=e;e.index&&(c=d.getX(c),l=d.getX(l),u=d.getX(u));let{position:p,normal:m,uv:h,uv1:g}=e.attributes;if(Array.isArray(t)){let e=r*3;for(let s=0,d=f.length;s<d;s++){let{start:d,count:_,materialIndex:v}=f[s];if(e>=d&&e<d+_){let e=Bt(t[v]),s=zt(n,p,m,h,g,c,l,u,e,a,o);if(s)if(s.faceIndex=r,s.face.materialIndex=v,i)i.push(s);else return s}}}else{let e=Bt(t),s=zt(n,p,m,h,g,c,l,u,e,a,o);if(s)if(s.faceIndex=r,s.face.materialIndex=0,i)i.push(s);else return s}return null}function q(e,t,n,r){let i=e.a,a=e.b,o=e.c,s=t,c=t+1,l=t+2;n&&(s=n.getX(s),c=n.getX(c),l=n.getX(l)),i.x=r.getX(s),i.y=r.getY(s),i.z=r.getZ(s),a.x=r.getX(c),a.y=r.getY(c),a.z=r.getZ(c),o.x=r.getX(l),o.y=r.getY(l),o.z=r.getZ(l)}var Ht=new D,Ut=new D,Wt=new D,Gt=new h,Kt=new h,qt=new h;function Jt(e,t,n,r){let i=t.getIndex().array,a=t.getAttribute(`position`),o=t.getAttribute(`uv`),s=i[n*3],c=i[n*3+1],l=i[n*3+2];Ht.fromBufferAttribute(a,s),Ut.fromBufferAttribute(a,c),Wt.fromBufferAttribute(a,l);let u=0,d=t.groups,f=n*3;for(let e=0,t=d.length;e<t;e++){let t=d[e],{start:n,count:r}=t;if(f>=n&&f<n+r){u=t.materialIndex;break}}let p=r&&r.barycoord?r.barycoord:new D;y.getBarycoord(e,Ht,Ut,Wt,p);let m=null;return o&&(Gt.fromBufferAttribute(o,s),Kt.fromBufferAttribute(o,c),qt.fromBufferAttribute(o,l),m=r&&r.uv?r.uv:new h,y.getInterpolation(e,Ht,Ut,Wt,Gt,Kt,qt,m)),r?(r.face||={},r.face.a=s,r.face.b=c,r.face.c=l,r.face.materialIndex=u,r.face.normal||(r.face.normal=new D),y.getNormal(Ht,Ut,Wt,r.face.normal),m&&(r.uv=m),r.barycoord=p,r):{face:{a:s,b:c,c:l,materialIndex:u,normal:y.getNormal(Ht,Ut,Wt,new D)},uv:m,barycoord:p}}function Yt(e,t,n,r,i,a,o,s){let{geometry:c,_indirectBuffer:l}=e;for(let e=r,l=r+i;e<l;e++)Vt(c,t,n,e,a,o,s)}function Xt(e,t,n,r,i,a,o){let{geometry:s,_indirectBuffer:c}=e,l=1/0,u=null;for(let e=r,c=r+i;e<c;e++){let r;r=Vt(s,t,n,e,null,a,o),r&&r.distance<l&&(u=r,l=r.distance)}return u}function Zt(e,t,n,r,i,a,o){let{geometry:s}=n,{index:c}=s,l=s.attributes.position;for(let n=e,s=t+e;n<s;n++){let e;if(e=n,q(o,e*3,c,l),o.needsUpdate=!0,r(o,e,i,a))return!0}return!1}function Qt(e,t=null){t&&Array.isArray(t)&&(t=new Set(t));let n=e.geometry,r=n.index?n.index.array:null,i=n.attributes.position,a,o,s,c,l=0,u=e._roots;for(let e=0,t=u.length;e<t;e++)a=u[e],o=new Uint32Array(a),s=new Uint16Array(a),c=new Float32Array(a),d(0,l),l+=a.byteLength;function d(e,n,a=!1){let l=e*2;if(k(l,s)){let t=A(e,o),n=j(l,s),a=1/0,u=1/0,d=1/0,f=-1/0,p=-1/0,m=-1/0;for(let e=3*t,o=3*(t+n);e<o;e++){let t=r[e],n=i.getX(t),o=i.getY(t),s=i.getZ(t);n<a&&(a=n),n>f&&(f=n),o<u&&(u=o),o>p&&(p=o),s<d&&(d=s),s>m&&(m=s)}return c[e+0]!==a||c[e+1]!==u||c[e+2]!==d||c[e+3]!==f||c[e+4]!==p||c[e+5]!==m?(c[e+0]=a,c[e+1]=u,c[e+2]=d,c[e+3]=f,c[e+4]=p,c[e+5]=m,!0):!1}else{let r=M(e),i=N(e,o),s=a,l=!1,u=!1;if(t){if(!s){let e=r/8+n/32,a=i/8+n/32;l=t.has(e),u=t.has(a),s=!l&&!u}}else l=!0,u=!0;let f=s||l,p=s||u,m=!1;f&&(m=d(r,n,s));let h=!1;p&&(h=d(i,n,s));let g=m||h;if(g)for(let t=0;t<3;t++){let n=r+t,a=i+t,o=c[n],s=c[n+3],l=c[a],u=c[a+3];c[e+t]=o<l?o:l,c[e+t+3]=s>u?s:u}return g}}}function J(e,t,n,r,i){let a,o,s,c,l,u,d=1/n.direction.x,f=1/n.direction.y,p=1/n.direction.z,m=n.origin.x,h=n.origin.y,g=n.origin.z,_=t[e],v=t[e+3],y=t[e+1],b=t[e+3+1],x=t[e+2],S=t[e+3+2];return d>=0?(a=(_-m)*d,o=(v-m)*d):(a=(v-m)*d,o=(_-m)*d),f>=0?(s=(y-h)*f,c=(b-h)*f):(s=(b-h)*f,c=(y-h)*f),a>c||s>o||((s>a||isNaN(a))&&(a=s),(c<o||isNaN(o))&&(o=c),p>=0?(l=(x-g)*p,u=(S-g)*p):(l=(S-g)*p,u=(x-g)*p),a>u||l>o)?!1:((l>a||a!==a)&&(a=l),(u<o||o!==o)&&(o=u),a<=i&&o>=r)}function $t(e,t,n,r,i,a,o,s){let{geometry:c,_indirectBuffer:l}=e;for(let e=r,u=r+i;e<u;e++)Vt(c,t,n,l?l[e]:e,a,o,s)}function en(e,t,n,r,i,a,o){let{geometry:s,_indirectBuffer:c}=e,l=1/0,u=null;for(let e=r,d=r+i;e<d;e++){let r;r=Vt(s,t,n,c?c[e]:e,null,a,o),r&&r.distance<l&&(u=r,l=r.distance)}return u}function tn(e,t,n,r,i,a,o){let{geometry:s}=n,{index:c}=s,l=s.attributes.position;for(let s=e,u=t+e;s<u;s++){let e;if(e=n.resolveTriangleIndex(s),q(o,e*3,c,l),o.needsUpdate=!0,r(o,e,i,a))return!0}return!1}function nn(e,t,n,r,i,a,o){L.setBuffer(e._roots[t]),rn(0,e,n,r,i,a,o),L.clearBuffer()}function rn(e,t,n,r,i,a,o){let{float32Array:s,uint16Array:c,uint32Array:l}=L,u=e*2;if(k(u,c))Yt(t,n,r,A(e,l),j(u,c),i,a,o);else{let c=M(e);J(c,s,r,a,o)&&rn(c,t,n,r,i,a,o);let u=N(e,l);J(u,s,r,a,o)&&rn(u,t,n,r,i,a,o)}}var an=[`x`,`y`,`z`];function on(e,t,n,r,i,a){L.setBuffer(e._roots[t]);let o=sn(0,e,n,r,i,a);return L.clearBuffer(),o}function sn(e,t,n,r,i,a){let{float32Array:o,uint16Array:s,uint32Array:c}=L,l=e*2;if(k(l,s))return Xt(t,n,r,A(e,c),j(l,s),i,a);{let s=Oe(e,c),l=an[s],u=r.direction[l]>=0,d,f;u?(d=M(e),f=N(e,c)):(d=N(e,c),f=M(e));let p=J(d,o,r,i,a)?sn(d,t,n,r,i,a):null;if(p){let e=p.point[l];if(u?e<=o[f+s]:e>=o[f+s+3])return p}let m=J(f,o,r,i,a)?sn(f,t,n,r,i,a):null;return p&&m?p.distance<=m.distance?p:m:p||m||null}}var cn=new E,ln=new W,un=new W,dn=new d,fn=new G,pn=new G;function mn(e,t,n,r){L.setBuffer(e._roots[t]);let i=hn(0,e,n,r);return L.clearBuffer(),i}function hn(e,t,n,r,i=null){let{float32Array:a,uint16Array:o,uint32Array:s}=L,c=e*2;if(i===null&&(n.boundingBox||n.computeBoundingBox(),fn.set(n.boundingBox.min,n.boundingBox.max,r),i=fn),k(c,o)){let i=t.geometry,l=i.index,u=i.attributes.position,d=n.index,f=n.attributes.position,p=A(e,s),m=j(c,o);if(dn.copy(r).invert(),n.boundsTree)return O(P(e),a,pn),pn.matrix.copy(dn),pn.needsUpdate=!0,n.boundsTree.shapecast({intersectsBounds:e=>pn.intersectsBox(e),intersectsTriangle:e=>{e.a.applyMatrix4(r),e.b.applyMatrix4(r),e.c.applyMatrix4(r),e.needsUpdate=!0;for(let t=p*3,n=(m+p)*3;t<n;t+=3)if(q(un,t,l,u),un.needsUpdate=!0,e.intersectsTriangle(un))return!0;return!1}});{let e=dt(n);for(let t=p*3,n=(m+p)*3;t<n;t+=3){q(ln,t,l,u),ln.a.applyMatrix4(dn),ln.b.applyMatrix4(dn),ln.c.applyMatrix4(dn),ln.needsUpdate=!0;for(let t=0,n=e*3;t<n;t+=3)if(q(un,t,d,f),un.needsUpdate=!0,ln.intersectsTriangle(un))return!0}}}else{let o=M(e),c=N(e,s);return O(P(o),a,cn),!!(i.intersectsBox(cn)&&hn(o,t,n,r,i)||(O(P(c),a,cn),i.intersectsBox(cn)&&hn(c,t,n,r,i)))}}var gn=new d,_n=new G,vn=new G,yn=new D,bn=new D,xn=new D,Sn=new D;function Cn(e,t,n,r={},i={},a=0,o=1/0){t.boundingBox||t.computeBoundingBox(),_n.set(t.boundingBox.min,t.boundingBox.max,n),_n.needsUpdate=!0;let s=e.geometry,c=s.attributes.position,l=s.index,u=t.attributes.position,d=t.index,f=K.getPrimitive(),p=K.getPrimitive(),m=yn,h=bn,g=null,_=null;i&&(g=xn,_=Sn);let v=1/0,y=null,b=null;return gn.copy(n).invert(),vn.matrix.copy(gn),e.shapecast({boundsTraverseOrder:e=>_n.distanceToBox(e),intersectsBounds:(e,t,n)=>n<v&&n<o?(t&&(vn.min.copy(e.min),vn.max.copy(e.max),vn.needsUpdate=!0),!0):!1,intersectsRange:(e,r)=>{if(t.boundsTree)return t.boundsTree.shapecast({boundsTraverseOrder:e=>vn.distanceToBox(e),intersectsBounds:(e,t,n)=>n<v&&n<o,intersectsRange:(t,i)=>{for(let o=t,s=t+i;o<s;o++){q(p,3*o,d,u),p.a.applyMatrix4(n),p.b.applyMatrix4(n),p.c.applyMatrix4(n),p.needsUpdate=!0;for(let t=e,n=e+r;t<n;t++){q(f,3*t,l,c),f.needsUpdate=!0;let e=f.distanceToTriangle(p,m,g);if(e<v&&(h.copy(m),_&&_.copy(g),v=e,y=t,b=o),e<a)return!0}}}});{let i=dt(t);for(let t=0,o=i;t<o;t++){q(p,3*t,d,u),p.a.applyMatrix4(n),p.b.applyMatrix4(n),p.c.applyMatrix4(n),p.needsUpdate=!0;for(let n=e,i=e+r;n<i;n++){q(f,3*n,l,c),f.needsUpdate=!0;let e=f.distanceToTriangle(p,m,g);if(e<v&&(h.copy(m),_&&_.copy(g),v=e,y=n,b=t),e<a)return!0}}}}}),K.releasePrimitive(f),K.releasePrimitive(p),v===1/0?null:(r.point?r.point.copy(h):r.point=h.clone(),r.distance=v,r.faceIndex=y,i&&(i.point?i.point.copy(_):i.point=_.clone(),i.point.applyMatrix4(gn),h.applyMatrix4(gn),i.distance=h.sub(i.point).length(),i.faceIndex=b),r)}function wn(e,t=null){t&&Array.isArray(t)&&(t=new Set(t));let n=e.geometry,r=n.index?n.index.array:null,i=n.attributes.position,a,o,s,c,l=0,u=e._roots;for(let e=0,t=u.length;e<t;e++)a=u[e],o=new Uint32Array(a),s=new Uint16Array(a),c=new Float32Array(a),d(0,l),l+=a.byteLength;function d(n,a,l=!1){let u=n*2;if(k(u,s)){let t=A(n,o),a=j(u,s),l=1/0,d=1/0,f=1/0,p=-1/0,m=-1/0,h=-1/0;for(let n=t,o=t+a;n<o;n++){let t=3*e.resolveTriangleIndex(n);for(let e=0;e<3;e++){let n=t+e;n=r?r[n]:n;let a=i.getX(n),o=i.getY(n),s=i.getZ(n);a<l&&(l=a),a>p&&(p=a),o<d&&(d=o),o>m&&(m=o),s<f&&(f=s),s>h&&(h=s)}}return c[n+0]!==l||c[n+1]!==d||c[n+2]!==f||c[n+3]!==p||c[n+4]!==m||c[n+5]!==h?(c[n+0]=l,c[n+1]=d,c[n+2]=f,c[n+3]=p,c[n+4]=m,c[n+5]=h,!0):!1}else{let e=M(n),r=N(n,o),i=l,s=!1,u=!1;if(t){if(!i){let n=e/8+a/32,o=r/8+a/32;s=t.has(n),u=t.has(o),i=!s&&!u}}else s=!0,u=!0;let f=i||s,p=i||u,m=!1;f&&(m=d(e,a,i));let h=!1;p&&(h=d(r,a,i));let g=m||h;if(g)for(let t=0;t<3;t++){let i=e+t,a=r+t,o=c[i],s=c[i+3],l=c[a],u=c[a+3];c[n+t]=o<l?o:l,c[n+t+3]=s>u?s:u}return g}}}function Tn(e,t,n,r,i,a,o){L.setBuffer(e._roots[t]),En(0,e,n,r,i,a,o),L.clearBuffer()}function En(e,t,n,r,i,a,o){let{float32Array:s,uint16Array:c,uint32Array:l}=L,u=e*2;if(k(u,c))$t(t,n,r,A(e,l),j(u,c),i,a,o);else{let c=M(e);J(c,s,r,a,o)&&En(c,t,n,r,i,a,o);let u=N(e,l);J(u,s,r,a,o)&&En(u,t,n,r,i,a,o)}}var Dn=[`x`,`y`,`z`];function On(e,t,n,r,i,a){L.setBuffer(e._roots[t]);let o=kn(0,e,n,r,i,a);return L.clearBuffer(),o}function kn(e,t,n,r,i,a){let{float32Array:o,uint16Array:s,uint32Array:c}=L,l=e*2;if(k(l,s))return en(t,n,r,A(e,c),j(l,s),i,a);{let s=Oe(e,c),l=Dn[s],u=r.direction[l]>=0,d,f;u?(d=M(e),f=N(e,c)):(d=N(e,c),f=M(e));let p=J(d,o,r,i,a)?kn(d,t,n,r,i,a):null;if(p){let e=p.point[l];if(u?e<=o[f+s]:e>=o[f+s+3])return p}let m=J(f,o,r,i,a)?kn(f,t,n,r,i,a):null;return p&&m?p.distance<=m.distance?p:m:p||m||null}}var An=new E,jn=new W,Mn=new W,Nn=new d,Pn=new G,Fn=new G;function In(e,t,n,r){L.setBuffer(e._roots[t]);let i=Ln(0,e,n,r);return L.clearBuffer(),i}function Ln(e,t,n,r,i=null){let{float32Array:a,uint16Array:o,uint32Array:s}=L,c=e*2;if(i===null&&(n.boundingBox||n.computeBoundingBox(),Pn.set(n.boundingBox.min,n.boundingBox.max,r),i=Pn),k(c,o)){let i=t.geometry,l=i.index,u=i.attributes.position,d=n.index,f=n.attributes.position,p=A(e,s),m=j(c,o);if(Nn.copy(r).invert(),n.boundsTree)return O(P(e),a,Fn),Fn.matrix.copy(Nn),Fn.needsUpdate=!0,n.boundsTree.shapecast({intersectsBounds:e=>Fn.intersectsBox(e),intersectsTriangle:e=>{e.a.applyMatrix4(r),e.b.applyMatrix4(r),e.c.applyMatrix4(r),e.needsUpdate=!0;for(let n=p,r=m+p;n<r;n++)if(q(Mn,3*t.resolveTriangleIndex(n),l,u),Mn.needsUpdate=!0,e.intersectsTriangle(Mn))return!0;return!1}});{let e=dt(n);for(let n=p,r=m+p;n<r;n++){q(jn,3*t.resolveTriangleIndex(n),l,u),jn.a.applyMatrix4(Nn),jn.b.applyMatrix4(Nn),jn.c.applyMatrix4(Nn),jn.needsUpdate=!0;for(let t=0,n=e*3;t<n;t+=3)if(q(Mn,t,d,f),Mn.needsUpdate=!0,jn.intersectsTriangle(Mn))return!0}}}else{let o=M(e),c=N(e,s);return O(P(o),a,An),!!(i.intersectsBox(An)&&Ln(o,t,n,r,i)||(O(P(c),a,An),i.intersectsBox(An)&&Ln(c,t,n,r,i)))}}var Rn=new d,zn=new G,Bn=new G,Vn=new D,Hn=new D,Un=new D,Wn=new D;function Gn(e,t,n,r={},i={},a=0,o=1/0){t.boundingBox||t.computeBoundingBox(),zn.set(t.boundingBox.min,t.boundingBox.max,n),zn.needsUpdate=!0;let s=e.geometry,c=s.attributes.position,l=s.index,u=t.attributes.position,d=t.index,f=K.getPrimitive(),p=K.getPrimitive(),m=Vn,h=Hn,g=null,_=null;i&&(g=Un,_=Wn);let v=1/0,y=null,b=null;return Rn.copy(n).invert(),Bn.matrix.copy(Rn),e.shapecast({boundsTraverseOrder:e=>zn.distanceToBox(e),intersectsBounds:(e,t,n)=>n<v&&n<o?(t&&(Bn.min.copy(e.min),Bn.max.copy(e.max),Bn.needsUpdate=!0),!0):!1,intersectsRange:(r,i)=>{if(t.boundsTree){let s=t.boundsTree;return s.shapecast({boundsTraverseOrder:e=>Bn.distanceToBox(e),intersectsBounds:(e,t,n)=>n<v&&n<o,intersectsRange:(t,o)=>{for(let x=t,S=t+o;x<S;x++){let t=s.resolveTriangleIndex(x);q(p,3*t,d,u),p.a.applyMatrix4(n),p.b.applyMatrix4(n),p.c.applyMatrix4(n),p.needsUpdate=!0;for(let t=r,n=r+i;t<n;t++){let n=e.resolveTriangleIndex(t);q(f,3*n,l,c),f.needsUpdate=!0;let r=f.distanceToTriangle(p,m,g);if(r<v&&(h.copy(m),_&&_.copy(g),v=r,y=t,b=x),r<a)return!0}}}})}else{let o=dt(t);for(let t=0,s=o;t<s;t++){q(p,3*t,d,u),p.a.applyMatrix4(n),p.b.applyMatrix4(n),p.c.applyMatrix4(n),p.needsUpdate=!0;for(let n=r,o=r+i;n<o;n++){let r=e.resolveTriangleIndex(n);q(f,3*r,l,c),f.needsUpdate=!0;let i=f.distanceToTriangle(p,m,g);if(i<v&&(h.copy(m),_&&_.copy(g),v=i,y=n,b=t),i<a)return!0}}}}}),K.releasePrimitive(f),K.releasePrimitive(p),v===1/0?null:(r.point?r.point.copy(h):r.point=h.clone(),r.distance=v,r.faceIndex=y,i&&(i.point?i.point.copy(_):i.point=_.clone(),i.point.applyMatrix4(Rn),h.applyMatrix4(Rn),i.distance=h.sub(i.point).length(),i.faceIndex=b),r)}function Kn(e,t,n){return e===null?null:(e.point.applyMatrix4(t.matrixWorld),e.distance=e.point.distanceTo(n.ray.origin),e.object=t,e)}var qn=new G,Jn=new S,Yn=new D,Xn=new d,Zn=new D,Qn=[`getX`,`getY`,`getZ`],$n=class e extends vt{static serialize(e,t={}){t={cloneBuffers:!0,...t};let n=e.geometry,r=e._roots,i=e._indirectBuffer,a=n.getIndex(),o={version:1,roots:null,index:null,indirectBuffer:null};return t.cloneBuffers?(o.roots=r.map(e=>e.slice()),o.index=a?a.array.slice():null,o.indirectBuffer=i?i.slice():null):(o.roots=r,o.index=a?a.array:null,o.indirectBuffer=i),o}static deserialize(t,n,r={}){r={setIndex:!0,indirect:!!t.indirectBuffer,...r};let{index:i,roots:a,indirectBuffer:o}=t;t.version||(console.warn(`MeshBVH.deserialize: Serialization format has been changed and will be fixed up. It is recommended to regenerate any stored serialized data.`),c(a));let s=new e(n,{...r,[xe]:!0});if(s._roots=a,s._indirectBuffer=o||null,r.setIndex){let e=n.getIndex();if(e===null){let e=new _(t.index,1,!1);n.setIndex(e)}else e.array!==i&&(e.array.set(i),e.needsUpdate=!0)}return s;function c(e){for(let t=0;t<e.length;t++){let n=e[t],r=new Uint32Array(n),i=new Uint16Array(n);for(let e=0,t=n.byteLength/32;e<t;e++){let t=8*e;k(2*t,i)||(r[t+6]=r[t+6]/8-e)}}}}get primitiveStride(){return 3}get resolveTriangleIndex(){return this.resolvePrimitiveIndex}constructor(e,t={}){t.maxLeafTris&&(console.warn(`MeshBVH: "maxLeafTris" option has been deprecated. Use maxLeafSize, instead.`),t={...t,maxLeafSize:t.maxLeafTris}),super(e,t)}shiftTriangleOffsets(e){return super.shiftPrimitiveOffsets(e)}writePrimitiveBounds(e,t,n){let r=this.geometry,i=this._indirectBuffer,a=r.attributes.position,o=r.index?r.index.array:null,s=(i?i[e]:e)*3,c=s+0,l=s+1,u=s+2;o&&(c=o[c],l=o[l],u=o[u]);for(let e=0;e<3;e++){let r=a[Qn[e]](c),i=a[Qn[e]](l),o=a[Qn[e]](u),s=r;i<s&&(s=i),o<s&&(s=o);let d=r;i>d&&(d=i),o>d&&(d=o),t[n+e]=s,t[n+e+3]=d}return t}computePrimitiveBounds(e,t,n){let r=this.geometry,i=this._indirectBuffer,a=r.attributes.position,o=r.index?r.index.array:null,s=a.normalized;if(e<0||t+e-n.offset>n.length/6)throw Error(`MeshBVH: compute triangle bounds range is invalid.`);let c=a.array,l=a.offset||0,u=3;a.isInterleavedBufferAttribute&&(u=a.data.stride);let d=[`getX`,`getY`,`getZ`],f=n.offset;for(let r=e,p=e+t;r<p;r++){let e=(i?i[r]:r)*3,t=(r-f)*6,p=e+0,m=e+1,h=e+2;o&&(p=o[p],m=o[m],h=o[h]),s||(p=p*u+l,m=m*u+l,h=h*u+l);for(let e=0;e<3;e++){let r,i,o;s?(r=a[d[e]](p),i=a[d[e]](m),o=a[d[e]](h)):(r=c[p+e],i=c[m+e],o=c[h+e]);let l=r;i<l&&(l=i),o<l&&(l=o);let u=r;i>u&&(u=i),o>u&&(u=o);let f=(u-l)/2,g=e*2;n[t+g+0]=l+f,n[t+g+1]=f+(Math.abs(l)+f)*be}}return n}raycastObject3D(e,t,n=[]){let{material:r}=e;if(r===void 0)return;Xn.copy(e.matrixWorld).invert(),Jn.copy(t.ray).applyMatrix4(Xn),Zn.setFromMatrixScale(e.matrixWorld),Yn.copy(Jn.direction).multiply(Zn);let i=Yn.length(),a=t.near/i,o=t.far/i;if(t.firstHitOnly===!0){let i=this.raycastFirst(Jn,r,a,o);i=Kn(i,e,t),i&&n.push(i)}else{let i=this.raycast(Jn,r,a,o);for(let r=0,a=i.length;r<a;r++){let a=Kn(i[r],e,t);a&&n.push(a)}}return n}refit(e=null){return(this.indirect?wn:Qt)(this,e)}raycast(e,t=0,n=0,r=1/0){let i=this._roots,a=[],o=this.indirect?Tn:nn;for(let s=0,c=i.length;s<c;s++)o(this,s,t,e,a,n,r);return a}raycastFirst(e,t=0,n=0,r=1/0){let i=this._roots,a=null,o=this.indirect?On:on;for(let s=0,c=i.length;s<c;s++){let i=o(this,s,t,e,n,r);i!=null&&(a==null||i.distance<a.distance)&&(a=i)}return a}intersectsGeometry(e,t){let n=!1,r=this._roots,i=this.indirect?In:mn;for(let a=0,o=r.length;a<o&&(n=i(this,a,e,t),!n);a++);return n}shapecast(e){let t=K.getPrimitive(),n=super.shapecast({...e,intersectsPrimitive:e.intersectsTriangle,scratchPrimitive:t,iterate:this.indirect?tn:Zt});return K.releasePrimitive(t),n}bvhcast(t,n,r){let{intersectsRanges:i,intersectsTriangles:a}=r,o=K.getPrimitive(),s=this.geometry.index,c=this.geometry.attributes.position,l=this.indirect?e=>{let t=this.resolveTriangleIndex(e);q(o,t*3,s,c)}:e=>{q(o,e*3,s,c)},u=K.getPrimitive(),d=t.geometry.index,f=t.geometry.attributes.position,p=t.indirect?e=>{let n=t.resolveTriangleIndex(e);q(u,n*3,d,f)}:e=>{q(u,e*3,d,f)};if(a){if(!(t instanceof e))throw Error(`MeshBVH: "intersectsTriangles" callback can only be used with another MeshBVH.`);let r=(e,t,r,i,s,c,d,f)=>{for(let m=r,h=r+i;m<h;m++){p(m),u.a.applyMatrix4(n),u.b.applyMatrix4(n),u.c.applyMatrix4(n),u.needsUpdate=!0;for(let n=e,r=e+t;n<r;n++)if(l(n),o.needsUpdate=!0,a(o,u,n,m,s,c,d,f))return!0}return!1};if(i){let e=i;i=function(t,n,i,a,o,s,c,l){return e(t,n,i,a,o,s,c,l)?!0:r(t,n,i,a,o,s,c,l)}}else i=r}return super.bvhcast(t,n,{intersectsRanges:i})}intersectsBox(e,t){return qn.set(e.min,e.max,t),qn.needsUpdate=!0,this.shapecast({intersectsBounds:e=>qn.intersectsBox(e),intersectsTriangle:e=>qn.intersectsTriangle(e)})}intersectsSphere(e){return this.shapecast({intersectsBounds:t=>e.intersectsBox(t),intersectsTriangle:t=>t.intersectsSphere(e)})}closestPointToGeometry(e,t,n={},r={},i=0,a=1/0){return(this.indirect?Gn:Cn)(this,e,t,n,r,i,a)}closestPointToPoint(e,t={},n=0,r=1/0){return Et(this,e,t,n,r)}},er=new d,tr=new S,nr=new Ke(()=>new u),rr=new D,ir=new D,ar=new E,or=[`getX`,`getY`,`getZ`],sr=class extends vt{get primitiveStride(){return 2}writePrimitiveBounds(e,t,n){let r=this._indirectBuffer,{geometry:i,primitiveStride:a}=this,o=i.attributes.position,s=i.index,c=s?s.count:o.count,l=(r?r[e]:e)*a,u=(l+1)%c;s&&(l=s.getX(l),u=s.getX(u));for(let e=0;e<3;e++){let r=o[or[e]](l),i=o[or[e]](u),a=r<i?r:i,s=r>i?r:i;t[n+e]=a,t[n+e+3]=s}return t}shapecast(e){let t=nr.getPrimitive(),n=super.shapecast({...e,intersectsPrimitive:e.intersectsLine,scratchPrimitive:t,iterate:ur});return nr.releasePrimitive(t),n}raycastObject3D(e,t,n=[]){let{matrixWorld:r}=e,{firstHitOnly:i}=t;er.copy(r).invert(),tr.copy(t.ray).applyMatrix4(er);let a=t.params.Line.threshold/((e.scale.x+e.scale.y+e.scale.z)/3),o=a*a,s=null,c=1/0;return this.shapecast({boundsTraverseOrder:e=>e.distanceToPoint(tr.origin),intersectsBounds:e=>(ar.copy(e).expandByScalar(Math.abs(a)),+!!tr.intersectsBox(ar)),intersectsLine:(a,l)=>{if(tr.distanceSqToSegment(a.start,a.end,rr,ir)>o)return;rr.applyMatrix4(e.matrixWorld);let u=t.ray.origin.distanceTo(rr);u<t.near||u>t.far||i&&u>=c||(c=u,l=this.resolvePrimitiveIndex(l),s={distance:u,point:ir.clone().applyMatrix4(r),index:l*this.primitiveStride,face:null,faceIndex:null,barycoord:null,object:e},i||n.push(s))}}),i&&s&&n.push(s),n}},cr=class extends sr{get primitiveStride(){return 1}constructor(e,t={}){t={...t,indirect:!0},super(e,t)}},lr=class extends cr{getRootRanges(...e){let t=super.getRootRanges(...e);return t.forEach(e=>e.count--),t}};function ur(e,t,n,r,i,a,o){let{geometry:s,primitiveStride:c}=n,{index:l}=s,u=s.attributes.position,d=l?l.count:u.count;for(let s=e,f=t+e;s<f;s++){let e=n.resolvePrimitiveIndex(s)*c,t=(e+1)%d;if(l&&(e=l.getX(e),t=l.getX(t)),o.start.fromBufferAttribute(u,e),o.end.fromBufferAttribute(u,t),r(o,s,i,a))return!0}return!1}var dr=new d,fr=new S,pr=new Ke(()=>new D),mr=new E,hr=class extends vt{get primitiveStride(){return 1}writePrimitiveBounds(e,t,n){let r=this._indirectBuffer,{geometry:i}=this,a=i.attributes.position,o=i.index,s=r?r[e]:e;o&&(s=o.getX(s));let c=a.getX(s),l=a.getY(s),u=a.getZ(s);return t[n+0]=c,t[n+1]=l,t[n+2]=u,t[n+3]=c,t[n+4]=l,t[n+5]=u,t}shapecast(e){let t=pr.getPrimitive(),n=super.shapecast({...e,intersectsPrimitive:e.intersectsPoint,scratchPrimitive:t,iterate:gr});return pr.releasePrimitive(t),n}raycastObject3D(e,t,n=[]){let{geometry:r}=this,{matrixWorld:i}=e,{firstHitOnly:a}=t;dr.copy(i).invert(),fr.copy(t.ray).applyMatrix4(dr);let o=t.params.Points.threshold/((e.scale.x+e.scale.y+e.scale.z)/3),s=o*o,c=null,l=1/0;return this.shapecast({boundsTraverseOrder:e=>e.distanceToPoint(fr.origin),intersectsBounds:e=>(mr.copy(e).expandByScalar(Math.abs(o)),+!!fr.intersectsBox(mr)),intersectsPoint:(o,u)=>{let d=fr.distanceSqToPoint(o);if(d<s){let s=new D;fr.closestPointToPoint(o,s),s.applyMatrix4(i);let f=t.ray.origin.distanceTo(s);if(f<t.near||f>t.far||a&&f>=l)return;l=f,u=this.resolvePrimitiveIndex(u),c={distance:f,distanceToRay:Math.sqrt(d),point:s,index:r.index?r.index.getX(u):u,face:null,faceIndex:null,barycoord:null,object:e},a||n.push(c)}}}),a&&c&&n.push(c),n}};function gr(e,t,n,r,i,a,o){let{geometry:s}=n,{index:c}=s,l=s.attributes.position;for(let s=e,u=t+e;s<u;s++){let e=n.resolvePrimitiveIndex(s),t=c?c.array[e]:e;if(o.fromBufferAttribute(l,t),r(o,s,i,a))return!0}return!1}var Y=new T,X=new d,_r=new d,vr=new E,yr=new se,Z=new D,br=new S,Q=new ue,xr={},Sr=class extends ct{constructor(e,t={}){t={precise:!1,includeInstances:!0,matrixWorld:Array.isArray(e)?new d:e.matrixWorld,maxLeafSize:1,...t},super();let n=new Set;Er(e,n);let r=Array.from(n),i=Math.ceil(Math.log2(r.length)),a=Cr(i);this.objects=r,this.idBits=i,this.idMask=a,this.primitiveBuffer=null,this.primitiveBufferStride=1,this.precise=t.precise,this.includeInstances=t.includeInstances,this.matrixWorld=t.matrixWorld,this.init(t)}getObjectFromId(e){let{idMask:t,objects:n}=this;return n[wr(e,t)]}getInstanceFromId(e){let{idMask:t,idBits:n}=this;return Tr(e,n,t)}init(e){let{objects:t,idBits:n}=this;this.primitiveBuffer=new Uint32Array(this._countPrimitives(t)),this._fillPrimitiveBuffer(t,n,this.primitiveBuffer),super.init(e)}writePrimitiveBounds(e,t,n){let{primitiveBuffer:r}=this;_r.copy(this.matrixWorld).invert(),this._getPrimitiveBoundingBox(r[e],_r,vr);let{min:i,max:a}=vr;t[n+0]=i.x,t[n+1]=i.y,t[n+2]=i.z,t[n+3]=a.x,t[n+4]=a.y,t[n+5]=a.z}getRootRanges(){return[{offset:0,count:this.primitiveBuffer.length}]}shapecast(e){return super.shapecast({...e,intersectsPrimitive:e.intersectsObject,scratchPrimitive:null,iterate:Or})}raycast(e,t=[]){let{matrixWorld:n,includeInstances:r}=this,{firstHitOnly:i}=e,a=[];_r.copy(n).invert(),br.copy(e.ray).applyMatrix4(_r);let o=1/0,s=null;return this.shapecast({boundsTraverseOrder:e=>e.distanceToPoint(br.origin),intersectsBounds:t=>i?br.intersectBox(t,Z)?(Z.applyMatrix4(n),+(e.ray.origin.distanceTo(Z)<o)):0:+!!br.intersectsBox(t),intersectsObject(n,c){if(n.visible){if(a.length=0,n.isInstancedMesh&&r)Q.geometry=n.geometry,Q.material=n.material,n.getMatrixAt(c,Q.matrixWorld),Q.matrixWorld.premultiply(n.matrixWorld),Q.raycast(e,a),a.forEach(e=>{e.object=n,e.instanceId=c}),Q.material=null;else if(n.isBatchedMesh&&r){if(!n.getVisibleAt(c))return;let t=n.getGeometryIdAt(c),r=n.getGeometryRangeAt(t,xr);Y.index=n.geometry.index,Y.attributes=n.geometry.attributes,Y.setDrawRange(r.start,r.count),Q.geometry=Y,Q.material=n.material,n.getMatrixAt(c,Q.matrixWorld),Q.matrixWorld.premultiply(n.matrixWorld),Q.raycast(e,a),a.forEach(e=>{e.object=n,e.batchId=c}),Q.material=null,Y.index=null,Y.attributes=null,Y.setDrawRange(0,1/0)}else n.raycast(e,a);i?a.forEach(e=>{e.distance<o&&(o=e.distance,s=e)}):t.push(...a)}}}),i&&s&&t.push(s),t}_getPrimitiveBoundingBox(e,t,n){let{objects:r,idMask:i,idBits:a,precise:o,includeInstances:s}=this,c=wr(e,i),l=Tr(e,a,i),u=r[c];if(!s&&(u.isInstancedMesh||u.isBatchedMesh))u.boundingBox||u.computeBoundingBox(),u.boundingSphere||u.computeBoundingSphere(),X.copy(u.matrixWorld).premultiply(t),yr.copy(u.boundingSphere).applyMatrix4(X),n.copy(u.boundingBox).applyMatrix4(X),kr(n,yr);else if(o)if(u.isInstancedMesh)u.getMatrixAt(l,X),X.premultiply(u.matrixWorld).premultiply(t),Dr(u.geometry,X,n);else if(u.isBatchedMesh){let e=u.getGeometryIdAt(l),r=u.getGeometryRangeAt(e,xr);Y.index=u.geometry.index,Y.attributes=u.geometry.attributes,Y.setDrawRange(r.start,r.count),u.getMatrixAt(l,X),X.premultiply(u.matrixWorld).premultiply(t),Dr(Y,X,n),Y.attributes=null}else X.copy(u.matrixWorld).premultiply(t),n.setFromObject(u,!0).applyMatrix4(t);else if(u.isInstancedMesh)u.geometry.boundingBox||u.geometry.computeBoundingBox(),u.geometry.boundingSphere||u.geometry.computeBoundingSphere(),u.getMatrixAt(l,X),X.premultiply(u.matrixWorld).premultiply(t),yr.copy(u.geometry.boundingSphere).applyMatrix4(X),n.copy(u.geometry.boundingBox).applyMatrix4(X),kr(n,yr);else if(u.isBatchedMesh){let e=u.getGeometryIdAt(l);u.getMatrixAt(l,X),X.premultiply(u.matrixWorld).premultiply(t),u.getBoundingSphereAt(e,yr).applyMatrix4(X),u.getBoundingBoxAt(e,n).applyMatrix4(X),kr(n,yr)}else n.setFromObject(u,!1).applyMatrix4(t)}_countPrimitives(e){let{includeInstances:t}=this,n=0;return e.forEach(e=>{if(e.isInstancedMesh&&t)n+=e.count;else if(e.isBatchedMesh&&t){if(!(`instanceCount`in e))throw Error(`ObjectBVH: Three.js revision >= r169 is required to use BatchedMesh.`);n+=e.instanceCount}else n++}),n}_fillPrimitiveBuffer(e,t,n){let{includeInstances:r}=this,i=0;e.forEach((e,a)=>{if(e.isInstancedMesh&&r){let r=e.count;for(let e=0;e<r;e++)n[i]=e<<t|a,i++}else if(e.isBatchedMesh&&r){let{instanceCount:r,maxInstanceCount:o}=e,s=0,c=0;for(;s<r&&c<o;){try{e.getVisibleAt(c),n[i]=c<<t|a,s++,i++}catch{}c++}}else n[i]=a,i++})}};function Cr(e){let t=0;for(let n=0;n<e;n++)t=t<<1|1;return t}function wr(e,t){return e&t}function Tr(e,t,n){return(e&~n)>>t}function Er(e,t=new Set){Array.isArray(e)?e.forEach(e=>Er(e,t)):e.traverse(e=>{(e.isMesh||e.isLine||e.isPoints)&&t.add(e)})}function Dr(e,t,n){n.makeEmpty();let r=e.drawRange,i=e.index,a=e.attributes.position,o=r.start,s=i?i.count:a.count,c=Math.min(s-o,r.count);for(let e=o,r=o+c;e<r;e++){let r=e;i&&(r=i.getX(r)),Z.fromBufferAttribute(a,r).applyMatrix4(t),n.expandByPoint(Z)}return n}function Or(e,t,n,r,i,a){let{primitiveBuffer:o,objects:s,idMask:c,idBits:l}=n;for(let n=e,u=t+e;n<u;n++){let e=o[n],t=wr(e,c),u=Tr(e,l,c),d=s[t];if(r(d,u,i,a))return!0}return!1}function kr(e,t){Z.copy(t.center).addScalar(-t.radius),e.min.max(Z),Z.copy(t.center).addScalar(t.radius),e.max.min(Z)}var Ar=new D,jr=new D,Mr=new D,Nr=new S,Pr=new d,Fr=new D,Ir=[`x`,`y`,`z`],Lr=!0,Rr=new h,zr=new h,Br=new h,Vr=new D,Hr=new D,Ur=new D,Wr=class extends vt{get primitiveStride(){return 3}constructor(e,t={}){if(!e.isMesh)throw Error(`SkinnedMeshBVH: First argument must be a Mesh.`);super(e.geometry,{...t,[xe]:!0}),this.mesh=e,t[xe]||this.init(t)}writePrimitiveBounds(e,t,n){let{mesh:r,geometry:i}=this,a=this._indirectBuffer,o=i.index?i.index.array:null,s=(a?a[e]:e)*3,c=s+0,l=s+1,u=s+2;o&&(c=o[c],l=o[l],u=o[u]),r.getVertexPosition(c,Ar),r.getVertexPosition(l,jr),r.getVertexPosition(u,Mr);for(let e=0;e<3;e++){let r=Ir[e],i=Ar[r],a=jr[r],o=Mr[r],s=i;a<s&&(s=a),o<s&&(s=o);let c=i;a>c&&(c=a),o>c&&(c=o),t[n+e]=s,t[n+e+3]=c}return t}shapecast(e){let t=new W;return super.shapecast({...e,intersectsPrimitive:e.intersectsTriangle,scratchPrimitive:t,iterate:Gr})}raycastObject3D(e,t,n=[]){let{material:r}=e;if(r===void 0)return;let{matrixWorld:i}=e,{firstHitOnly:a}=t;Pr.copy(i).invert(),Nr.copy(t.ray).applyMatrix4(Pr);let o=null,s=1/0;return this.shapecast({boundsTraverseOrder:e=>e.distanceToPoint(Nr.origin),intersectsBounds:e=>+!!Nr.intersectsBox(e),intersectsTriangle:(c,l)=>{let u=null;if(u=r.side===0?Nr.intersectTriangle(c.a,c.b,c.c,!0,Fr):r.side===1?Nr.intersectTriangle(c.c,c.b,c.a,!0,Fr):Nr.intersectTriangle(c.a,c.b,c.c,!1,Fr),!u)return;u=u.clone().applyMatrix4(i);let d=t.ray.origin.distanceTo(u);if(d>=t.near&&d<=t.far){if(a&&d>=s)return;let{geometry:t}=this,{index:r}=t,i=this.resolvePrimitiveIndex(l),f=i*3,p=f+0,m=f+1,g=f+2;r&&(p=r.array[p],m=r.array[m],g=r.array[g]);let _={distance:d,point:u.clone(),object:e,uv:null,uv1:null,normal:null,face:{a:p,b:m,c:g,normal:y.getNormal(c.a,c.b,c.c,new D),materialIndex:0},faceIndex:i};if(Lr){let e=new D;y.getBarycoord(Fr,c.a,c.b,c.c,e),_.barycoord=e}let v=t.attributes.uv,b=t.attributes.uv1,x=t.attributes.normal;if(v){Rr.fromBufferAttribute(v,p),zr.fromBufferAttribute(v,m),Br.fromBufferAttribute(v,g),_.uv=new h;let e=y.getInterpolation(Fr,c.a,c.b,c.c,Rr,zr,Br,_.uv);Lr||(_.uv=e)}if(b){Rr.fromBufferAttribute(b,p),zr.fromBufferAttribute(b,m),Br.fromBufferAttribute(b,g),_.uv1=new h;let e=y.getInterpolation(Fr,c.a,c.b,c.c,Rr,zr,Br,_.uv1);Lr||(_.uv1=e)}if(x){Vr.fromBufferAttribute(x,p),Hr.fromBufferAttribute(x,m),Ur.fromBufferAttribute(x,g),_.normal=new D;let e=y.getInterpolation(Fr,c.a,c.b,c.c,Vr,Hr,Ur,_.normal);_.normal.dot(Nr.direction)>0&&_.normal.multiplyScalar(-1),Lr||(_.normal=e)}s=_.distance,o=_,a||n.push(_)}}}),a&&o&&n.push(o),n}};function Gr(e,t,n,r,i,a,o){let{mesh:s,geometry:c}=n,l=c.index?c.index.array:null;for(let c=e,u=t+e;c<u;c++){let e=n.resolvePrimitiveIndex(c),t=3*e+0,u=3*e+1,d=3*e+2;if(l&&(t=l[t],u=l[u],d=l[d]),s.getVertexPosition(t,o.a),s.getVertexPosition(u,o.b),s.getVertexPosition(d,o.c),o.needsUpdate=!0,r(o,c,i,a))return!0}return!1}var Kr=new E,qr=new d,Jr=new D,Yr=class extends s{get isMesh(){return!this.displayEdges}get isLineSegments(){return this.displayEdges}get isLine(){return this.displayEdges}getVertexPosition(...e){return ue.prototype.getVertexPosition.call(this,...e)}constructor(e,t,n=10,r=0){super(),this.material=t,this.geometry=new T,this.name=`BVHRootHelper`,this.depth=n,this.displayParents=!1,this.bvh=e,this.displayEdges=!0,this._group=r}raycast(){}update(){let e=this.bvh;this.geometry.dispose(),this.visible=!1,e&&(this.geometry=this.getGeometry(e),this.visible=!0)}getGeometry(e){let t=this._group,n=null;if(t!==-1)n=this.getBVHBoundPositions(e,t);else{let t=e._roots.map((t,n)=>this.getBVHBoundPositions(e,n)),r=t.reduce((e,t)=>e+t.length,0);n=new Float32Array(r);let i=0;t.forEach(e=>{n.set(e,i),i+=e.length})}let r=this.getBVHBoundIndices(n),i=new T;return i.setIndex(new _(r,1,!1)),i.setAttribute(`position`,new _(n,3,!1)),i}getBVHBoundIndices(e){let t=e.length/24,n,r;r=this.displayEdges?new Uint8Array([0,4,1,5,2,6,3,7,0,2,1,3,4,6,5,7,0,1,2,3,4,5,6,7]):new Uint8Array([0,1,2,2,1,3,4,6,5,6,7,5,1,4,5,0,4,1,2,3,6,3,7,6,0,2,4,2,6,4,1,5,3,3,5,7]),n=e.length>65535?new Uint32Array(r.length*t):new Uint16Array(r.length*t);let i=r.length;for(let e=0;e<t;e++){let t=e*8,a=e*i;for(let e=0;e<i;e++)n[a+e]=t+r[e]}return n}getBVHBoundPositions(e,t=0,n=null){let r=this.depth-1,i=this.displayParents,a=0;e.traverse((e,t)=>{if(e>=r||t)return a++,!0;i&&a++},t);let o=0,s=new Float32Array(24*a);return e.traverse((e,t,a)=>{let c=e>=r||t;if(c||i){O(0,a,Kr);let{min:e,max:t}=Kr;for(let r=-1;r<=1;r+=2){let i=r<0?e.x:t.x;for(let r=-1;r<=1;r+=2){let a=r<0?e.y:t.y;for(let r=-1;r<=1;r+=2){let c=r<0?e.z:t.z;Jr.set(i,a,c),n&&Jr.applyMatrix4(n),Jr.toArray(s,o),o+=3}}}return c}},t),s}},Xr=class e extends r{get color(){return this.edgeMaterial.color}get opacity(){return this.edgeMaterial.opacity}set opacity(e){this.edgeMaterial.opacity=e,this.meshMaterial.opacity=e}get objectIndex(){return console.warn(`BVHHelper: "objectIndex" has been renamed "instanceId".`),this.instanceId}set objectIndex(e){console.warn(`BVHHelper: "objectIndex" has been renamed "instanceId".`),this.instanceId=e}constructor(e=null,t=null,r=10){e instanceof $n&&(r=t||10,t=e,e=null),typeof t==`number`&&(r=t,t=null),super(),this.name=`BVHHelper`,this.depth=r,this.mesh=e,this.bvh=t,this.displayParents=!1,this.displayEdges=!0,this.instanceId=0,this._roots=[];let i=new w({color:65416,transparent:!0,opacity:.3,depthWrite:!1}),a=new n({color:65416,transparent:!0,opacity:.3,depthWrite:!1});a.color=i.color,this.edgeMaterial=i,this.meshMaterial=a,this.update()}update(){let e=this.mesh,t=this.instanceId,n=this.bvh||e.boundsTree||e.geometry&&e.geometry.boundsTree||null;if(e&&e.isBatchedMesh&&e.boundsTrees&&!n&&t>=0){let r=e._drawInfo[t];r&&(n=e.boundsTrees[r.geometryIndex]||n)}let r=n?n._roots.length:0;for(;this._roots.length>r;){let e=this._roots.pop();e.geometry.dispose(),this.remove(e)}for(let e=0;e<r;e++){let{depth:t,edgeMaterial:r,meshMaterial:i,displayParents:a,displayEdges:o}=this;if(e>=this._roots.length){let i=new Yr(n,r,t,e);this.add(i),this._roots.push(i)}let s=this._roots[e];s.bvh=n,s.depth=t,s.displayParents=a,s.displayEdges=o,s.material=o?r:i,s.update()}}updateMatrixWorld(...e){let t=this.mesh,n=this.parent,r=this.instanceId;t!==null&&(t.updateWorldMatrix(!0,!1),n?this.matrix.copy(n.matrixWorld).invert().multiply(t.matrixWorld):this.matrix.copy(t.matrixWorld),(t.isInstancedMesh||t.isBatchedMesh)&&r>=0&&(t.getMatrixAt(r,qr),this.matrix.multiply(qr)),this.matrix.decompose(this.position,this.quaternion,this.scale)),super.updateMatrixWorld(...e)}copy(e){this.depth=e.depth,this.mesh=e.mesh,this.bvh=e.bvh,this.opacity=e.opacity,this.color.copy(e.color)}clone(){return new e().copy(this)}dispose(){this.edgeMaterial.dispose(),this.meshMaterial.dispose();let e=this.children;for(let t=0,n=e.length;t<n;t++)e[t].geometry.dispose()}},Zr=class extends Xr{constructor(...e){console.warn(`MeshBVHHelper: Class has been deprecated. Use BVHHelper instead.`),super(...e)}},Qr=new E,$r=new E;function ei(e){switch(typeof e){case`number`:return 8;case`string`:return e.length*2;case`boolean`:return 4;default:return 0}}function ti(e){return/(Uint|Int|Float)(8|16|32)Array/.test(e.constructor.name)}function ni(e,t){let n={nodeCount:0,leafNodeCount:0,depth:{min:1/0,max:-1/0},primitives:{min:1/0,max:-1/0},splits:[0,0,0],surfaceAreaScore:0};return e.traverse((e,t,r,i,a)=>{let o=r[3]-r[0],s=r[4]-r[1],c=r[5]-r[2],l=2*(o*s+s*c+c*o);n.nodeCount++,t?(n.leafNodeCount++,n.depth.min=Math.min(e,n.depth.min),n.depth.max=Math.max(e,n.depth.max),n.primitives.min=Math.min(a,n.primitives.min),n.primitives.max=Math.max(a,n.primitives.max),n.surfaceAreaScore+=l*_e*a):(n.splits[i]++,n.surfaceAreaScore+=l*1)},t),n.primitives.min===1/0&&(n.primitives.min=0,n.primitives.max=0),n.depth.min===1/0&&(n.depth.min=0,n.depth.max=0),n}function ri(e){return e._roots.map((t,n)=>ni(e,n))}function ii(e){let t=new Set,n=[e],r=0;for(;n.length;){let e=n.pop();if(!t.has(e)){t.add(e);for(let t in e){if(!Object.hasOwn(e,t))continue;r+=ei(t);let i=e[t];i&&(typeof i==`object`||typeof i==`function`)?ti(i)||lt()&&i instanceof SharedArrayBuffer||i instanceof ArrayBuffer?r+=i.byteLength:n.push(i):r+=ei(i)}}}return r}function ai(e){let t=[],n=new Float32Array(6),r=!0;return e.traverse((i,a,o,s,c)=>{t[i]={depth:i,isLeaf:a,boundingData:o,offset:s,count:c},O(0,o,Qr);let l=t[i-1];if(a){e.writePrimitiveRangeBounds(s,c,n,0),$r.min.set(n[0],n[1],n[2]),$r.max.set(n[3],n[4],n[5]);let t=Qr.containsBox($r);console.assert(t,`Leaf bounds does not fully contain primitives.`),r&&=t}if(l){O(0,l.boundingData,$r);let e=$r.containsBox(Qr);console.assert(e,`Parent bounds does not fully contain child.`),r&&=e}}),r}function oi(e){let t=[];return e.traverse((e,n,r,i,a)=>{let o={bounds:O(0,r,new E)};n?(o.count=a,o.offset=i):(o.left=null,o.right=null),t[e]=o;let s=t[e-1];s&&(s.left===null?s.left=o:s.right=o)}),t[0]}var si=!0,ci={Mesh:ue.prototype.raycast,Line:a.prototype.raycast,LineSegments:C.prototype.raycast,LineLoop:t.prototype.raycast,Points:p.prototype.raycast,BatchedMesh:re.prototype.raycast},$=new ue,li=[];function ui(e,n){if(this.isBatchedMesh)di.call(this,e,n);else{let{geometry:r}=this;if(r.boundsTree)r.boundsTree.raycastObject3D(this,e,n);else{let r;if(this instanceof ue)r=ci.Mesh;else if(this instanceof C)r=ci.LineSegments;else if(this instanceof t)r=ci.LineLoop;else if(this instanceof a)r=ci.Line;else if(this instanceof p)r=ci.Points;else throw Error(`BVH: Fallback raycast function not found.`);r.call(this,e,n)}}}function di(e,t){if(this.boundsTrees){let n=this.boundsTrees,r=this._drawInfo||this._instanceInfo,i=this._drawRanges||this._geometryInfo,a=this.matrixWorld;$.material=this.material,$.geometry=this.geometry;let o=$.geometry.boundsTree,s=$.geometry.drawRange;$.geometry.boundingSphere===null&&($.geometry.boundingSphere=new se);for(let o=0,s=r.length;o<s;o++){if(!this.getVisibleAt(o))continue;let s=r[o].geometryIndex;if($.geometry.boundsTree=n[s],this.getMatrixAt(o,$.matrixWorld).premultiply(a),!$.geometry.boundsTree){this.getBoundingBoxAt(s,$.geometry.boundingBox),this.getBoundingSphereAt(s,$.geometry.boundingSphere);let e=i[s];$.geometry.setDrawRange(e.start,e.count)}$.raycast(e,li);for(let e=0,n=li.length;e<n;e++){let n=li[e];n.object=this,n.batchId=o,t.push(n)}li.length=0}$.geometry.boundsTree=o,$.geometry.drawRange=s,$.material=null,$.geometry=null}else ci.BatchedMesh.call(this,e,t)}function fi(e={}){let{type:t=$n}=e;return this.boundsTree=new t(this,e),this.boundsTree}function pi(){this.boundsTree=null}function mi(e=-1,t={}){if(!si)throw Error(`BatchedMesh: Three r166+ is required to compute bounds trees.`);t={...t,range:null};let n=this._drawRanges||this._geometryInfo,r=this._geometryCount;this.boundsTrees||=Array(r).fill(null);let i=this.boundsTrees;for(;i.length<r;)i.push(null);if(e<0){for(let e=0;e<r;e++)t.range=n[e],i[e]=new $n(this.geometry,t);return i}else return e<n.length&&(t.range=n[e],i[e]=new $n(this.geometry,t)),i[e]||null}function hi(e=-1){e<0?this.boundsTrees.fill(null):e<this.boundsTrees.length&&(this.boundsTrees[e]=null)}function gi(e){switch(e){case 1:return`R`;case 2:return`RG`;case 3:return`RGBA`;case 4:return`RGBA`}throw Error()}function _i(t){switch(t){case 1:return g;case 2:return ae;case 3:return e;case 4:return e}}function vi(e){switch(e){case 1:return ie;case 2:return ne;case 3:return v;case 4:return v}}var yi=class extends m{constructor(){super(),this.minFilter=f,this.magFilter=f,this.generateMipmaps=!1,this.overrideItemSize=null,this._forcedType=null}updateFrom(e){let t=this.overrideItemSize,n=e.itemSize,r=e.count;if(t!==null){if(n*r%t!==0)throw Error(`VertexAttributeTexture: overrideItemSize must divide evenly into buffer length.`);e.itemSize=t,e.count=r*n/t}let i=e.itemSize,a=e.count,s=e.normalized,c=e.array.constructor,l=c.BYTES_PER_ELEMENT,u=this._forcedType,d=i;if(u===null)switch(c){case Float32Array:u=ce;break;case Uint8Array:case Uint16Array:case Uint32Array:u=x;break;case Int8Array:case Int16Array:case Int32Array:u=o;break}let f,p,m,h,g=gi(i);switch(u){case ce:m=1,p=_i(i),s&&l===1?(h=c,g+=`8`,c===Uint8Array?f=te:(f=oe,g+=`_SNORM`)):(h=Float32Array,g+=`32F`,f=ce);break;case o:g+=l*8+`I`,m=s?2**(c.BYTES_PER_ELEMENT*8-1):1,p=vi(i),l===1?(h=Int8Array,f=oe):l===2?(h=Int16Array,f=ee):(h=Int32Array,f=o);break;case x:g+=l*8+`UI`,m=s?2**(c.BYTES_PER_ELEMENT*8-1):1,p=vi(i),l===1?(h=Uint8Array,f=te):l===2?(h=Uint16Array,f=b):(h=Uint32Array,f=x);break}d===3&&(p===1023||p===1033)&&(d=4);let _=Math.ceil(Math.sqrt(a))||1,v=d*_*_,y=new h(v),S=e.normalized;e.normalized=!1;for(let t=0;t<a;t++){let n=d*t;y[n]=e.getX(t)/m,i>=2&&(y[n+1]=e.getY(t)/m),i>=3&&(y[n+2]=e.getZ(t)/m,d===4&&(y[n+3]=1)),i>=4&&(y[n+3]=e.getW(t)/m)}e.normalized=S,this.internalFormat=g,this.format=p,this.type=f,this.image.width=_,this.image.height=_,this.image.data=y,this.needsUpdate=!0,this.dispose(),e.itemSize=n,e.count=r}},bi=class extends yi{constructor(){super(),this._forcedType=x}},xi=class extends yi{constructor(){super(),this._forcedType=o}},Si=class extends yi{constructor(){super(),this._forcedType=ce}},Ci=class{constructor(){this.index=new bi,this.position=new Si,this.bvhBounds=new m,this.bvhContents=new m,this._cachedIndexAttr=null,this.index.overrideItemSize=3}updateFrom(e){let{geometry:t}=e;if(Ti(e,this.bvhBounds,this.bvhContents),this.position.updateFrom(t.attributes.position),e.indirect){let n=e._indirectBuffer;if(this._cachedIndexAttr===null||this._cachedIndexAttr.count!==n.length)if(t.index)this._cachedIndexAttr=t.index.clone();else{let e=ft(ut(t));this._cachedIndexAttr=new _(e,1,!1)}wi(t,n,this._cachedIndexAttr),this.index.updateFrom(this._cachedIndexAttr)}else this.index.updateFrom(t.index)}dispose(){let{index:e,position:t,bvhBounds:n,bvhContents:r}=this;e&&e.dispose(),t&&t.dispose(),n&&n.dispose(),r&&r.dispose()}};function wi(e,t,n){let r=n.array,i=e.index?e.index.array:null;for(let e=0,n=t.length;e<n;e++){let n=3*e,a=3*t[e];for(let e=0;e<3;e++)r[n+e]=i?i[a+e]:a+e}}function Ti(t,n,r){let i=t._roots;if(i.length!==1)throw Error(`MeshBVHUniformStruct: Multi-root BVHs not supported.`);let a=i[0],o=new Uint16Array(a),s=new Uint32Array(a),c=new Float32Array(a),l=a.byteLength/32,u=2*Math.ceil(Math.sqrt(l/2)),d=new Float32Array(4*u*u),p=Math.ceil(Math.sqrt(l)),m=new Uint32Array(2*p*p);for(let e=0;e<l;e++){let t=e*32/4,n=t*2,r=P(t);for(let t=0;t<3;t++)d[8*e+0+t]=c[r+0+t],d[8*e+4+t]=c[r+3+t];if(k(n,o)){let r=j(n,o),i=A(t,s),a=ye|r;m[e*2+0]=a,m[e*2+1]=i}else{let n=s[t+6],r=Oe(t,s);m[e*2+0]=r,m[e*2+1]=n}}n.image.data=d,n.image.width=u,n.image.height=u,n.format=e,n.type=ce,n.internalFormat=`RGBA32F`,n.minFilter=f,n.magFilter=f,n.generateMipmaps=!1,n.needsUpdate=!0,n.dispose(),r.image.data=m,r.image.width=p,r.image.height=p,r.format=ne,r.type=x,r.internalFormat=`RG32UI`,r.minFilter=f,r.magFilter=f,r.generateMipmaps=!1,r.needsUpdate=!0,r.dispose()}var Ei=new D,Di=new D,Oi=new D,ki=new le,Ai=new D,ji=new D,Mi=new le,Ni=new le,Pi=new d,Fi=new d;function Ii(e,t){if(!e&&!t)return;let n=e.count===t.count,r=e.normalized===t.normalized,i=e.array.constructor===t.array.constructor,a=e.itemSize===t.itemSize;if(!n||!r||!i||!a)throw Error()}function Li(e,t=null){let n=e.array.constructor,r=e.normalized,i=e.itemSize;return new _(new n(i*(t===null?e.count:t)),i,r)}function Ri(e,t,n=0){if(e.isInterleavedBufferAttribute){let r=e.itemSize;for(let i=0,a=e.count;i<a;i++){let a=i+n;t.setX(a,e.getX(i)),r>=2&&t.setY(a,e.getY(i)),r>=3&&t.setZ(a,e.getZ(i)),r>=4&&t.setW(a,e.getW(i))}}else{let r=t.array,i=r.constructor,a=r.BYTES_PER_ELEMENT*e.itemSize*n;new i(r.buffer,a,e.array.length).set(e.array)}}function zi(e,t,n){let r=e.elements,i=t.elements;for(let e=0,t=i.length;e<t;e++)r[e]+=i[e]*n}function Bi(e,t,n){let r=e.skeleton,i=e.geometry,a=r.bones,o=r.boneInverses;Mi.fromBufferAttribute(i.attributes.skinIndex,t),Ni.fromBufferAttribute(i.attributes.skinWeight,t),Pi.elements.fill(0);for(let e=0;e<4;e++){let t=Ni.getComponent(e);if(t!==0){let n=Mi.getComponent(e);Fi.multiplyMatrices(a[n].matrixWorld,o[n]),zi(Pi,Fi,t)}}return Pi.multiply(e.bindMatrix).premultiply(e.bindMatrixInverse),n.transformDirection(Pi),n}function Vi(e,t,n,r,i){Ai.set(0,0,0);for(let a=0,o=e.length;a<o;a++){let o=t[a],s=e[a];o!==0&&(ji.fromBufferAttribute(s,r),n?Ai.addScaledVector(ji,o):Ai.addScaledVector(ji.sub(i),o))}i.add(Ai)}function Hi(e,t={useGroups:!1,updateIndex:!1,skipAttributes:[]},n=new T){let r=e[0].index!==null,{useGroups:i=!1,updateIndex:a=!1,skipAttributes:o=[]}=t,s=new Set(Object.keys(e[0].attributes)),c={},l=0;n.clearGroups();for(let t=0;t<e.length;++t){let a=e[t],o=0;if(r!==(a.index!==null))throw Error(`StaticGeometryGenerator: All geometries must have compatible attributes; make sure index attribute exists among all geometries, or in none of them.`);for(let e in a.attributes){if(!s.has(e))throw Error(`StaticGeometryGenerator: All geometries must have compatible attributes; make sure "`+e+`" attribute exists among all geometries, or in none of them.`);c[e]===void 0&&(c[e]=[]),c[e].push(a.attributes[e]),o++}if(o!==s.size)throw Error(`StaticGeometryGenerator: Make sure all geometries have the same number of attributes.`);if(i){let e;if(r)e=a.index.count;else if(a.attributes.position!==void 0)e=a.attributes.position.count;else throw Error(`StaticGeometryGenerator: The geometry must have either an index or a position attribute`);n.addGroup(l,e,t),l+=e}}if(r){let t=!1;if(!n.index){let r=0;for(let t=0;t<e.length;++t)r+=e[t].index.count;n.setIndex(new _(new Uint32Array(r),1,!1)),t=!0}if(a||t){let t=n.index,r=0,i=0;for(let n=0;n<e.length;++n){let a=e[n],s=a.index;if(o[n]!==!0)for(let e=0;e<s.count;++e)t.setX(r,s.getX(e)+i),r++;i+=a.attributes.position.count}}}for(let e in c){let t=c[e];if(!(e in n.attributes)){let r=0;for(let e in t)r+=t[e].count;n.setAttribute(e,Li(c[e][0],r))}let r=n.attributes[e],i=0;for(let e=0,n=t.length;e<n;e++){let n=t[e];o[e]!==!0&&Ri(n,r,i),i+=n.count}}return n}function Ui(e,t){if(e===null||t===null)return e===t;if(e.length!==t.length)return!1;for(let n=0,r=e.length;n<r;n++)if(e[n]!==t[n])return!1;return!0}function Wi(e){let{index:t,attributes:n}=e;if(t)for(let e=0,n=t.count;e<n;e+=3){let n=t.getX(e),r=t.getX(e+2);t.setX(e,r),t.setX(e+2,n)}else for(let e in n){let t=n[e],r=t.itemSize;for(let e=0,n=t.count;e<n;e+=3)for(let n=0;n<r;n++){let r=t.getComponent(e,n),i=t.getComponent(e+2,n);t.setComponent(e,n,i),t.setComponent(e+2,n,r)}}return e}var Gi=class{constructor(e){this.matrixWorld=new d,this.geometryHash=null,this.boneMatrices=null,this.primitiveCount=-1,this.mesh=e,this.update()}update(){let e=this.mesh,t=e.geometry,n=e.skeleton,r=(t.index?t.index.count:t.attributes.position.count)/3;if(this.matrixWorld.copy(e.matrixWorld),this.geometryHash=t.attributes.position.version,this.primitiveCount=r,n){n.boneTexture||n.computeBoneTexture(),n.update();let e=n.boneMatrices;!this.boneMatrices||this.boneMatrices.length!==e.length?this.boneMatrices=e.slice():this.boneMatrices.set(e)}else this.boneMatrices=null}didChange(){let e=this.mesh,t=e.geometry,n=(t.index?t.index.count:t.attributes.position.count)/3;return!(this.matrixWorld.equals(e.matrixWorld)&&this.geometryHash===t.attributes.position.version&&Ui(e.skeleton&&e.skeleton.boneMatrices||null,this.boneMatrices)&&this.primitiveCount===n)}},Ki=class{constructor(e){Array.isArray(e)||(e=[e]);let t=[];e.forEach(e=>{e.traverseVisible(e=>{e.isMesh&&t.push(e)})}),this.meshes=t,this.useGroups=!0,this.applyWorldTransforms=!0,this.attributes=[`position`,`normal`,`color`,`tangent`,`uv`,`uv2`],this._intermediateGeometry=Array(t.length).fill().map(()=>new T),this._diffMap=new WeakMap}getMaterials(){let e=[];return this.meshes.forEach(t=>{Array.isArray(t.material)?e.push(...t.material):e.push(t.material)}),e}generate(e=new T){let t=[],{meshes:n,useGroups:r,_intermediateGeometry:i,_diffMap:a}=this;for(let e=0,r=n.length;e<r;e++){let r=n[e],o=i[e],s=a.get(r);!s||s.didChange(r)?(this._convertToStaticGeometry(r,o),t.push(!1),s?s.update():a.set(r,new Gi(r))):t.push(!0)}if(i.length===0){e.setIndex(null);let t=e.attributes;for(let n in t)e.deleteAttribute(n);for(let t in this.attributes)e.setAttribute(this.attributes[t],new _(new Float32Array,4,!1))}else Hi(i,{useGroups:r,skipAttributes:t},e);for(let t in e.attributes)e.attributes[t].needsUpdate=!0;return e}_convertToStaticGeometry(e,t=new T){let n=e.geometry,r=this.applyWorldTransforms,i=this.attributes.includes(`normal`),a=this.attributes.includes(`tangent`),o=n.attributes,s=t.attributes;!t.index&&n.index&&(t.index=n.index.clone()),s.position||t.setAttribute(`position`,Li(o.position)),i&&!s.normal&&o.normal&&t.setAttribute(`normal`,Li(o.normal)),a&&!s.tangent&&o.tangent&&t.setAttribute(`tangent`,Li(o.tangent)),Ii(n.index,t.index),Ii(o.position,s.position),i&&Ii(o.normal,s.normal),a&&Ii(o.tangent,s.tangent);let c=o.position,u=i?o.normal:null,d=a?o.tangent:null,f=n.morphAttributes.position,p=n.morphAttributes.normal,m=n.morphAttributes.tangent,h=n.morphTargetsRelative,g=e.morphTargetInfluences,_=new l;_.getNormalMatrix(e.matrixWorld),n.index&&t.index.array.set(n.index.array);for(let t=0,n=o.position.count;t<n;t++)Ei.fromBufferAttribute(c,t),u&&Di.fromBufferAttribute(u,t),d&&(ki.fromBufferAttribute(d,t),Oi.fromBufferAttribute(d,t)),g&&(f&&Vi(f,g,h,t,Ei),p&&Vi(p,g,h,t,Di),m&&Vi(m,g,h,t,Oi)),e.isSkinnedMesh&&(e.applyBoneTransform(t,Ei),u&&Bi(e,t,Di),d&&Bi(e,t,Oi)),r&&Ei.applyMatrix4(e.matrixWorld),s.position.setXYZ(t,Ei.x,Ei.y,Ei.z),u&&(r&&Di.applyNormalMatrix(_),s.normal.setXYZ(t,Di.x,Di.y,Di.z)),d&&(r&&Oi.transformDirection(e.matrixWorld),s.tangent.setXYZW(t,Oi.x,Oi.y,Oi.z,ki.w));for(let e in this.attributes){let n=this.attributes[e];n===`position`||n===`tangent`||n===`normal`||!(n in o)||(s[n]||t.setAttribute(n,Li(o[n])),Ii(o[n],s[n]),Ri(o[n],s[n]))}return e.matrixWorld.determinant()<0&&Wi(t),t}},qi=`

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
`,Zi=i({bvh_distance_functions:()=>Ji,bvh_ray_functions:()=>Yi,bvh_struct_definitions:()=>Xi,common_functions:()=>qi}),Qi=Xi,$i=Ji,ea=`
	${qi}
	${Yi}
`;export{fe as AVERAGE,ct as BVH,Xr as BVHHelper,Zi as BVHShaderGLSL,de as CENTER,ge as CONTAINED,W as ExtendedTriangle,Si as FloatVertexAttributeTexture,vt as GeometryBVH,he as INTERSECTED,xi as IntVertexAttributeTexture,lr as LineBVH,cr as LineLoopBVH,sr as LineSegmentsBVH,$n as MeshBVH,Zr as MeshBVHHelper,Ci as MeshBVHUniformStruct,me as NOT_INTERSECTED,Sr as ObjectBVH,G as OrientedBox,hr as PointsBVH,pe as SAH,xe as SKIP_GENERATION,Wr as SkinnedMeshBVH,Ki as StaticGeometryGenerator,bi as UIntVertexAttributeTexture,yi as VertexAttributeTexture,ui as acceleratedRaycast,mi as computeBatchedBoundsTree,fi as computeBoundsTree,hi as disposeBatchedBoundsTree,pi as disposeBoundsTree,ii as estimateMemoryInBytes,_t as generateIndirectBuffer,ri as getBVHExtremes,oi as getJSONStructure,Jt as getTriangleHitPointInfo,$i as shaderDistanceFunction,ea as shaderIntersectFunction,Qi as shaderStructs,ai as validateBounds};