// Sending the Mapbox token from a phone to the car, so nobody types 90 characters on a car
// screen. Same encrypted channel as the video pairing (link.js): the car shows a QR code holding a
// one-time code; the phone page (#give=CODE) sends {t:'token'} and the car answers {t:'got'}.
import { el, loadScript } from './ui.js'
import { newCode, cleanCode, prettyCode, openChannel } from './link.js'

// Car side: shows the QR code and resolves with the token once one arrives.
export function receiveToken(container) {
  const code = newCode()
  const url = location.origin + location.pathname + '#give=' + code
  const qr = el('div', { class: 'qr' })
  const status = el('div', { class: 'status wait' }, 'Waiting for your phone\u2026')
  container.replaceChildren(
    el('p', { class: 'muted' }, 'Scan with your phone, paste your token there, and tap Send.'),
    qr,
    el('div', { class: 'code' }, prettyCode(code)),
    status)
  ;(async () => {
    try {
      if (!window.qrcode) await loadScript(new URL('../vendor/qrcode.js', import.meta.url).href)
      const q = window.qrcode(0, 'M')
      q.addData(url)
      q.make()
      qr.innerHTML = q.createSvgTag({ cellSize: 6, margin: 2, scalable: true })
    } catch { qr.textContent = url }
  })()
  let channel
  const promise = new Promise(resolve => {
    openChannel(code, m => {
      if (m.t !== 'token' || typeof m.token !== 'string') return
      status.className = 'status ok'
      status.textContent = 'Token received. Checking it\u2026'
      channel?.send({ t: 'got' })
      resolve({ token: m.token.trim(), status, close: () => channel?.close() })
    }).then(c => { channel = c })
  })
  return promise
}

// Phone side (#give=CODE): paste the token and send it.
export async function start() {
  document.body.className = 'phone-page'
  const code = cleanCode(location.hash.split('=')[1])
  const app = document.getElementById('app')
  if (!code) {
    app.replaceChildren(el('div', { class: 'phone-wrap' }, el('h1', {}, 'Scan the code on the car again'),
      el('p', { class: 'muted' }, 'This link is missing its code.')))
    return
  }
  const input = el('input', { class: 'token-input', placeholder: 'pk.eyJ1Ijoi\u2026', autocomplete: 'off', spellcheck: 'false', 'aria-label': 'Mapbox public token' })
  const status = el('p', { class: 'muted' })
  const send = el('button', { class: 'btn primary wide big' }, 'Send to the car')
  app.replaceChildren(el('div', { class: 'phone-wrap' },
    el('h1', {}, 'Sign the car in to Mapbox'),
    el('p', { class: 'muted' }, 'Paste your Mapbox public token (it starts with pk.). It goes to the car encrypted with the code ' + prettyCode(code) + ', and isn\u2019t kept on this phone.'),
    input, send, status))
  const channel = await openChannel(code, m => {
    if (m.t === 'got') {
      clearInterval(timer)
      status.className = 'status ok'
      status.textContent = 'The car has it. You can close this page.'
      send.disabled = true
    }
  })
  let timer = 0
  send.addEventListener('click', () => {
    const token = input.value.trim()
    if (!/^pk\./.test(token)) { status.textContent = 'That doesn\u2019t look like a public token. It starts with pk.'; return }
    status.className = 'status wait'
    status.textContent = 'Sending\u2026 keep this page open.'
    // Repeat until the car confirms: a relay may drop a note.
    let n = 0
    const once = () => channel.send({ t: 'token', token })
    once()
    clearInterval(timer)
    timer = setInterval(() => { if (++n > 20) { clearInterval(timer); status.textContent = 'The car didn\u2019t answer. Is the QR code still showing on it?' } else once() }, 2000)
  })
}
