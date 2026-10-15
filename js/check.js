// Connection check (#check): tests each piece the phone link needs, in this browser, and shows
// the results large enough to photograph off a car screen.
import { el } from './ui.js'
import { RELAYS, openChannel, newCode } from './link.js'

export const BUILD = '2026-10-14'
const ICE = [{ urls: 'stun:stun.l.google.com:19302' }, { urls: 'stun:stun.cloudflare.com:3478' }]

let list

function row(label) {
  const state = el('span', { class: 'ck-state wait' }, '\u2026')
  const detail = el('span', { class: 'ck-detail muted' })
  list.append(el('div', { class: 'ck-row' }, state, el('span', { class: 'ck-label' }, label), detail))
  return {
    ok(text = '') { state.className = 'ck-state ok'; state.textContent = 'OK'; detail.textContent = text },
    fail(text = '') { state.className = 'ck-state bad'; state.textContent = 'NO'; detail.textContent = text },
    info(text = '') { state.className = 'ck-state'; state.textContent = '\u2014'; detail.textContent = text },
  }
}

const timeout = (ms, what) => new Promise((_, reject) => setTimeout(() => reject(new Error(what || 'timed out')), ms))

export async function start() {
  document.body.className = 'car-page dark check-page'
  list = el('div', { class: 'ck-list' })
  document.getElementById('app').replaceChildren(el('main', { class: 'ck' },
    el('h1', {}, 'MinMap connection check'),
    el('p', { class: 'muted' }, 'Tests what the phone link needs in this browser. Take a photo of this screen when it finishes.'),
    list,
    el('p', { class: 'fine' }, 'Build ' + BUILD)))

  const ua = navigator.userAgent
  const chrome = ua.match(/Chrom(e|ium)\/([\d.]+)/)?.[2]
  row('Browser').info((chrome ? 'Chrome ' + chrome + ' \u00b7 ' : '') + ua.replace(/^Mozilla\/5\.0 /, '').slice(0, 120))
  const secure = row('Secure page (HTTPS)')
  window.isSecureContext ? secure.ok() : secure.fail('pairing needs https')
  const subtle = row('Encryption (WebCrypto)')
  crypto?.subtle ? subtle.ok() : subtle.fail('crypto.subtle missing')
  const big = row('BigInt (for signing)')
  typeof BigInt === 'function' ? big.ok() : big.fail('missing')
  const rtc = row('WebRTC')
  const hasRtc = typeof RTCPeerConnection === 'function'
  hasRtc ? rtc.ok() : rtc.fail('RTCPeerConnection missing: this browser cannot receive the video stream')

  // Each relay on its own: can this browser open a WebSocket to it?
  const relayRows = RELAYS.map(url => [url, row('Relay ' + url.replace('wss://', ''))])
  await Promise.all(relayRows.map(async ([url, r]) => {
    const t0 = performance.now()
    try {
      await Promise.race([new Promise((resolve, reject) => {
        const ws = new WebSocket(url)
        ws.onopen = () => { ws.close(); resolve() }
        ws.onerror = () => reject(new Error('refused or blocked'))
      }), timeout(8000)])
      r.ok(Math.round(performance.now() - t0) + ' ms')
    } catch (e) { r.fail(e.message) }
  }))

  // The real pairing path: two channels on one code, a signed encrypted note from one to the other.
  const trip = row('Pairing notes round trip')
  try {
    const code = newCode()
    let got
    const heard = new Promise(resolve => { got = resolve })
    const b = await openChannel(code, m => { if (m.t === 'ping') got(m) })
    const a = await openChannel(code, () => {})
    await new Promise(r => setTimeout(r, 2500))
    const t0 = performance.now()
    const send = setInterval(() => a.send({ t: 'ping' }), 1500)
    a.send({ t: 'ping' })
    try {
      await Promise.race([heard, timeout(15000, 'no note came back through any relay')])
      trip.ok(Math.round(performance.now() - t0) + ' ms')
    } finally { clearInterval(send); a.close(); b.close() }
  } catch (e) { trip.fail(e.message) }

  if (!hasRtc) return

  // Which kinds of network address this browser finds: host = local, srflx = public via STUN.
  const ice = row('Network addresses (ICE)')
  const nat = row('Network type')
  try {
    const pc = new RTCPeerConnection({ iceServers: ICE })
    pc.createDataChannel('x')
    const types = {}
    const publics = []
    pc.onicecandidate = e => {
      if (!e.candidate) return
      const f = e.candidate.candidate.split(' ')
      types[f[7]] = (types[f[7]] || 0) + 1
      if (f[7] === 'srflx' && f[2].toLowerCase() === 'udp') publics.push({ ip: f[4], port: f[5] })
    }
    await pc.setLocalDescription(await pc.createOffer())
    await Promise.race([new Promise(r => pc.addEventListener('icegatheringstatechange', () => pc.iceGatheringState === 'complete' && r())), new Promise(r => setTimeout(r, 6000))])
    pc.close()
    const text = Object.entries(types).map(([k, v]) => k + ' \u00d7' + v).join(', ') || 'none'
    types.srflx ? ice.ok(text) : ice.fail(text + ' (no public address: UDP to STUN may be blocked)')
    // One local socket asked two STUN servers. The same public IP with different ports means the
    // network gives each destination its own port (symmetric NAT), which blocks direct links.
    const byIp = {}
    for (const p of publics) (byIp[p.ip] ||= new Set()).add(p.port)
    const strict = (types.host || 0) <= 1 && Object.values(byIp).some(ports => ports.size > 1)
    if (!publics.length) nat.info('unknown')
    else if (strict) nat.fail('strict (symmetric NAT): a phone on mobile data can\u2019t link to it directly. Use the phone\u2019s hotspot.')
    else nat.ok('open enough for direct links')
  } catch (e) { ice.fail(e.message); nat.info('unknown') }

  // Two connections inside this page talking to each other: does WebRTC itself work here?
  const loop = row('WebRTC loopback')
  try {
    const p1 = new RTCPeerConnection(), p2 = new RTCPeerConnection()
    p1.onicecandidate = e => e.candidate && p2.addIceCandidate(e.candidate).catch(() => {})
    p2.onicecandidate = e => e.candidate && p1.addIceCandidate(e.candidate).catch(() => {})
    const dc = p1.createDataChannel('t')
    const opened = new Promise(r => { dc.onopen = r })
    await p1.setLocalDescription(await p1.createOffer())
    await p2.setRemoteDescription(p1.localDescription)
    await p2.setLocalDescription(await p2.createAnswer())
    await p1.setRemoteDescription(p2.localDescription)
    await Promise.race([opened, timeout(8000)])
    loop.ok('data channel opened')
    p1.close(); p2.close()
  } catch (e) { loop.fail(e.message) }

  const codecs = row('Video formats')
  try {
    const names = [...new Set((RTCRtpReceiver.getCapabilities?.('video')?.codecs || []).map(c => c.mimeType.replace('video/', '')))]
      .filter(n => !/rtx|red|ulpfec|flexfec/i.test(n))
    names.length ? codecs.ok(names.join(', ')) : codecs.fail('none reported')
  } catch (e) { codecs.fail(e.message) }

  const media = row('Showing a live stream')
  try {
    const c = document.createElement('canvas')
    c.width = 64; c.height = 36
    const g = c.getContext('2d')
    const tick = setInterval(() => { g.fillStyle = '#' + Math.floor(Math.random() * 0xffffff).toString(16).padStart(6, '0'); g.fillRect(0, 0, 64, 36) }, 100)
    if (!c.captureStream) { clearInterval(tick); media.info('skipped (no canvas capture)'); return }
    const v = el('video', { muted: true, playsinline: true, autoplay: true, class: 'ck-video' })
    v.muted = true
    v.srcObject = c.captureStream(10)
    list.append(v)
    await v.play().catch(() => {})
    await new Promise(r => setTimeout(r, 1500))
    clearInterval(tick)
    v.videoWidth > 0 && !v.paused ? media.ok(v.videoWidth + '\u00d7' + v.videoHeight + ' playing') : media.fail(v.paused ? 'video paused by the browser' : 'no frames')
    v.remove()
  } catch (e) { media.fail(e.message) }
}
