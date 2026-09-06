"""Loads packed Cube Mark photos and applies the same 160x288 stretch the app uses.

A frame may have several labelled faces of the same cube. The deployed network
returns one quadrilateral, so the training target is the largest face — the one
pointing most directly at the camera. Unlabelled frames are skipped: they still
contain a cube and would poison the presence head if treated as negatives.

Negatives come from 9:16 crops that miss every labelled quad (table, keyboard,
hands). Geometric jitter is applied in pixel space, then the image is stretched
to the ONNX input size so train-time geometry matches CameraX inference.
"""

import json
import os

import cv2
import numpy as np
import torch

INPUT_WIDTH = 160
INPUT_HEIGHT = 288


def quad_area(quad):
    pts = np.asarray(quad, dtype=np.float64)
    x, y = pts[:, 0], pts[:, 1]
    return abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))) / 2.0


def order_corners(pts):
    pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
    centre = pts.mean(axis=0)
    angles = np.arctan2(pts[:, 1] - centre[1], pts[:, 0] - centre[0])
    ordered = pts[np.argsort(angles)]
    start = int(np.argmin(ordered[:, 0] + ordered[:, 1]))
    return np.array([ordered[(start + i) % 4] for i in range(4)], dtype=np.float32)


def largest_quad(annotations):
    if not annotations:
        return None
    best = max(annotations, key=lambda item: quad_area(item['quad_xy']))
    return np.asarray(best['quad_xy'], dtype=np.float32)


def load_packed(root):
    payload = json.loads(open(os.path.join(root, 'annotations.json'), encoding='utf-8').read())
    split = json.loads(open(os.path.join(root, 'split.json'), encoding='utf-8').read())
    images_dir = os.path.join(root, 'images')
    by_name = {frame['file_name']: frame for frame in payload['frames']}
    records = []
    for frame in payload['frames']:
        records.append({
            'path': os.path.join(images_dir, frame['file_name']),
            'file_name': frame['file_name'],
            'width': frame.get('width'),
            'height': frame.get('height'),
            'annotations': frame.get('annotations') or [],
        })
    return records, split, by_name


def aabb(quad):
    xs, ys = quad[:, 0], quad[:, 1]
    return float(xs.min()), float(ys.min()), float(xs.max()), float(ys.max())


def intersects(a, b):
    return a[0] < b[2] and a[2] > b[0] and a[1] < b[3] and a[3] > b[1]


class RealCubeDataset(torch.utils.data.Dataset):
    def __init__(self, root, names, train, width=INPUT_WIDTH, height=INPUT_HEIGHT):
        records, _, by_name = load_packed(root)
        lookup = {row['file_name']: row for row in records}
        self.rows = [lookup[name] for name in names if name in lookup]
        self.train = train
        self.out_w = width
        self.out_h = height

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, index):
        row = self.rows[index]
        image = cv2.imread(row['path'], cv2.IMREAD_COLOR)
        if image is None:
            raise FileNotFoundError(row['path'])
        quads = [np.asarray(item['quad_xy'], dtype=np.float32) for item in row['annotations']]
        present = 1.0 if quads else 0.0
        primary = largest_quad(row['annotations'])

        if self.train:
            image, primary, present = augment(image, quads, primary, present)

        height, width = image.shape[:2]
        image = cv2.resize(image, (self.out_w, self.out_h), interpolation=cv2.INTER_AREA)
        if present > 0.5 and primary is not None:
            corners = primary.copy()
            corners[:, 0] *= self.out_w / width
            corners[:, 1] *= self.out_h / height
            corners[:, 0] /= self.out_w
            corners[:, 1] /= self.out_h
            corners = order_corners(corners)
        else:
            corners = np.zeros((4, 2), dtype=np.float32)
            present = 0.0

        rgb = np.ascontiguousarray(image[:, :, ::-1]).astype(np.float32) / 255.0
        if self.train:
            rgb = photometric(rgb)
        tensor = torch.from_numpy(rgb).permute(2, 0, 1)
        return tensor, torch.from_numpy(corners.reshape(8)), torch.tensor([present], dtype=torch.float32)


def augment(image, quads, primary, present):
    rng = np.random
    height, width = image.shape[:2]

    # Rare, and only from regions that miss every labelled face. Too many of these
    # taught the presence head to key on desk/keyboard instead of the cube.
    if present > 0.5 and rng.rand() < 0.05:
        negative = negative_crop(image, quads)
        if negative is not None:
            return negative, None, 0.0

    if rng.rand() < 0.5:
        image = image[:, ::-1].copy()
        if primary is not None:
            primary = primary.copy()
            primary[:, 0] = width - 1 - primary[:, 0]
        quads = [q.copy() for q in quads]
        for quad in quads:
            quad[:, 0] = width - 1 - quad[:, 0]

    angle = rng.uniform(-16, 16)
    scale = rng.uniform(0.88, 1.12)
    tx = rng.uniform(-0.06, 0.06) * width
    ty = rng.uniform(-0.06, 0.06) * height
    matrix = cv2.getRotationMatrix2D((width / 2.0, height / 2.0), angle, scale)
    matrix[0, 2] += tx
    matrix[1, 2] += ty
    image = cv2.warpAffine(image, matrix, (width, height), flags=cv2.INTER_LINEAR,
                           borderMode=cv2.BORDER_REFLECT_101)
    if primary is not None:
        primary = transform_points(primary, matrix)
    quads = [transform_points(quad, matrix) for quad in quads]

    if present > 0.5 and primary is not None and rng.rand() < 0.45:
        cropped = zoom_crop(image, primary)
        if cropped is not None:
            image, primary = cropped

    return image, primary, present


def transform_points(points, matrix):
    pts = np.hstack([points, np.ones((len(points), 1), dtype=np.float32)])
    return (pts @ matrix.T).astype(np.float32)


def zoom_crop(image, primary):
    height, width = image.shape[:2]
    zoom = np.random.uniform(0.72, 1.0)
    crop_h = int(round(height * zoom))
    crop_w = int(round(width * zoom))
    # Keep the 9:16 canvas the app feeds the network.
    crop_w = max(32, min(width, int(round(crop_h * width / height))))
    crop_h = max(32, min(height, crop_h))
    x1, y1, x2, y2 = aabb(primary)
    margin_x = 0.08 * (x2 - x1 + 1)
    margin_y = 0.08 * (y2 - y1 + 1)
    min_x = int(np.clip(x2 + margin_x - crop_w, 0, max(0, width - crop_w)))
    max_x = int(np.clip(x1 - margin_x, 0, max(0, width - crop_w)))
    min_y = int(np.clip(y2 + margin_y - crop_h, 0, max(0, height - crop_h)))
    max_y = int(np.clip(y1 - margin_y, 0, max(0, height - crop_h)))
    if max_x < min_x or max_y < min_y:
        return None
    x = np.random.randint(min_x, max_x + 1)
    y = np.random.randint(min_y, max_y + 1)
    crop = image[y:y + crop_h, x:x + crop_w]
    if crop.size == 0:
        return None
    shifted = primary.copy()
    shifted[:, 0] -= x
    shifted[:, 1] -= y
    return crop, shifted


def negative_crop(image, quads):
    height, width = image.shape[:2]
    boxes = [aabb(quad) for quad in quads]
    boxes = [(x1 - 12, y1 - 12, x2 + 12, y2 + 12) for x1, y1, x2, y2 in boxes]
    for _ in range(10):
        zoom = np.random.uniform(0.32, 0.55)
        crop_h = int(round(height * zoom))
        crop_w = int(round(crop_h * width / height))
        if crop_w >= width or crop_h >= height:
            continue
        x = np.random.randint(0, width - crop_w + 1)
        y = np.random.randint(0, height - crop_h + 1)
        window = (x, y, x + crop_w, y + crop_h)
        if any(intersects(window, box) for box in boxes):
            continue
        crop = image[y:y + crop_h, x:x + crop_w]
        if crop.size:
            return crop
    return None


def photometric(rgb):
    rgb = rgb * np.random.uniform(0.82, 1.18, (1, 1, 3)).astype(np.float32)
    rgb = rgb + np.random.uniform(-0.07, 0.07)
    if np.random.rand() < 0.3:
        k = int(np.random.choice([3, 5]))
        rgb = cv2.GaussianBlur(rgb, (k, k), 0)
    if np.random.rand() < 0.22:
        noise = np.random.normal(0, 0.025, rgb.shape).astype(np.float32)
        rgb = rgb + noise
    if np.random.rand() < 0.2:
        u8 = np.clip(rgb * 255, 0, 255).astype(np.uint8)
        quality = int(np.random.randint(35, 85))
        ok, encoded = cv2.imencode('.jpg', cv2.cvtColor(u8, cv2.COLOR_RGB2BGR),
                                   [int(cv2.IMWRITE_JPEG_QUALITY), quality])
        if ok:
            decoded = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
            rgb = cv2.cvtColor(decoded, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    return np.clip(rgb, 0, 1).astype(np.float32)
