// The trailer's engine (see index.html): the score and voice, and every frame's picture,
// drawn from a cut laid out by editor.js (timeline()).
"use strict";
const W = 1920, H = 1080;
// Opened straight from disk (dragged into the browser): every file is its own origin there, so
// the clips load without asking for cross-origin access and the voice plays through plain
// audio elements; only Export needs the page served (tools/trailer/serve.bat)
const LOCAL = location.protocol === "file:";
const canvas = document.getElementById("screen"), g = canvas.getContext("2d");
const statusEl = document.getElementById("status");
// The voice's subtitles: off unless chosen (for viewers who need them; Steam has its own)
let subtitles = false;
try { subtitles = localStorage.getItem("xg-trailer-subtitles") === "1"; } catch (e) {}

// ---------------------------------------------------------------- sound
let ac, master, musicBus, voiceBus, recordDest, voices = {};
async function audioReady() {
  if (ac) return;
  ac = new AudioContext();
  master = ac.createDynamicsCompressor(); master.threshold.value = -10; master.ratio.value = 4;
  master.connect(ac.destination);
  recordDest = ac.createMediaStreamDestination(); master.connect(recordDest);
  musicBus = ac.createGain(); musicBus.gain.value = 0.55; musicBus.connect(master);
  voiceBus = ac.createGain(); voiceBus.gain.value = 1.0; voiceBus.connect(master);
  const names = new Set(TRAILER.scenes.flatMap(s => (s.vo && s.vo.line ? [s.vo.line] : [])));
  await Promise.all([...names].map(async n => {
    try {
      if (LOCAL) {
        const el = new Audio("audio/" + n + ".wav"); el.preload = "auto";
        await new Promise((ok, fail) => { el.addEventListener("loadedmetadata", ok, { once: true }); el.addEventListener("error", fail, { once: true }); });
        voices[n] = el;
        return;
      }
      const r = await fetch("audio/" + n + ".wav");
      voices[n] = await ac.decodeAudioData(await r.arrayBuffer());
    } catch (e) { statusEl.textContent = "Voice line " + n + " couldn't load: serve this folder over http (see README)."; }
  }));
}

const scheduled = [];
function keep(node) { scheduled.push(node); return node; }

function noiseBuffer(seconds) {
  const b = ac.createBuffer(1, ac.sampleRate * seconds, ac.sampleRate), d = b.getChannelData(0);
  for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
  return b;
}

/** A trailer "braam": a low brassy chord swelling in fast and dying slowly, a thud of sub under it. */
function hit(at, size) {
  if (at < ac.currentTime - 0.05) return;
  const out = ac.createGain(); out.connect(musicBus);
  const lp = ac.createBiquadFilter(); lp.type = "lowpass"; lp.connect(out);
  lp.frequency.setValueAtTime(180, at); lp.frequency.linearRampToValueAtTime(1400 * size, at + 0.12); lp.frequency.exponentialRampToValueAtTime(160, at + 3.5);
  out.gain.setValueAtTime(0, at); out.gain.linearRampToValueAtTime(0.5 * size, at + 0.05); out.gain.exponentialRampToValueAtTime(0.001, at + 4.5);
  for (const f of [55, 55.4, 82.4, 110, 109.6]) {
    const o = keep(ac.createOscillator()); o.type = "sawtooth"; o.frequency.value = f; o.connect(lp); o.start(at); o.stop(at + 4.6);
  }
  const sub = keep(ac.createOscillator()), sg = ac.createGain();
  sub.frequency.setValueAtTime(90, at); sub.frequency.exponentialRampToValueAtTime(32, at + 0.6);
  sg.gain.setValueAtTime(0.9 * size, at); sg.gain.exponentialRampToValueAtTime(0.001, at + 1.4);
  sub.connect(sg); sg.connect(musicBus); sub.start(at); sub.stop(at + 1.5);
  const n = keep(ac.createBufferSource()), ng = ac.createGain(), nf = ac.createBiquadFilter();
  n.buffer = noiseBuffer(1); nf.type = "lowpass"; nf.frequency.value = 2500;
  ng.gain.setValueAtTime(0.35 * size, at); ng.gain.exponentialRampToValueAtTime(0.001, at + 0.5);
  n.connect(nf); nf.connect(ng); ng.connect(musicBus); n.start(at);
}

/** A rising whoosh of filtered noise ending exactly at a cut. */
function riser(end, seconds) {
  if (end - seconds < ac.currentTime - 0.05) return;
  const n = keep(ac.createBufferSource()), f = ac.createBiquadFilter(), gn = ac.createGain();
  n.buffer = noiseBuffer(seconds + 0.1); f.type = "bandpass"; f.Q.value = 6;
  f.frequency.setValueAtTime(300, end - seconds); f.frequency.exponentialRampToValueAtTime(6000, end);
  gn.gain.setValueAtTime(0.0001, end - seconds); gn.gain.exponentialRampToValueAtTime(0.35, end - 0.02); gn.gain.linearRampToValueAtTime(0, end);
  n.connect(f); f.connect(gn); gn.connect(musicBus); n.start(end - seconds); n.stop(end + 0.05);
}

/**
 * The score under the whole cut: a drone in A whose brightness follows each scene's energy, a
 * ticking pulse once things get going, hits on the scenes that ask for one and risers into them.
 */
function scheduleMusic(t0, tl) {
  // (played from partway through, what has already gone by is left out, and the drone fades in from now)
  const now = ac.currentTime;
  const drone = ac.createGain(), lp = ac.createBiquadFilter();
  lp.type = "lowpass"; lp.Q.value = 3; drone.connect(musicBus); lp.connect(drone);
  drone.gain.setValueAtTime(0, Math.max(t0, now)); drone.gain.linearRampToValueAtTime(0.22, Math.max(t0, now) + (t0 >= now ? 3 : 0.6));
  for (const [f, type] of [[55, "sawtooth"], [55.3, "sawtooth"], [82.6, "triangle"], [164.8, "sine"], [220.5, "sine"]]) {
    const o = keep(ac.createOscillator()); o.type = type; o.frequency.value = f; o.connect(lp); o.start(Math.max(t0, now)); o.stop(t0 + tl.length + 6);
  }
  const pulse = ac.createGain(); pulse.gain.value = 0; pulse.connect(musicBus);
  const beat = 60 / (TRAILER.bpm || 100) / 2;
  for (const s of tl.scenes) {
    const at = t0 + s.start, e = s.energy ?? 0.3;
    lp.frequency.setTargetAtTime(150 + 1800 * e * e, at, 0.6);
    if (s.hit) hit(at, s.hit === true ? 1 : s.hit);
    if (s.riser) riser(at, s.riser);
    if (s.silence) drone.gain.setTargetAtTime(0.02, at, 0.1), drone.gain.setTargetAtTime(0.22, at + s.silence, 0.8);
    // (the pulse: eighth notes on a muted bass pluck, from energy 0.5 up)
    const notes = [110, 110, 130.8, 110, 98, 110, 146.8, 130.8];
    for (let k = 0, t = at; t < at + s.dur && e >= 0.5; k++, t += beat) {
      if (t < now) continue;
      const o = keep(ac.createOscillator()), env = ac.createGain(), f = ac.createBiquadFilter();
      o.type = "square"; o.frequency.value = notes[k % 8] / (k % 2 ? 1 : 2);
      f.type = "lowpass"; f.frequency.value = 500 + 1500 * e;
      env.gain.setValueAtTime(0.0001, t); env.gain.exponentialRampToValueAtTime(0.11 * e, t + 0.01); env.gain.exponentialRampToValueAtTime(0.0001, t + beat * 0.9);
      o.connect(f); f.connect(env); env.connect(musicBus); o.start(t); o.stop(t + beat);
    }
  }
  drone.gain.setTargetAtTime(0, t0 + tl.length - 0.5, 1.2);
}

const voiceTimers = [];
function scheduleVoices(t0, tl) {
  const now = ac.currentTime;
  for (const line of tl.voices) {
    const sound = voices[line.line];
    if (!sound) continue;
    const at = t0 + line.t;
    // (one already under way when playing starts is picked up partway through)
    if (at + sound.duration < now) continue;
    const into = Math.max(0, now - at);
    // (the music dips under the voice)
    musicBus.gain.setTargetAtTime(0.32, Math.max(now, at - 0.1), 0.15);
    musicBus.gain.setTargetAtTime(0.55, at + sound.duration, 0.4);
    if (LOCAL) {
      voiceTimers.push(setTimeout(() => { sound.currentTime = into; sound.play().catch(() => {}); }, Math.max(0, at - now) * 1000));
      continue;
    }
    const src = keep(ac.createBufferSource()); src.buffer = sound;
    const bp = ac.createBiquadFilter(); bp.type = "highpass"; bp.frequency.value = 120;
    src.connect(bp); bp.connect(voiceBus); src.start(Math.max(at, now), into);
  }
}

// ---------------------------------------------------------------- pictures
function ease(t) { return t < 0 ? 0 : t > 1 ? 1 : t * t * (3 - 2 * t); }

const held = {};
function drawClip(s, local, alpha) {
  if (!s.clip) return;
  const v = videoFor(s);
  // (while a clip seeks or catches up, its last frame stays up rather than a black one)
  const fresh = v.readyState >= 2 && !v.seeking;
  if (fresh) {
    const hk = s.uid || s.id;
    if (!held[hk]) { held[hk] = document.createElement("canvas"); held[hk].width = W / 2; held[hk].height = H / 2; }
    held[hk].getContext("2d").drawImage(v, 0, 0, W / 2, H / 2);
  }
  const picture = fresh ? v : held[s.uid || s.id];
  if (!picture) return;
  const p = s.dur > 0 ? local / s.dur : 0;
  const [z0, z1] = s.zoom || [1.0, 1.05];
  const z = z0 + (z1 - z0) * p;
  const w = W * z, h = H * z;
  const [px, py] = s.pan || [0, 0];
  g.globalAlpha = alpha;
  g.filter = s.grade || "none";
  g.drawImage(picture, (W - w) / 2 + px * (z - 1) * W / 2, (H - h) / 2 + py * (z - 1) * H / 2, w, h);
  g.filter = "none";
  g.globalAlpha = 1;
}

/** Text on the picture: title (the big statement), sub (smaller line under it), caption (subtitles for the voice). */
function drawText(item, local) {
  const t = local - (item.at || 0), dur = item.dur ?? 3;
  if (t < 0 || t > dur) return;
  const fadeIn = Math.min(0.5, dur / 4), a = Math.min(ease(t / fadeIn), ease((dur - t) / 0.4));
  g.save(); g.globalAlpha = a; g.textAlign = "center"; g.textBaseline = "middle";
  const style = item.style || "title";
  const y = item.y ?? (style === "caption" ? H - 120 : H / 2);
  if (style === "caption") {
    g.font = "500 38px 'IBM Plex Mono', Consolas, monospace";
    const lines = wrap(item.text, 1500);
    lines.forEach((ln, i) => {
      const yy = y - (lines.length - 1 - i) * 52;
      const m = g.measureText(ln).width;
      g.fillStyle = "rgba(0,0,0,0.55)"; g.fillRect(W / 2 - m / 2 - 18, yy - 28, m + 36, 56);
      g.fillStyle = "#c9fbe9"; g.fillText(ln, W / 2, yy);
    });
    if (item.who) { g.font = "600 24px 'Chakra Petch', sans-serif"; g.fillStyle = "#6ff0c8"; g.fillText(item.who.toUpperCase(), W / 2, y - lines.length * 52 - 6); }
  } else {
    const size = item.size || (style === "sub" ? 46 : 104);
    const spread = (1 - ease(t / 1.2)) * 24 + (style === "sub" ? 8 : 14);
    if (style === "sub") {
      // (a dark band behind the smaller words, so they read over bright screens like the results)
      const band = g.createLinearGradient(0, y - size, 0, y + size);
      band.addColorStop(0, "rgba(0,0,0,0)"); band.addColorStop(0.5, "rgba(0,0,0,0.55)"); band.addColorStop(1, "rgba(0,0,0,0)");
      g.fillStyle = band; g.fillRect(0, y - size, W, size * 2);
    }
    g.font = (style === "sub" ? "600 " : "700 ") + size + "px 'Chakra Petch', Bahnschrift, sans-serif";
    g.letterSpacing = spread + "px";
    g.shadowColor = "rgba(111,240,200,0.55)"; g.shadowBlur = style === "sub" ? 12 : 30;
    g.fillStyle = item.colour || (style === "sub" ? "#d9fff2" : "#ffffff");
    (item.text.split("\n")).forEach((ln, i, all) => g.fillText(ln, W / 2, y + (i - (all.length - 1) / 2) * size * 1.15));
  }
  g.restore();
}

function wrap(text, width) {
  const words = text.split(" "), lines = []; let line = "";
  for (const w of words) { const next = line ? line + " " + w : w; if (g.measureText(next).width > width && line) { lines.push(line); line = w; } else line = next; }
  if (line) lines.push(line); return lines;
}

/** The logo card: the name, built from its letters, with the scan line Xenocorp's screens have. */
function drawLogo(local, s) {
  const a = ease(local / 0.8) * ease((s.dur - local) / 0.6);
  g.save(); g.globalAlpha = a; g.textAlign = "center"; g.textBaseline = "middle";
  g.font = "700 168px 'Chakra Petch', Bahnschrift, sans-serif";
  g.letterSpacing = (40 - 26 * ease(local / 2.5)) + "px";
  g.shadowColor = "rgba(111,240,200,0.8)"; g.shadowBlur = 40;
  g.fillStyle = "#ffffff"; g.fillText("XENOGUESSER", W / 2, H / 2 - 20);
  g.shadowBlur = 0; g.letterSpacing = "10px";
  g.font = "600 34px 'Chakra Petch', sans-serif"; g.fillStyle = "#6ff0c8";
  if (s.tagline) g.fillText(s.tagline.toUpperCase(), W / 2, H / 2 + 110);
  if (s.cta) { g.font = "600 40px 'Chakra Petch', sans-serif"; g.fillStyle = "#ffffff"; g.fillText(s.cta.toUpperCase(), W / 2, H / 2 + 200); }
  g.restore();
  const sweep = (local * 0.6) % 1.4 - 0.2;
  const grad = g.createLinearGradient(0, H * sweep - 80, 0, H * sweep + 80);
  grad.addColorStop(0, "rgba(111,240,200,0)"); grad.addColorStop(0.5, "rgba(111,240,200,0.06)"); grad.addColorStop(1, "rgba(111,240,200,0)");
  g.fillStyle = grad; g.fillRect(0, 0, W, H);
}

// ---------------------------------------------------------------- the planets
// Each world's globe as its landing pod sees it (globes/globes.js, made from the game by
// TrailerGlobes), spun here: its pixels, read once
const planets = [];
if (typeof GLOBES !== "undefined") {
  for (const gl of GLOBES) {
    const img = new Image();
    const planet = { ...gl, pixels: null, w: 0, h: 0 };
    img.onload = () => {
      const c = document.createElement("canvas"); c.width = img.width; c.height = img.height;
      const x = c.getContext("2d"); x.drawImage(img, 0, 0);
      planet.pixels = new Uint32Array(x.getImageData(0, 0, img.width, img.height).data.buffer);
      planet.w = img.width; planet.h = img.height;
    };
    img.src = gl.picture;
    planets.push(planet);
  }
}
const GLOBE_PX = 200;
const globeCanvas = document.createElement("canvas"); globeCanvas.width = globeCanvas.height = GLOBE_PX;
const globeG = globeCanvas.getContext("2d"), globeImage = globeG.createImageData(GLOBE_PX, GLOBE_PX);
const globePixels = new Uint32Array(globeImage.data.buffer);

/** One planet drawn as a lit sphere turned by spin (radians), its axis tipped by tilt. */
function paintGlobe(planet, spin, tilt) {
  const n = GLOBE_PX, ct = Math.cos(tilt), st = Math.sin(tilt);
  const lx = -0.55, ly = 0.45, lz = 0.7;
  for (let j = 0; j < n; j++) {
    const y = 1 - (j + 0.5) / n * 2;
    for (let i = 0; i < n; i++) {
      const x = (i + 0.5) / n * 2 - 1, rr = x * x + y * y, k = j * n + i;
      if (rr > 1) { globePixels[k] = 0; continue; }
      const z = Math.sqrt(1 - rr);
      const yy = y * ct - z * st, zz = y * st + z * ct;
      const lat = Math.asin(yy), lon = Math.atan2(x, zz) + spin;
      let u = ((lon / (2 * Math.PI)) % 1 + 1) % 1;
      const px = Math.min(planet.w - 1, (u * planet.w) | 0), py = Math.min(planet.h - 1, ((0.5 - lat / Math.PI) * planet.h) | 0);
      const c = planet.pixels[py * planet.w + px];
      const light = 0.22 + 0.9 * Math.max(0, x * lx + y * ly + z * lz) + 0.25 * Math.pow(1 - z, 3);
      const rch = Math.min(255, (c & 255) * light), gch = Math.min(255, ((c >> 8) & 255) * light), bch = Math.min(255, ((c >> 16) & 255) * light);
      globePixels[k] = 0xff000000 | (bch << 16) | (gch << 8) | rch;
    }
  }
  globeG.putImageData(globeImage, 0, 0);
  return globeCanvas;
}

// A fixed field of stars behind the planets
const stars = Array.from({ length: 260 }, (_, i) => [((i * 7919) % 1920), ((i * 104729) % 1080), ((i * 31) % 7) / 6]);

/** The planets scene: the worlds appear one after another, each turning, named under it. */
function drawGlobes(local, s) {
  g.fillStyle = "#03050a"; g.fillRect(0, 0, W, H);
  for (const [x, y, b] of stars) { g.fillStyle = "rgba(220,235,255," + (0.25 + 0.6 * b) + ")"; g.fillRect(x, y, 1 + b * 1.5, 1 + b * 1.5); }
  const shown = planets.filter(p => p.pixels).slice(0, s.globes);
  const cols = Math.ceil(shown.length / 2), size = 220, gapX = (W - 260) / cols;
  shown.forEach((planet, i) => {
    const appear = (local - 0.25 - i * 0.16) / 0.45;
    if (appear <= 0) return;
    const a = Math.min(1, appear), pop = a < 1 ? 1 + 0.12 * Math.sin(a * Math.PI) : 1;
    const row = i % 2, col = (i / 2) | 0;
    // (two rows, the lower one offset half a step, with a band between them for the words)
    const cx = 130 + gapX * (col + 0.5) + (row ? gapX * 0.15 : -gapX * 0.15), cy = row ? 790 : 260;
    const r = size / 2 * a * pop;
    const spin = local * (0.35 + (i % 5) * 0.09) + i * 1.7, tilt = 0.2 + (i % 4) * 0.12;
    // (a thin haze round each, in its sea's colour)
    const sea = planet.pixels[0], sr = sea & 255, sg = (sea >> 8) & 255, sb = (sea >> 16) & 255;
    const halo = g.createRadialGradient(cx, cy, r * 0.95, cx, cy, r * 1.25);
    halo.addColorStop(0, `rgba(${sr},${sg},${sb},0.45)`); halo.addColorStop(1, `rgba(${sr},${sg},${sb},0)`);
    g.fillStyle = halo; g.fillRect(cx - r * 1.3, cy - r * 1.3, r * 2.6, r * 2.6);
    g.drawImage(paintGlobe(planet, spin, tilt), cx - r, cy - r, r * 2, r * 2);
    g.save(); g.globalAlpha = Math.min(1, appear * 1.5); g.textAlign = "center";
    g.font = "600 30px 'Chakra Petch', sans-serif"; g.letterSpacing = "6px"; g.fillStyle = "#e9fff7";
    g.fillText(planet.name.toUpperCase(), cx, cy + size / 2 + 46);
    g.font = "400 18px 'IBM Plex Mono', monospace"; g.letterSpacing = "2px"; g.fillStyle = "#6ff0c8";
    g.fillText("SEED " + planet.seed, cx, cy + size / 2 + 72);
    g.restore();
  });
}

function letterbox() {
  const bar = TRAILER.letterbox || 0;
  if (!bar) return;
  g.fillStyle = "#000"; g.fillRect(0, 0, W, bar); g.fillRect(0, H - bar, W, bar);
}

function frameAt(t, tl) {
  g.fillStyle = "#000"; g.fillRect(0, 0, W, H);
  const live = tl.scenes.filter(s => t >= s.start && t < s.start + s.dur + (nextXf(tl, s)));
  for (const s of live) {
    const local = t - s.start;
    let alpha = s.xf ? ease(local / s.xf) : 1;
    const out = s.fadeOut ? ease((s.dur - local) / s.fadeOut) : 1;
    alpha *= out;
    if (s.globes) { g.save(); g.globalAlpha = alpha; drawGlobes(local, s); g.restore(); }
    else if (s.logo) { g.globalAlpha = 1; drawClip(s, local, 0.35 * alpha); drawLogo(local, s); }
    else drawClip(s, local, alpha);
  }
  letterbox();
  // (the titles and voice lines on their own tracks, each at its own time)
  for (const item of tl.titles) drawText(item, t - item.t);
  if (subtitles) {
    for (const line of tl.voices) {
      if (line.caption) drawText({ style: "caption", text: line.caption, who: line.who ?? "Xenocorp", dur: voiceLength(line.line) + 0.6 }, t - line.t);
    }
  }
  for (const s of live) {
    const local = t - s.start;
    if (s.flash) { const f = 1 - local / 0.35; if (f > 0) { g.fillStyle = "rgba(255,255,255," + f + ")"; g.fillRect(0, 0, W, H); } }
  }
}
function nextXf(tl, s) { const i = tl.scenes.indexOf(s); return i + 1 < tl.scenes.length ? tl.scenes[i + 1].xf : 0; }
