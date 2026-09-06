const $ = s => document.querySelector(s);
const MIN_BOX = 8;
const MIN_AREA = 64;
const MAX_BOXES = 3;

const state = {
  frames: [],
  current: -1,
  annotations: {},
  selected: null,
  action: null,
  placing: null,
  hover: null,
  replaceNext: false,
};

const layer = $('#annotationLayer');
const image = $('#frameImage');
const wrap = $('#canvasWrap');
const toastEl = $('#toast');

function toast(message) {
  toastEl.textContent = message;
  toastEl.classList.add('show');
  clearTimeout(toastEl._t);
  toastEl._t = setTimeout(() => toastEl.classList.remove('show'), 1800);
}

function uid() {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function currentFrame() {
  return state.frames[state.current];
}

function boxes() {
  const frame = currentFrame();
  return frame ? (state.annotations[frame.id] ||= []) : [];
}

function fileKey(file) {
  const path = file.webkitRelativePath || file.name;
  return `${path}::${file.size}::${file.lastModified}`;
}

function isImageFile(file) {
  if (file.type && file.type.startsWith('image/')) return true;
  return /\.(jpe?g|png|gif|webp|bmp|tif{1,2}|avif)$/i.test(file.name);
}

function collectImages(fileList) {
  return [...fileList]
    .filter(isImageFile)
    .sort((a, b) => {
      const ka = a.webkitRelativePath || a.name;
      const kb = b.webkitRelativePath || b.name;
      return ka.localeCompare(kb, undefined, { numeric: true });
    });
}

function revokeFrames(frames) {
  frames.forEach(frame => URL.revokeObjectURL(frame.url));
}

async function filesFromDataTransfer(dataTransfer) {
  const items = [...(dataTransfer.items || [])];
  if (!items.length) return [...dataTransfer.files];

  const files = [];
  await Promise.all(items.map(item => {
    const entry = item.webkitGetAsEntry?.();
    if (entry) return collectEntry(entry, files);
    const file = item.getAsFile?.();
    if (file) files.push(file);
    return Promise.resolve();
  }));
  return files.length ? files : [...dataTransfer.files];
}

function readAllEntries(reader) {
  return new Promise((resolve, reject) => {
    const all = [];
    const pump = () => {
      reader.readEntries(batch => {
        if (!batch.length) return resolve(all);
        all.push(...batch);
        pump();
      }, reject);
    };
    pump();
  });
}

async function collectEntry(entry, files) {
  if (!entry) return;
  if (entry.isFile) {
    const file = await new Promise((resolve, reject) => entry.file(resolve, reject));
    files.push(file);
    return;
  }
  if (entry.isDirectory) {
    const children = await readAllEntries(entry.createReader());
    await Promise.all(children.map(child => collectEntry(child, files)));
  }
}

function importImages(fileList, { replace = false } = {}) {
  const images = collectImages(fileList);
  if (!images.length) return toast('没有检测到图片文件');

  const shouldReplace = replace || state.replaceNext || !state.frames.length;
  state.replaceNext = false;

  if (shouldReplace && state.frames.length) {
    revokeFrames(state.frames);
    state.frames = [];
    state.annotations = {};
    state.selected = null;
    state.action = null;
    state.placing = null;
    state.current = -1;
  }

  const seen = new Set(state.frames.map(frame => frame.key));
  const added = [];
  for (const file of images) {
    const key = fileKey(file);
    if (seen.has(key)) continue;
    seen.add(key);
    added.push({
      id: uid(),
      key,
      name: file.name,
      path: file.webkitRelativePath || file.name,
      url: URL.createObjectURL(file),
      file,
    });
  }

  if (!added.length) {
    toast(shouldReplace ? '没有检测到新的图片' : '这些图片已经在列表中');
    if (shouldReplace) render();
    return;
  }

  const wasEmpty = state.current < 0;
  state.frames.push(...added);
  if (wasEmpty) state.current = 0;
  render();

  const extra = images.length - added.length;
  const skip = extra > 0 ? `，跳过 ${extra} 张重复` : '';
  toast(shouldReplace
    ? `已加载 ${added.length} 张图片${skip}`
    : `已追加 ${added.length} 张，当前共 ${state.frames.length} 张${skip}`);
}

function render() {
  const frame = currentFrame();
  const has = !!frame;
  $('#stage').classList.toggle('is-empty', !has);
  $('#dropMessage').hidden = has;
  wrap.hidden = !has;
  $('#frameTotal').textContent = state.frames.length;
  $('#completion').textContent = `${state.frames.filter(item => state.annotations[item.id]?.length).length} / ${state.frames.length}`;
  $('#frameIndex').textContent = has ? `${state.current + 1} / ${state.frames.length}` : '0 / 0';
  $('#frameTitle').textContent = has ? frame.name : '选择一组帧开始标注';
  $('#loadedHint').textContent = has
    ? `已加载 ${state.frames.length} 张，可继续追加图片或文件夹。`
    : '选择 frames/ 里的文件夹，或一次选中多张图片；也可直接拖入。';
  if (!has) {
    renderFrames();
    renderInspector();
    return;
  }
  const previous = image.src;
  image.onload = () => {
    rememberSize();
    renderBoxes();
    renderFrames();
    renderInspector();
  };
  if (previous === frame.url && image.complete && image.naturalWidth) {
    rememberSize();
    renderBoxes();
    renderFrames();
    renderInspector();
  } else {
    image.src = frame.url;
  }
  renderFrames();
  renderInspector();
}

function rememberSize() {
  const frame = currentFrame();
  if (frame && image.naturalWidth) {
    frame.width = image.naturalWidth;
    frame.height = image.naturalHeight;
  }
}

function renderFrames() {
  $('#frameList').innerHTML = state.frames.length
    ? state.frames.map((frame, i) => `<button class="frame-item ${i === state.current ? 'active' : ''} ${state.annotations[frame.id]?.length ? 'done' : ''}" data-index="${i}"><img class="frame-thumb" src="${frame.url}" alt=""><span>${String(i + 1).padStart(4, '0')} · ${frame.name}</span></button>`).join('')
    : '<p class="empty-list">等待图片…</p>';
  document.querySelectorAll('.frame-item').forEach(button => {
    button.onclick = () => {
      state.current = +button.dataset.index;
      state.selected = null;
      state.action = null;
      state.placing = null;
      render();
    };
  });
}

function displayScale() {
  const rect = layer.getBoundingClientRect();
  return image.naturalWidth / Math.max(1, rect.width);
}

function handleSize() {
  return Math.max(6, 8 * displayScale());
}

function handleHitSize() {
  return Math.max(handleSize() * 1.8, 14 * displayScale());
}

function clonePoints(points) {
  return points.map(point => ({ x: point.x, y: point.y }));
}

function rectPoints(x, y, w, h) {
  return [
    { x, y },
    { x: x + w, y },
    { x: x + w, y: y + h },
    { x, y: y + h },
  ];
}

function pointsAttr(points) {
  return points.map(point => `${point.x},${point.y}`).join(' ');
}

function centroid(points) {
  return {
    x: points.reduce((sum, point) => sum + point.x, 0) / points.length,
    y: points.reduce((sum, point) => sum + point.y, 0) / points.length,
  };
}

function polygonArea(points) {
  let area = 0;
  for (let i = 0; i < points.length; i++) {
    const next = points[(i + 1) % points.length];
    area += points[i].x * next.y - next.x * points[i].y;
  }
  return area / 2;
}

function aabb(points) {
  const xs = points.map(point => point.x);
  const ys = points.map(point => point.y);
  const x = Math.min(...xs);
  const y = Math.min(...ys);
  return { x, y, w: Math.max(...xs) - x, h: Math.max(...ys) - y };
}

function orderCorners(points) {
  const center = centroid(points);
  const ordered = [...points].sort((a, b) =>
    Math.atan2(a.y - center.y, a.x - center.x) - Math.atan2(b.y - center.y, b.x - center.x));
  let start = 0;
  let best = Infinity;
  ordered.forEach((point, i) => {
    const score = point.x + point.y;
    if (score < best) {
      best = score;
      start = i;
    }
  });
  return [0, 1, 2, 3].map(i => ordered[(start + i) % 4]);
}

function pointInPolygon(point, points) {
  let inside = false;
  for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
    const a = points[i];
    const b = points[j];
    const intersect = ((a.y > point.y) !== (b.y > point.y))
      && (point.x < (b.x - a.x) * (point.y - a.y) / ((b.y - a.y) || 1e-9) + a.x);
    if (intersect) inside = !inside;
  }
  return inside;
}

function dist2(a, b) {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  return dx * dx + dy * dy;
}

function nearestVertex(point, points, threshold) {
  let best = -1;
  let bestDist = threshold * threshold;
  points.forEach((vertex, i) => {
    const d = dist2(point, vertex);
    if (d <= bestDist) {
      bestDist = d;
      best = i;
    }
  });
  return best;
}

function distToSegment(point, a, b) {
  const vx = b.x - a.x;
  const vy = b.y - a.y;
  const len2 = vx * vx + vy * vy || 1;
  const t = clamp(((point.x - a.x) * vx + (point.y - a.y) * vy) / len2, 0, 1);
  return Math.hypot(point.x - (a.x + t * vx), point.y - (a.y + t * vy));
}

function nearestEdge(point, points, threshold) {
  let best = -1;
  let bestDist = threshold;
  for (let i = 0; i < points.length; i++) {
    const d = distToSegment(point, points[i], points[(i + 1) % points.length]);
    if (d <= bestDist) {
      bestDist = d;
      best = i;
    }
  }
  return best;
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function clampPoint(point) {
  return {
    x: clamp(point.x, 0, image.naturalWidth),
    y: clamp(point.y, 0, image.naturalHeight),
  };
}

function shiftPoints(points, dx, dy, indices = null) {
  const width = image.naturalWidth;
  const height = image.naturalHeight;
  const subset = (indices || points.map((_, i) => i)).map(i => points[i]);
  const minX = Math.min(...subset.map(point => point.x));
  const maxX = Math.max(...subset.map(point => point.x));
  const minY = Math.min(...subset.map(point => point.y));
  const maxY = Math.max(...subset.map(point => point.y));
  const tdx = clamp(dx, -minX, width - maxX);
  const tdy = clamp(dy, -minY, height - maxY);
  return points.map((point, i) => {
    if (indices && !indices.includes(i)) return { ...point };
    return { x: point.x + tdx, y: point.y + tdy };
  });
}

function hitTest(point) {
  const list = boxes();
  const threshold = handleHitSize() / 2;
  const selected = list.find(box => box.id === state.selected);
  if (selected) {
    const vertex = nearestVertex(point, selected.points, threshold);
    if (vertex >= 0) return { box: selected, vertex };
    const edge = nearestEdge(point, selected.points, threshold);
    if (edge >= 0) return { box: selected, edge };
  }
  for (let i = list.length - 1; i >= 0; i--) {
    const box = list[i];
    const vertex = nearestVertex(point, box.points, threshold);
    if (vertex >= 0) return { box, vertex };
    if (pointInPolygon(point, box.points)) return { box, move: true };
  }
  return null;
}

function renderBoxes() {
  if (!currentFrame() || !image.naturalWidth) return;
  layer.setAttribute('viewBox', `0 0 ${image.naturalWidth} ${image.naturalHeight}`);
  const size = handleSize();
  const hit = handleHitSize();
  const radius = size / 2;
  const hitRadius = hit / 2;
  const surface = `<rect class="surface" x="0" y="0" width="${image.naturalWidth}" height="${image.naturalHeight}"/>`;
  const quads = boxes().map((box, i) => {
    const selected = state.selected === box.id;
    const top = box.points.reduce((best, point) => (point.y < best.y ? point : best), box.points[0]);
    const labelX = top.x;
    const labelY = Math.max(0, top.y - 22);
    let handles = '';
    if (selected) {
      handles = box.points.map((point, n) => {
        const next = box.points[(n + 1) % box.points.length];
        const mx = (point.x + next.x) / 2;
        const my = (point.y + next.y) / 2;
        return `<circle class="handle-hit" data-edge="${n}" cx="${mx}" cy="${my}" r="${hitRadius}"/><rect class="handle edge" data-edge="${n}" x="${mx - radius * 0.7}" y="${my - radius * 0.7}" width="${size * 0.7}" height="${size * 0.7}"/>`;
      }).join('') + box.points.map((point, n) =>
        `<circle class="handle-hit" data-vertex="${n}" cx="${point.x}" cy="${point.y}" r="${hitRadius}"/><circle class="handle vertex" data-vertex="${n}" cx="${point.x}" cy="${point.y}" r="${radius}"/>`
      ).join('');
    }
    return `<g data-id="${box.id}"><polygon class="box ${selected ? 'selected' : ''}" points="${pointsAttr(box.points)}"/><rect class="box-label" x="${labelX}" y="${labelY}" width="38" height="20"/><text class="box-text" x="${labelX + 8}" y="${labelY + 14}">${i + 1}</text>${handles}</g>`;
  }).join('');

  let placing = '';
  if (state.placing) {
    const pts = state.placing.points;
    const preview = state.hover ? [...pts, clampPoint(state.hover)] : pts;
    if (preview.length >= 2) {
      placing += `<polyline class="place-line" points="${pointsAttr(preview)}"/>`;
    }
    if (preview.length === 4) {
      placing += `<polygon class="box selected drawing" points="${pointsAttr(preview)}"/>`;
    }
    placing += pts.map(point => `<circle class="place-dot" cx="${point.x}" cy="${point.y}" r="${radius}"/>`).join('');
    if (state.hover && pts.length < 4) {
      const cursor = clampPoint(state.hover);
      placing += `<circle class="place-dot ghost" cx="${cursor.x}" cy="${cursor.y}" r="${radius}"/>`;
    }
  }

  layer.innerHTML = surface + quads + placing;
}

function renderInspector() {
  const list = boxes();
  $('#boxCount').textContent = list.length;
  $('#meterFill').style.width = `${list.length / MAX_BOXES * 100}%`;
  let status = '尚未加载帧';
  if (currentFrame()) {
    if (state.placing) status = `正在点第 ${state.placing.points.length + 1} / 4 个角`;
    else if (list.length === 0) status = '待标注';
    else if (list.length === MAX_BOXES) status = `已满 ${MAX_BOXES} 个四边形`;
    else status = `还可添加 ${MAX_BOXES - list.length} 个四边形`;
  }
  $('#frameState').textContent = status;
  $('#annotationList').innerHTML = !currentFrame()
    ? '<p class="empty-note">选中一帧后，可在画面中标注四边形。</p>'
    : !list.length
      ? '<p class="empty-note">本帧没有标注。<br>拖动可画出初始四边形，或连续点击四个角点。</p>'
      : list.map((box, i) => {
        const bounds = aabb(box.points);
        return `<div class="annotation ${state.selected === box.id ? 'active' : ''}" data-id="${box.id}"><span class="annotation-badge">${i + 1}</span><span>四边形 · ${Math.round(bounds.w)}×${Math.round(bounds.h)}</span><button aria-label="删除此框" data-delete="${box.id}">×</button></div>`;
      }).join('');
  document.querySelectorAll('.annotation').forEach(el => {
    el.onclick = event => {
      if (event.target.dataset.delete) return removeBox(event.target.dataset.delete);
      state.selected = el.dataset.id;
      state.placing = null;
      renderBoxes();
      renderInspector();
    };
  });
}

function pointFromEvent(event) {
  const rect = layer.getBoundingClientRect();
  return {
    x: (event.clientX - rect.left) * image.naturalWidth / rect.width,
    y: (event.clientY - rect.top) * image.naturalHeight / rect.height,
  };
}

function commitQuad(points) {
  if (Math.abs(polygonArea(points)) < MIN_AREA) {
    toast('四边形太小，请重新标注');
    return null;
  }
  const box = { id: uid(), points: clonePoints(points) };
  boxes().push(box);
  state.selected = box.id;
  return box;
}

function addPlacingPoint(point) {
  if (boxes().length >= MAX_BOXES) return toast(`每帧最多只能标注 ${MAX_BOXES} 个框`);
  const next = clampPoint(point);
  if (!state.placing) {
    state.placing = { points: [next] };
  } else {
    state.placing.points.push(next);
  }
  if (state.placing.points.length >= 4) {
    const box = commitQuad(state.placing.points);
    if (!box) {
      state.placing.points.pop();
    } else {
      state.placing = null;
      state.hover = null;
    }
  }
  renderBoxes();
  renderFrames();
  renderInspector();
}

function startAction(event) {
  if (event.button != null && event.button !== 0) return;
  if (!currentFrame() || !image.naturalWidth) return;
  event.preventDefault();
  const point = pointFromEvent(event);

  if (state.placing) {
    addPlacingPoint(point);
    return;
  }

  const vertexFromDom = event.target.dataset.vertex;
  const edgeFromDom = event.target.dataset.edge;
  const hit = hitTest(point);
  const selectedBox = boxes().find(box => box.id === state.selected);

  if (vertexFromDom != null && selectedBox) {
    state.action = { type: 'vertex', index: +vertexFromDom, start: point, points: clonePoints(selectedBox.points) };
  } else if (edgeFromDom != null && selectedBox) {
    state.action = { type: 'edge', index: +edgeFromDom, start: point, points: clonePoints(selectedBox.points) };
  } else if (hit) {
    state.selected = hit.box.id;
    if (hit.vertex != null) {
      state.action = { type: 'vertex', index: hit.vertex, start: point, points: clonePoints(hit.box.points) };
    } else if (hit.edge != null) {
      state.action = { type: 'edge', index: hit.edge, start: point, points: clonePoints(hit.box.points) };
    } else {
      state.action = { type: 'move', start: point, points: clonePoints(hit.box.points) };
    }
  } else {
    if (boxes().length >= MAX_BOXES) return toast(`每帧最多只能标注 ${MAX_BOXES} 个框`);
    state.selected = null;
    state.action = { type: 'draw', start: point, temp: rectPoints(point.x, point.y, 0, 0) };
  }
  layer.setPointerCapture(event.pointerId);
  wrap.style.cursor = cursorForAction(state.action);
  renderBoxes();
  renderInspector();
}

function cursorForAction(action) {
  if (!action) return '';
  if (action.type === 'move' || action.type === 'edge') return 'move';
  if (action.type === 'vertex') return 'grabbing';
  return 'crosshair';
}

function moveAction(event) {
  if (state.placing) {
    state.hover = pointFromEvent(event);
    renderBoxes();
    return;
  }
  if (!state.action) return;
  const point = pointFromEvent(event);
  const action = state.action;
  if (action.type === 'draw') {
    const current = clampPoint(point);
    const x = Math.min(action.start.x, current.x);
    const y = Math.min(action.start.y, current.y);
    const w = Math.abs(current.x - action.start.x);
    const h = Math.abs(current.y - action.start.y);
    action.temp = rectPoints(x, y, w, h);
    renderBoxes();
    layer.insertAdjacentHTML('beforeend', `<polygon class="box selected drawing" points="${pointsAttr(action.temp)}"/>`);
    return;
  }
  const box = boxes().find(item => item.id === state.selected);
  if (!box) return;
  const dx = point.x - action.start.x;
  const dy = point.y - action.start.y;
  if (action.type === 'move') {
    box.points = shiftPoints(action.points, dx, dy);
  } else if (action.type === 'vertex') {
    const next = clonePoints(action.points);
    next[action.index] = clampPoint({
      x: action.points[action.index].x + dx,
      y: action.points[action.index].y + dy,
    });
    box.points = next;
  } else if (action.type === 'edge') {
    const a = action.index;
    const b = (action.index + 1) % 4;
    box.points = shiftPoints(action.points, dx, dy, [a, b]);
  }
  renderBoxes();
  renderInspector();
}

function endAction(event) {
  if (!state.action) return;
  const action = state.action;
  if (action.type === 'draw') {
    const bounds = aabb(action.temp);
    if (bounds.w > MIN_BOX && bounds.h > MIN_BOX) {
      commitQuad(action.temp);
    } else {
      const point = event?.clientX != null ? clampPoint(pointFromEvent(event)) : action.start;
      addPlacingPoint(point);
      state.action = null;
      wrap.style.cursor = '';
      return;
    }
  }
  state.action = null;
  wrap.style.cursor = '';
  renderBoxes();
  renderFrames();
  renderInspector();
}

function cancelPlacing() {
  if (!state.placing) return;
  state.placing = null;
  state.hover = null;
  renderBoxes();
  renderInspector();
}

function removeBox(boxId) {
  if (!currentFrame()) return;
  state.annotations[currentFrame().id] = boxes().filter(box => box.id !== boxId);
  if (state.selected === boxId) state.selected = null;
  renderBoxes();
  renderFrames();
  renderInspector();
}

function go(step) {
  if (!state.frames.length) return;
  state.current = clamp(state.current + step, 0, state.frames.length - 1);
  state.selected = null;
  state.action = null;
  state.placing = null;
  render();
}

function resetPicker(input) {
  input.value = '';
}

layer.addEventListener('pointerdown', startAction);
layer.addEventListener('pointermove', moveAction);
layer.addEventListener('pointerup', endAction);
layer.addEventListener('pointercancel', endAction);

$('#prevBtn').onclick = () => go(-1);
$('#nextBtn').onclick = () => go(1);

$('#framePicker').onchange = event => {
  importImages(event.target.files, { replace: state.replaceNext });
  resetPicker(event.target);
};

$('#folderPicker').onchange = event => {
  importImages(event.target.files, { replace: state.replaceNext });
  resetPicker(event.target);
};

['framePicker', 'folderPicker'].forEach(id => {
  $(`#${id}`).addEventListener('cancel', () => { state.replaceNext = false; });
});

$('#replaceBtn').onclick = () => {
  state.replaceNext = true;
  $('#folderPicker').click();
};

$('#clearFramesBtn').onclick = () => {
  if (!state.frames.length) return;
  if (!confirm('确定清空已加载的图片和标注？')) return;
  revokeFrames(state.frames);
  state.frames = [];
  state.annotations = {};
  state.selected = null;
  state.action = null;
  state.placing = null;
  state.current = -1;
  render();
};

$('#stage').addEventListener('dragover', event => {
  event.preventDefault();
  $('#stage').classList.add('is-drop-target');
});

$('#stage').addEventListener('dragleave', event => {
  if (event.currentTarget.contains(event.relatedTarget)) return;
  $('#stage').classList.remove('is-drop-target');
});

$('#stage').addEventListener('drop', async event => {
  event.preventDefault();
  $('#stage').classList.remove('is-drop-target');
  const files = await filesFromDataTransfer(event.dataTransfer);
  importImages(files);
});

$('#clearAllBtn').onclick = () => {
  if (!state.frames.length) return;
  if (!confirm('确定清空所有帧的标注？')) return;
  state.annotations = {};
  state.selected = null;
  state.placing = null;
  render();
};

$('#exportBtn').onclick = () => {
  if (!state.frames.length) return toast('请先加载拆帧图片');
  const payload = {
    format: 'cube-mark/v2',
    created_at: new Date().toISOString(),
    shape: 'quad',
    max_boxes_per_frame: MAX_BOXES,
    frames: state.frames.map((frame, i) => {
      const current = state.current === i;
      return {
        frame_index: i + 1,
        file_name: frame.name,
        width: frame.width || (current ? image.naturalWidth || null : null),
        height: frame.height || (current ? image.naturalHeight || null : null),
        annotations: (state.annotations[frame.id] || []).map((box, n) => {
          const ordered = orderCorners(box.points);
          const bounds = aabb(ordered);
          return {
            id: n + 1,
            category: 'rubiks_cube',
            quad_xy: ordered.map(point => [Math.round(point.x), Math.round(point.y)]),
            bbox_xywh: ['x', 'y', 'w', 'h'].map(key => Math.round(bounds[key])),
          };
        }),
      };
    }),
  };
  const link = document.createElement('a');
  link.href = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }));
  link.download = 'cube_annotations.json';
  link.click();
  URL.revokeObjectURL(link.href);
  toast('标注 JSON 已导出');
};

document.addEventListener('keydown', event => {
  if (event.key === 'Escape') {
    event.preventDefault();
    cancelPlacing();
    return;
  }
  if (event.key === 'ArrowLeft') {
    event.preventDefault();
    go(-1);
  }
  if (event.key === 'ArrowRight') {
    event.preventDefault();
    go(1);
  }
  if (event.key === 'Delete' || event.key === 'Backspace') {
    const tag = event.target.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA') return;
    event.preventDefault();
    if (state.placing) {
      state.placing.points.pop();
      if (!state.placing.points.length) state.placing = null;
      renderBoxes();
      renderInspector();
      return;
    }
    if (state.selected) removeBox(state.selected);
  }
});
