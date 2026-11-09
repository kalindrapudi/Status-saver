'use strict';
/* Muse Studio — personal image-styling app.
   Runs as a PWA in any browser, or inside the Android WebView shell, which
   exposes window.MuseAndroid (encrypted key storage, gallery saving, share-in). */
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const Bridge = window.MuseAndroid || null;
const P = window.MuseProviders;
const VERSION = '2.1.0';

const state = {
  source: null, extras: new Set(), reference: null, category: 'All',
  results: [], handoff: null, busy: false, photos: [], saved: [], personFilter: null,
  settings: {}
};

/* ---------------- settings & keys ---------------- */
const DEFAULTS = { mOpenai: 'gpt-image-1', mOpenaiText: 'gpt-4.1-mini', mGemini: 'gemini-2.5-flash-image', mGeminiText: 'gemini-2.5-flash', assistant: 'auto', fidelity: true, useApps: true, useOpenai: false, useGemini: false, useDemo: false };
function loadSettings() {
  try { state.settings = Object.assign({}, DEFAULTS, JSON.parse(localStorage.getItem('muse.settings') || '{}')); }
  catch (_) { state.settings = Object.assign({}, DEFAULTS); }
}
function saveSettingsObj() { try { localStorage.setItem('muse.settings', JSON.stringify(state.settings)); } catch (_) { /* ignore */ } }
function getKey(name) {
  try { return Bridge ? (Bridge.getSecret(name) || '') : (localStorage.getItem('muse.key.' + name) || ''); } catch (_) { return ''; }
}
function setKey(name, value) {
  try { if (Bridge) Bridge.setSecret(name, value || ''); else if (value) localStorage.setItem('muse.key.' + name, value); else localStorage.removeItem('muse.key.' + name); } catch (_) { toast('Could not store the key on this device.'); }
}
function assistantProvider() {
  const a = state.settings.assistant;
  if (a !== 'auto') return a;
  if (getKey('openai')) return 'openai';
  if (getKey('gemini')) return 'gemini';
  return 'demo';
}
function assistantArgs() {
  const p = assistantProvider();
  return { provider: p, key: p === 'demo' ? '' : getKey(p), model: p === 'openai' ? state.settings.mOpenaiText : state.settings.mGeminiText };
}

/* ---------------- storage (IndexedDB) ---------------- */
let db;
function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('muse-studio', 1);
    req.onupgradeneeded = () => { for (const n of ['photos', 'saved']) if (!req.result.objectStoreNames.contains(n)) req.result.createObjectStore(n, { keyPath: 'id' }); };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
function store(name, mode, fn) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(name, mode); const req = fn(tx.objectStore(name)); let out;
    req.onsuccess = () => { out = req.result; };
    tx.oncomplete = () => resolve(out); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
  });
}
async function refresh() {
  if (!db) return;
  state.photos = (await store('photos', 'readonly', s => s.getAll())).sort((a, b) => b.created - a.created);
  state.saved = (await store('saved', 'readonly', s => s.getAll())).sort((a, b) => b.created - a.created);
  $('#count-photos').textContent = state.photos.length;
  $('#count-saved').textContent = state.saved.length;
  renderPhotos(); renderSaved(); renderExtras();
  $('#people-list').innerHTML = people().map(p => `<option value="${esc(p)}">`).join('');
}
const people = () => [...new Set(state.photos.map(p => (p.person || '').trim()).filter(Boolean))].sort();

/* ---------------- helpers ---------------- */
function esc(v) { return String(v == null ? '' : v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
const uid = () => (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36) + Math.random().toString(36).slice(2));
function toast(t) { const el = $('#toast'); el.textContent = t; el.classList.remove('hidden'); clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.add('hidden'), 4200); }
function status(msg, busy) { const el = $('#status'); el.textContent = msg; el.classList.toggle('hidden', !msg); el.classList.toggle('busy', !!busy); }
function guard() { if (state.busy) { toast('Wait for the current job to finish.'); return true; } return false; }
const loadImg = src => new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = () => rej(new Error('This file could not be read as an image.')); i.src = src; });
async function resize(dataUrl, max, quality = 0.9) {
  const img = await loadImg(dataUrl);
  const scale = Math.min(1, max / Math.max(img.width, img.height));
  const c = document.createElement('canvas'); c.width = Math.round(img.width * scale); c.height = Math.round(img.height * scale);
  c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
  return c.toDataURL('image/jpeg', quality);
}
function fileToDataUrl(file) {
  return new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(r.result); r.onerror = () => rej(new Error('Could not read this file.')); r.readAsDataURL(file); });
}
async function makePhoto(dataUrl, name) {
  const data = await resize(dataUrl, 2048, 0.92);           // keep a sharp local copy
  const thumb = await resize(dataUrl, 360, 0.8);
  return { id: uid(), name: name || 'Photo', person: '', tags: '', data, thumb, created: Date.now() };
}

/* ---------------- navigation ---------------- */
function show(view) {
  if (!['create', 'photos', 'saved', 'settings'].includes(view)) view = 'create';
  $$('.view').forEach(v => v.classList.toggle('hidden', v.id !== 'view-' + view));
  $$('.tabbar button').forEach(b => b.classList.toggle('on', b.dataset.view === view));
  history.replaceState(null, '', '#' + view);
  window.scrollTo(0, 0);
}
$$('.tabbar button').forEach(b => b.addEventListener('click', () => show(b.dataset.view)));

/* ---------------- file picking ---------------- */
let pickMode = 'source';
function pick(mode) { if (guard()) return; pickMode = mode; const f = $('#file'); f.multiple = mode === 'library'; f.value = ''; f.click(); }
$('#file').addEventListener('change', async e => {
  const files = [...e.target.files].slice(0, pickMode === 'library' ? 30 : 1);
  let n = 0;
  for (const file of files) {
    try {
      if (!/^image\/(png|jpeg|webp)$/.test(file.type)) throw new Error('Choose a JPG, PNG or WebP image.');
      if (file.size > 25 * 1024 * 1024) throw new Error('That image is over 25 MB.');
      const photo = await makePhoto(await fileToDataUrl(file), file.name);
      if (pickMode === 'reference') { state.reference = photo; renderRef(); }
      else { await store('photos', 'readwrite', s => s.put(photo)); if (pickMode === 'source') selectSource(photo); }
      n++;
    } catch (err) { toast(err.message); }
  }
  await refresh();
  if (n && pickMode === 'library') toast(`${n} photo${n > 1 ? 's' : ''} added. Tap Label to name the person.`);
});
$('#source-pick').onclick = () => pick('source');
$('#ref-pick').onclick = () => pick('reference');
$('#photos-add').onclick = () => pick('library');
$('#source-from-library').onclick = () => openPicker();

function openPicker() {
  if (!state.photos.length) { toast('No photos yet — add some first.'); return pick('source'); }
  $('#picker-grid').innerHTML = state.photos.map(p => `<button type="button" class="tile" data-id="${p.id}"><img src="${p.thumb || p.data}" alt=""><span>${esc(p.person || p.name)}</span></button>`).join('');
  $$('#picker-grid .tile').forEach(t => t.onclick = () => { selectSource(state.photos.find(p => p.id === t.dataset.id)); $('#picker-dialog').close(); });
  $('#picker-dialog').showModal();
}

function selectSource(photo) {
  if (guard() || !photo) return;
  state.source = photo; state.extras.clear();
  const b = $('#source-pick'); b.innerHTML = `<img src="${photo.data}" alt="${esc(photo.name)}">`; b.classList.add('has');
  renderExtras(); updateCost();
}
function renderExtras() {
  const wrap = $('#identity-extras');
  const person = state.source && (state.source.person || '').trim();
  const others = person ? state.photos.filter(p => p.id !== state.source.id && (p.person || '').trim() === person) : [];
  wrap.classList.toggle('hidden', !others.length);
  if (!others.length) return;
  $('#person-name').textContent = person;
  $('#extras-strip').innerHTML = others.map(p => `<button class="tile ${state.extras.has(p.id) ? 'on' : ''}" data-id="${p.id}"><img src="${p.thumb || p.data}" alt=""></button>`).join('');
  $$('#extras-strip .tile').forEach(t => t.onclick = () => {
    if (state.extras.has(t.dataset.id)) state.extras.delete(t.dataset.id);
    else if (state.extras.size < 3) state.extras.add(t.dataset.id);
    else toast('Up to 3 extra identity photos.');
    renderExtras();
  });
}
function renderRef() {
  const el = $('#ref-selected');
  el.classList.toggle('hidden', !state.reference);
  if (!state.reference) { el.innerHTML = ''; return; }
  el.innerHTML = `<img src="${state.reference.thumb || state.reference.data}" alt="Style reference"><span>Style reference<small>${esc(state.reference.name)}</small></span><button aria-label="Remove reference">×</button>`;
  el.querySelector('button').onclick = () => { if (guard()) return; state.reference = null; renderRef(); };
}

/* ---------------- inspiration ---------------- */
const PRESETS = [
  { t: 'Golden hour, on film', c: 'Portraits', p: 'A natural portrait in warm golden-hour light, subtle 35mm film grain, muted olive and cream tones, relaxed expression.' },
  { t: 'Editorial studio', c: 'Fashion', p: 'A refined fashion editorial: elegant tailoring, confident standing pose, warm neutral seamless backdrop, soft directional key light.' },
  { t: 'Classic saree portrait', c: 'Fashion', p: 'An elegant traditional saree portrait with soft temple-gold jewellery, warm diffused light, graceful three-quarter pose, heritage courtyard background.' },
  { t: 'Night neon', c: 'Portraits', p: 'A cinematic night portrait with soft pink and violet neon reflections, defocused city lights, natural skin texture.' },
  { t: 'Japandi calm', c: 'Spaces', p: 'Restyle this space in a calm Japandi aesthetic: organic materials, warm neutrals, low furniture, uncluttered surfaces, soft daylight. Preserve the room layout.' },
  { t: 'Watercolor dream', c: 'Art', p: 'Transform the image into a delicate watercolor illustration with translucent washes, textured paper and soft sage and blush tones.' },
  { t: 'Monochrome classic', c: 'Portraits', p: 'A timeless black-and-white portrait, high contrast, Rembrandt lighting, three-quarter angle.' },
  { t: 'Street style', c: 'Fashion', p: 'Urban street-style photo, mid-stride pose, layered outfit, overcast soft light, shallow depth of field.' }
];
function renderSuggestions(list) {
  const kw = $('#keyword').value.trim().toLowerCase();
  const words = kw.split(/\s+/).filter(w => w.length > 2);
  const items = list || PRESETS.filter(s => (state.category === 'All' || s.c === state.category) && (!words.length || words.some(w => (s.t + ' ' + s.p).toLowerCase().includes(w))));
  $('#suggestions').innerHTML = items.map((s, i) => `<button class="idea" data-i="${i}"><strong>${esc(s.t || s.title)}</strong><small>${esc((s.p || s.prompt).slice(0, 90))}…</small></button>`).join('') || '<p class="hint">No preset matches — tap ✧ Suggest looks for AI ideas.</p>';
  $$('#suggestions .idea').forEach(b => b.onclick = () => {
    const s = items[+b.dataset.i];
    $('#prompt').value = s.p || s.prompt;
    $$('#suggestions .idea').forEach(x => x.classList.toggle('on', x === b));
    toast('Look added to your vision — edit it freely.');
  });
}
function renderPinQueries(queries) {
  const kw = $('#keyword').value.trim();
  const base = kw || (state.category === 'All' ? 'portrait photography' : state.category.toLowerCase() + ' inspiration');
  const qs = (queries && queries.length ? queries : [base, `${base} aesthetic`, `${base} pose ideas`, `${base} lighting`]).slice(0, 6);
  $('#pin-queries').innerHTML = qs.map(q => `<button class="chip pin" data-q="${esc(q)}">P · ${esc(q)}</button>`).join('');
  $$('#pin-queries .pin').forEach(b => b.onclick = () => openExternal('https://www.pinterest.com/search/pins/?q=' + encodeURIComponent(b.dataset.q)));
}
function openExternal(url) {
  if (Bridge && Bridge.openExternal) Bridge.openExternal(url); else window.open(url, '_blank', 'noopener,noreferrer');
}
$('#categories').addEventListener('click', e => {
  const b = e.target.closest('[data-cat]'); if (!b) return;
  state.category = b.dataset.cat; $$('#categories .chip').forEach(x => x.classList.toggle('on', x === b));
  renderSuggestions(); renderPinQueries();
});
$('#keyword').addEventListener('input', () => { renderSuggestions(); renderPinQueries(); });
$('#suggest').onclick = async () => {
  if (guard()) return;
  const a = assistantArgs();
  if (a.provider !== 'demo' && !a.key) return toast('Add an OpenAI or Gemini key in Settings for AI suggestions.');
  state.busy = true; $('#suggest').disabled = true; $('#suggest').textContent = 'Thinking…';
  try {
    const images = state.source ? [await resize(state.source.data, 768, 0.85)] : [];
    const out = await P.suggest({ ...a, images, keyword: $('#keyword').value.trim(), category: state.category, hasPhoto: !!state.source });
    renderSuggestions((out.suggestions || []).filter(s => s && s.prompt));
    renderPinQueries(out.pinterestQueries);
    toast(a.provider === 'demo' ? 'Demo ideas shown (add a key for real AI suggestions).' : 'Fresh ideas ready — tap one to use it.');
  } catch (err) { toast(err.message); }
  finally { state.busy = false; $('#suggest').disabled = false; $('#suggest').textContent = '✧ Suggest looks'; }
};

/* ---------------- prompt ---------------- */
$('#draft').onclick = () => {
  const kw = $('#keyword').value.trim();
  let p = $('#prompt').value.trim() || (kw ? `Restyle the photo with this creative direction: ${kw}. Coherent composition, intentional lighting, realistic detail.` : 'A natural, thoughtfully styled photo with soft window light, a calm background and realistic detail.');
  if (state.reference && !/reference/i.test(p)) p += ' Borrow the lighting, palette, outfit styling and mood from the style reference.';
  $('#prompt').value = p; toast('Prompt drafted — make it yours.');
};
$$('#adders [data-add]').forEach(b => b.onclick = () => { const t = $('#prompt'); if (!t.value.includes(b.dataset.add)) t.value = (t.value.trim() + ' ' + b.dataset.add).trim(); });

function finalPrompt(nIdentity) {
  const id = {
    strong: 'Preserve the person\'s identity exactly: same face shape, eyes, nose, lips, skin tone, hairline, facial proportions, age and natural skin texture. Do not beautify or replace the face.',
    balanced: 'Keep the person clearly recognisable (face, skin tone, proportions) while applying the requested style.',
    creative: 'Allow creative stylistic interpretation while keeping the subject recognisable.'
  }[$('#identity').value];
  const lines = [
    nIdentity > 1 ? `Images 1 to ${nIdentity} show the same person from different angles — use them together as the identity reference. Image 1 is the main photo to transform.` : 'Image 1 is the original photo to transform.',
    state.reference ? `The last image is a STYLE REFERENCE only: borrow its pose, outfit styling, lighting, palette and mood as requested — never its person's face or identity.` : '',
    $('#prompt').value.trim(),
    id,
    $('#negative').value.trim() ? 'Avoid: ' + $('#negative').value.trim() + '.' : '',
    'Photorealistic unless another art style is requested. Natural hands and anatomy.'
  ];
  return lines.filter(Boolean).join('\n\n');
}

/* ---------------- tools & create ---------------- */
function selectedTools() {
  return [['apps', '#use-apps'], ['openai', '#use-openai'], ['gemini', '#use-gemini'], ['demo', '#use-demo']].filter(([, s]) => $(s).checked).map(([n]) => n);
}
function updateCost() {
  const n = selectedTools().filter(t => t === 'openai' || t === 'gemini').length * Number($('#variations').value);
  $('#cost-hint').textContent = n ? `${n} paid API request${n > 1 ? 's' : ''}` : 'no API cost';
}
['#use-apps', '#use-openai', '#use-gemini', '#use-demo', '#variations'].forEach(s => $(s).addEventListener('change', () => {
  state.settings.useApps = $('#use-apps').checked; state.settings.useOpenai = $('#use-openai').checked; state.settings.useGemini = $('#use-gemini').checked; state.settings.useDemo = $('#use-demo').checked;
  saveSettingsObj(); updateCost();
}));

$('#create').onclick = async () => {
  if (guard()) return;
  if (!state.source) return toast('Choose your photo first.');
  if ($('#prompt').value.trim().length < 3) return toast('Describe your vision, or pick a look.');
  const tools = selectedTools();
  if (!tools.length) return toast('Tick at least one tool.');
  const apiTools = tools.filter(t => t !== 'apps');
  for (const t of apiTools) if (t !== 'demo' && !getKey(t)) { show('settings'); return toast(`Add your ${t === 'openai' ? 'OpenAI' : 'Gemini'} key, or untick it. The AI apps tool needs no key.`); }
  if (apiTools.some(t => t !== 'demo') && !$('#consent').checked) return toast('Tick the consent box to send your photos to the API provider.');

  state.busy = true; $('#create').disabled = true;
  const variations = Number($('#variations').value);
  try {
    status('Preparing photos…', true);
    const extras = [...state.extras].map(id => state.photos.find(p => p.id === id)).filter(Boolean);
    const identity = [state.source, ...extras];
    const images = [];
    for (const p of identity) images.push(await resize(p.data, 1536, 0.92));
    if (state.reference) images.push(await resize(state.reference.data, 1536, 0.9));
    const prompt = finalPrompt(identity.length);
    if (tools.includes('apps')) {
      state.handoff = { prompt: appPrompt(prompt, images.length), images, original: state.source.data, userPrompt: $('#prompt').value.trim() };
      renderApps();
      if (!apiTools.length) { status('Ready. Choose an AI app below.'); $('#apps-card').scrollIntoView({ behavior: 'smooth' }); return; }
    }
    const jobs = [];
    for (const tool of apiTools) for (let v = 0; v < variations; v++) jobs.push({ tool, v });
    let done = 0, failed = 0;
    status(`Creating ${jobs.length} image${jobs.length > 1 ? 's' : ''} with ${apiTools.join(' + ')}… keep the app open.`, true);
    $('#results-card').classList.remove('hidden');
    await Promise.all(jobs.map(async ({ tool, v }) => {
      const s = state.settings;
      try {
        const data = await P.generate[tool]({
          key: tool === 'demo' ? '' : getKey(tool), model: tool === 'openai' ? s.mOpenai : s.mGemini,
          prompt, images, size: $('#size').value, quality: $('#quality').value, fidelity: s.fidelity && $('#identity').value !== 'creative', variant: v
        });
        state.results.unshift({ id: uid(), tool, data, original: state.source.data, prompt: $('#prompt').value.trim(), fullPrompt: prompt, created: Date.now(), scores: null });
        done++;
      } catch (err) {
        failed++;
        toast(`${tool}: ${err.name === 'AbortError' ? 'timed out — check your provider usage before retrying' : err.message}`);
      }
      status(`${done} ready${failed ? `, ${failed} failed` : ''} of ${jobs.length}…`, true);
      renderResults();
    }));
    status(done ? `${done} image${done > 1 ? 's' : ''} ready${failed ? ` (${failed} failed)` : ''}. Tap ★ Rank to find the closest match, or compare yourself.` : 'Nothing was created — see the messages above.');
    if (done) $('#results-card').scrollIntoView({ behavior: 'smooth' });
  } catch (err) { status(err.message); }
  finally { state.busy = false; $('#create').disabled = false; }
};

/* ---------------- AI apps (no API key) ---------------- */
const WEB_APPS = [
  { id: 'chatgpt', name: 'ChatGPT', note: 'Image creation', url: 'https://chatgpt.com/' },
  { id: 'gemini', name: 'Gemini', note: 'Image creation', url: 'https://gemini.google.com/app' },
  { id: 'claude', name: 'Claude', note: 'Ideas & prompts', url: 'https://claude.ai/new' },
  { id: 'copilot', name: 'Copilot', note: 'Image creation', url: 'https://copilot.microsoft.com/' },
  { id: 'grok', name: 'Grok', note: 'Image creation', url: 'https://grok.com/' },
  { id: 'metaai', name: 'Meta AI', note: 'Image creation', url: 'https://www.meta.ai/' }
];
function appPrompt(prompt, nImages) {
  return `Please create a new image from the ${nImages > 1 ? nImages + ' photos' : 'photo'} I attached, following these instructions.\n\n${prompt}`;
}
function aiAppList() {
  if (Bridge && Bridge.aiApps) { try { return JSON.parse(Bridge.aiApps()); } catch (_) { /* fall through */ } }
  return WEB_APPS;
}
function renderApps() {
  const card = $('#apps-card');
  card.classList.toggle('hidden', !state.handoff);
  if (!state.handoff) return;
  const apps = aiAppList();
  $('#apps-list').innerHTML = apps.map(a => `<button class="app-btn" data-app="${esc(a.id)}"><strong>${esc(a.name)}</strong><small>${esc(a.note || '')}</small>${Bridge ? `<em>${a.installed ? 'Opens the app' : 'Opens the website'}</em>` : ''}</button>`).join('');
  $$('#apps-list [data-app]').forEach(b => b.onclick = () => sendToApp(apps.find(a => a.id === b.dataset.app)));
}
async function copyText(t) {
  try { await navigator.clipboard.writeText(t); return true; } catch (_) {
    const ta = document.createElement('textarea'); ta.value = t; document.body.append(ta); ta.select();
    let ok = false; try { ok = document.execCommand('copy'); } catch (_) { /* ignore */ } ta.remove(); return ok;
  }
}
async function sendToApp(app) {
  const h = state.handoff; if (!app || !h) return;
  if (Bridge && Bridge.sendToAi) {
    const res = Bridge.sendToAi(app.id, h.prompt, JSON.stringify(h.images));
    if (res !== 'ok') toast('Could not send: ' + res);
    return;
  }
  await copyText(h.prompt);
  window.open(app.url, '_blank', 'noopener,noreferrer');
  toast(`Prompt copied. Attach your photo in ${app.name} and paste the prompt.`);
}
$('#apps-copy').onclick = async () => { if (state.handoff) toast(await copyText(state.handoff.prompt) ? 'Prompt copied.' : 'Could not copy.'); };

/* ---------------- results ---------------- */
const overall = s => s ? Math.round((s.identity * 0.5 + s.style * 0.3 + s.quality * 0.2) * 10) / 10 : null;
function renderResults() {
  $('#results-card').classList.toggle('hidden', !state.results.length);
  const list = [...state.results].sort((a, b) => (overall(b.scores) || -1) - (overall(a.scores) || -1));
  const bestId = list[0] && list[0].scores ? list[0].id : null;
  $('#results').innerHTML = list.map(r => `
    <article class="result ${r.id === bestId ? 'best' : ''}">
      <img src="${r.data}" alt="Result from ${esc(r.tool)}">
      <div class="meta"><span class="tag soft">${esc(r.tool)}</span>${r.id === bestId ? '<span class="tag">★ Best match</span>' : ''}${r.scores ? `<span class="score">${overall(r.scores)}/10</span>` : ''}</div>
      ${r.scores ? `<p class="hint">Identity ${r.scores.identity} · Style ${r.scores.style} · Quality ${r.scores.quality}${r.scores.note ? ' — ' + esc(r.scores.note) : ''}</p>` : ''}
      <div class="actions">
        <button data-act="compare" data-id="${r.id}">⇆ Compare</button>
        <button data-act="gallery" data-id="${r.id}">↧ Gallery</button>
        <button data-act="keep" data-id="${r.id}">♡ Keep</button>
        <button data-act="remix" data-id="${r.id}">↻ Remix</button>
      </div>
    </article>`).join('');
  $$('#results [data-act]').forEach(b => b.onclick = () => resultAction(b.dataset.act, state.results.find(r => r.id === b.dataset.id)));
}
async function resultAction(act, r) {
  if (!r) return;
  if (act === 'compare') {
    $('#cmp-a').src = r.original; $('#cmp-b').src = r.data; $('#compare').classList.remove('hidden');
    $('#compare').scrollIntoView({ behavior: 'smooth', block: 'center' });
  } else if (act === 'gallery') saveToGallery(r);
  else if (act === 'keep') {
    try { await store('saved', 'readwrite', s => s.put({ ...r, created: Date.now() })); await refresh(); toast('Kept in Saved.'); } catch (_) { toast('Could not save — storage may be full.'); }
  } else if (act === 'remix') {
    if (guard()) return;
    const photo = await makePhoto(r.data, 'Remix ' + new Date().toLocaleString());
    photo.person = state.source ? state.source.person : '';
    await store('photos', 'readwrite', s => s.put(photo)); await refresh();
    selectSource(photo); window.scrollTo({ top: 0, behavior: 'smooth' }); toast('Result is now your starting photo.');
  }
}
$('#cmp-range').addEventListener('input', e => { $('#cmp-clip').style.width = e.target.value + '%'; });

function saveToGallery(r) {
  const name = `muse-${new Date().toISOString().replace(/[:.]/g, '-')}.png`;
  if (Bridge && Bridge.saveImage) {
    const { mime, b64 } = P.dataUrlParts(r.data);
    const res = Bridge.saveImage(b64, mime, name);
    toast(res === 'ok' ? 'Saved to your gallery (Pictures/StatusSaver).' : 'Could not save: ' + res);
    return;
  }
  const a = document.createElement('a'); a.href = r.data; a.download = name; document.body.append(a); a.click(); a.remove();
  toast('Downloaded. On Android, find it in Downloads or Files.');
}

$('#rank').onclick = async () => {
  if (guard() || !state.results.length) return;
  const a = assistantArgs();
  if (a.provider !== 'demo' && !a.key) return toast('Add an OpenAI or Gemini key in Settings to rank results.');
  state.busy = true; $('#rank').disabled = true; $('#rank').textContent = 'Scoring…';
  try {
    const batch = state.results.slice(0, 8);
    const images = [await resize(batch[0].original, 768, 0.85)];
    if (state.reference) images.push(await resize(state.reference.data, 768, 0.85));
    for (const r of batch) images.push(await resize(r.data, 768, 0.85));
    const out = await P.rank({ ...a, images, candidates: batch, hasReference: !!state.reference, prompt: batch[0].prompt });
    (out.scores || []).forEach(s => { const r = batch[(s.index || 0) - 1]; if (r) r.scores = { identity: +s.identity || 0, style: +s.style || 0, quality: +s.quality || 0, note: s.note || '' }; });
    renderResults();
    $('#rank-note').textContent = `Ranked by ${a.provider}${a.provider === 'demo' ? ' (demo scores)' : ''}. Identity counts most (50%), then style (30%) and quality (20%).`;
  } catch (err) { toast(err.message); }
  finally { state.busy = false; $('#rank').disabled = false; $('#rank').textContent = '★ Rank best match'; }
};

/* ---------------- photos & saved ---------------- */
function renderPhotos() {
  const q = $('#photos-search').value.trim().toLowerCase();
  const ppl = people();
  $('#people').innerHTML = ppl.length ? ['All', ...ppl].map(p => `<button class="chip ${(state.personFilter || 'All') === p ? 'on' : ''}" data-p="${esc(p)}">${p === 'All' ? 'Everyone' : '👤 ' + esc(p)}</button>`).join('') : '';
  $$('#people .chip').forEach(b => b.onclick = () => { state.personFilter = b.dataset.p === 'All' ? null : b.dataset.p; renderPhotos(); });
  const list = state.photos.filter(p => (!state.personFilter || p.person === state.personFilter) && (!q || `${p.name} ${p.person} ${p.tags}`.toLowerCase().includes(q)));
  $('#photos-grid').innerHTML = list.map(p => `
    <article class="item"><img src="${p.thumb || p.data}" alt="${esc(p.name)}" loading="lazy">
      <strong>${esc(p.person ? '👤 ' + p.person : p.name)}</strong><small>${esc(p.tags || p.name)}</small>
      <div class="actions"><button data-a="use" data-id="${p.id}">Use</button><button data-a="label" data-id="${p.id}">Label</button><button data-a="ref" data-id="${p.id}">As style</button><button data-a="del" data-id="${p.id}">Delete</button></div>
    </article>`).join('') || `<div class="empty"><strong>${q || state.personFilter ? 'No matches' : 'Your library is empty'}</strong><small>${q ? 'Try another name or tag.' : 'Add photos, then label who is in them.'}</small></div>`;
  $$('#photos-grid [data-a]').forEach(b => b.onclick = () => photoAction(b.dataset.a, state.photos.find(p => p.id === b.dataset.id)));
}
$('#photos-search').addEventListener('input', renderPhotos);
let labelId = null;
async function photoAction(a, p) {
  if (!p) return;
  if (a === 'use') { selectSource(p); show('create'); }
  else if (a === 'ref') { if (guard()) return; state.reference = p; renderRef(); show('create'); toast('Set as style reference.'); }
  else if (a === 'label') { labelId = p.id; $('#lbl-name').value = p.name; $('#lbl-person').value = p.person || ''; $('#lbl-tags').value = p.tags || ''; $('#label-dialog').showModal(); }
  else if (a === 'del') {
    if (guard() || !confirm('Delete this photo from Muse? (Your phone gallery is not affected.)')) return;
    await store('photos', 'readwrite', s => s.delete(p.id));
    if (state.source && state.source.id === p.id) resetSource();
    state.extras.delete(p.id); await refresh();
  }
}
$('#label-dialog').addEventListener('close', async () => {
  if ($('#label-dialog').returnValue !== 'save' || !labelId) return;
  const p = state.photos.find(x => x.id === labelId); if (!p) return;
  const item = { ...p, name: $('#lbl-name').value.trim() || p.name, person: $('#lbl-person').value.trim(), tags: $('#lbl-tags').value.trim() };
  await store('photos', 'readwrite', s => s.put(item));
  if (state.source && state.source.id === item.id) state.source = item;
  await refresh(); toast('Labels saved.');
});
function renderSaved() {
  $('#saved-grid').innerHTML = state.saved.map(r => `
    <article class="item"><img src="${r.data}" alt="Saved creation" loading="lazy">
      <strong>${esc(r.tool || 'creation')}</strong><small>${esc((r.prompt || '').slice(0, 120))}</small>
      <div class="actions"><button data-a="gallery" data-id="${r.id}">↧ Gallery</button><button data-a="prompt" data-id="${r.id}">Reuse prompt</button><button data-a="del" data-id="${r.id}">Delete</button></div>
    </article>`).join('') || '<div class="empty"><strong>Nothing kept yet</strong><small>Tap ♡ Keep on a result.</small></div>';
  $$('#saved-grid [data-a]').forEach(b => b.onclick = async () => {
    const r = state.saved.find(x => x.id === b.dataset.id); if (!r) return;
    if (b.dataset.a === 'gallery') saveToGallery(r);
    if (b.dataset.a === 'prompt') { $('#prompt').value = r.prompt || ''; show('create'); }
    if (b.dataset.a === 'del' && confirm('Delete this saved creation?')) { await store('saved', 'readwrite', s => s.delete(r.id)); await refresh(); }
  });
}

/* ---------------- settings ---------------- */
function fillSettings() {
  const s = state.settings;
  $('#key-openai').value = getKey('openai'); $('#key-gemini').value = getKey('gemini');
  $('#m-openai').value = s.mOpenai; $('#m-openai-text').value = s.mOpenaiText; $('#m-gemini').value = s.mGemini; $('#m-gemini-text').value = s.mGeminiText;
  $('#assistant').value = s.assistant; $('#fidelity').checked = !!s.fidelity;
  $('#use-apps').checked = !!s.useApps; $('#use-openai').checked = !!s.useOpenai; $('#use-gemini').checked = !!s.useGemini; $('#use-demo').checked = !!s.useDemo;
  $('#key-storage').textContent = Bridge ? 'Keys are kept in Android encrypted storage on this phone.' : 'Browser mode: keys are kept in this browser\'s local storage on this device. Use the Android app for encrypted storage, and never use this on a shared device.';
  $('#version').textContent = `Muse Studio ${VERSION} · ${Bridge ? 'Android app' : 'web'}`;
  updateStatusPills(); updateCost();
}
function updateStatusPills() {
  const o = !!getKey('openai'), g = !!getKey('gemini');
  $('#openai-state').textContent = o ? 'Key set · ' + state.settings.mOpenai : 'Optional API key · add in Settings';
  $('#gemini-state').textContent = g ? 'Key set · ' + state.settings.mGemini : 'Optional API key · add in Settings';
  $('#mode-pill').textContent = o || g ? `AI apps + ${[o && 'OpenAI', g && 'Gemini'].filter(Boolean).join(' + ')}` : 'AI apps · no key needed';
  $('#mode-pill').classList.remove('warn');
}
$('#save-settings').onclick = () => {
  const s = state.settings;
  s.mOpenai = $('#m-openai').value.trim() || DEFAULTS.mOpenai; s.mOpenaiText = $('#m-openai-text').value.trim() || DEFAULTS.mOpenaiText;
  s.mGemini = $('#m-gemini').value.trim() || DEFAULTS.mGemini; s.mGeminiText = $('#m-gemini-text').value.trim() || DEFAULTS.mGeminiText;
  s.assistant = $('#assistant').value; s.fidelity = $('#fidelity').checked;
  setKey('openai', $('#key-openai').value.trim()); setKey('gemini', $('#key-gemini').value.trim());
  saveSettingsObj(); fillSettings(); toast('Settings saved.');
};
$('#clear-keys').onclick = () => { if (!confirm('Remove both API keys from this device?')) return; setKey('openai', ''); setKey('gemini', ''); fillSettings(); toast('Keys removed.'); };
$('#clear-data').onclick = async () => {
  if (guard() || !confirm('Delete all photos and saved creations in Muse? Your phone gallery is not affected.')) return;
  await store('photos', 'readwrite', s => s.clear()); await store('saved', 'readwrite', s => s.clear());
  resetStudio(); await refresh(); toast('All Muse data deleted.');
};

/* ---------------- reset ---------------- */
function resetSource() {
  state.source = null; state.extras.clear();
  const b = $('#source-pick'); b.classList.remove('has');
  b.innerHTML = '<span class="drop-icon">＋</span><strong>Choose a photo</strong><small>JPG · PNG · WebP</small>';
  renderExtras();
}
function resetStudio() {
  resetSource(); state.reference = null; state.results = []; state.handoff = null; renderApps();
  $('#prompt').value = ''; $('#negative').value = ''; $('#keyword').value = ''; $('#consent').checked = false;
  $('#compare').classList.add('hidden'); status('');
  renderRef(); renderResults(); renderSuggestions(); renderPinQueries();
}
$('#reset').onclick = () => { if (guard()) return; if (state.results.length && !confirm('Start fresh? Unkept results will be cleared.')) return; resetStudio(); };
window.addEventListener('beforeunload', e => { if (state.busy) { e.preventDefault(); e.returnValue = ''; } });

/* ---------------- share-in (Android share sheet / PWA share target) ---------------- */
async function receiveShared(items) {
  if (state.handoff && items && items.length && confirm('Add the shared picture as an AI result to compare and keep?\n\nCancel uses it as a style reference instead.')) {
    let r = 0;
    for (const it of items) {
      try {
        const data = await resize(it.data, 2048, 0.92);
        state.results.unshift({ id: uid(), tool: 'AI app', data, original: state.handoff.original, prompt: state.handoff.userPrompt, fullPrompt: state.handoff.prompt, created: Date.now(), scores: null });
        r++;
      } catch (_) { /* skip unreadable */ }
    }
    if (r) { show('create'); renderResults(); $('#results-card').scrollIntoView({ behavior: 'smooth' }); toast(`Added ${r} AI result${r > 1 ? 's' : ''}. Compare, rank or keep ${r > 1 ? 'them' : 'it'}.`); return; }
  }
  let n = 0;
  for (const it of items || []) {
    try {
      const photo = await makePhoto(it.data, it.name || 'Shared image');
      photo.tags = 'shared';
      await store('photos', 'readwrite', s => s.put(photo));
      if (!n) { state.reference = photo; renderRef(); }
      n++;
    } catch (_) { /* skip unreadable */ }
  }
  if (n) { await refresh(); show('create'); toast(`Shared image${n > 1 ? 's' : ''} received — first one set as your style reference.`); }
}
window.museOnShare = () => { try { receiveShared(JSON.parse(Bridge.takeShared() || '[]')); } catch (_) { /* ignore */ } };
async function pwaShared() {
  if (!/[?&]shared=1/.test(location.search) || !('caches' in window)) return;
  try {
    const cache = await caches.open('muse-share');
    const keys = await cache.keys(); const items = [];
    for (const k of keys) { const r = await cache.match(k); const blob = await r.blob(); items.push({ name: decodeURIComponent(k.url.split('/').pop()), data: await fileToDataUrl(blob) }); await cache.delete(k); }
    history.replaceState(null, '', location.pathname + '#create');
    await receiveShared(items);
  } catch (_) { /* ignore */ }
}

/* ---------------- init ---------------- */
async function init() {
  loadSettings(); fillSettings();
  show(location.hash.slice(1) || 'create');
  renderSuggestions(); renderPinQueries(); renderRef();
  try { db = await openDB(); await refresh(); } catch (_) { toast('Storage is unavailable — enable site storage and reload.'); }
  if (Bridge && Bridge.takeShared) window.museOnShare();
  await pwaShared();
  if (!Bridge && 'serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});
}
init();
