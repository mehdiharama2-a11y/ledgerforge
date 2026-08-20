import {NextRequest,NextResponse} from 'next/server';
export function proxy(request:NextRequest){
 const nonce=Buffer.from(crypto.randomUUID()).toString('base64'),dev=process.env.NODE_ENV==='development';
 const connect=["'self'",...origin(process.env.NEXT_PUBLIC_API_URL)];if(dev)connect.push('ws://localhost:*','ws://127.0.0.1:*');
 const policy=["default-src 'self'",`script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev?" 'unsafe-eval'":''}`,`style-src 'self' 'nonce-${nonce}'`,"img-src 'self' data: blob:","font-src 'self'",`connect-src ${connect.join(' ')}`,"object-src 'none'","base-uri 'self'","form-action 'self'","frame-ancestors 'none'",...(dev?[]:['upgrade-insecure-requests'])].join('; ');
 const headers=new Headers(request.headers);headers.set('x-nonce',nonce);headers.set('content-security-policy',policy);
 const response=NextResponse.next({request:{headers}});response.headers.set('content-security-policy',policy);response.headers.set('x-content-type-options','nosniff');response.headers.set('x-frame-options','DENY');response.headers.set('referrer-policy','no-referrer');response.headers.set('cross-origin-opener-policy','same-origin');response.headers.set('cross-origin-resource-policy','same-origin');response.headers.set('permissions-policy','camera=(), microphone=(), geolocation=(), payment=(), usb=()');if(!dev)response.headers.set('strict-transport-security','max-age=63072000; includeSubDomains');return response;
}
function origin(value:string|undefined){try{const url=new URL(value??'');return ['http:','https:'].includes(url.protocol)?[url.origin]:[]}catch{return[]}}
export const config={matcher:['/((?!_next/static|_next/image|favicon.ico).*)']};
