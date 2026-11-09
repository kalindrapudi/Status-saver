'use strict';
/* Muse providers: image generation (OpenAI, Gemini, Demo) and the AI assistant
   (look suggestions + result ranking). All calls go straight from the device to
   the provider with the user's own API key. */
(function () {
  const OPENAI = 'https://api.openai.com/v1';
  const GEMINI = 'https://generativelanguage.googleapis.com/v1beta';

  function dataUrlParts(dataUrl) {
    const [head, b64] = dataUrl.split(',', 2);
    const mime = (head.match(/data:([^;]+)/) || [])[1] || 'image/jpeg';
    return { mime, b64 };
  }
  function dataUrlToBlob(dataUrl) {
    const { mime, b64 } = dataUrlParts(dataUrl);
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new Blob([bytes], { type: mime });
  }
  async function readError(response, label) {
    let detail = '';
    try { const j = await response.json(); detail = (j.error && (j.error.message || j.error.status)) || ''; } catch (_) { /* ignore */ }
    return new Error(`${label} returned HTTP ${response.status}${detail ? ': ' + detail : ''}`);
  }
  function withTimeout(ms) {
    const c = new AbortController();
    const t = setTimeout(() => c.abort(), ms);
    return { signal: c.signal, done: () => clearTimeout(t) };
  }
  function parseJsonLoose(text) {
    try { return JSON.parse(text); } catch (_) { /* fall through */ }
    const m = String(text).match(/\{[\s\S]*\}/);
    if (m) return JSON.parse(m[0]);
    throw new Error('The assistant did not return valid JSON.');
  }
  const aspectFor = size => ({ '1024x1536': '2:3', '1536x1024': '3:2', '1024x1024': '1:1' }[size] || null);

  /* ---------- OpenAI image edits ---------- */
  async function openaiGenerate({ key, model, prompt, images, size, quality, fidelity }) {
    if (!key) throw new Error('Add your OpenAI API key in Settings.');
    const build = withFidelity => {
      const fd = new FormData();
      fd.append('model', model || 'gpt-image-1');
      fd.append('prompt', prompt);
      fd.append('n', '1');
      fd.append('size', size || 'auto');
      fd.append('quality', quality || 'medium');
      if (withFidelity) fd.append('input_fidelity', 'high');
      images.forEach((d, i) => fd.append('image[]', dataUrlToBlob(d), `image-${i + 1}.jpg`));
      return fd;
    };
    const send = async withFidelity => {
      const t = withTimeout(300000);
      try {
        return await fetch(`${OPENAI}/images/edits`, { method: 'POST', headers: { Authorization: 'Bearer ' + key }, body: build(withFidelity), signal: t.signal });
      } finally { t.done(); }
    };
    let r = await send(!!fidelity);
    if (!r.ok && r.status === 400 && fidelity) {
      const copy = r.clone();
      let msg = '';
      try { msg = JSON.stringify(await copy.json()); } catch (_) { /* ignore */ }
      if (/fidelity/i.test(msg)) r = await send(false);
    }
    if (!r.ok) throw await readError(r, 'OpenAI');
    const j = await r.json();
    const b64 = j.data && j.data[0] && j.data[0].b64_json;
    if (!b64) throw new Error('OpenAI returned no image.');
    return 'data:image/png;base64,' + b64;
  }

  /* ---------- Gemini image generation ---------- */
  async function geminiGenerate({ key, model, prompt, images, size }) {
    if (!key) throw new Error('Add your Gemini API key in Settings.');
    const parts = [{ text: prompt }].concat(images.map(d => {
      const { mime, b64 } = dataUrlParts(d);
      return { inline_data: { mime_type: mime, data: b64 } };
    }));
    const send = async withAspect => {
      const generationConfig = { responseModalities: ['IMAGE', 'TEXT'] };
      const ar = aspectFor(size);
      if (withAspect && ar) generationConfig.imageConfig = { aspectRatio: ar };
      const t = withTimeout(300000);
      try {
        return await fetch(`${GEMINI}/models/${encodeURIComponent(model || 'gemini-2.5-flash-image')}:generateContent`, {
          method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
          body: JSON.stringify({ contents: [{ role: 'user', parts }], generationConfig }), signal: t.signal
        });
      } finally { t.done(); }
    };
    let r = await send(true);
    if (!r.ok && r.status === 400) r = await send(false);
    if (!r.ok) throw await readError(r, 'Gemini');
    const j = await r.json();
    const outParts = (j.candidates && j.candidates[0] && j.candidates[0].content && j.candidates[0].content.parts) || [];
    const img = outParts.map(p => p.inlineData || p.inline_data).find(Boolean);
    if (!img) {
      const text = outParts.map(p => p.text).filter(Boolean).join(' ').slice(0, 200);
      const reason = j.candidates && j.candidates[0] && j.candidates[0].finishReason;
      throw new Error('Gemini returned no image' + (reason ? ` (${reason})` : '') + (text ? ': ' + text : '.'));
    }
    return `data:${img.mimeType || img.mime_type || 'image/png'};base64,${img.data}`;
  }

  /* ---------- Demo provider (no key, no network) ---------- */
  async function demoGenerate({ images, prompt, variant = 0 }) {
    await new Promise(r => setTimeout(r, 600));
    const load = src => new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = src; });
    const base = await load(images[0]);
    const ref = images.length > 1 ? await load(images[images.length - 1]) : null;
    const w = 768, h = Math.round(768 * base.height / base.width);
    const c = document.createElement('canvas'); c.width = w; c.height = h;
    const g = c.getContext('2d');
    const filters = ['sepia(0.55) saturate(1.2) contrast(1.05)', 'hue-rotate(200deg) saturate(1.4)', 'grayscale(0.8) contrast(1.25)', 'saturate(1.6) brightness(1.05)'];
    g.filter = filters[variant % filters.length];
    g.drawImage(base, 0, 0, w, h);
    g.filter = 'none';
    if (ref) { g.globalAlpha = 0.22; g.drawImage(ref, 0, 0, w, h); g.globalAlpha = 1; }
    g.fillStyle = 'rgba(0,0,0,.55)'; g.fillRect(0, h - 44, w, 44);
    g.fillStyle = '#fff'; g.font = '20px sans-serif';
    g.fillText('DEMO · ' + String(prompt).replace(/\s+/g, ' ').slice(0, 52), 14, h - 16);
    return c.toDataURL('image/png');
  }

  /* ---------- Assistant (vision + text JSON) ---------- */
  async function askJSON({ provider, key, model, text, images }) {
    if (provider === 'openai') {
      const content = [{ type: 'text', text }].concat(images.map(url => ({ type: 'image_url', image_url: { url, detail: 'low' } })));
      const t = withTimeout(120000);
      let r;
      try {
        r = await fetch(`${OPENAI}/chat/completions`, {
          method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + key },
          body: JSON.stringify({ model: model || 'gpt-4.1-mini', response_format: { type: 'json_object' }, messages: [{ role: 'user', content }] }), signal: t.signal
        });
      } finally { t.done(); }
      if (!r.ok) throw await readError(r, 'OpenAI assistant');
      const j = await r.json();
      return parseJsonLoose(j.choices[0].message.content);
    }
    if (provider === 'gemini') {
      const parts = [{ text }].concat(images.map(d => { const { mime, b64 } = dataUrlParts(d); return { inline_data: { mime_type: mime, data: b64 } }; }));
      const t = withTimeout(120000);
      let r;
      try {
        r = await fetch(`${GEMINI}/models/${encodeURIComponent(model || 'gemini-2.5-flash')}:generateContent`, {
          method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
          body: JSON.stringify({ contents: [{ role: 'user', parts }], generationConfig: { responseMimeType: 'application/json' } }), signal: t.signal
        });
      } finally { t.done(); }
      if (!r.ok) throw await readError(r, 'Gemini assistant');
      const j = await r.json();
      const out = ((j.candidates || [])[0] || {}).content;
      return parseJsonLoose(((out && out.parts) || []).map(p => p.text || '').join(''));
    }
    throw new Error('No assistant provider is available. Add an OpenAI or Gemini key, or turn on demo mode.');
  }

  async function suggest(opts) {
    const { keyword, category, hasPhoto } = opts;
    if (opts.provider === 'demo') {
      const k = keyword || 'portrait';
      return {
        suggestions: [
          { title: 'Golden-hour candid', prompt: `A candid ${k} in warm golden-hour light, relaxed shoulders, soft film grain.` },
          { title: 'Studio editorial', prompt: `A clean studio editorial ${k}, neutral backdrop, sculpted key light, confident pose.` },
          { title: 'Street style', prompt: `An urban street-style ${k}, mid-stride pose, shallow depth of field, overcast light.` },
          { title: 'Monochrome classic', prompt: `A timeless black-and-white ${k}, high contrast, three-quarter angle.` }
        ],
        pinterestQueries: [`${k} aesthetic`, `${k} pose ideas`, `${k} outfit inspiration`, `${k} lighting photography`]
      };
    }
    const text = `You are a creative director for a personal photo-styling app.
${hasPhoto ? 'The first image is the user\'s own photo (the identity to preserve).' : 'No photo is attached yet.'}
Theme or keyword: "${keyword || '(none)'}". Category: ${category}.
Return JSON: {"suggestions":[{"title":string (max 5 words),"prompt":string (one or two sentences describing pose, look/outfit, lighting, background and mood; never describe changing the person's face or identity)}] (exactly 6, varied),
"pinterestQueries":[string] (4 to 6 short Pinterest search phrases that would find good style references for these ideas)}`;
    return askJSON({ ...opts, text });
  }

  async function rank(opts) {
    const { candidates, hasReference } = opts;
    if (opts.provider === 'demo') {
      return { scores: candidates.map((_, i) => ({ index: i + 1, identity: 6 + ((i * 3) % 4), style: 5 + ((i * 5) % 5), quality: 7, note: 'Demo score — not a real evaluation.' })) };
    }
    const text = `You are judging AI-edited photos. Image 1 is the ORIGINAL photo of the subject.
${hasReference ? 'Image 2 is the STYLE REFERENCE. ' : ''}The remaining ${candidates.length} images are CANDIDATES numbered 1 to ${candidates.length} in order.
For each candidate score 0-10: "identity" (same recognisable person/subject as the original: face shape, features, skin tone, proportions), "style" (${hasReference ? 'match to the style reference and ' : ''}fit to this brief: "${String(opts.prompt || '').slice(0, 400)}"), "quality" (anatomy, hands, artefacts, realism).
Return JSON: {"scores":[{"index":number,"identity":number,"style":number,"quality":number,"note":string (max 15 words, the main weakness)}]}`;
    return askJSON({ ...opts, text });
  }

  window.MuseProviders = {
    generate: { openai: openaiGenerate, gemini: geminiGenerate, demo: demoGenerate },
    suggest, rank, dataUrlToBlob, dataUrlParts
  };
})();
