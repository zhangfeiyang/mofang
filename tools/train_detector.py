"""Trains the cube-face corner detector and exports it to ONNX.

The model answers the one question the geometric detector could not: given a camera frame, where
are the four corners of the face currently pointing at the camera, and is there a cube at all.

Corners come out of a spatial-softmax heatmap rather than a flat regression, because averaging a
heatmap keeps sub-pixel precision that a global-pooled vector throws away. The coordinate grid is
deliberately wider than the image so a corner just outside the frame is still representable.
"""

import argparse
import os
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

# Heatmap coordinates span this range in normalised image space, letting the model place a corner
# slightly outside the frame instead of clamping it to the border.
GRID_MIN, GRID_MAX = -0.25, 1.25


def separable(in_ch, out_ch, stride):
    return nn.Sequential(
        nn.Conv2d(in_ch, in_ch, 3, stride, 1, groups=in_ch, bias=False),
        nn.BatchNorm2d(in_ch), nn.ReLU6(inplace=True),
        nn.Conv2d(in_ch, out_ch, 1, bias=False),
        nn.BatchNorm2d(out_ch), nn.ReLU6(inplace=True))


class CubeFaceNet(nn.Module):
    """
    Encoder-decoder. Placing the corner head directly on early high-resolution features leaves it
    with a receptive field of about 31 pixels, far less than the 60-120 pixels a cube face spans,
    so it cannot tell which corner of which face it is looking at. The encoder goes deep enough to
    see the whole cube and the decoder brings that context back to stride 4 for a precise heatmap.
    """

    def __init__(self, width=1.0):
        super().__init__()
        c = lambda n: max(8, int(n * width))
        self.stem = nn.Sequential(
            nn.Conv2d(3, c(24), 3, 2, 1, bias=False),
            nn.BatchNorm2d(c(24)), nn.ReLU6(inplace=True))            # 80, stride 2
        self.down1 = nn.Sequential(separable(c(24), c(48), 2),
                                   separable(c(48), c(48), 1))        # 40, stride 4
        self.down2 = nn.Sequential(separable(c(48), c(72), 2),
                                   separable(c(72), c(72), 1))        # 20, stride 8
        self.down3 = nn.Sequential(separable(c(72), c(112), 2),
                                   separable(c(112), c(112), 1))      # 10, stride 16
        self.down4 = nn.Sequential(separable(c(112), c(160), 2),
                                   separable(c(160), c(160), 1))      # 5, stride 32

        self.lateral3 = nn.Conv2d(c(112), c(96), 1)
        self.lateral2 = nn.Conv2d(c(72), c(96), 1)
        self.lateral1 = nn.Conv2d(c(48), c(96), 1)
        self.top = nn.Conv2d(c(160), c(96), 1)
        self.smooth = nn.Sequential(separable(c(96), c(96), 1),
                                    separable(c(96), c(96), 1))
        self.heat = nn.Conv2d(c(96), 4, 1)
        self.presence = nn.Linear(c(160), 1)

    def forward(self, x):
        s1 = self.down1(self.stem(x))
        s2 = self.down2(s1)
        s3 = self.down3(s2)
        s4 = self.down4(s3)

        up = self.top(s4)
        up = F.interpolate(up, size=s3.shape[2:], mode='nearest') + self.lateral3(s3)
        up = F.interpolate(up, size=s2.shape[2:], mode='nearest') + self.lateral2(s2)
        up = F.interpolate(up, size=s1.shape[2:], mode='nearest') + self.lateral1(s1)
        heat = self.heat(self.smooth(up))

        corners = soft_argmax(heat)
        presence = self.presence(s4.mean(dim=(2, 3)))
        return corners, presence, heat


def soft_argmax(heat):
    """Turns 4 heatmaps into 4 (x, y) pairs in normalised image space."""
    n, k, h, w = heat.shape
    flat = heat.reshape(n, k, h * w)
    weights = F.softmax(flat, dim=2).reshape(n, k, h, w)
    xs = torch.linspace(GRID_MIN, GRID_MAX, w, device=heat.device, dtype=heat.dtype)
    ys = torch.linspace(GRID_MIN, GRID_MAX, h, device=heat.device, dtype=heat.dtype)
    x = (weights.sum(dim=2) * xs).sum(dim=2)
    y = (weights.sum(dim=3) * ys).sum(dim=2)
    return torch.stack([x, y], dim=2).reshape(n, 8)


def align_cyclically(predicted, target):
    """
    Rotates each target quad to whichever cyclic shift the prediction already matches best.

    The renderer has to write corners in *some* order, and it starts from the top-left-most one.
    That choice jumps to a different physical corner as the cube rolls, so visually identical
    frames carry different label orderings and no model can fit both. Only the cyclic order is
    real information, so the loss is made invariant to where the quad starts. The app re-derives
    its own canonical ordering from the four points anyway.
    """
    n = predicted.shape[0]
    p = predicted.reshape(n, 4, 2)
    t = target.reshape(n, 4, 2)
    shifts = torch.stack([torch.roll(t, k, dims=1) for k in range(4)], dim=1)  # (n, 4, 4, 2)
    cost = (p.unsqueeze(1) - shifts).abs().sum(dim=(2, 3))                     # (n, 4)
    pick = cost.argmin(dim=1)
    return shifts[torch.arange(n, device=predicted.device), pick]


def heatmap_targets(corners, height, width, sigma=1.6):
    """Normalised Gaussian blobs, so each heatmap gets a dense gradient instead of only the
    single scalar that survives soft-argmax. Axes are scaled independently because the camera
    frame, and therefore the feature map, is not square."""
    n = corners.shape[0]
    span = GRID_MAX - GRID_MIN
    xs = torch.arange(width, device=corners.device, dtype=corners.dtype).view(1, 1, 1, width)
    ys = torch.arange(height, device=corners.device, dtype=corners.dtype).view(1, 1, height, 1)
    cx = ((corners[:, :, 0] - GRID_MIN) / span * (width - 1)).reshape(n, 4, 1, 1)
    cy = ((corners[:, :, 1] - GRID_MIN) / span * (height - 1)).reshape(n, 4, 1, 1)
    blobs = torch.exp(-((xs - cx) ** 2 + (ys - cy) ** 2) / (2 * sigma ** 2))
    return blobs / blobs.sum(dim=(2, 3), keepdim=True).clamp_min(1e-8)


def order_corners_batch(pts):
    """Canonical order: clockwise starting from the top-left-most corner, matching the renderer."""
    centre = pts.mean(axis=1, keepdims=True)
    angles = np.arctan2(pts[:, :, 1] - centre[:, :, 1], pts[:, :, 0] - centre[:, :, 0])
    idx = np.argsort(angles, axis=1)
    ordered = np.take_along_axis(pts, idx[:, :, None], axis=1)
    start = np.argmin(ordered[:, :, 0] + ordered[:, :, 1], axis=1)
    rolled = np.stack([np.roll(ordered[i], -start[i], axis=0) for i in range(pts.shape[0])])
    return rolled


class SynthDataset(torch.utils.data.Dataset):
    def __init__(self, root, indices, train):
        self.images = np.load(os.path.join(root, 'images.npy'), mmap_mode='r')
        self.corners = np.load(os.path.join(root, 'corners.npy'), mmap_mode='r')
        self.present = np.load(os.path.join(root, 'present.npy'), mmap_mode='r')
        self.indices = indices
        self.train = train

    def __len__(self):
        return len(self.indices)

    def __getitem__(self, i):
        index = self.indices[i]
        image = np.asarray(self.images[index], dtype=np.float32) / 255.0
        corners = np.asarray(self.corners[index], dtype=np.float32).reshape(4, 2)
        present = float(self.present[index][0])

        if self.train:
            if np.random.rand() < 0.5:                       # horizontal flip
                image = image[:, ::-1]
                corners = corners.copy()
                corners[:, 0] = 1.0 - corners[:, 0]
                if present > 0:
                    corners = order_corners_batch(corners[None])[0]
            # Mild colour jitter on top of what the renderer already varied.
            image = image * np.random.uniform(0.85, 1.15, (1, 1, 3)).astype(np.float32)
            image = image + np.random.uniform(-0.06, 0.06)
            image = np.clip(image, 0, 1)

        image = np.ascontiguousarray(image[:, :, ::-1])       # BGR store -> RGB model input
        tensor = torch.from_numpy(image).permute(2, 0, 1)
        return tensor, torch.from_numpy(corners.reshape(8).copy()), torch.tensor([present])


def evaluate(model, loader, device):
    model.eval()
    total_error, total_present, correct, seen = 0.0, 0, 0, 0
    with torch.no_grad():
        for images, corners, present in loader:
            images, corners, present = images.to(device), corners.to(device), present.to(device)
            predicted, presence, _ = model(images)
            mask = present.squeeze(1) > 0.5
            if mask.any():
                # Scored against the best cyclic shift, matching how the loss is defined and how
                # the app consumes the four points.
                goal = align_cyclically(predicted[mask], corners[mask])
                error = (predicted[mask].reshape(-1, 4, 2) - goal).norm(dim=2)
                total_error += error.mean(dim=1).sum().item()
                total_present += int(mask.sum())
            correct += int(((presence.sigmoid() > 0.5).float() == present).sum())
            seen += images.shape[0]
    model.train()
    return total_error / max(total_present, 1), correct / max(seen, 1)


def usable_indices(root):
    """
    Rows that actually hold a render.

    A generation run that dies part way leaves its remaining rows as zeros, and training on those
    would teach the model that a black frame is a legitimate negative. Rows carrying corners are
    kept outright; the rest are checked for an all-zero image, which only an unwritten row has.
    """
    corners = np.load(os.path.join(root, 'corners.npy'), mmap_mode='r')
    images = np.load(os.path.join(root, 'images.npy'), mmap_mode='r')
    labelled = np.asarray(corners).sum(axis=1) != 0
    candidates = np.flatnonzero(~labelled)
    keep = list(np.flatnonzero(labelled))
    for index in candidates:
        if int(images[index, ::16, ::16].max()) > 0:
            keep.append(int(index))          # a genuine background-only negative
    keep.sort()
    if len(keep) < len(labelled):
        print(f'skipping {len(labelled) - len(keep)} unwritten rows')
    return np.array(keep, dtype=np.int64)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--data', required=True)
    parser.add_argument('--epochs', type=int, default=18)
    parser.add_argument('--batch', type=int, default=128)
    parser.add_argument('--lr', type=float, default=3e-3)
    parser.add_argument('--width', type=float, default=1.0)
    parser.add_argument('--val', type=int, default=3000)
    parser.add_argument('--out', default='models')
    parser.add_argument('--workers', type=int, default=6)
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    usable = usable_indices(args.data)
    total = len(usable)
    rng = np.random.default_rng(0)
    order = usable[rng.permutation(total)]
    val_idx, train_idx = order[:args.val], order[args.val:]
    print(f'{len(train_idx)} train / {len(val_idx)} val on {device}')

    train_loader = torch.utils.data.DataLoader(
        SynthDataset(args.data, train_idx, True), batch_size=args.batch, shuffle=True,
        num_workers=args.workers, pin_memory=True, drop_last=True, persistent_workers=True)
    val_loader = torch.utils.data.DataLoader(
        SynthDataset(args.data, val_idx, False), batch_size=args.batch, shuffle=False,
        num_workers=2, pin_memory=True, persistent_workers=True)

    model = CubeFaceNet(args.width).to(device)
    params = sum(p.numel() for p in model.parameters())
    print(f'{params/1e6:.2f}M parameters')

    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    schedule = torch.optim.lr_scheduler.OneCycleLR(
        optimizer, max_lr=args.lr, total_steps=args.epochs * len(train_loader), pct_start=0.25)
    scaler = torch.amp.GradScaler(device)
    best = float('inf')

    for epoch in range(args.epochs):
        running = 0.0
        for step, (images, corners, present) in enumerate(train_loader):
            images = images.to(device, non_blocking=True)
            corners = corners.to(device, non_blocking=True)
            present = present.to(device, non_blocking=True)
            with torch.amp.autocast(device):
                predicted, presence, heat = model(images)
                presence_loss = F.binary_cross_entropy_with_logits(presence, present)
                mask = present.squeeze(1) > 0.5
                if mask.any():
                    goal = corners[mask]
                    # The first epochs keep the dataset ordering as an anchor; once the heatmap
                    # channels have specialised, the ordering constraint is relaxed.
                    if epoch >= 2:
                        goal = align_cyclically(predicted[mask].float(), goal).reshape(-1, 8)
                    corner_loss = F.smooth_l1_loss(predicted[mask], goal, beta=0.02)
                    target_maps = heatmap_targets(goal.reshape(-1, 4, 2).float(),
                                                  heat.shape[2], heat.shape[3])
                    log_probability = F.log_softmax(
                        heat[mask].float().reshape(goal.shape[0], 4, -1), dim=2)
                    heat_loss = -(target_maps.reshape(goal.shape[0], 4, -1)
                                  * log_probability).sum(dim=2).mean()
                else:
                    corner_loss = predicted.sum() * 0
                    heat_loss = heat.sum() * 0
                loss = corner_loss * 10.0 + heat_loss * 0.15 + presence_loss
            optimizer.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            schedule.step()
            running += loss.item()

        error, accuracy = evaluate(model, val_loader, device)
        print(f'epoch {epoch+1}/{args.epochs} loss={running/len(train_loader):.4f} '
              f'corner_err={error:.4f} presence_acc={accuracy:.4f}', flush=True)
        if error < best:
            best = error
            torch.save({'state': model.state_dict(), 'width': args.width},
                       os.path.join(args.out, 'cubeface.pt'))

    print(f'best mean corner error {best:.4f} of image width')
    export(os.path.join(args.out, 'cubeface.pt'), os.path.join(args.out, 'cubeface.onnx'), args.width)


def export(checkpoint_path, onnx_path, width):
    model = CubeFaceNet(width)
    model.load_state_dict(torch.load(checkpoint_path, map_location='cpu')['state'])
    model.eval()

    class Exportable(nn.Module):
        """Drops the heatmap output so the deployed graph returns just corners and presence."""
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, x):
            corners, presence, _ = self.inner(x)
            return corners, torch.sigmoid(presence)

    dummy = torch.zeros(1, 3, 160, 160)
    # Height and width stay dynamic: the network is fully convolutional, and the camera frame is
    # portrait, so being able to feed the whole frame rather than a square crop matters.
    torch.onnx.export(Exportable(model), dummy, onnx_path,
                      input_names=['image'], output_names=['corners', 'presence'],
                      dynamic_axes={'image': {0: 'batch', 2: 'height', 3: 'width'}},
                      opset_version=17, dynamo=False)
    print(f'exported {onnx_path} ({os.path.getsize(onnx_path)/1e6:.2f} MB)')


if __name__ == '__main__':
    main()
