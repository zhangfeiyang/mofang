"""Video benchmark for the scan pipeline: per-frame readings, simulated captures, scoring.

The two demo videos are the only footage with a known cube, so they are the closest thing to a
phone session that can run on a desk. Two steps:

  # 1. Detector + refiner + both samplers on every frame (minutes; cached as a pickle).
  python3 tools/replay_bench.py frames --video video_20260816_204654.mp4 \\
      --model app/src/main/assets/cubeface.onnx --out /tmp/v1.pkl

  # 2. The app's capture policy and stabilizer over those frames, scored against the cube.
  python3 tools/replay_bench.py captures --frames /tmp/v1.pkl \\
      --truth RRRBUFUFDBRDURUBUUBULDFBLLLFBUDDRDFLFRRDLLRBDBDULBFFLF \\
      --out app/src/test/resources/video-captures.json

The capture policy mirrors CubeAnalyzer, CaptureGate and FaceStabilizer: a refined lattice is
used when it agrees with the network's quad (mean sticker-centre shift < 0.4 pitch), the
network's quad when the refiner cannot anchor, nothing when the two disagree; an unrefined look
also needs a sticker-like centre. Two steady frames 66 ms apart make a capture, as at the app's
analysis rate. The truth's colours are learned from the readings themselves (k-means seeded on
the canonical palette), so a capture is "exact" when every readable sticker matches a face of the
true cube at some rotation.

The JSON output is the scan-dump format the JVM tests read (ScanFailReplayTest.load).
"""

import argparse
import json
import pickle
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, __file__.rsplit('/', 1)[0])
import pipeline_sim as ps  # noqa: E402

PRESENCE = 0.6
AGREEMENT = 0.4
CW = [6, 3, 0, 7, 4, 1, 8, 5, 2]
CANONICAL_RGB = {'U': (244, 244, 240), 'R': (233, 67, 53), 'F': (87, 200, 77), 'D': (245, 230, 41),
                 'L': (255, 159, 45), 'B': (57, 116, 217)}


def close_to(prev, quad):
    """CubeAnalyzer.sameFace: the previous lattice is a fair start for this coarse quad."""
    pc, qc = prev.mean(axis=0), quad.mean(axis=0)
    pp = np.mean(np.hypot(*(np.roll(prev, -1, axis=0) - prev).T)) / 3
    qp = np.mean(np.hypot(*(np.roll(quad, -1, axis=0) - quad).T)) / 3
    return np.hypot(*(pc - qc)) < 1.2 * pp and 0.65 < qp / pp < 1.55


def run_frames(args):
    detector = ps.Detector(args.model)
    capture = cv2.VideoCapture(args.video)
    records, prev, prev_age, index = [], None, 99, 0
    started = time.time()
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        rgb = cv2.cvtColor(cv2.resize(frame, (720, 1280), interpolation=cv2.INTER_AREA),
                           cv2.COLOR_BGR2RGB)
        corners, presence = detector(rgb)
        rec = {'frame': index, 'p': presence}
        index += 1
        if presence < PRESENCE or ps.shape_reject(corners, 720, 1280):
            prev_age += 1
            records.append(rec)
            continue
        coarse = ps.order_corners(corners)
        rec['coarse'] = coarse
        rec['legacy'] = ps.legacy_sample(rgb, coarse)[:2]
        refined = None
        if prev is not None and prev_age <= 2 and close_to(prev, coarse):
            refined = ps.refine(rgb, prev)
        if refined is None:
            refined = ps.refine(rgb, coarse)
        if refined is not None:
            rec['quad'] = refined.quad
            rec['lattice'] = ps.lattice_sample(rgb, refined.quad)[:2]
            prev, prev_age = refined.quad, 0
        else:
            prev_age += 1
        records.append(rec)
    pickle.dump(records, open(args.out, 'wb'))
    print(f'{len(records)} frames in {time.time() - started:.0f}s -> {args.out}')


def policy_sample(rec):
    """The reading the app would hand to the stabilizer for this frame, or None."""
    if 'coarse' not in rec:
        return None
    if 'quad' in rec:
        if ps.centre_error(rec['quad'], rec['coarse']) < AGREEMENT:
            return rec['lattice']
        return None                                    # disputed
    lab, reliable = rec['legacy']
    centre = lab[4]
    chroma = float(np.hypot(centre[1], centre[2]))
    if chroma >= 40 or (centre[0] >= 68 and chroma <= 22):
        return rec['legacy']
    return None


def aligned(reference, lab, reliable):
    best, out = None, (lab, reliable)
    for _ in range(4):
        cost = float(np.sqrt(((reference - lab) ** 2).sum(1)).sum())
        if best is None or cost < best:
            best, out = cost, (lab, reliable)
        lab, reliable = lab[CW], reliable[CW]
    return out


class Stabilizer:
    """FaceStabilizer(2) with its 14-unit drift tolerance and two tolerated misses."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.canon, self.stable, self.miss, self.emitted = None, 0, 0, False
        self.total = self.count = None

    def push(self, sample):
        if sample is None or not ps.usable(sample[1]):
            self.miss += 1
            if self.miss > 2:
                self.reset()
            return None
        self.miss = 0
        lab, reliable = sample
        if self.canon is not None:
            al, ar = aligned(self.canon[0], lab, reliable)
            both = self.canon[1] & ar
            still = bool((np.sqrt(((self.canon[0] - al) ** 2).sum(1))[both] <= 14).all())
        else:
            still = False
        if not still:
            self.canon, self.stable, self.emitted = (lab, reliable), 1, False
            self.total = np.where(reliable[:, None], lab, 0).astype(np.float64)
            self.count = reliable.astype(int)
            return None
        self.stable += 1
        self.total += np.where(ar[:, None], al, 0)
        self.count += ar.astype(int)
        if not self.emitted and self.stable >= 2:
            self.emitted = True
            mean = np.where(self.count[:, None] > 0, self.total / np.maximum(self.count, 1)[:, None], al)
            return mean.astype(np.float32), self.count > 0
        return None


def learn_palette(records):
    centroids = ps.lab_from_rgb(np.array([CANONICAL_RGB[k] for k in 'URFDLB'], np.float32))
    points = []
    for rec in records:
        for key in ('lattice', 'legacy'):
            if key in rec:
                lab, reliable = rec[key]
                points.extend(lab[reliable])
    points = np.array(points)
    for _ in range(30):
        label = ((points[:, None, :] - centroids[None]) ** 2).sum(-1).argmin(1)
        for k in range(6):
            if (label == k).any():
                centroids[k] = points[label == k].mean(0)
    return centroids


def mismatches(lab, reliable, palette, faces):
    names = ['URFDLB'[int(((palette - cell) ** 2).sum(1).argmin())] for cell in lab]
    best = 9
    for face in faces:
        for _ in range(4):
            if names[4] == face[4]:
                best = min(best, sum(1 for i in range(9) if reliable[i] and names[i] != face[i]))
            face = ''.join(face[CW[i]] for i in range(9))
    return best


def run_captures(args):
    records = pickle.load(open(args.frames, 'rb'))[::args.step]
    stabilizer, captures = Stabilizer(), []
    for rec in records:
        out = stabilizer.push(policy_sample(rec))
        if out is not None:
            captures.append((rec['frame'], out))
            stabilizer.reset()                 # MainActivity re-arms after every capture
    print(f'{len(captures)} captures')
    names = ['WHITE', 'RED', 'GREEN', 'YELLOW', 'ORANGE', 'BLUE']
    palette = learn_palette(records)
    if args.truth:
        faces = [args.truth[i * 9:(i + 1) * 9] for i in range(6)]
        errors = [mismatches(lab, rel, palette, faces) for _, (lab, rel) in captures]
        exact = sum(1 for e in errors if e == 0)
        wrong = sum(1 for e in errors if e >= 2)
        print(f'exact {exact}, off by one {len(errors) - exact - wrong}, wrong (2+) {wrong} '
              f'({wrong / max(1, len(errors)):.1%})')
    if args.out:
        with open(args.out, 'w') as out:
            out.write('[\n')
            for i, (frame, (lab, reliable)) in enumerate(captures):
                centre = names[int(((palette - lab[4]) ** 2).sum(1).argmin())]
                out.write('  {"confidence": 0.9, "center": "%s", "lab": [%s], "reliable": [%s], '
                          '"frame": %d}%s\n' % (
                              centre, ', '.join('[%.4f, %.4f, %.4f]' % tuple(x) for x in lab),
                              ', '.join('true' if b else 'false' for b in reliable), frame,
                              ',' if i < len(captures) - 1 else ''))
            out.write(']\n')
        print(f'wrote {args.out}')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    frames = sub.add_parser('frames')
    frames.add_argument('--video', required=True)
    frames.add_argument('--model', default='app/src/main/assets/cubeface.onnx')
    frames.add_argument('--out', required=True)
    caps = sub.add_parser('captures')
    caps.add_argument('--frames', required=True)
    caps.add_argument('--truth', default=None, help='54-facelet URFDLB state of the filmed cube')
    caps.add_argument('--step', type=int, default=2, help='video frames per analysed frame')
    caps.add_argument('--out', default=None)
    args = parser.parse_args()
    run_frames(args) if args.command == 'frames' else run_captures(args)


if __name__ == '__main__':
    main()
