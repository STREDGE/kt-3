// Runs the frame-generation shaders in headless Chromium (WebGL2 = GLSL ES 3.00) on synthetic
// frames with a known in-between frame and reports PSNR against a plain blend.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const SHADER_DIR = process.argv[2];
const OUT = process.argv[3] || '.';
const shaders = {};
for (const f of ['fullscreen.vert', 'flow.frag', 'warp.frag', 'blit.frag']) {
  shaders[f] = fs.readFileSync(path.join(SHADER_DIR, f), 'utf8');
}

async function pageMain({ shaders, scene }) {
  const W = scene.w, H = scene.h;
  // ---- synthetic scene ----
  function rng(seed) { let s = seed >>> 0; return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296); }
  const world = document.createElement('canvas');
  world.width = W * 2; world.height = H * 2;
  {
    const c = world.getContext('2d'); const r = rng(7);
    const g = c.createLinearGradient(0, 0, world.width, world.height);
    g.addColorStop(0, '#203050'); g.addColorStop(1, '#705030');
    c.fillStyle = g; c.fillRect(0, 0, world.width, world.height);
    for (let i = 0; i < 900; i++) {
      c.fillStyle = `rgb(${r() * 255 | 0},${r() * 255 | 0},${r() * 255 | 0})`;
      const x = r() * world.width, y = r() * world.height, s = 6 + r() * 70;
      if (r() < 0.5) c.fillRect(x, y, s, s * (0.3 + r()));
      else { c.beginPath(); c.arc(x, y, s * 0.5, 0, 7); c.fill(); }
    }
    c.lineWidth = 2;
    for (let i = 0; i < 200; i++) {
      c.strokeStyle = `rgb(${r() * 255 | 0},${r() * 255 | 0},${r() * 255 | 0})`;
      c.beginPath(); c.moveTo(r() * world.width, r() * world.height); c.lineTo(r() * world.width, r() * world.height); c.stroke();
    }
  }
  function frame(time) {
    const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
    const c = cv.getContext('2d');
    const px = W * 0.5 + scene.pan[0] * time, py = H * 0.5 + scene.pan[1] * time;
    c.drawImage(world, px, py, W, H, 0, 0, W, H);
    // foreground object moving differently from the background
    const ox = W * 0.55 + scene.obj[0] * time, oy = H * 0.5 + scene.obj[1] * time;
    c.fillStyle = '#e8e020'; c.fillRect(ox - 60, oy - 90, 120, 180);
    c.fillStyle = '#c02020'; c.fillRect(ox - 40, oy - 70, 80, 50);
    c.fillStyle = '#2040c0'; c.beginPath(); c.arc(ox, oy + 40, 30, 0, 7); c.fill();
    // static HUD
    c.fillStyle = 'rgba(0,0,0,0.6)'; c.fillRect(20, 20, 220, 120);
    c.fillStyle = '#ffffff'; c.font = 'bold 28px sans-serif'; c.fillText('HP 100', 34, 60);
    c.fillStyle = '#40ff40'; c.fillRect(34, 80, 180, 14);
    c.font = '18px sans-serif'; c.fillStyle = '#ffd040'; c.fillText('AMMO 12 / 48', W - 190, H - 30);
    return cv;
  }
  const cvA = frame(0), cvB = frame(1), cvM = frame(scene.t);

  // ---- WebGL2 pipeline (mirrors FrameGenerator.java) ----
  const canvas = document.createElement('canvas'); canvas.width = W; canvas.height = H;
  const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
  if (!gl) return { error: 'no webgl2' };
  const log = [];
  function program(fsName) {
    const p = gl.createProgram();
    for (const [type, src] of [[gl.VERTEX_SHADER, shaders['fullscreen.vert']], [gl.FRAGMENT_SHADER, shaders[fsName]]]) {
      const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) log.push(fsName + ': ' + gl.getShaderInfoLog(s));
      gl.attachShader(p, s);
    }
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) log.push(fsName + ' link: ' + gl.getProgramInfoLog(p));
    return p;
  }
  const pFlow = program('flow.frag'), pWarp = program('warp.frag'), pBlit = program('blit.frag');
  if (log.length) return { error: log.join('\n') };

  function frameTexture(src) {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.generateMipmap(gl.TEXTURE_2D);
    return t;
  }
  function target(w, h) {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const f = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, f);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    return { t, f, w, h };
  }
  const texA = frameTexture(cvA), texB = frameTexture(cvB);
  const BASE = scene.base || 8;
  const level = (i) => target(Math.ceil(W / (BASE * (1 << i))), Math.ceil(H / (BASE * (1 << i))));
  const baseLod = Math.log2(BASE);
  const L = [level(0), level(1), level(2)];
  const spare = [level(0), level(0)];
  function bind(unit, tex) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex); }
  function flowPass(dst, coarse, coarseScale, radius, stepSize, lod, propagate) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, dst.f); gl.viewport(0, 0, dst.w, dst.h);
    gl.useProgram(pFlow);
    bind(0, texA); bind(1, texB); bind(2, coarse ? coarse.t : texA);
    gl.uniform1i(gl.getUniformLocation(pFlow, 'frameA'), 0);
    gl.uniform1i(gl.getUniformLocation(pFlow, 'frameB'), 1);
    gl.uniform1i(gl.getUniformLocation(pFlow, 'coarseFlow'), 2);
    gl.uniform1f(gl.getUniformLocation(pFlow, 'lod'), lod);
    gl.uniform2f(gl.getUniformLocation(pFlow, 'texel'), 1 / dst.w, 1 / dst.h);
    gl.uniform1f(gl.getUniformLocation(pFlow, 'coarseScale'), coarseScale);
    gl.uniform1i(gl.getUniformLocation(pFlow, 'radius'), radius);
    gl.uniform1f(gl.getUniformLocation(pFlow, 'stepSize'), stepSize);
    gl.uniform1i(gl.getUniformLocation(pFlow, 'propagate'), propagate ? 1 : 0);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }
  gl.disable(gl.BLEND);
  const t0 = performance.now();
  // Same pass list as FrameGenerator.java: [kind, step, lodOffset]
  const PIPELINES = {
    fast: [['refine', 0.5, 0]],
    balanced: [['propagate', 1, 0], ['refine', 0.5, 0], ['propagate', 1, 0]],
    quality: [['propagate', 1, 0], ['refine', 0.5, 0], ['propagate', 1, 0], ['refine', 0.25, -1], ['propagate', 1, -1]],
  };
  flowPass(L[2], null, 0, 4, 1, baseLod + 2, false);
  flowPass(L[1], L[2], 2, 1, 1, baseLod + 1, false);
  flowPass(L[0], L[1], 2, 1, 1, baseLod, false);
  let finalFlow = L[0], flip = 0;
  for (const [kind, step, lodOff] of PIPELINES[scene.pipeline || 'fast']) {
    const dst = spare[flip]; 
    flowPass(dst, finalFlow, 1, 1, step, Math.max(0, baseLod + lodOff), kind === 'propagate');
    finalFlow = dst; flip = 1 - flip;
  }

  function readScreen() {
    const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px); return px;
  }
  function warp(t) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.viewport(0, 0, W, H);
    gl.useProgram(pWarp);
    bind(0, texA); bind(1, texB); bind(2, finalFlow.t);
    gl.uniform1i(gl.getUniformLocation(pWarp, 'frameA'), 0);
    gl.uniform1i(gl.getUniformLocation(pWarp, 'frameB'), 1);
    gl.uniform1i(gl.getUniformLocation(pWarp, 'flow'), 2);
    gl.uniform2f(gl.getUniformLocation(pWarp, 'flowTexel'), 1 / finalFlow.w, 1 / finalFlow.h);
    gl.uniform1f(gl.getUniformLocation(pWarp, 't'), t);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }
  warp(scene.t);
  const interp = readScreen();
  const elapsed = performance.now() - t0;
  const interpURL = canvas.toDataURL('image/png');

  // flow visualisation
  gl.useProgram(pBlit); bind(0, finalFlow.t);
  gl.uniform1i(gl.getUniformLocation(pBlit, 'frame'), 0);
  gl.drawArrays(gl.TRIANGLES, 0, 3);
  const flowURL = canvas.toDataURL('image/png');
  // mean flow in pixels (decoded) for a sanity check
  const fpx = new Uint8Array(finalFlow.w * finalFlow.h * 4);
  gl.bindFramebuffer(gl.FRAMEBUFFER, finalFlow.f); gl.readPixels(0, 0, finalFlow.w, finalFlow.h, gl.RGBA, gl.UNSIGNED_BYTE, fpx);
  const hist = {};
  for (let i = 0; i < fpx.length; i += 4) {
    const k = ((fpx[i] - 128) / 8 * BASE).toFixed(0) + ',' + ((fpx[i + 1] - 128) / 8 * BASE).toFixed(0);
    hist[k] = (hist[k] || 0) + 1;
  }
  const top = Object.entries(hist).sort((a, b) => b[1] - a[1]).slice(0, 4).map(([k, n]) => k + ' x' + n);

  // ---- reference images (canvas 2D, top-left origin) ----
  function pixels(cv) { return cv.getContext('2d').getImageData(0, 0, W, H).data; }
  const pM = pixels(cvM), pA = pixels(cvA), pB = pixels(cvB);
  function psnr(get) {
    let se = 0, n = 0;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) for (let ch = 0; ch < 3; ch++) {
      const d = get(x, y, ch) - pM[(y * W + x) * 4 + ch]; se += d * d; n++;
    }
    return 10 * Math.log10(255 * 255 / (se / n));
  }
  // GL readPixels rows start at the bottom
  const pInterp = psnr((x, y, ch) => interp[((H - 1 - y) * W + x) * 4 + ch]);
  const pBlend = psnr((x, y, ch) => pA[(y * W + x) * 4 + ch] * (1 - scene.t) + pB[(y * W + x) * 4 + ch] * scene.t);
  const pRepeat = psnr((x, y, ch) => pA[(y * W + x) * 4 + ch]);
  // HUD region only (static elements must stay intact)
  function psnrRect(x0, y0, x1, y1) {
    let se = 0, n = 0;
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) for (let ch = 0; ch < 3; ch++) {
      const d = interp[((H - 1 - y) * W + x) * 4 + ch] - pM[(y * W + x) * 4 + ch]; se += d * d; n++;
    }
    return 10 * Math.log10(255 * 255 / Math.max(se / n, 1e-9));
  }
  return {
    psnrInterp: pInterp, psnrBlend: pBlend, psnrRepeat: pRepeat, psnrHud: psnrRect(34, 30, 214, 100),
    ms: elapsed, flowTop: top, interpURL, flowURL, truthURL: cvM.toDataURL('image/png'),
    renderer: gl.getParameter(gl.VERSION),
  };
}

(async () => {
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  await page.goto('about:blank');
  const scenes = JSON.parse(process.argv[4] || '[]');
  for (const scene of scenes) {
    const r = await page.evaluate(pageMain, { shaders, scene });
    if (r.error) { console.log(scene.name, 'ERROR', r.error); continue; }
    console.log(`${scene.name}: interp ${r.psnrInterp.toFixed(2)} dB | blend ${r.psnrBlend.toFixed(2)} dB | repeat ${r.psnrRepeat.toFixed(2)} dB | HUD ${r.psnrHud.toFixed(1)} dB | flow(px) ${r.flowTop.join('  ')} | ${r.ms.toFixed(0)} ms | ${r.renderer}`);
    if (scene.save) for (const k of ['interp', 'flow', 'truth']) {
      fs.writeFileSync(path.join(OUT, `${scene.name}-${k}.png`), Buffer.from(r[k + 'URL'].split(',')[1], 'base64'));
    }
  }
  await browser.close();
})();
