"""Turns labelled real frames into cube-free backgrounds for the synthetic renderer.

Flat coloured rectangles taught the detector nothing about desks, keyboards and the hand that
holds the cube, which is where its real-frame errors come from. The labelled quads cover the
visible faces, so dilating their union and inpainting it leaves the real scene without the cube.
Only training-split frames are used, so validation frames never leak in as backgrounds.
"""

import argparse
import json
import os

import cv2
import numpy as np


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--real', default='data/real_cubes')
    parser.add_argument('--out', default='data/backgrounds')
    parser.add_argument('--width', type=int, default=360)
    args = parser.parse_args()

    payload = json.load(open(os.path.join(args.real, 'annotations.json'), encoding='utf-8'))
    split = json.load(open(os.path.join(args.real, 'split.json'), encoding='utf-8'))
    train = set(split['train'])
    os.makedirs(args.out, exist_ok=True)
    written = 0
    for frame in payload['frames']:
        if frame['file_name'] not in train or not frame.get('annotations'):
            continue
        image = cv2.imread(os.path.join(args.real, 'images', frame['file_name']))
        if image is None:
            continue
        h, w = image.shape[:2]
        scale = args.width / w
        small = cv2.resize(image, (args.width, int(round(h * scale))), interpolation=cv2.INTER_AREA)
        mask = np.zeros(small.shape[:2], np.uint8)
        for item in frame['annotations']:
            quad = (np.asarray(item['quad_xy'], np.float32) * scale).astype(np.int32)
            cv2.fillConvexPoly(mask, quad, 255)
        # The labelled quads hug the faces; the cube's bevel and shadow sit just outside them.
        side = max(9, int(round(args.width * 0.06)))
        mask = cv2.dilate(mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (side, side)))
        clean = cv2.inpaint(small, mask, 7, cv2.INPAINT_TELEA)
        # Inpainting leaves a smooth smear; noise keeps it from reading as a flat cue.
        noise = np.random.default_rng(written).normal(0, 6, clean.shape)
        clean = np.where(mask[..., None] > 0, np.clip(clean + noise, 0, 255), clean).astype(np.uint8)
        cv2.imwrite(os.path.join(args.out, os.path.splitext(frame['file_name'])[0] + '.png'), clean)
        written += 1
    print(f'wrote {written} backgrounds to {args.out}')


if __name__ == '__main__':
    main()
