// Pairing and the video link between a phone and the car, with no server of MinMap's own.
//
// The video goes straight from the phone to the car over WebRTC. Before that, the two swap one
// offer and one answer through public Nostr relays: short-lived notes tagged with a hash of the
// pairing code, encrypted with a key made from the same code. Relays forward these notes live
// and don't keep them, and they can't read them.
//
// The MinMap Mirror Android app speaks the same protocol (see PROTOCOL.md). Change both together.
import { schnorr } from '../vendor/noble-secp256k1.js'

export const RELAYS = [
  'wss://nos.lol',
  'wss://relay.damus.io',
  'wss://relay.primal.net',
  'wss://nostr.mom',
  'wss://offchain.pub',
  'wss://relay.nostr.net',
]
const KIND = 25050   // 20000-29999 is "ephemeral": relays pass it on and don't store it
const ICE = [{ urls: 'stun:stun.l.google.com:19302' }, { urls: 'stun:stun.cloudflare.com:3478' }]

// No 0/O, 1/I/L: the code is read off a car screen and typed on a phone.
const ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ'

export function newCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(8))
  return Array.from(bytes, b => ALPHABET[b % ALPHABET.length]).join('')
}

export function cleanCode(text) {
  const code = String(text || '').toUpperCase().replace(/[^0-9A-Z]/g, '')
  return code.length === 8 && [...code].every(c => ALPHABET.includes(c)) ? code : null
}

export function prettyCode(code) {
  return code.slice(0, 4) + '-' + code.slice(4)
}

export function castUrl(code) {
  return location.origin + location.pathname + '#cast=' + code
}

// ---- encrypted notes over Nostr ----

const enc = new TextEncoder(), dec = new TextDecoder()
const hex = bytes => Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, '0')).join('')
const sha256 = async text => new Uint8Array(await crypto.subtle.digest('SHA-256', typeof text === 'string' ? enc.encode(text) : text))
const b64 = bytes => btoa(String.fromCharCode(...bytes))
const unb64 = text => Uint8Array.from(atob(text), c => c.charCodeAt(0))
const randomId = () => hex(crypto.getRandomValues(new Uint8Array(8)))

export async function openChannel(code, onMessage) {
  const topic = hex(await sha256('minmap-cast-v2|topic|' + code))
  const key = await crypto.subtle.importKey('raw', await sha256('minmap-cast-v2|key|' + code), 'AES-GCM', false, ['encrypt', 'decrypt'])
  const { secretKey, publicKey } = schnorr.keygen()
  const pubkey = hex(publicKey)
  const seen = new Set()
  const sockets = new Map()
  let closed = false

  const connect = (url, delay = 1000) => {
    if (closed) return
    const ws = new WebSocket(url)
    sockets.set(url, ws)
    ws.onopen = () => {
      delay = 1000
      ws.send(JSON.stringify(['REQ', 'mm', { kinds: [KIND], '#x': [topic], since: Math.floor(Date.now() / 1000) - 30 }]))
    }
    ws.onmessage = async m => {
      let msg
      try { msg = JSON.parse(m.data) } catch { return }
      if (msg[0] !== 'EVENT' || !msg[2]) return
      const ev = msg[2]
      if (ev.pubkey === pubkey || seen.has(ev.id)) return
      if (!ev.tags?.some(t => t[0] === 'x' && t[1] === topic)) return
      seen.add(ev.id)
      try {
        const raw = unb64(ev.content)
        const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: raw.slice(0, 12) }, key, raw.slice(12))
        onMessage(JSON.parse(dec.decode(plain)))
      } catch { /* not ours, or a different code */ }
    }
    ws.onclose = () => {
      if (sockets.get(url) === ws) sockets.delete(url)
      if (!closed) setTimeout(() => connect(url, Math.min(delay * 2, 30000)), delay)
    }
    ws.onerror = () => { }
  }
  RELAYS.forEach(url => connect(url))

  return {
    async send(obj) {
      const iv = crypto.getRandomValues(new Uint8Array(12))
      const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(JSON.stringify(obj))))
      const body = new Uint8Array(12 + ct.length)
      body.set(iv); body.set(ct, 12)
      const ev = { pubkey, created_at: Math.floor(Date.now() / 1000), kind: KIND, tags: [['x', topic]], content: b64(body) }
      const id = await sha256(JSON.stringify([0, ev.pubkey, ev.created_at, ev.kind, ev.tags, ev.content]))
      ev.id = hex(id)
      ev.sig = hex(await schnorr.signAsync(id, secretKey))
      const frame = JSON.stringify(['EVENT', ev])
      for (const ws of sockets.values()) if (ws.readyState === 1) ws.send(frame)
    },
    close() {
      closed = true
      for (const ws of sockets.values()) ws.close()
      sockets.clear()
    },
  }
}

// Wait for ICE candidates to be gathered, so one offer and one answer are all that's swapped.
function iceGathered(pc, ms = 3000) {
  if (pc.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise(resolve => {
    const t = setTimeout(resolve, ms)
    pc.addEventListener('icegatheringstatechange', () => {
      if (pc.iceGatheringState === 'complete') { clearTimeout(t); resolve() }
    })
  })
}

// Ask for stereo, higher-bitrate Opus: films and music, not a voice call.
export function stereoOpus(sdp) {
  const pt = sdp.match(/a=rtpmap:(\d+) opus\/48000/i)?.[1]
  if (!pt) return sdp
  return sdp.replace(new RegExp('a=fmtp:' + pt + ' ([^\\r\\n]*)'), (line, params) =>
    'a=fmtp:' + pt + ' ' + params.replace(/;?(stereo|sprop-stereo|maxaveragebitrate)=[^;]*/g, '') + ';stereo=1;sprop-stereo=1;maxaveragebitrate=192000')
}

// What each side offered, for the car's "blocked" message. Read from the offer and answer,
// which survive the connection closing. Types: host = on the device, srflx = public (via STUN).
function describeLink(offerSdp, answerSdp) {
  const list = sdp => {
    const seen = new Set()
    for (const line of (sdp || '').split(/\r?\n/)) {
      const f = line.match(/^a=candidate:\S+ \d+ (\S+) \d+ (\S+) \d+ typ (\S+)/i)
      if (f) seen.add(f[3] + ' ' + f[2] + (f[1].toLowerCase() === 'tcp' ? ' (tcp)' : ''))
    }
    return [...seen].join(', ') || 'none'
  }
  return 'Phone offered: ' + list(offerSdp) + '. Car offered: ' + list(answerSdp) + '.'
}

// TURN relay servers the phone sent in its (encrypted) offer, with short-lived credentials from
// the phone owner's own relay account. Only well-formed turn/stun entries are used.
function relayServers(list) {
  if (!Array.isArray(list)) return []
  return list.filter(s => s && s.urls && [].concat(s.urls).every(u => /^(turns?|stun):/.test(String(u))))
    .map(s => ({ urls: [].concat(s.urls).map(String), username: s.username, credential: s.credential }))
}

// ---- the car: answers a phone's offer and shows its stream ----

export class Receiver {
  constructor(code, { onStream, onStatus, onState }) {
    this.code = code
    this.on = { onStream, onStatus, onState }
    this.answers = new Map()   // offer id -> answer note (or a promise of one), to resend if lost
    this.pc = null
    this.dc = null
    this.peer = null
  }

  async start() {
    this.ch = await openChannel(this.code, m => this.message(m))
    this.on.onState?.('waiting')
  }

  async message(m) {
    if (m.t === 'offer' && m.id && m.sdp) {
      const known = this.answers.get(m.id)
      if (known) { const a = await known; if (a) this.ch.send(a); return }
      const job = this.answer(m)
      this.answers.set(m.id, job)
      const a = await job
      if (a) this.ch.send(a)
    } else if (m.t === 'bye' && m.from === this.peer) {
      this.drop()
    }
  }

  async answer(m) {
    this.drop()
    // ?relayonly forces everything through the relay: a test of the path strict networks use.
    const relay = relayServers(m.ice)
    const relayOnly = relay.length > 0 && /[?&]relayonly\b/.test(location.search)
    const pc = new RTCPeerConnection({ iceServers: ICE.concat(relay), iceTransportPolicy: relayOnly ? 'relay' : 'all' })
    this.pc = pc
    this.peer = m.from
    const stream = new MediaStream()
    this.stream = stream
    pc.ontrack = e => {
      stream.addTrack(e.track)
      this.on.onStream?.(stream)
    }
    pc.ondatachannel = e => {
      this.dc = e.channel
      this.dc.onmessage = ev => { try { this.on.onStatus?.(JSON.parse(ev.data)) } catch { } }
    }
    pc.onconnectionstatechange = () => {
      if (this.pc !== pc) return
      const s = pc.connectionState
      if (s === 'connected') this.on.onState?.('connected')
      else if (s === 'failed') { this.on.onState?.('blocked', describeLink(m.sdp, pc.localDescription?.sdp || this.lastAnswer)); this.drop() }
      else if (s === 'closed') this.drop()
    }
    try {
      await pc.setRemoteDescription({ type: 'offer', sdp: m.sdp })
      const ans = await pc.createAnswer()
      await pc.setLocalDescription({ type: 'answer', sdp: stereoOpus(ans.sdp) })
      await iceGathered(pc)
      this.on.onState?.('connecting')
      this.lastAnswer = pc.localDescription.sdp
      return { t: 'answer', id: m.id, to: m.from, sdp: pc.localDescription.sdp }
    } catch (e) {
      console.warn('MinMap: could not answer the phone', e)
      return null
    }
  }

  drop() {
    if (this.pc) {
      try { this.pc.close() } catch { }
      this.pc = null
      this.stream = null
      this.dc = null
      this.peer = null
      this.on.onState?.('waiting')
    }
  }

  control(obj) {
    if (this.dc?.readyState !== 'open') return false
    this.dc.send(JSON.stringify(obj))
    return true
  }

  get connected() { return this.pc?.connectionState === 'connected' }

  close() {
    this.drop()
    this.ch?.close()
  }
}

// ---- the phone (web version): offers its video and audio to whoever holds the code ----

export class Sender {
  constructor(code, { onState, onControl }) {
    this.code = code
    this.on = { onState, onControl }
    this.id = randomId()
    this.tracks = { video: null, audio: null }
    this.pc = null
  }

  async start() {
    this.ch = await openChannel(this.code, m => this.message(m))
    await this.offer()
    addEventListener('pagehide', () => this.ch.send({ t: 'bye', from: this.id }))
  }

  async offer() {
    clearInterval(this.repeat)
    if (this.pc) try { this.pc.close() } catch { }
    const pc = new RTCPeerConnection({ iceServers: ICE })
    this.pc = pc
    this.answered = false
    this.dc = pc.createDataChannel('ctl')
    this.dc.onmessage = e => { try { this.on.onControl?.(JSON.parse(e.data)) } catch { } }
    this.video = pc.addTransceiver('video', { direction: 'sendonly' })
    this.audio = pc.addTransceiver('audio', { direction: 'sendonly' })
    if (this.tracks.video) await this.video.sender.replaceTrack(this.tracks.video)
    if (this.tracks.audio) await this.audio.sender.replaceTrack(this.tracks.audio)
    pc.onconnectionstatechange = () => {
      if (this.pc !== pc) return
      const s = pc.connectionState
      this.on.onState?.(s === 'connected' ? 'connected' : 'waiting')
      if (s === 'connected') { clearInterval(this.repeat); this.raiseBitrate() }
      // The car went away (page closed, out of range): start over so it can come back.
      if (s === 'failed' || s === 'closed') this.offer()
      if (s === 'disconnected') setTimeout(() => { if (this.pc === pc && pc.connectionState === 'disconnected') this.offer() }, 4000)
    }
    const offer = await pc.createOffer()
    await pc.setLocalDescription(offer)
    await iceGathered(pc)
    this.offerMsg = { t: 'offer', id: randomId(), from: this.id, sdp: pc.localDescription.sdp }
    // Repeat until the car answers: it may not be listening yet, or a relay may drop a note.
    const send = () => this.ch.send(this.offerMsg)
    send()
    let n = 0
    this.repeat = setInterval(() => { if (++n < 40 || n % 4 === 0) send() }, 3000)
    this.on.onState?.('waiting')
  }

  async message(m) {
    if (m.t === 'answer' && m.to === this.id && m.id === this.offerMsg?.id && !this.answered) {
      this.answered = true
      try { await this.pc.setRemoteDescription({ type: 'answer', sdp: m.sdp }) } catch (e) { console.warn(e) }
    }
  }

  async setTracks({ video, audio }) {
    if (video !== undefined) { this.tracks.video = video; await this.video?.sender.replaceTrack(video) }
    if (audio !== undefined) { this.tracks.audio = audio; await this.audio?.sender.replaceTrack(audio) }
    this.raiseBitrate()
  }

  // Phones default to a low WebRTC bitrate; a film on a big car screen looks better with more.
  raiseBitrate() {
    const sender = this.video?.sender
    if (!sender?.track || this.pc?.connectionState !== 'connected') return
    const p = sender.getParameters()
    if (!p.encodings?.length) return
    p.encodings[0].maxBitrate = 4_000_000
    sender.setParameters(p).catch(() => {})
  }

  status(obj) {
    if (this.dc?.readyState === 'open') this.dc.send(JSON.stringify(obj))
  }

  get connected() { return this.pc?.connectionState === 'connected' }
}
