const labels = ["happy", "surprised", "sad", "angry", "disgusted", "fearful", "neutral"];
const translations = {
  en: {
    pageTitle: "Mood Mirror — Live expression signals", privacy: "ON-DEVICE SESSION", eyebrow: "LIVE EXPRESSION LAB", heroLine1: "Your face.", heroLine2: "In full color.",
    heroCopy: "Turn on your camera and watch visible expression cues become a living signal map. Nothing is recorded.", realtime: "REAL-TIME",
    liveFeed: "01 / LIVE FEED", expressionCapture: "Expression capture", dogMode: "DOG MODE", dogModeOn: "DOG MODE ON",
    cameraWaiting: "Camera is waiting", cameraPermission: "Your browser will ask for permission", readyWhenYouAre: "READY WHEN YOU ARE",
    startCamera: "START CAMERA", stopCamera: "STOP CAMERA", cameraLive: "CAMERA IS LIVE", tryDemo: "TRY DEMO", stopDemo: "STOP DEMO",
    cameraNote: "Frames are analyzed during this session and are never saved.", signalReadout: "02 / SIGNAL READOUT", visibleExpression: "Visible expression",
    approximate: "APPROXIMATE", awaitingSignal: "AWAITING SIGNAL", awaitingCopy: "Let’s see what your expression is giving.", signal: "SIGNAL",
    signalScores: "Expression signal scores", recentPulse: "RECENT PULSE", disclaimerLead: "A face is not a mind reader.",
    disclaimerCopy: "This playful demo estimates visible expressions—not feelings, intent, personality, or mental health.", footerStudy: "MM—01 / EXPRESSION SIGNAL STUDY", footerModel: "PYTHON + HSEMOTION ONNX",
    yourFaceSource: "YOUR FACE / SOURCE", thumbnailAria: "Your face is the source for this dog expression", strongestCue: "STRONGEST VISIBLE CUE", samples: "SAMPLES", sample: "SAMPLE",
    statuses: { standby: "STANDBY", signalLocked: "SIGNAL LOCKED", findingFace: "FINDING FACE", engineError: "ENGINE ERROR", cameraBlocked: "CAMERA BLOCKED", warmingUp: "WARMING UP", demoSignal: "DEMO SIGNAL" },
    moods: {
      happy: ["Happy", "Bright, lifted expression cues."], surprised: ["Surprised", "Wide eyes and an open expression."], sad: ["Sad", "Lowered, softer expression cues."],
      angry: ["Angry", "Tense, focused expression cues."], disgusted: ["Disgusted", "A scrunched, compressed expression."], fearful: ["Fearful", "Alert, widened expression cues."], neutral: ["Neutral", "Calm, low-intensity expression cues."],
    },
  },
  it: {
    pageTitle: "Mood Mirror — Segnali di espressione dal vivo", privacy: "SESSIONE LOCALE", eyebrow: "LABORATORIO DI ESPRESSIONE", heroLine1: "Il tuo volto.", heroLine2: "A colori.",
    heroCopy: "Accendi la fotocamera e guarda i segnali visibili della tua espressione diventare una mappa viva. Nulla viene registrato.", realtime: "IN TEMPO REALE",
    liveFeed: "01 / FLUSSO LIVE", expressionCapture: "Cattura dell’espressione", dogMode: "MODALITÀ CANE", dogModeOn: "MODALITÀ CANE ATTIVA",
    cameraWaiting: "Fotocamera in attesa", cameraPermission: "Il browser chiederà il permesso", readyWhenYouAre: "PRONTO QUANDO VUOI",
    startCamera: "AVVIA FOTOCAMERA", stopCamera: "FERMA FOTOCAMERA", cameraLive: "FOTOCAMERA ATTIVA", tryDemo: "PROVA DEMO", stopDemo: "FERMA DEMO",
    cameraNote: "I fotogrammi vengono analizzati durante la sessione e non vengono mai salvati.", signalReadout: "02 / LETTURA DEL SEGNALE", visibleExpression: "Espressione visibile",
    approximate: "APPROSSIMATIVA", awaitingSignal: "IN ATTESA DEL SEGNALE", awaitingCopy: "Vediamo cosa comunica la tua espressione.", signal: "SEGNALE",
    signalScores: "Punteggi dei segnali espressivi", recentPulse: "IMPULSO RECENTE", disclaimerLead: "Un volto non legge la mente.",
    disclaimerCopy: "Questa demo giocosa stima espressioni visibili, non sentimenti, intenzioni, personalità o salute mentale.", footerStudy: "MM—01 / STUDIO DEI SEGNALI ESPRESSIVI", footerModel: "PYTHON + HSEMOTION ONNX",
    yourFaceSource: "IL TUO VOLTO / FONTE", thumbnailAria: "Il tuo volto è la fonte di questa espressione canina", strongestCue: "SEGNALE VISIBILE PIÙ FORTE", samples: "CAMPIONI", sample: "CAMPIONE",
    statuses: { standby: "IN ATTESA", signalLocked: "SEGNALE AGGANCIATO", findingFace: "CERCO IL VOLTO", engineError: "ERRORE DEL MOTORE", cameraBlocked: "FOTOCAMERA BLOCCATA", warmingUp: "AVVIO IN CORSO", demoSignal: "SEGNALE DEMO" },
    moods: {
      happy: ["Felice", "Segnali di un’espressione luminosa e aperta."], surprised: ["Sorpreso", "Occhi spalancati e un’espressione aperta."], sad: ["Triste", "Segnali di un’espressione più morbida e abbassata."],
      angry: ["Arrabbiato", "Segnali di tensione e concentrazione."], disgusted: ["Disgustato", "Un’espressione contratta e arricciata."], fearful: ["Impaurito", "Segnali di allerta e occhi più aperti."], neutral: ["Neutro", "Segnali di un’espressione calma e poco intensa."],
    },
  },
};

const moodMeta = { happy: "◡", surprised: "○", sad: "⌒", angry: "⌁", disgusted: "≈", fearful: "◇", neutral: "—" };
let language = localStorage.getItem("mood-mirror-language") || "en";
let statusKey = "standby";
let statusActive = false;

function tr(key) { return translations[language][key] ?? translations.en[key] ?? key; }
function moodLabel(label) { return translations[language].moods[label][0]; }
function moodPhrase(label) { return translations[language].moods[label][1]; }

const video = document.querySelector("#video");
const capture = document.querySelector("#capture");
const overlay = document.querySelector("#overlay");
const cameraButton = document.querySelector("#cameraButton");
const demoButton = document.querySelector("#demoButton");
const dogModeButton = document.querySelector("#dogModeButton");
const dogImage = document.querySelector("#dogImage");
const thumbnailCanvas = document.querySelector("#thumbnailCanvas");
const thumbnailContext = thumbnailCanvas.getContext("2d");
const placeholder = document.querySelector("#placeholder");
const stage = document.querySelector("#stage");
const livePill = document.querySelector("#livePill");
const readout = document.querySelector("#readout");
const historyCanvas = document.querySelector("#history");
const languageButton = document.querySelector("#languageButton");

let stream = null;
let timer = null;
let busy = false;
let demoTimer = null;
let sampleCount = 0;
let history = [];
let smoothed = Object.fromEntries(labels.map(label => [label, label === "neutral" ? 1 : 0]));
let lastLabel = "neutral";
let currentMood = "neutral";
let dogMode = false;
let dogImageLabel = "neutral";
let pendingDogLabel = "neutral";
let pendingDogFrames = 0;
const dogPreloads = {};
let thumbnailFrame = null;
let audioContext = null;

function soundEnabled() {
  return document.querySelector("#soundButton").getAttribute("aria-pressed") === "true";
}

function playSignalTone(label) {
  if (!soundEnabled() || label === lastLabel) return;
  audioContext ||= new AudioContext();
  const frequencies = { happy: 659, surprised: 784, sad: 294, angry: 196, disgusted: 247, fearful: 370, neutral: 440 };
  const oscillator = audioContext.createOscillator();
  const gain = audioContext.createGain();
  oscillator.type = "sine";
  oscillator.frequency.value = frequencies[label];
  gain.gain.setValueAtTime(.0001, audioContext.currentTime);
  gain.gain.exponentialRampToValueAtTime(.035, audioContext.currentTime + .02);
  gain.gain.exponentialRampToValueAtTime(.0001, audioContext.currentTime + .16);
  oscillator.connect(gain).connect(audioContext.destination);
  oscillator.start();
  oscillator.stop(audioContext.currentTime + .17);
}

function buildSignalGrid() {
  const grid = document.querySelector("#signalGrid");
  labels.forEach(label => {
    const row = document.createElement("div");
    row.className = "signal-row";
    row.dataset.label = label;
    row.innerHTML = `<label>${moodLabel(label)}</label><div class="meter"><i></i></div><output>0</output>`;
    grid.append(row);
  });
}

function setStatus(active, key) {
  statusActive = active;
  statusKey = key;
  livePill.classList.toggle("active", active);
  livePill.querySelector("span").textContent = translations[language].statuses[key] || key;
}

function updateSampleCount() {
  document.querySelector("#sampleCount").textContent = `${sampleCount} ${sampleCount === 1 ? tr("sample") : tr("samples")}`;
}

function refreshControls() {
  const running = Boolean(stream);
  cameraButton.querySelector("small").textContent = running ? tr("cameraLive") : tr("readyWhenYouAre");
  cameraButton.querySelector("strong").textContent = running ? tr("stopCamera") : tr("startCamera");
  cameraButton.querySelector(".button-arrow").textContent = running ? "×" : "↗";
  demoButton.textContent = demoTimer ? tr("stopDemo") : tr("tryDemo");
  dogModeButton.lastElementChild.textContent = dogMode ? tr("dogModeOn") : tr("dogMode");
  livePill.querySelector("span").textContent = translations[language].statuses[statusKey] || statusKey;
}

function renderMoodText(label) {
  const meta = translations[language].moods[label];
  document.querySelector("#moodKicker").textContent = tr("strongestCue");
  document.querySelector("#moodLabel").textContent = meta[0];
  document.querySelector("#moodPhrase").textContent = meta[1];
  document.querySelector("#moodEmoji").textContent = moodMeta[label];
}

function applyLanguage() {
  document.documentElement.lang = language;
  document.title = tr("pageTitle");
  document.querySelectorAll("[data-i18n]").forEach(element => {
    element.textContent = tr(element.dataset.i18n);
  });
  document.querySelectorAll("[data-i18n-aria]").forEach(element => {
    element.setAttribute("aria-label", tr(element.dataset.i18nAria));
  });
  document.querySelectorAll(".signal-row").forEach(row => {
    row.querySelector("label").textContent = moodLabel(row.dataset.label);
  });
  languageButton.dataset.language = language;
  languageButton.setAttribute("aria-label", language === "en" ? "Passa all'italiano" : "Switch to English");
  document.querySelector("#soundButton").setAttribute("aria-label", language === "en" ? "Toggle sounds" : "Attiva/disattiva suoni");
  if (sampleCount) renderMoodText(currentMood);
  else {
    document.querySelector("#moodKicker").textContent = tr("awaitingSignal");
    document.querySelector("#moodLabel").textContent = moodLabel(currentMood);
    document.querySelector("#moodPhrase").textContent = tr("awaitingCopy");
    document.querySelector("#moodEmoji").textContent = moodMeta[currentMood];
  }
  document.querySelector("#cameraThumbnail").setAttribute("aria-label", tr("thumbnailAria"));
  dogImage.alt = language === "it" ? `Espressione canina: ${moodLabel(dogImageLabel)}` : `Dog expression: ${moodLabel(dogImageLabel)}`;
  updateSampleCount();
  refreshControls();
}

function updateMood(scores, forcedLabel = null) {
  labels.forEach(label => {
    const next = Number(scores[label] || 0);
    smoothed[label] = smoothed[label] * .52 + next * .48;
  });
  const label = forcedLabel || labels.reduce((best, current) => smoothed[current] > smoothed[best] ? current : best);
  const value = Math.round(smoothed[label] * 100);
  playSignalTone(label);
  lastLabel = label;
  currentMood = label;
  if (dogMode) queueDogImage(label);
  readout.dataset.mood = label;
  renderMoodText(label);
  document.querySelector("#confidence").textContent = `${value}%`;
  document.querySelector("#confidenceDial").style.setProperty("--value", value);

  document.querySelectorAll(".signal-row").forEach(row => {
    const score = Math.round(smoothed[row.dataset.label] * 100);
    row.classList.toggle("active", row.dataset.label === label);
    row.querySelector("i").style.width = `${score}%`;
    row.querySelector("output").textContent = score;
  });

  history.push({ label, value });
  if (history.length > 42) history.shift();
  sampleCount += 1;
  updateSampleCount();
  drawHistory();
}

function drawHistory() {
  const ratio = window.devicePixelRatio || 1;
  const rect = historyCanvas.getBoundingClientRect();
  historyCanvas.width = rect.width * ratio;
  historyCanvas.height = rect.height * ratio;
  const ctx = historyCanvas.getContext("2d");
  ctx.scale(ratio, ratio);
  ctx.clearRect(0, 0, rect.width, rect.height);
  ctx.strokeStyle = "rgba(255,255,255,.07)";
  ctx.setLineDash([2, 4]);
  [16, 33, 50].forEach(y => { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(rect.width, y); ctx.stroke(); });
  ctx.setLineDash([]);
  if (history.length < 2) return;
  const color = getComputedStyle(readout).getPropertyValue("--mood").trim();
  ctx.strokeStyle = color;
  ctx.shadowBlur = 10;
  ctx.shadowColor = color;
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  history.forEach((point, index) => {
    const x = index * (rect.width / Math.max(1, history.length - 1));
    const y = rect.height - 5 - (point.value / 100) * (rect.height - 12);
    index ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
  });
  ctx.stroke();
}

function drawFaceBox(bounds) {
  const rect = overlay.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  overlay.width = rect.width * ratio;
  overlay.height = rect.height * ratio;
  const ctx = overlay.getContext("2d");
  ctx.scale(ratio, ratio);
  ctx.clearRect(0, 0, rect.width, rect.height);
  if (!bounds || dogMode) return;

  const x = (1 - bounds.x - bounds.width) * rect.width;
  const y = bounds.y * rect.height;
  const w = bounds.width * rect.width;
  const h = bounds.height * rect.height;
  const size = Math.min(25, w * .16);
  ctx.strokeStyle = getComputedStyle(readout).getPropertyValue("--mood").trim();
  ctx.lineWidth = 1.5;
  ctx.shadowBlur = 8;
  ctx.shadowColor = ctx.strokeStyle;
  [[x,y,1,1], [x+w,y,-1,1], [x,y+h,1,-1], [x+w,y+h,-1,-1]].forEach(([cx,cy,dx,dy]) => {
    ctx.beginPath(); ctx.moveTo(cx + dx * size, cy); ctx.lineTo(cx, cy); ctx.lineTo(cx, cy + dy * size); ctx.stroke();
  });
  ctx.font = "8px ui-monospace, monospace";
  ctx.fillStyle = ctx.strokeStyle;
  ctx.shadowBlur = 0;
  ctx.fillText("FACE SIGNAL LOCK", x, Math.max(10, y - 7));
}

function renderThumbnail() {
  if (stream && video.readyState >= 2) {
    const width = thumbnailCanvas.width;
    const height = thumbnailCanvas.height;
    thumbnailContext.save();
    thumbnailContext.clearRect(0, 0, width, height);
    thumbnailContext.translate(width, 0);
    thumbnailContext.scale(-1, 1);
    thumbnailContext.drawImage(video, 0, 0, width, height);
    thumbnailContext.restore();
  }
  thumbnailFrame = requestAnimationFrame(renderThumbnail);
}

function queueDogImage(label) {
  if (label === dogImageLabel) {
    pendingDogLabel = label;
    pendingDogFrames = 0;
    return;
  }
  if (label === pendingDogLabel) pendingDogFrames += 1;
  else {
    pendingDogLabel = label;
    pendingDogFrames = 1;
  }
  // Hold a label for three samples so small classifier wobble does not cause
  // a visible dog swap every half second.
  if (pendingDogFrames >= 3) setDogImage(label);
}

function setDogImage(label) {
  if (label === dogImageLabel) return;
  dogImageLabel = label;
  pendingDogLabel = label;
  pendingDogFrames = 0;
  const preloaded = dogPreloads[label];
  if (preloaded?.complete && preloaded.naturalWidth > 0) {
    dogImage.src = preloaded.src;
    dogImage.alt = language === "it" ? `Espressione canina: ${moodLabel(label)}` : `Dog expression: ${moodLabel(label)}`;
  }
}

function setDogMode(enabled) {
  dogMode = enabled;
  dogModeButton.setAttribute("aria-pressed", String(enabled));
  dogModeButton.querySelector(".toggle-track i").textContent = enabled ? "🐕" : "";
  refreshControls();
  stage.classList.toggle("dog-mode", enabled);
  if (enabled) setDogImage(currentMood);
  drawFaceBox(null);
}

async function analyzeFrame() {
  if (busy || !stream || video.readyState < 2) return;
  busy = true;
  const ctx = capture.getContext("2d", { alpha: false });
  ctx.drawImage(video, 0, 0, capture.width, capture.height);
  const blob = await new Promise(resolve => capture.toBlob(resolve, "image/jpeg", .72));
  const body = new FormData();
  body.append("frame", blob, "frame.jpg");
  try {
    const response = await fetch("/api/analyze", { method: "POST", body });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "Analysis failed");
    if (data.detected) {
      setStatus(true, "signalLocked");
      updateMood(data.scores);
      drawFaceBox(data.bounds);
    } else {
      setStatus(true, "findingFace");
      drawFaceBox(null);
    }
  } catch (error) {
    setStatus(false, "engineError");
    document.querySelector("#moodPhrase").textContent = error.message;
  } finally {
    busy = false;
  }
}

async function startCamera() {
  stopDemo();
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: "user", width: { ideal: 960 }, height: { ideal: 720 } },
      audio: false,
    });
    video.srcObject = stream;
    placeholder.classList.add("hidden");
    stage.classList.add("active");
    cameraButton.classList.add("running");
    refreshControls();
    setStatus(true, "warmingUp");
    thumbnailFrame = requestAnimationFrame(renderThumbnail);
    timer = window.setInterval(analyzeFrame, 520);
  } catch (error) {
    setStatus(false, "cameraBlocked");
    document.querySelector("#moodPhrase").textContent = language === "it"
      ? "Accesso alla fotocamera bloccato. Prova la demo."
      : "Camera access was blocked. Try the demo instead.";
  }
}

function stopCamera() {
  window.clearInterval(timer);
  timer = null;
  if (thumbnailFrame) cancelAnimationFrame(thumbnailFrame);
  thumbnailFrame = null;
  stream?.getTracks().forEach(track => track.stop());
  stream = null;
  video.srcObject = null;
  placeholder.classList.remove("hidden");
  stage.classList.remove("active");
  cameraButton.classList.remove("running");
  refreshControls();
  setStatus(false, "standby");
  drawFaceBox(null);
}

function startDemo() {
  stopCamera();
  let index = 0;
  setStatus(true, "demoSignal");
  const pulse = () => {
    const label = labels[index++ % labels.length];
    const scores = Object.fromEntries(labels.map(item => [item, item === label ? .72 : .28 / (labels.length - 1)]));
    updateMood(scores, label);
  };
  pulse();
  demoTimer = window.setInterval(pulse, 1450);
  refreshControls();
}

function stopDemo() {
  window.clearInterval(demoTimer);
  demoTimer = null;
  refreshControls();
}

cameraButton.addEventListener("click", () => stream ? stopCamera() : startCamera());
demoButton.addEventListener("click", () => demoTimer ? (stopDemo(), setStatus(false, "standby")) : startDemo());
dogModeButton.addEventListener("click", () => setDogMode(!dogMode));
languageButton.addEventListener("click", () => {
  language = language === "en" ? "it" : "en";
  localStorage.setItem("mood-mirror-language", language);
  applyLanguage();
});
window.addEventListener("resize", () => { drawHistory(); drawFaceBox(null); });
window.addEventListener("beforeunload", stopCamera);
document.querySelector("#soundButton").addEventListener("click", event => {
  const button = event.currentTarget;
  button.setAttribute("aria-pressed", button.getAttribute("aria-pressed") === "false" ? "true" : "false");
});

buildSignalGrid();
applyLanguage();
labels.forEach(label => {
  const image = new Image();
  image.onload = () => {
    if (dogMode && dogImageLabel === label) {
      dogImage.src = image.src;
      dogImage.alt = language === "it" ? `Espressione canina: ${moodLabel(label)}` : `Dog expression: ${moodLabel(label)}`;
    }
  };
  image.src = `/static/images/dog/${label}.png`;
  dogPreloads[label] = image;
});
drawHistory();
