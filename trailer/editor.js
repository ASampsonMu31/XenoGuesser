// The trailer's editor (see index.html): the cut being edited (a sequence of pictures, titles
// and voice lines, remembered in this browser), the transport (play, pause, back to the start,
// a bar to drag through the cut) and the sequencer the cut is built on.
"use strict";

// ---------------------------------------------------------------- the cut
// The library: every picture (a scene of scenes.js), title and voice line, by key
const LIB = { pictures: {}, titles: {}, voices: {} };
for (const s of TRAILER.scenes) {
  if (s.act) continue;
  const { text, vo, ...picture } = s;
  LIB.pictures[s.id] = picture;
  (text || []).forEach((item, i) => { LIB.titles[s.id + "#" + i] = { ...item, key: s.id + "#" + i, at: 0 }; });
  if (vo && vo.line) LIB.voices[s.id + "~vo"] = { ...vo, key: s.id + "~vo", at: 0 };
}

/** The cut as scenes.js has it: its scenes in order, each title and voice line at the time it falls. */
function originalSequence() {
  const seq = { pictures: [], titles: [], voices: [] };
  let t = 0, n = 0;
  for (const s of TRAILER.scenes) {
    if (s.act || s.enabled === false) continue;
    if (n++) t -= s.xfade || 0;
    seq.pictures.push({ id: s.id, uid: s.id });
    (s.text || []).forEach((item, i) => seq.titles.push({ key: s.id + "#" + i, t: round(t + (item.at || 0)) }));
    if (s.vo && s.vo.line) seq.voices.push({ key: s.id + "~vo", t: round(t + (s.vo.at || 0)) });
    t += s.dur;
  }
  return seq;
}
const round = x => Math.round(x * 10) / 10;

// The cut being edited, remembered in this browser (anything since gone from scenes.js is dropped)
let sequence = originalSequence();
try {
  const saved = JSON.parse(localStorage.getItem("xg-trailer-sequence") || "null");
  if (saved && saved.pictures) {
    sequence = {
      pictures: saved.pictures.filter(p => LIB.pictures[p.id]),
      titles: saved.titles.filter(p => LIB.titles[p.key]),
      voices: saved.voices.filter(p => LIB.voices[p.key]),
    };
  }
} catch (e) {}
function saveSequence() { try { localStorage.setItem("xg-trailer-sequence", JSON.stringify(sequence)); } catch (e) {} }
let uids = 0;
const newUid = id => id + "@" + Date.now().toString(36) + (uids++);

// Each picture on the track has a video of its own, even where two cut from the same clip:
// sharing one, a scene about to start would seek it away from under the scene still showing it
const videos = {}, missing = new Set();
const clipSrc = s => "clips/" + s.clip + ".mp4";
function videoFor(s) {
  const key = s.uid || s.id;
  if (!videos[key]) {
    const v = document.createElement("video");
    v.src = clipSrc(s); v.muted = true; v.preload = "auto"; v.playsInline = true;
    if (!LOCAL) v.crossOrigin = "anonymous";
    v.addEventListener("error", () => { missing.add(clipSrc(s)); renderTracks(); });
    videos[key] = v;
  }
  return videos[key];
}

const voiceLength = line => (voices[line] ? voices[line].duration : 3);

/** The cut laid out: the pictures with their starts (a crossfade overlaps the one before), and the titles and voice lines. */
function timeline() {
  let t = 0; const scenes = [];
  for (const p of sequence.pictures) {
    const s = LIB.pictures[p.id];
    const xf = scenes.length ? (s.xfade || 0) : 0;
    t -= xf;
    scenes.push({ ...s, uid: p.uid, start: t, xf });
    t += s.dur;
  }
  const titles = sequence.titles.map((p, i) => ({ ...LIB.titles[p.key], t: p.t, index: i }));
  const lines = sequence.voices.map((p, i) => ({ ...LIB.voices[p.key], t: p.t, index: i }));
  let length = t;
  for (const x of titles) length = Math.max(length, x.t + (x.dur ?? 3));
  for (const x of lines) length = Math.max(length, x.t + voiceLength(x.line));
  return { scenes, titles, voices: lines, length };
}

// ---------------------------------------------------------------- the transport
// Where the cut is (seconds), and the run while it's playing
let position = 0, playing = null;

/** Keeps each live picture's video playing from the right moment (seeking only when it has drifted). */
function steerVideos(t, tl) {
  for (const s of tl.scenes) {
    if (!s.clip) continue;
    const v = videoFor(s);
    const lead = 0.6, inScene = t >= s.start - lead && t < s.start + s.dur + nextXf(tl, s);
    if (!inScene) { if (!v.paused) v.pause(); continue; }
    const want = (s.from || 0) + Math.max(0, t - s.start) * (s.rate || 1);
    v.playbackRate = s.rate || 1;
    if (v.seeking) continue;
    if (t < s.start) { if (Math.abs(v.currentTime - (s.from || 0)) > 0.05) v.currentTime = s.from || 0; continue; }
    if (Math.abs(v.currentTime - want) > 0.4) v.currentTime = want;
    if (v.paused) v.play().catch(() => {});
  }
}

/** The videos of the pictures live at t, each at its frame for t, ready to be drawn. */
function seekVideos(t, tl) {
  return Promise.all(tl.scenes.filter(s => s.clip && t >= s.start - 0.6 && t < s.start + s.dur + nextXf(tl, s)).map(s => new Promise(res => {
    const v = videoFor(s);
    if (missing.has(clipSrc(s))) return res();
    v.pause();
    const want = (s.from || 0) + Math.max(0, t - s.start) * (s.rate || 1);
    if (Math.abs(v.currentTime - want) < 0.02 && v.readyState >= 2) return res();
    v.addEventListener("seeked", res, { once: true });
    v.addEventListener("error", res, { once: true });
    setTimeout(res, 3000);
    v.currentTime = want;
  })));
}

/** Plays the cut from a position (from the start for an export); recording is the export's recorder. */
async function play(recording, from = position) {
  halt();
  await audioReady();
  await ac.resume();
  await document.fonts.ready;
  const tl = timeline();
  if (from >= tl.length - 0.05) from = 0;
  statusEl.textContent = recording ? statusEl.textContent : "Loading the clips...";
  await seekVideos(from, tl);
  if (!recording) statusEl.textContent = LOCAL ? statusEl.dataset.local : "";
  const t0 = ac.currentTime + 0.3 - from;
  scheduleMusic(t0, tl); scheduleVoices(t0, tl);
  playing = { t0, tl, recording };
  playButton.innerHTML = "&#10074;&#10074;"; playButton.setAttribute("aria-label", "Pause");
  const step = () => {
    if (!playing) return;
    const t = Math.max(0, ac.currentTime - playing.t0);
    position = Math.min(t, tl.length);
    steerVideos(t, tl);
    frameAt(t, tl);
    showPosition(t, tl);
    if (t > tl.length + 0.5) { finishPlay(); return; }
    requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

function finishPlay() {
  const rec = playing && playing.recording;
  halt();
  position = 0;
  if (rec) rec.stop();
}

/** Stops the sound and the videos where they are (the position's kept, to carry on from). */
function halt() {
  playing = null;
  for (const n of scheduled.splice(0)) { try { n.stop(); } catch (e) {} }
  if (musicBus) { musicBus.gain.cancelScheduledValues(0); musicBus.gain.value = 0.55; }
  for (const v of Object.values(videos)) v.pause();
  for (const id of voiceTimers.splice(0)) clearTimeout(id);
  if (LOCAL) for (const el of Object.values(voices)) el.pause();
  playButton.innerHTML = "&#9654;"; playButton.setAttribute("aria-label", "Play");
}

// (only the latest asked-for still is drawn, however quickly the bar is dragged)
let stillWanted = null, stillBusy = false;
/** Shows the cut still at t (while paused, or dragging the bar). */
async function showStill(t) {
  stillWanted = t;
  if (stillBusy) return;
  stillBusy = true;
  while (stillWanted !== null) {
    const at = stillWanted; stillWanted = null;
    const tl = timeline();
    showPosition(at, tl);
    await seekVideos(at, tl);
    frameAt(at, tl);
  }
  stillBusy = false;
}

/** Moves to t: carries on playing from there if it was playing, else shows the still. */
function seek(t) {
  const tl = timeline();
  position = Math.max(0, Math.min(t, tl.length));
  if (playing && !playing.recording) play(null, position);
  else showStill(position);
}

const fmt = s => Math.floor(s / 60) + ":" + String(Math.floor(s % 60)).padStart(2, "0");
/** The bar, the time and the sequencer's playhead at t. */
function showPosition(t, tl) {
  const p = tl.length ? Math.max(0, Math.min(1, t / tl.length)) : 0;
  document.getElementById("fill").style.width = (p * 100) + "%";
  document.getElementById("knob").style.left = (p * 100) + "%";
  document.getElementById("time").textContent = fmt(Math.min(t, tl.length)) + " / " + fmt(tl.length);
  scrub.setAttribute("aria-valuenow", Math.round(t));
  scrub.setAttribute("aria-valuemax", Math.round(tl.length));
  const head = document.getElementById("playhead");
  if (head) head.style.left = (LABEL_W + t * PPS) + "px";
  const now = tl.scenes.find(s => t >= s.start && t < s.start + s.dur);
  document.querySelectorAll(".block.pic").forEach(b => b.classList.toggle("now", !!now && b.dataset.uid === now.uid));
}

const playButton = document.getElementById("play"), scrub = document.getElementById("scrub");
playButton.onclick = () => {
  if (playing) { halt(); showStill(position); } else play(null);
};
document.getElementById("rewind").onclick = () => seek(0);
document.getElementById("stage").onclick = () => playButton.onclick();
document.addEventListener("keydown", e => {
  if (e.target.closest("input, textarea")) return;
  if (e.code === "Space") { e.preventDefault(); playButton.onclick(); }
  if (e.target === scrub && (e.key === "ArrowLeft" || e.key === "ArrowRight")) { e.preventDefault(); seek(position + (e.key === "ArrowLeft" ? -2 : 2)); }
});

// Dragging the bar: the still follows the pointer; a playing cut carries on from where it's let go
let resumeAfterDrag = false;
function scrubTo(e) {
  const r = scrub.getBoundingClientRect(), tl = timeline();
  position = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * tl.length;
  showStill(position);
}
scrub.addEventListener("pointerdown", e => {
  scrub.setPointerCapture(e.pointerId);
  resumeAfterDrag = !!playing && !playing.recording;
  if (playing && !playing.recording) halt();
  scrubTo(e);
});
scrub.addEventListener("pointermove", e => { if (scrub.hasPointerCapture(e.pointerId)) scrubTo(e); });
scrub.addEventListener("pointerup", e => {
  scrub.releasePointerCapture(e.pointerId);
  if (resumeAfterDrag) play(null, position);
});

// ---------------------------------------------------------------- the sequencer
// Pixels per second along the tracks, and the width of the tracks' labels
const PPS = 26, LABEL_W = 0, TRACK_H = { pic: 50, title: 80, voice: 50 };
const tracksEl = document.getElementById("tracks"), libraryEl = document.getElementById("library");

/** The library: every picture, title and voice line, each to drag onto its track. */
function renderLibrary() {
  libraryEl.textContent = "";
  const used = kind => new Set(kind === "pic" ? sequence.pictures.map(p => p.id) : (kind === "title" ? sequence.titles : sequence.voices).map(p => p.key));
  const group = (title, kind, entries) => {
    const h = document.createElement("h3"); h.textContent = title; libraryEl.append(h);
    const inCut = used(kind);
    for (const [key, label] of entries) {
      const chip = document.createElement("div");
      chip.className = "chip " + kind; chip.draggable = true;
      chip.textContent = label;
      if (inCut.has(key)) { const u = document.createElement("span"); u.className = "used"; u.textContent = " (in the cut)"; chip.append(u); }
      chip.title = label;
      chip.addEventListener("dragstart", e => { e.dataTransfer.setData("text/plain", JSON.stringify({ from: "library", kind, key })); e.dataTransfer.effectAllowed = "copy"; });
      libraryEl.append(chip);
    }
  };
  group("Pictures", "pic", Object.values(LIB.pictures).map(p => [p.id, p.id + "  " + p.dur.toFixed(1) + " s"]));
  group("Titles", "title", Object.values(LIB.titles).map(t => [t.key, t.text]));
  group("Voice", "voice", Object.values(LIB.voices).map(v => [v.key, v.caption || v.line]));
}

/** The tracks: a ruler, the pictures one after another, the titles and voice lines at their times. */
function renderTracks() {
  const tl = timeline();
  const width = Math.max(tracksEl.parentElement.clientWidth, (tl.length + 12) * PPS);
  tracksEl.textContent = "";
  tracksEl.style.width = width + "px";
  const ruler = div("ruler");
  for (let s = 0; s <= tl.length + 10; s += 5) { const m = document.createElement("span"); m.style.left = (s * PPS) + "px"; m.textContent = fmt(s); ruler.append(m); }
  ruler.onclick = e => seek((e.clientX - ruler.getBoundingClientRect().left) / PPS);
  tracksEl.append(ruler);

  const pics = track("pic", "Pictures");
  tl.scenes.forEach((s, i) => {
    const b = block("pic", s.id, s.start, s.dur, TRACK_H.pic);
    b.dataset.uid = s.uid;
    if (s.clip && missing.has(clipSrc(s))) b.classList.add("missing");
    b.draggable = true;
    b.addEventListener("dragstart", e => { e.dataTransfer.setData("text/plain", JSON.stringify({ from: "track", kind: "pic", index: i })); e.dataTransfer.effectAllowed = "move"; });
    b.querySelector(".x").onclick = e => { e.stopPropagation(); sequence.pictures.splice(i, 1); changed(); };
    pics.append(b);
  });
  dropOnPictures(pics, tl);

  const titles = track("title", "Titles");
  laneOut(tl.titles.map(x => ({ x, dur: x.dur ?? 3 }))).forEach(({ x, lane, dur }) => {
    const b = block("title", x.text, x.t, dur, 26, lane);
    movable(b, sequence.titles[x.index]);
    b.querySelector(".x").onclick = e => { e.stopPropagation(); sequence.titles.splice(x.index, 1); changed(); };
    titles.append(b);
  });
  dropAtTime(titles, "title");

  const lines = track("voice", "Voice");
  laneOut(tl.voices.map(x => ({ x, dur: voiceLength(x.line) }))).forEach(({ x, lane, dur }) => {
    const b = block("voice", x.caption || x.line, x.t, dur, 26, lane);
    movable(b, sequence.voices[x.index]);
    b.querySelector(".x").onclick = e => { e.stopPropagation(); sequence.voices.splice(x.index, 1); changed(); };
    lines.append(b);
  });
  dropAtTime(lines, "voice");

  const head = div("playhead"); head.id = "playhead"; tracksEl.append(head);
  showPosition(position, tl);
}

function div(cls) { const d = document.createElement("div"); d.className = cls; return d; }

function track(kind, label) {
  const t = div("track " + kind); t.style.height = TRACK_H[kind] + "px";
  const l = div("label"); l.textContent = label; t.append(l);
  tracksEl.append(t);
  return t;
}

function block(kind, label, start, dur, height, lane = 0) {
  const b = div("block " + kind);
  b.style.left = (Math.max(0, start) * PPS) + "px"; b.style.width = Math.max(14, dur * PPS - 2) + "px";
  // (below the track's label)
  b.style.top = (18 + lane * 30) + "px";
  b.textContent = label; b.title = label + " (" + start.toFixed(1) + " s)";
  const x = document.createElement("span"); x.className = "x"; x.textContent = "×"; x.title = "Remove"; b.append(x);
  return b;
}

/** Overlapping blocks put on separate lanes (two at most, the rest on the second). */
function laneOut(items) {
  const ends = [-1, -1];
  return items.slice().sort((a, b) => a.x.t - b.x.t).map(it => {
    const lane = ends[0] <= it.x.t ? 0 : 1;
    ends[lane] = it.x.t + it.dur;
    return { ...it, lane };
  });
}

/** A title or voice line moved along its track by dragging it sideways. */
function movable(b, entry) {
  b.draggable = false;
  b.addEventListener("pointerdown", e => {
    if (e.target.classList.contains("x")) return;
    b.setPointerCapture(e.pointerId);
    const startX = e.clientX, startT = entry.t;
    const move = ev => {
      entry.t = Math.max(0, round(startT + (ev.clientX - startX) / PPS));
      b.style.left = (entry.t * PPS) + "px";
    };
    const up = () => { b.removeEventListener("pointermove", move); b.removeEventListener("pointerup", up); changed(); };
    b.addEventListener("pointermove", move);
    b.addEventListener("pointerup", up);
  });
}

/** The picture track takes pictures from the library (inserted where dropped) and its own (moved). */
function dropOnPictures(el, tl) {
  const marker = div("insert"); marker.style.top = "16px"; marker.style.height = "30px";
  const indexAt = x => {
    let i = 0;
    for (const s of tl.scenes) { if (x > (s.start + s.dur / 2) * PPS) i++; }
    return i;
  };
  const xOf = i => (i < tl.scenes.length ? tl.scenes[i].start : tl.scenes.length ? tl.scenes[tl.scenes.length - 1].start + tl.scenes[tl.scenes.length - 1].dur : 0) * PPS;
  el.addEventListener("dragover", e => {
    e.preventDefault();
    el.classList.add("over");
    marker.style.left = xOf(indexAt(e.clientX - el.getBoundingClientRect().left)) + "px";
    if (!marker.parentElement) el.append(marker);
  });
  el.addEventListener("dragleave", () => { el.classList.remove("over"); marker.remove(); });
  el.addEventListener("drop", e => {
    e.preventDefault();
    el.classList.remove("over"); marker.remove();
    let d; try { d = JSON.parse(e.dataTransfer.getData("text/plain")); } catch (err) { return; }
    if (d.kind !== "pic") return;
    let i = indexAt(e.clientX - el.getBoundingClientRect().left);
    if (d.from === "library") sequence.pictures.splice(i, 0, { id: d.key, uid: newUid(d.key) });
    else {
      const [moved] = sequence.pictures.splice(d.index, 1);
      if (d.index < i) i--;
      sequence.pictures.splice(i, 0, moved);
    }
    changed();
  });
}

/** A title or voice track takes lines from the library, each at the time it's dropped at. */
function dropAtTime(el, kind) {
  el.addEventListener("dragover", e => { e.preventDefault(); el.classList.add("over"); });
  el.addEventListener("dragleave", () => el.classList.remove("over"));
  el.addEventListener("drop", e => {
    e.preventDefault();
    el.classList.remove("over");
    let d; try { d = JSON.parse(e.dataTransfer.getData("text/plain")); } catch (err) { return; }
    if (d.kind !== kind || d.from !== "library") return;
    const t = Math.max(0, round((e.clientX - el.getBoundingClientRect().left) / PPS));
    (kind === "title" ? sequence.titles : sequence.voices).push({ key: d.key, t });
    changed();
  });
}

/** After every edit: saved, laid out again, and the still at the position redrawn. */
function changed() {
  saveSequence();
  renderLibrary();
  renderTracks();
  if (playing && !playing.recording) play(null, position);
  else showStill(position);
}

// ---------------------------------------------------------------- export and the rest
/** Records the cut as it plays from the start (picture and sound) and saves it as a video file. */
async function exportVideo() {
  await audioReady();
  let stream;
  if (LOCAL) { statusEl.textContent = "Export needs the page served: run tools/trailer/serve.bat and export from there."; return; }
  try { stream = canvas.captureStream(30); } catch (e) { statusEl.textContent = "Export needs the page served over http (see README)."; return; }
  stream.addTrack(recordDest.stream.getAudioTracks()[0]);
  const types = ["video/mp4;codecs=avc1.640028,mp4a.40.2", "video/mp4", "video/webm;codecs=vp9,opus", "video/webm"];
  const type = types.find(t => MediaRecorder.isTypeSupported(t));
  const rec = new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: 16e6 });
  const parts = [];
  rec.ondataavailable = e => e.data.size && parts.push(e.data);
  rec.onstop = () => {
    const blob = new Blob(parts, { type });
    const a = document.createElement("a"); a.href = URL.createObjectURL(blob);
    a.download = "xenoguesser-trailer." + (type.startsWith("video/mp4") ? "mp4" : "webm"); a.click();
    statusEl.textContent = "Exported " + a.download + " (" + (blob.size / 1e6).toFixed(1) + " MB).";
  };
  statusEl.textContent = "Recording: let it play to the end.";
  rec.start(1000);
  play(rec, 0);
}

document.getElementById("export").onclick = exportVideo;
document.getElementById("reset").onclick = () => {
  if (!resetArmed) {
    resetArmed = true;
    document.getElementById("reset").textContent = "Click again to replace your cut";
    setTimeout(() => { resetArmed = false; document.getElementById("reset").textContent = "Back to the original cut"; }, 3000);
    return;
  }
  resetArmed = false;
  document.getElementById("reset").textContent = "Back to the original cut";
  sequence = originalSequence();
  changed();
};
let resetArmed = false;
document.getElementById("full").onclick = () => { const st = document.getElementById("stage"); (st.requestFullscreen ? st.requestFullscreen() : Promise.reject()).catch(() => {}); };
const subtitlesBox = document.getElementById("subtitles");
subtitlesBox.checked = subtitles;
subtitlesBox.onchange = () => {
  subtitles = subtitlesBox.checked;
  try { localStorage.setItem("xg-trailer-subtitles", subtitles ? "1" : "0"); } catch (e) {}
  if (!playing) showStill(position);
};
window.addEventListener("resize", () => renderTracks());

renderLibrary();
renderTracks();
for (const p of sequence.pictures) if (LIB.pictures[p.id].clip) videoFor({ ...LIB.pictures[p.id], uid: p.uid });
document.fonts.ready.then(() => showStill(0));
// (the voice's lengths are known once it's loaded, for the voice track)
if (!LOCAL) audioReady().then(() => renderTracks()).catch(() => {});
statusEl.dataset.local = "Opened from disk: it plays, but Export needs it served (tools/trailer/serve.bat).";
if (LOCAL) statusEl.textContent = statusEl.dataset.local;
