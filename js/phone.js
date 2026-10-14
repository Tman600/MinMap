// Phone side in a browser: pick a video file, play it here, and stream the picture and sound to
// the car. Mirroring the whole screen (YouTube and other apps) needs the MinMap Mirror Android
// app, because phone browsers can't capture the screen.
import { el, icon, consent, store, fmtClock } from './ui.js'
import { cleanCode, prettyCode, Sender } from './link.js'

let sender = null
let audio = null            // { ctx, dest } built once, on the first tap
let video, statusEl, hint, nowEl
let wakeLock = null
let sending = false

const isAndroid = /Android/i.test(navigator.userAgent)

export async function start() {
  document.body.classList.add('phone-page')
  const app = document.getElementById('app')
  app.replaceChildren(el('div', { class: 'phone-wrap' }, el('p', { class: 'muted' }, 'MinMap')))

  if (!await consent()) {
    app.replaceChildren(el('div', { class: 'phone-wrap' },
      el('h1', {}, 'Not connected'),
      el('p', { class: 'muted' }, 'Video stays off unless you accept the passenger-only notice.'),
      el('button', { class: 'btn primary wide', onclick: () => location.reload() }, 'Show the notice again')))
    return
  }

  const fromHash = cleanCode(location.hash.split('=')[1])
  const code = fromHash || cleanCode(store.get('lastCar'))
  if (fromHash) store.set('lastCar', fromHash)
  code ? buildPlayer(app, code) : askCode(app)
}

function askCode(app) {
  const input = el('input', {
    class: 'code-input', inputmode: 'text', autocapitalize: 'characters', autocomplete: 'off',
    spellcheck: 'false', maxlength: '9', placeholder: 'ABCD-EFGH', 'aria-label': 'Pairing code',
  })
  const err = el('p', { class: 'err' })
  const go = () => {
    const code = cleanCode(input.value)
    if (!code) { err.textContent = 'That code has 8 letters and numbers, shown on the car screen.'; return }
    store.set('lastCar', code)
    history.replaceState(null, '', '#cast=' + code)
    buildPlayer(app, code)
  }
  input.addEventListener('keydown', e => { if (e.key === 'Enter') go() })
  app.replaceChildren(el('div', { class: 'phone-wrap' },
    el('h1', {}, 'Send video to the car'),
    el('p', { class: 'muted' }, 'On the car screen, open MinMap and tap the video button. Type the code it shows.'),
    input, err,
    el('button', { class: 'btn primary wide', onclick: go }, 'Connect')))
  input.focus()
}

// Opens the MinMap Mirror app with this code; if it isn't installed, Chrome comes back here.
function mirrorAppLink(code) {
  const back = location.origin + location.pathname + '#cast=' + code + '&noapp'
  return `intent://cast/${code}#Intent;scheme=minmapmirror;package=app.minmap.mirror;S.browser_fallback_url=${encodeURIComponent(back)};end`
}

function buildPlayer(app, code) {
  statusEl = el('div', { class: 'status wait' }, 'Looking for the car\u2026')
  video = el('video', { class: 'local', playsinline: true, controls: true, preload: 'auto' })
  video.disablePictureInPicture = true
  nowEl = el('p', { class: 'now muted' }, 'No video yet')

  const file = el('input', { type: 'file', accept: 'video/*', hidden: true })
  file.addEventListener('change', () => { if (file.files[0]) playFile(file.files[0]); file.value = '' })
  const pickBtn = el('button', {
    class: 'btn primary wide big',
    onclick: () => { ensureAudio(); file.click() },
  }, icon('play'), 'Choose a video')

  // YouTube and other apps: the whole screen, through the Android app.
  const noApp = location.hash.includes('noapp')
  const mirror = isAndroid
    ? el('div', { class: 'mirror-box' },
        el('a', { class: 'btn wide', href: mirrorAppLink(code) }, icon('screen'), 'Mirror the whole screen (YouTube)'),
        el('p', { class: 'muted small' }, noApp
          ? 'MinMap Mirror isn\u2019t installed. Install it to mirror YouTube and other apps; video files work right here.'
          : 'Opens the MinMap Mirror app, which can send any app to the car.'))
    : null

  hint = el('p', { class: 'muted small' }, 'Keep this page open while you watch. Sound plays through the car.')

  app.replaceChildren(el('div', { class: 'phone-wrap' },
    el('header', { class: 'phone-head' },
      el('span', { class: 'logo', html: '<svg viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="#fff"/><path d="M32 12c-8.3 0-15 6.5-15 14.6C17 37.5 32 52 32 52s15-14.5 15-25.4C47 18.5 40.3 12 32 12zm0 20.5a6 6 0 1 1 0-12 6 6 0 0 1 0 12z" fill="#000"/></svg>' }),
      el('div', {}, el('strong', {}, 'MinMap'), el('span', { class: 'muted' }, 'Car ' + prettyCode(code))),
      el('button', { class: 'btn ghost small', onclick: () => { store.set('lastCar', null); location.hash = '#cast'; location.reload() } }, 'Change car')),
    statusEl,
    video, nowEl,
    pickBtn, file, hint,
    mirror,
    el('p', { class: 'fine' }, 'Passenger use only.')))

  sender = new Sender(code, { onState: setStatus, onControl: control })
  sender.start()
  wireVideo()
}

function setStatus() {
  if (!statusEl) return
  const live = sender?.connected
  statusEl.className = 'status ' + (live ? 'ok' : 'wait')
  statusEl.textContent = live
    ? (sending ? 'Playing on the car screen' : 'Connected to the car. Choose a video.')
    : 'Looking for the car\u2026 Keep MinMap open on the car with the video panel showing.'
  if (live) sendStatus()
}

function control(cmd) {
  if (!video.src) return
  if (cmd.op === 'toggle') video.paused ? video.play().catch(() => {}) : video.pause()
  else if (cmd.op === 'seek' && isFinite(video.duration)) video.currentTime = Math.max(0, Math.min(video.duration, cmd.to))
  else if (cmd.op === 'skip' && isFinite(video.duration)) video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + cmd.by))
}

// Audio goes through Web Audio so it can be sent to the car instead of the phone speaker.
// The context is made during a tap, because browsers only start sound after one.
function ensureAudio() {
  if (audio) { audio.ctx.resume(); return }
  const Ctx = window.AudioContext || window.webkitAudioContext
  if (!Ctx) return
  const ctx = new Ctx()
  const source = ctx.createMediaElementSource(video)
  const dest = ctx.createMediaStreamDestination()
  source.connect(dest)
  audio = { ctx, dest }
  ctx.resume()
  sender?.setTracks({ audio: dest.stream.getAudioTracks()[0] })
}

let pendingFileStream = false

function playFile(f) {
  if (video.src) URL.revokeObjectURL(video.src)
  video.src = URL.createObjectURL(f)
  video.dataset.title = f.name.replace(/\.[^.]+$/, '')
  nowEl.textContent = video.dataset.title
  pendingFileStream = true
  video.play().catch(() => { hint.textContent = 'Tap play on the video above to start.' })
  requestWake()
}

function wireVideo() {
  video.addEventListener('playing', async () => {
    if (!pendingFileStream) return
    pendingFileStream = false
    await sender.setTracks({ video: await videoTrack() })
    sending = true
    setStatus()
    hint.textContent = 'Keep this page open while you watch. Sound plays through the car.'
  })
  for (const ev of ['play', 'pause', 'seeked', 'durationchange', 'ended']) video.addEventListener(ev, () => sendStatus())
  setInterval(() => sendStatus(), 1000)
}

// The picture: the element's own capture where the browser has it, otherwise a canvas copy (iPhone).
async function videoTrack() {
  const capture = video.captureStream || video.mozCaptureStream
  if (capture) {
    const s = capture.call(video)
    let t = s.getVideoTracks()[0]
    if (!t) t = await new Promise(res => {
      s.addEventListener('addtrack', e => { if (e.track.kind === 'video') res(e.track) })
      setTimeout(() => res(s.getVideoTracks()[0] || null), 1500)
    })
    if (t) { t.contentHint = 'motion'; return t }
  }
  return canvasTrack()
}

let canvasState = null
function canvasTrack() {
  if (canvasState) return canvasState.track
  const canvas = document.createElement('canvas')
  const g = canvas.getContext('2d')
  const draw = () => {
    const w = video.videoWidth, h = video.videoHeight
    if (w && h) {
      const scale = Math.min(1, 1280 / Math.max(w, h))
      const cw = Math.round(w * scale), ch = Math.round(h * scale)
      if (canvas.width !== cw || canvas.height !== ch) { canvas.width = cw; canvas.height = ch }
      g.drawImage(video, 0, 0, cw, ch)
    }
    video.requestVideoFrameCallback ? video.requestVideoFrameCallback(draw) : requestAnimationFrame(draw)
  }
  canvas.width = 1280; canvas.height = 720
  draw()
  const track = canvas.captureStream(30).getVideoTracks()[0]
  track.contentHint = 'motion'
  canvasState = { canvas, track }
  return track
}

function sendStatus() {
  if (!sender?.connected) return
  const msg = {
    t: 'st',
    title: video.dataset.title || '',
    playing: !video.paused && !video.ended,
    time: video.currentTime || 0,
    dur: isFinite(video.duration) ? video.duration : 0,
    screen: false,
  }
  sender.status(msg)
  if (nowEl && msg.title) nowEl.textContent = msg.title + '  \u00b7  ' + fmtClock(msg.time) + ' / ' + fmtClock(msg.dur)
}

async function requestWake() {
  try {
    if (!wakeLock && navigator.wakeLock) {
      wakeLock = await navigator.wakeLock.request('screen')
      wakeLock.addEventListener('release', () => { wakeLock = null })
    }
  } catch { /* not allowed here */ }
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && sending) requestWake()
})
