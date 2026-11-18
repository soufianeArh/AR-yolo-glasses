"""
Bicubic interpolation written as two matrix multiplications - for the plant model's ONNX export.

Why: YOLOS resizes its position embeddings to the input image size on every run, with
torch.nn.functional.interpolate(mode="bicubic", align_corners=False). Exported to ONNX this
becomes a "Resize" (cubic) node that ONNX Runtime runs extremely slowly at this tensor size:
measured 3.6 s of a ~5 s inference, and a single 3.3 s node that also delays aborting a run by
~2 s (ONNX Runtime can only stop between nodes).

Bicubic resizing is separable and linear: out = Wy @ x @ Wx^T, where Wy / Wx depend only on the
input and output sizes. Built here with the exact rules of PyTorch's upsample_bicubic2d
(align_corners=False, A = -0.75, source index = scale * (dst + 0.5) - 0.5, edge pixels repeated),
so the result is the same numbers; ONNX Runtime then runs two small MatMuls instead.

Use:  with matmul_bicubic(): torch.onnx.export(...)   (patches only transformers' YOLOS module)
"""
import contextlib

import torch
import torch.nn.functional as F

A = -0.75


def _cubic1(x):              # |x| <= 1
    return ((A + 2) * x - (A + 3)) * x * x + 1


def _cubic2(x):              # 1 < |x| < 2
    return ((A * x - 5 * A) * x + 8 * A) * x - 4 * A


def bicubic_matrix(in_size: int, out_size, dtype=torch.float32):
    """[out_size, in_size] matrix W so that W @ v resizes v (along that axis) like PyTorch."""
    dst = torch.arange(out_size, dtype=dtype)
    scale = in_size / out_size
    real = (dst + 0.5) * scale - 0.5
    x0 = torch.floor(real)
    t = real - x0
    weights = torch.stack([_cubic2(t + 1), _cubic1(t), _cubic1(1 - t), _cubic2(2 - t)], dim=1)   # [out, 4]
    idx = (x0.to(torch.int64)[:, None] + torch.arange(-1, 3)[None, :]).clamp(0, in_size - 1)    # [out, 4]
    onehot = F.one_hot(idx, in_size).to(dtype)                                                 # [out, 4, in]
    return (onehot * weights[:, :, None]).sum(dim=1)                                           # [out, in]


def interpolate_bicubic(x, size):
    """Same as F.interpolate(x, size=size, mode="bicubic", align_corners=False) for x [N, C, H, W]."""
    h_in, w_in = x.shape[-2], x.shape[-1]
    wy = bicubic_matrix(h_in, size[0], x.dtype)        # [out_h, H]
    wx = bicubic_matrix(w_in, size[1], x.dtype)        # [out_w, W]
    return torch.matmul(torch.matmul(wy, x), wx.transpose(0, 1))


class _Functional:
    def __getattr__(self, name):
        return getattr(F, name)

    @staticmethod
    def interpolate(x, size=None, mode="nearest", align_corners=None, **kw):
        if mode == "bicubic" and align_corners is False and size is not None and not kw:
            return interpolate_bicubic(x, size)
        return F.interpolate(x, size=size, mode=mode, align_corners=align_corners, **kw)


class _NN:
    functional = _Functional()

    def __getattr__(self, name):
        return getattr(torch.nn, name)


@contextlib.contextmanager
def matmul_bicubic():
    """Make transformers' YOLOS code use interpolate_bicubic, only inside this block."""
    from transformers.models.yolos import modeling_yolos
    saved = modeling_yolos.nn
    modeling_yolos.nn = _NN()
    try:
        yield
    finally:
        modeling_yolos.nn = saved


if __name__ == "__main__":
    # Self-check against PyTorch at the sizes the plant model uses (grid 32x54 -> 32..54 x 32..54).
    torch.manual_seed(0)
    worst = 0.0
    for (h, w) in [(32, 54)]:
        x = torch.randn(11, 384, h, w)
        for oh, ow in [(32, 42), (42, 32), (32, 54), (32, 43), (20, 54), (54, 32), (32, 32)]:
            d = (interpolate_bicubic(x, (oh, ow)) - F.interpolate(x, size=(oh, ow), mode="bicubic",
                                                                  align_corners=False)).abs().max().item()
            worst = max(worst, d)
            print(f"  {h}x{w} -> {oh}x{ow}: max abs diff {d:.2e}")
    print("PASS" if worst < 1e-4 else "FAIL", f"(worst {worst:.2e})")
