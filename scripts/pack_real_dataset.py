#!/usr/bin/env python3
"""Merge per-video Cube Mark exports into one training set with unique filenames.

Both video folders used the same `frame_000001.jpg` names, so a naive copy would
overwrite. Images are copied as `{video}_{original}` and the JSON is rewritten to
match. Unlabelled frames are kept in the catalog (they still contain a cube and
must not be treated as negatives) but are left out of the train/val split.
"""

import argparse
import json
import os
import shutil
from collections import Counter
from datetime import datetime, timezone

import cv2
import numpy as np


def quad_area(quad):
    pts = np.asarray(quad, dtype=np.float64)
    x, y = pts[:, 0], pts[:, 1]
    return abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))) / 2.0


def largest_quad(annotations):
    if not annotations:
        return None
    return max(annotations, key=lambda item: quad_area(item['quad_xy']))


def collect_videos(frames_root):
    videos = []
    for name in sorted(os.listdir(frames_root)):
        folder = os.path.join(frames_root, name)
        ann_path = os.path.join(folder, 'cube_annotations.json')
        if os.path.isdir(folder) and os.path.isfile(ann_path):
            videos.append((name, folder, ann_path))
    return videos


def draw_preview(records, images_dir, out_path, count=16):
    labelled = [row for row in records if row['annotations']]
    if not labelled:
        return
    step = max(1, len(labelled) // count)
    chosen = labelled[::step][:count]
    tiles = []
    for row in chosen:
        image = cv2.imread(os.path.join(images_dir, row['file_name']))
        if image is None:
            continue
        primary = largest_quad(row['annotations'])
        for item in row['annotations']:
            pts = np.asarray(item['quad_xy'], dtype=np.int32)
            color = (0, 0, 255) if item is primary else (0, 220, 255)
            cv2.polylines(image, [pts], True, color, 4)
        image = cv2.resize(image, (270, 480))
        cv2.putText(image, row['source_video'][-6:], (8, 28),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)
        tiles.append(image)
    if not tiles:
        return
    cols = 4
    rows = int(np.ceil(len(tiles) / cols))
    h, w = tiles[0].shape[:2]
    canvas = np.full((rows * h, cols * w, 3), 30, np.uint8)
    for i, tile in enumerate(tiles):
        r, c = divmod(i, cols)
        canvas[r * h:(r + 1) * h, c * w:(c + 1) * w] = tile
    cv2.imwrite(out_path, canvas)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--frames', default='frames')
    parser.add_argument('--out', default='data/real_cubes')
    parser.add_argument('--val-fraction', type=float, default=0.2)
    args = parser.parse_args()

    videos = collect_videos(args.frames)
    if not videos:
        raise SystemExit(f'no cube_annotations.json under {args.frames}')

    images_dir = os.path.join(args.out, 'images')
    os.makedirs(images_dir, exist_ok=True)

    records = []
    sources = {}
    for video, folder, ann_path in videos:
        payload = json.loads(open(ann_path, encoding='utf-8').read())
        for frame in payload['frames']:
            original = frame['file_name']
            unique = f'{video}_{original}'
            src = os.path.join(folder, original)
            if not os.path.isfile(src):
                raise SystemExit(f'missing image {src}')
            shutil.copy2(src, os.path.join(images_dir, unique))
            records.append({
                'file_name': unique,
                'source_video': video,
                'source_file': original,
                'frame_index': frame.get('frame_index'),
                'width': frame.get('width'),
                'height': frame.get('height'),
                'annotations': frame.get('annotations') or [],
            })
            sources[unique] = {
                'video': video,
                'original': original,
                'annotation_file': os.path.relpath(ann_path),
            }

    labelled = [row for row in records if row['annotations']]
    skipped = [row['file_name'] for row in records if not row['annotations']]
    counts = Counter(len(row['annotations']) for row in records)

    train, val = [], []
    for video, _, _ in videos:
        subset = [row for row in labelled if row['source_video'] == video]
        subset.sort(key=lambda row: (row['frame_index'] is None, row['frame_index'] or 0,
                                     row['source_file']))
        n_val = max(1, int(round(len(subset) * args.val_fraction))) if subset else 0
        val.extend(row['file_name'] for row in subset[-n_val:])
        train_part = subset[:-n_val] if n_val else subset
        train.extend(row['file_name'] for row in train_part)

    merged = {
        'format': 'cube-mark/v2',
        'created_at': datetime.now(timezone.utc).isoformat(),
        'shape': 'quad',
        'source_videos': [video for video, _, _ in videos],
        'notes': (
            'Filenames are prefixed with the source video so copies do not collide. '
            'Unlabelled frames still show a cube and are catalogued but excluded from splits.'
        ),
        'frames': [
            {
                'file_name': row['file_name'],
                'source_video': row['source_video'],
                'source_file': row['source_file'],
                'frame_index': row['frame_index'],
                'width': row['width'],
                'height': row['height'],
                'annotations': row['annotations'],
            }
            for row in records
        ],
    }

    split = {
        'train': train,
        'val': val,
        'unlabelled': skipped,
        'val_fraction': args.val_fraction,
    }

    open(os.path.join(args.out, 'annotations.json'), 'w', encoding='utf-8').write(
        json.dumps(merged, indent=2, ensure_ascii=False))
    open(os.path.join(args.out, 'split.json'), 'w', encoding='utf-8').write(
        json.dumps(split, indent=2))
    open(os.path.join(args.out, 'sources.json'), 'w', encoding='utf-8').write(
        json.dumps(sources, indent=2))

    draw_preview(records, images_dir, os.path.join(args.out, 'preview.jpg'))

    n_quads = sum(len(row['annotations']) for row in records)
    print(f'packed {len(records)} frames ({len(labelled)} labelled, {len(skipped)} unlabelled)')
    print(f'  videos {len(videos)}  quads {n_quads}  boxes/frame {dict(counts)}')
    print(f'  split train {len(train)} / val {len(val)}')
    if skipped:
        print('  unlabelled (excluded from train/val):')
        for name in skipped:
            print(f'    {name}')
    print(f'  wrote {args.out}')


if __name__ == '__main__':
    main()
