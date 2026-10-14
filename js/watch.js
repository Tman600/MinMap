// Car side of phone video: shows a pairing QR, then the phone's stream on the passenger side.
import { el, icon, icons, consent, store, fmtClock, loadScript, toast } from './ui.js'
import { newCode, prettyCode, castUrl, Receiver } from './link.js'

// Video without the map: for testing, or when the Mapbox account is out of free use.
export function watchOnly() {
  document.body.className = 'car-page dark'
  const again = el('button', { class: 'btn primary', onclick: () => w.toggle() }, icon('screen'), 'Watch from phone')
  document.getElementById('app').replaceChildren(el('main', { class: 'signin' },
    el('h1', {}, 'MinMap video'), el('p', { class: 'muted' }, 'Phone video only, without the map.'), again))
  const w = new Watch({})
  w.size = 'full'
  w.applyLayout()
  w.toggle()
}

export class Watch {
  constructor({ onLayout }) {
    this.onLayout = onLayout   // tells the map how much of the screen the video covers
    this.accepted = false
    this.receiver = null
    this.linked = false
    this.picture = false
    this.state = null
    this.side = store.get('watchSide', 'right')   // passenger side in a left-hand-drive car
    this.size = store.get('watchSize', 'half')
    this.build()
  }

  build() {
    this.videoEl = el('video', { autoplay: true, playsinline: true })
    this.videoEl.disablePictureInPicture = true
    // The picture counts as there once real frames arrive; a muted placeholder track has none.
    const checkPicture = () => {
      const has = this.videoEl.videoWidth > 2
      if (has !== this.picture) { this.picture = has; this.setPhase(); if (this.state) this.showState(this.state) }
    }
    this.videoEl.addEventListener('resize', checkPicture)
    this.videoEl.addEventListener('loadeddata', checkPicture)
    this.tapToPlay = el('button', { class: 'tap-play', hidden: true, onclick: () => this.playVideo() }, icon('play'), 'Tap to play')
    this.qr = el('div', { class: 'qr' })
    this.codeEl = el('div', { class: 'code' })
    this.urlEl = el('div', { class: 'url muted' })
    this.pairStatus = el('div', { class: 'status wait' }, 'Waiting for a phone\u2026')
    this.pair = el('div', { class: 'pair' },
      el('h2', {}, 'Watch from your phone'),
      el('p', { class: 'muted' }, 'Scan with the phone camera, pick a video there, and it plays here.'),
      this.qr,
      el('div', { class: 'or muted' }, 'or open this page on the phone and enter'),
      this.codeEl, this.urlEl, this.pairStatus,
      el('div', { class: 'row' },
        el('button', { class: 'btn ghost small', onclick: () => this.newCode() }, 'New code'),
        el('a', { class: 'btn ghost small', href: location.pathname + '#check', onclick: () => setTimeout(() => location.reload(), 0) }, 'Not connecting? Run a check')))

    this.titleEl = el('div', { class: 'title' })
    this.timeEl = el('div', { class: 'time muted' })
    this.playBtn = el('button', { class: 'iconbtn', 'aria-label': 'Play or pause', onclick: () => this.send({ op: 'toggle' }) }, icon('pause'))
    this.seek = el('input', { type: 'range', min: '0', max: '1000', value: '0', class: 'seek', 'aria-label': 'Position' })
    this.seek.addEventListener('change', () => {
      if (this.state?.dur) this.send({ op: 'seek', to: this.state.dur * this.seek.value / 1000 })
    })
    this.seek.addEventListener('input', () => { this.seeking = true; clearTimeout(this.seekT); this.seekT = setTimeout(() => { this.seeking = false }, 1500) })
    this.sizeBtn = el('button', { class: 'iconbtn', 'aria-label': 'Bigger or smaller', onclick: () => this.cycleSize() }, icon('expand'))

    this.controls = el('div', { class: 'watch-bar' },
      el('div', { class: 'meta' }, this.titleEl, this.timeEl),
      el('div', { class: 'transport' },
        el('button', { class: 'iconbtn', 'aria-label': 'Back 10 seconds', onclick: () => this.send({ op: 'skip', by: -10 }) }, icon('back10')),
        this.playBtn,
        el('button', { class: 'iconbtn', 'aria-label': 'Forward 10 seconds', onclick: () => this.send({ op: 'skip', by: 10 }) }, icon('fwd10'))),
      this.seek,
      el('div', { class: 'tools' },
        el('button', { class: 'iconbtn', 'aria-label': 'Move to the other side', onclick: () => this.flipSide() }, icon('side')),
        this.sizeBtn,
        el('button', { class: 'iconbtn', 'aria-label': 'Close video', onclick: () => this.close() }, icon('close'))))

    this.stage = el('div', { class: 'stage' }, this.videoEl, this.tapToPlay, this.pair)
    this.root = el('section', { class: 'watch panel', hidden: true, 'aria-label': 'Phone video' },
      el('div', { class: 'note' }, 'Passenger use only'),
      this.stage, this.controls)
    document.body.append(this.root)
    this.applyLayout()
  }

  get open() { return !this.root.hidden }

  async toggle() {
    if (this.open) return this.close()
    if (!this.accepted) {
      if (!await consent()) return
      this.accepted = true
    }
    this.root.hidden = false
    this.join()
    this.applyLayout()
  }

  close() {
    this.root.hidden = true
    this.leave()
    this.applyLayout()
  }

  code() {
    let c = store.get('castCode')
    if (!c) { c = newCode(); store.set('castCode', c) }
    return c
  }

  newCode() {
    store.set('castCode', newCode())
    this.leave()
    this.join()
  }

  async join() {
    const code = this.code()
    this.codeEl.textContent = prettyCode(code)
    const url = castUrl(code)
    this.urlEl.textContent = url.replace(/^https?:\/\//, '').replace(/#.*/, '')
    this.renderQr(url)
    this.linked = false
    this.receiver = new Receiver(code, {
      onStream: stream => {
        if (this.videoEl.srcObject !== stream) this.videoEl.srcObject = stream
        this.playVideo()
      },
      onStatus: s => this.showState(s),
      onState: state => {
        this.linked = state === 'connected'
        // Only a dropped link clears the picture; tracks arrive before the link is up.
        if (state === 'waiting') { this.state = null; this.picture = false; this.videoEl.srcObject = null }
        if (this.linked && this.receiver?.stream && this.videoEl.srcObject !== this.receiver.stream) {
          this.videoEl.srcObject = this.receiver.stream
          this.playVideo()
        }
        this.setPhase()
      },
    })
    await this.receiver.start()
    this.setPhase()
  }

  leave() {
    this.receiver?.close()
    this.receiver = null
    this.linked = false
    this.picture = false
    this.state = null
    this.videoEl.srcObject = null
    this.setPhase()
  }

  async renderQr(url) {
    try {
      if (!window.qrcode) await loadScript(new URL('../vendor/qrcode.js', import.meta.url).href)
      const q = window.qrcode(0, 'M')
      q.addData(url)
      q.make()
      this.qr.innerHTML = q.createSvgTag({ cellSize: 6, margin: 2, scalable: true })
    } catch {
      this.qr.textContent = ''
    }
  }

  playVideo() {
    this.tapToPlay.hidden = true
    this.videoEl.play().catch(() => {
      // Sound needs a tap on this screen first; show the picture muted until then.
      this.tapToPlay.hidden = false
    })
  }

  // A phone can be connected before it sends a picture (the web page waits for a file).
  setPhase() {
    const showing = this.linked && this.picture
    this.pair.hidden = showing
    this.controls.classList.toggle('dim', !showing)
    this.root.classList.toggle('mirroring', !!this.state?.screen)
    this.root.classList.toggle('controllable', !this.state?.screen || !!this.state?.controls)
    if (this.linked) {
      this.pairStatus.className = 'status ok'
      this.pairStatus.textContent = 'Phone connected. Choose a video or start mirroring on the phone.'
    } else {
      this.pairStatus.className = 'status wait'
      this.pairStatus.textContent = this.receiver ? 'Waiting for a phone\u2026' : 'Not connected'
    }
    if (!showing) { this.titleEl.textContent = this.linked ? 'Phone connected' : 'No phone yet'; this.timeEl.textContent = '' }
  }

  showState(s) {
    this.state = s
    // A mirrored screen can be played and seeked only when the app has media control access.
    this.root.classList.toggle('mirroring', !!s.screen)
    this.root.classList.toggle('controllable', !s.screen || !!s.controls)
    if (!this.picture) return
    this.titleEl.textContent = s.title || 'Phone video'
    this.timeEl.textContent = s.screen && !s.dur ? 'Mirroring the phone screen' : fmtClock(s.time) + ' / ' + fmtClock(s.dur)
    this.playBtn.innerHTML = '<span class="ic">' + (s.playing ? icons.pause : icons.play) + '</span>'
    if (!this.seeking) this.seek.value = s.dur ? Math.round(1000 * s.time / s.dur) : 0
    this.seek.disabled = !s.dur
  }

  send(cmd) {
    if (!this.receiver?.control(cmd)) toast('No phone connected')
  }

  flipSide() {
    this.side = this.side === 'right' ? 'left' : 'right'
    store.set('watchSide', this.side)
    this.applyLayout()
  }

  cycleSize() {
    this.size = { small: 'half', half: 'full', full: 'small' }[this.size] || 'half'
    store.set('watchSize', this.size)
    this.applyLayout()
  }

  applyLayout() {
    this.root.className = `watch panel side-${this.side} size-${this.size}`
    this.sizeBtn.innerHTML = '<span class="ic">' + (this.size === 'full' ? icons.shrink : icons.expand) + '</span>'
    const covers = this.open && this.size === 'half' ? this.side : null
    this.onLayout?.(covers)
  }
}
