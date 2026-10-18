/** Warm development modules too: a dev HTML shell alone cannot survive an offline refresh. */
export async function prepareOfflineShell(development:boolean){
  if(!('serviceWorker' in navigator)||!window.isSecureContext)throw new Error('离线重开需要 localhost 或受信任的 HTTPS')
  await navigator.serviceWorker.ready
  if(!development)return
  const urls=new Set<string>([new URL('/index.html',location.origin).href])
  for(const script of document.querySelectorAll<HTMLScriptElement>('script[src]'))urls.add(script.src)
  for(const entry of performance.getEntriesByType('resource')){
    const url=new URL(entry.name,location.href)
    if(url.origin===location.origin&&(/^\/(src|@vite|@vite-plugin-pwa|@id|node_modules)\//.test(url.pathname)||/\.(css|woff2?|svg)$/.test(url.pathname))&&!url.pathname.includes('dev-sw')&&!url.pathname.includes('hot-update'))urls.add(url.href)
  }
  const cache=await caches.open('farm-dev-shell-v1')
  const results=await Promise.allSettled([...urls].map(async url=>{const response=await fetch(url,{cache:'reload'});if(!response.ok){console.error('离线资源下载失败',url,response.status);throw new Error('页面资源下载失败')};await cache.put(url,response)}))
  if(results.some(result=>result.status==='rejected'))throw new Error('部分离线页面资源尚未准备，请联网刷新后再试')
}
