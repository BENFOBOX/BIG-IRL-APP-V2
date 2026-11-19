#!/usr/bin/env bash
set -euo pipefail
sudo tee /var/www/html/index.html >/dev/null <<'HTML'
<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>BENFOBOX StreamBox Monitor</title>
<script src="https://cdn.jsdelivr.net/npm/hls.js@1"></script>
<style>
:root{color-scheme:dark;font-family:system-ui;background:#071019;color:white}
body{margin:0;padding:16px;background:#071019}main{max-width:1280px;margin:auto}
nav{display:flex;gap:14px;margin:8px 0 16px}a{color:#51e1a6}video{display:block;width:100%;background:#000;border-radius:12px}
#status{margin-top:12px;padding:10px 12px;border-radius:10px;background:#101d2b;color:#51e1a6}
button{margin-top:12px;padding:12px 16px;border:0;border-radius:10px;background:#51e1a6;color:#002116;font-weight:700}
</style></head><body><main>
<h1>BENFOBOX StreamBox Monitor</h1>
<nav><a href="/">Live monitor</a><a href="http://192.168.1.27:8081/">Recordings</a></nav>
<p>Original received H.264/AAC feed. No scaling or re-encoding.</p>
<video id="video" controls autoplay muted playsinline></video>
<div id="status">Waiting for StreamBox video…</div>
<button id="audio">Enable audio</button>
<script>
const video=document.getElementById('video'),status=document.getElementById('status'),audio=document.getElementById('audio');
let hls=null;
function start(){if(hls)hls.destroy();if(window.Hls&&Hls.isSupported()){hls=new Hls({liveSyncDurationCount:2,liveMaxLatencyDurationCount:5,enableWorker:true});hls.loadSource('/live/stream.m3u8');hls.attachMedia(video);hls.on(Hls.Events.MANIFEST_PARSED,()=>{status.textContent='StreamBox feed available';video.play().catch(()=>{})});hls.on(Hls.Events.ERROR,(_,d)=>{if(d.fatal){status.textContent='Waiting for feed or reconnecting…';setTimeout(start,1500)}})}else if(video.canPlayType('application/vnd.apple.mpegurl')){video.src='/live/stream.m3u8';video.play().catch(()=>{})}else status.textContent='This browser cannot play HLS.'}
audio.onclick=()=>{video.muted=!video.muted;audio.textContent=video.muted?'Enable audio':'Mute audio';video.play().catch(()=>{})};
start();
</script></main></body></html>
HTML
echo "Monitor page updated: http://192.168.1.27/"
