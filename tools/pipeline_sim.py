"""Offline mirror of the app's per-frame pipeline: CNN corners, lattice refinement, sampling.

Everything here follows the Java implementation closely enough that numbers measured on recorded
footage carry over to the phone (RefinerParityTest pins the two together on the labelled frames):

- ``Detector`` matches ``CubeFaceModel`` (160x288 INTER_AREA stretch, RGB/255, NCHW).
- ``legacy_sample`` matches ``FaceSampler`` on a coarse quad (0.84 inset, 0.10-cell patches).
- ``refine`` / ``lattice_sample`` match ``FaceRefiner`` and ``FaceSampler`` on a refined lattice.

Frames are handled as RGB uint8 arrays at the analysis resolution (720x1280 on the phone).
See tools/replay_bench.py for the video benchmark built on this.
"""

import math

import cv2
import numpy as np

INPUT_WIDTH, INPUT_HEIGHT = 160, 288


# ----------------------------------------------------------------------------- colour

def lab_from_rgb(rgb):
    """sRGB -> CIELAB (D65), same constants and float32 math as Lab.fromRgb."""
    c = np.asarray(rgb, dtype=np.float32) / 255.0
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    r, g, b = lin[..., 0], lin[..., 1], lin[..., 2]
    x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

    def pivot(v):
        return np.where(v > 0.008856, np.cbrt(v), 7.787 * v + 16.0 / 116.0)

    fx, fy, fz = pivot(x), pivot(y), pivot(z)
    return np.stack([116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)], axis=-1).astype(np.float32)


def chroma(lab):
    return np.hypot(lab[..., 1], lab[..., 2])


# ----------------------------------------------------------------------------- detector

class Detector:
    def __init__(self, path):
        import onnxruntime as ort
        self.session = ort.InferenceSession(path, providers=['CPUExecutionProvider'])
        self.input = self.session.get_inputs()[0].name

    def __call__(self, rgb):
        h, w = rgb.shape[:2]
        small = cv2.resize(rgb, (INPUT_WIDTH, INPUT_HEIGHT), interpolation=cv2.INTER_AREA)
        tensor = (small.astype(np.float32) / 255.0).transpose(2, 0, 1)[None]
        corners, presence = self.session.run(None, {self.input: tensor})
        pts = corners[0].reshape(4, 2) * np.array([w, h], dtype=np.float32)
        return pts.astype(np.float64), float(presence[0][0])


def order_corners(pts):
    """FaceSampler.orderCorners: clockwise (image coords) from the top-left-most corner."""
    pts = np.asarray(pts, dtype=np.float64).reshape(4, 2)
    centre = pts.mean(axis=0)
    angles = np.arctan2(pts[:, 1] - centre[1], pts[:, 0] - centre[0])
    ordered = pts[np.argsort(angles, kind='stable')]
    start = int(np.argmin(ordered[:, 0] + ordered[:, 1]))
    return np.array([ordered[(start + i) % 4] for i in range(4)])


def shape_reject(pts, w, h):
    """CubeFaceModel.measure."""
    x, y = pts[:, 0], pts[:, 1]
    area = abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))) / 2.0
    sides = np.hypot(*(np.roll(pts, -1, axis=0) - pts).T)
    shortest, longest = sides.min(), sides.max()
    aspect = 99 if shortest <= 0 else longest / shortest
    if area / (w * h) < 0.006:
        return 'implausible_area'
    if shortest <= 12:
        return 'implausible_short'
    if aspect >= 2.2:
        return 'implausible_aspect'
    return None


# ----------------------------------------------------------------------------- legacy sampler

def _median_lower(values):
    """The histogram median the Java sampler computes: smallest v with 2*cum >= n."""
    s = np.sort(values)
    return int(s[(len(s) - 1) // 2])


def legacy_sample(rgb, corners):
    """Port of the original FaceSampler.sample (inset 0.84, radius 10 in a 300px warp)."""
    corners = np.asarray(corners, dtype=np.float64)
    centre = corners.mean(axis=0)
    src = (centre + 0.84 * (corners - centre)).astype(np.float32)
    dst = np.float32([[0, 0], [300, 0], [300, 300], [0, 300]])
    m = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(rgb, m, (300, 300), flags=cv2.INTER_LINEAR,
                                 borderMode=cv2.BORDER_REPLICATE)
    return _read_cells(warped, 100, 10, 0.45, 46)


def _read_cells(warped, cell, radius, max_dispersion, deviation):
    lab = np.zeros((9, 3), np.float32)
    reliable = np.zeros(9, bool)
    medians = np.zeros((9, 3), np.int32)
    for row in range(3):
        for col in range(3):
            cx, cy = col * cell + cell // 2, row * cell + cell // 2
            patch = warped[cy - radius:cy + radius, cx - radius:cx + radius].reshape(-1, 3)
            med = np.array([_median_lower(patch[:, k]) for k in range(3)])
            dev = np.abs(patch.astype(np.int32) - med[None]).max(axis=1)
            dispersion = float((dev > deviation).mean())
            i = row * 3 + col
            medians[i] = med
            lab[i] = lab_from_rgb(med[None].astype(np.float32))[0]
            plastic = lab[i][0] < 45 and chroma(lab[i]) < 25
            reliable[i] = dispersion <= max_dispersion and not plastic
    return lab, reliable, medians


def usable(reliable):
    """FaceStabilizer.usable on a Lab-carrying sample."""
    return bool(reliable[4]) and int((~reliable).sum()) <= 3


# ----------------------------------------------------------------------------- refinement

class Refinement:
    def __init__(self, quad, matched, pitch, homography):
        self.quad = quad              # (4,2) image coords, lattice boundary (3 pitches wide)
        self.matched = matched        # stickers that anchored the fit
        self.pitch = pitch            # sticker pitch in image px (geometric mean)
        self.homography = homography  # lattice units (0..3) -> image


def _homography_from_quad(quad, size):
    """Maps the unit-lattice square [0,3]^2, expanded by `margin`, onto `quad` (image coords)."""
    src = np.float32([[0, 0], [3, 0], [3, 3], [0, 3]])
    return cv2.getPerspectiveTransform(src, np.float32(quad))


def _apply(h, pts):
    pts = np.asarray(pts, dtype=np.float64).reshape(-1, 2)
    hom = np.hstack([pts, np.ones((len(pts), 1))]) @ np.asarray(h, dtype=np.float64).T
    return hom[:, :2] / hom[:, 2:3]


def warp_lattice(rgb, h_lattice_to_image, margin, size):
    """Samples the lattice square [-margin, 3+margin]^2 into a size x size RGB tile."""
    span = 3 + 2 * margin
    scale = size / span
    # tile px -> lattice units -> image
    t = np.array([[1 / scale, 0, -margin], [0, 1 / scale, -margin], [0, 0, 1]], dtype=np.float64)
    m = np.asarray(h_lattice_to_image, dtype=np.float64) @ t
    tile = cv2.warpPerspective(rgb, m, (size, size), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
                               borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    return tile, scale


def otsu(values):
    hist = np.bincount(values.ravel(), minlength=256).astype(np.float64)
    total = hist.sum()
    if total == 0:
        return 128
    omega = np.cumsum(hist) / total
    mu = np.cumsum(hist * np.arange(256)) / total
    mu_t = mu[-1]
    with np.errstate(divide='ignore', invalid='ignore'):
        sigma = (mu_t * omega - mu) ** 2 / (omega * (1 - omega))
    sigma = np.nan_to_num(sigma)
    return int(np.argmax(sigma))


def sticker_blobs(tile, scale, margin, debug=None):
    """Bright/saturated regions separated by the dark plastic body, as candidate stickers.

    Returns (x, y, area, aspect, fill) per blob in tile pixels.
    """
    size = tile.shape[0]
    v = tile.max(axis=2)
    pitch = scale  # one lattice unit in tile px
    lo, hi = int(round(margin * scale)), int(round((margin + 3) * scale))
    thr = otsu(v[lo:hi, lo:hi])
    thr = int(np.clip(thr, 28, 170))
    raw = (v > thr).astype(np.uint8)
    k = max(1, int(round(pitch * 0.06)))
    mask = cv2.erode(raw, np.ones((2 * k + 1, 2 * k + 1), np.uint8))
    count, labels, stats, cents = cv2.connectedComponentsWithStats(mask, connectivity=4)
    expected = (0.80 * pitch) ** 2
    blobs = []
    ys, xs = np.mgrid[0:size, 0:size]
    for i in range(1, count):
        area = stats[i, cv2.CC_STAT_AREA]
        if area < 0.20 * expected or area > 2.2 * expected:
            continue
        x0, y0, bw, bh = stats[i, 0], stats[i, 1], stats[i, 2], stats[i, 3]
        if x0 == 0 or y0 == 0 or x0 + bw >= size or y0 + bh >= size:
            continue  # cut by the tile edge: its centroid is biased
        sel = labels == i
        px, py = xs[sel].astype(np.float64), ys[sel].astype(np.float64)
        mx, my = px.mean(), py.mean()
        cxx, cyy, cxy = ((px - mx) ** 2).mean(), ((py - my) ** 2).mean(), ((px - mx) * (py - my)).mean()
        tr, det = cxx + cyy, cxx * cyy - cxy * cxy
        disc = math.sqrt(max(tr * tr / 4 - det, 0))
        l1, l2 = tr / 2 + disc, max(tr / 2 - disc, 1e-6)
        aspect = math.sqrt(l1 / l2)
        fill = area / (12 * math.sqrt(l1 * l2))
        if aspect > 2.2 or fill < 0.58:
            continue
        blobs.append((mx, my, float(area), aspect, fill))
    if debug is not None:
        debug['mask'] = mask
        debug['thr'] = thr
    return blobs, raw


def gap_width(raw, a, b):
    """Length of plastic (dark) crossed on the segment between two blob centres, tile px."""
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    steps = max(2, int(length * 2))
    t = (np.arange(steps) + 0.5) / steps
    xs = np.clip(np.round(a[0] + (b[0] - a[0]) * t).astype(int), 0, raw.shape[1] - 1)
    ys = np.clip(np.round(a[1] + (b[1] - a[1]) * t).astype(int), 0, raw.shape[0] - 1)
    return float((raw[ys, xs] == 0).sum()) * length / steps


# Fold/continuation probes along lattice lines. Measured on video 1 they cut per-frame wrong
# reads from 15.6% to 12.5% but changed nothing at the capture level (5.9% vs 6.2%), where the
# stabilizer already drops transient straddles, so the app does not use them.
USE_GAPS = False
W_PRIOR, W_SCALE, W_FOLD, W_CONT = 0.3, 0.6, 3.0, 1.5
FOLD_RATIO, CONT_RATIO = 1.6, 1.35
SHORTLIST = 8


def blob_weight(aspect, area_ratio, resid_ratio, strict):
    """How much one blob counts as a sticker of this lattice, 0..1."""
    a_lo, a_hi = (1.30, 1.75) if strict else (1.55, 2.2)
    w_aspect = np.clip((a_hi - aspect) / (a_hi - a_lo), 0, 1)
    dev = abs(math.log(area_ratio))
    d_lo, d_hi = (0.30, 0.75) if strict else (0.45, 1.0)
    w_area = np.clip((d_hi - dev) / (d_hi - d_lo), 0, 1)
    w_resid = 1 - resid_ratio ** 2
    return float(w_aspect * w_area * w_resid)


def dark_fraction(raw, a, b):
    """Share of the segment a->b (tile px) that crosses plastic, or None if it leaves the tile."""
    h, w = raw.shape
    if not (0 <= a[0] < w - 1 and 0 <= a[1] < h - 1 and 0 <= b[0] < w - 1 and 0 <= b[1] < h - 1):
        return None
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    steps = max(4, int(length))
    t = (np.arange(steps) + 0.5) / steps
    xs = np.round(a[0] + (b[0] - a[0]) * t).astype(int)
    ys = np.round(a[1] + (b[1] - a[1]) * t).astype(int)
    return float((raw[ys, xs] == 0).mean())


def gap_penalties(raw, origin, u, v, oi, oj):
    """
    Fold and continuation evidence for the 3x3 window centred on lattice cell (oi, oj).

    Each lattice line is probed between the cell centres on either side of it. Inside one face
    the plastic there is uniformly thin; across the cube edge it is two borders plus the bevel,
    two to three times wider. A wide inner line means the window folds over an edge; a thin line
    just outside the window means the face continues past it, which no real face does.
    """
    def centre(a, b):
        return origin + a * u + b * v

    inner, outer = [], []
    for axis in (0, 1):
        for line in (-1, 0, 1, 2):
            fracs = []
            for t in (-1, 0, 1):
                if axis == 0:
                    p, q = centre(oi + line - 1, oj + t), centre(oi + line, oj + t)
                else:
                    p, q = centre(oi + t, oj + line - 1), centre(oi + t, oj + line)
                f = dark_fraction(raw, p, q)
                if f is not None:
                    fracs.append(f)
            if len(fracs) >= 2:
                (inner if line in (0, 1) else outer).append(float(np.median(fracs)))
    if len(inner) < 2:
        return 0.0, 0.0
    ref = max(min(inner), 0.04)
    fold = max(0.0, max(inner) / ref - FOLD_RATIO)
    cont = max([0.0] + [CONT_RATIO - f / ref for f in outer])
    return fold, cont


def fit_lattice(blobs, pitch, prior_centre, strict=False, raw=None):
    """
    Finds the 3x3 sticker lattice among blob centres.

    A hypothesis is a blob plus two lattice steps to neighbours one or two pitches away at roughly
    right angles. Every 3x3 window of its 7x7 extension is scored by the summed weight of the blobs
    it explains: a blob counts fully only when it is square, sized like its peers and sits on the
    lattice. A neighbouring face's stickers come out foreshortened after rectification, so a window
    that folds over the cube edge loses that row's weight — which is what separates a true face
    from a window shifted by one row.
    """
    if len(blobs) < 4:
        return None
    pts = np.asarray([b[:2] for b in blobs], dtype=np.float64)
    areas = np.asarray([b[2] for b in blobs])
    aspects = np.asarray([b[3] for b in blobs])
    n = len(pts)
    best = None
    candidates = []
    for c in range(n):
        d = pts - pts[c]
        dist = np.hypot(d[:, 0], d[:, 1])
        steps = []
        for i in range(n):
            if i == c:
                continue
            if 0.6 * pitch < dist[i] < 1.6 * pitch:
                steps.append(d[i])
            elif 1.6 * pitch <= dist[i] < 3.1 * pitch:
                steps.append(d[i] / 2.0)
        for ai in range(len(steps)):
            for bi in range(ai + 1, len(steps)):
                u, v = steps[ai], steps[bi]
                lu, lv = math.hypot(*u), math.hypot(*v)
                if not (0.6 * pitch < lu < 1.6 * pitch and 0.6 * pitch < lv < 1.6 * pitch):
                    continue
                cos = abs(u @ v) / (lu * lv)
                if cos > 0.45 or not (0.6 < lu / lv < 1.67):
                    continue
                basis = np.array([u, v]).T
                coords = np.linalg.solve(basis, d.T).T
                rounded = np.round(coords)
                resid = np.hypot(*((coords - rounded) @ basis.T).T)
                tol = 0.3 * min(lu, lv)
                cell = {}
                for i in range(n):
                    gi, gj = int(rounded[i, 0]), int(rounded[i, 1])
                    if abs(gi) > 3 or abs(gj) > 3 or resid[i] > tol:
                        continue
                    if (gi, gj) not in cell or cell[(gi, gj)][1] > resid[i]:
                        cell[(gi, gj)] = (i, resid[i])
                lattice_pitch = math.sqrt(lu * lv)
                for oi in range(-2, 3):
                    for oj in range(-2, 3):
                        members = [(gi - oi, gj - oj, i, r) for (gi, gj), (i, r) in cell.items()
                                   if abs(gi - oi) <= 1 and abs(gj - oj) <= 1]
                        m = len(members)
                        if m < 4 or (best is not None and m < best[0] - 2.5):
                            continue
                        idx = np.array([mm[2] for mm in members])
                        med = float(np.median(areas[idx]))
                        weights = [blob_weight(aspects[mm[2]], areas[mm[2]] / med, mm[3] / tol, strict)
                                   for mm in members]
                        support = sum(weights)
                        centre = pts[c] + oi * u + oj * v
                        off = math.hypot(*(centre - prior_centre)) / pitch
                        scale_pen = abs(math.log(lattice_pitch / pitch))
                        score = support - W_PRIOR * off - W_SCALE * scale_pen
                        candidates.append((score, members, weights, centre, u, v, support, pts[c], oi, oj))
                        if best is None or score > best[0]:
                            best = (score, None)
    if not candidates:
        return None
    candidates.sort(key=lambda item: -item[0])
    shortlist = []
    for cand in candidates:
        if any(math.hypot(*(cand[3] - other[3])) < 0.3 * pitch for other in shortlist):
            continue
        shortlist.append(cand)
        if len(shortlist) == SHORTLIST:
            break
    best = None
    for score, members, weights, centre, u, v, support, origin, oi, oj in shortlist:
        if raw is not None:
            fold, cont = gap_penalties(raw, origin, u, v, oi, oj)
            score = score - W_FOLD * fold - W_CONT * cont
        if best is None or score > best[0]:
            kept = [mm for mm, w in zip(members, weights) if w > 0.25]
            best = (score, kept, centre, u, v, support)
    if best is None:
        return None
    score, members, centre, u, v, support = best
    src = np.array([[mm[0], mm[1]] for mm in members], np.float64).reshape(-1, 2)
    dst = np.array([pts[mm[2]] for mm in members], np.float64).reshape(-1, 2)
    return src, dst, u, v, score, support


# The app's FaceRefiner: one pitch of margin around the face, a 160 px tile (32 px per pitch).
MARGIN, SIZE = 1.0, 160


def refine(rgb, quad, margin=None, size=None, iterations=3, debug=None):
    """
    Snaps a coarse face quad onto the actual sticker lattice.

    Returns a Refinement whose quad is the lattice boundary (three pitches wide, sticker centres at
    the 1/6, 1/2, 5/6 subdivisions) or None when the stickers could not be found. The later
    iterations run on a tile rectified by the previous fit and must agree with it; a fit that keeps
    moving is a fit that is not anchored on one face.
    """
    margin = MARGIN if margin is None else margin
    size = SIZE if size is None else size
    quad = np.asarray(quad, dtype=np.float64)
    result = None
    previous = None
    for it in range(iterations):
        h = _homography_from_quad(quad, size)
        tile, scale = warp_lattice(rgb, h, margin, size)
        dbg = {} if debug is not None else None
        blobs, raw = sticker_blobs(tile, scale, margin, dbg)
        prior = np.array([(1.5 + margin) * scale] * 2)
        fit = fit_lattice(blobs, scale, prior, strict=it > 0, raw=raw if USE_GAPS else None)
        if debug is not None:
            debug.update(dbg)
            debug['tile'] = tile
            debug['blobs'] = blobs
            debug['fit'] = fit
        if fit is None:
            return None
        src, dst, u, v, score, support = fit
        if len(src) < 5 or support < 4.5:
            return None
        lattice_dst = dst / scale - margin           # tile px -> lattice units of `quad`
        lattice_src = src + 1.5                      # window cells -1..1 -> centres 0.5..2.5
        if len(src) >= 6:
            hm, _ = cv2.findHomography(lattice_src, lattice_dst, 0)
        else:
            a, _ = cv2.estimateAffine2D(lattice_src, lattice_dst, method=cv2.LMEDS)
            hm = None if a is None else np.vstack([a, [0, 0, 1]])
        if hm is None:
            return None
        reproj = _apply(hm, lattice_src)
        rms = float(np.sqrt(np.mean(np.sum((reproj - lattice_dst) ** 2, axis=1))))
        corners_units = _apply(hm, [[0, 0], [3, 0], [3, 3], [0, 3]])
        new_quad = order_corners(_apply(h, corners_units))
        sides = np.hypot(*(np.roll(new_quad, -1, axis=0) - new_quad).T)
        if sides.min() < 9 or sides.max() / max(sides.min(), 1e-6) > 2.6 or rms > 0.2:
            return None
        h_final = _homography_from_quad(new_quad, size)
        result = Refinement(new_quad, len(src), float(np.mean(sides)) / 3.0, h_final)
        result.rms = rms
        result.support = support
        if it > 0 and previous is not None:
            moved = np.hypot(*(lattice_centres(new_quad) - lattice_centres(previous)).T).max()
            result.moved = moved / result.pitch
            if it == iterations - 1 and result.moved > 0.35:
                return None
        previous = new_quad
        quad = new_quad
    return result


def lattice_sample(rgb, quad, radius_fraction=0.22, max_dispersion=0.45, deviation=46, inset=0.92):
    """FaceSampler on a refined lattice: cells read at 0.92 of their offset, 0.22-cell patches."""
    quad = np.asarray(quad, dtype=np.float64)
    centre = quad.mean(axis=0)
    quad = centre + inset * (quad - centre)
    dst = np.float32([[0, 0], [300, 0], [300, 300], [0, 300]])
    m = cv2.getPerspectiveTransform(np.float32(quad), dst)
    warped = cv2.warpPerspective(rgb, m, (300, 300), flags=cv2.INTER_LINEAR,
                                 borderMode=cv2.BORDER_REPLICATE)
    return _read_cells(warped, 100, int(round(100 * radius_fraction)), max_dispersion, deviation)


# ----------------------------------------------------------------------------- metrics

def lattice_centres(quad):
    """The 3x3 subdivision centres of a quad, in row-major order from its first corner."""
    h = cv2.getPerspectiveTransform(np.float32([[0, 0], [3, 0], [3, 3], [0, 3]]), np.float32(quad))
    grid = [[c + 0.5, r + 0.5] for r in range(3) for c in range(3)]
    return _apply(h, grid)


def centre_error(quad, truth):
    """Mean sticker-centre error in pitches, minimised over the cyclic corner order of `quad`."""
    truth_c = lattice_centres(truth)
    t_sides = np.hypot(*(np.roll(truth, -1, axis=0) - truth).T)
    pitch = float(np.mean(t_sides)) / 3.0
    best = float('inf')
    for k in range(4):
        rolled = np.roll(quad, -k, axis=0)
        for flip in (False, True):
            q = rolled[::-1] if flip else rolled
            q = np.roll(q, 1, axis=0) if flip else q
            err = np.hypot(*(lattice_centres(q) - truth_c).T).mean() / pitch
            best = min(best, err)
    return best
