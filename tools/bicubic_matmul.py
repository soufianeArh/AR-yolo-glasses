import contextlib

import torch
import torch.nn.functional as F

A = -0.75


# onnx bicubic resize is slow on cpu so it is rewritten as two matmuls
def _cubic1(x):
    return ((A + 2) * x - (A + 3)) * x * x + 1


def _cubic2(x):
    return ((A * x - 5 * A) * x + 8 * A) * x - 4 * A


# weights are computed once per size so the model only does matmuls
def bicubic_matrix(in_size: int, out_size, dtype=torch.float32):
    dst = torch.arange(out_size, dtype=dtype)
    scale = in_size / out_size
    real = (dst + 0.5) * scale - 0.5
    x0 = torch.floor(real)
    t = real - x0
    weights = torch.stack([_cubic2(t + 1), _cubic1(t), _cubic1(1 - t), _cubic2(2 - t)], dim=1)
    idx = (x0.to(torch.int64)[:, None] + torch.arange(-1, 3)[None, :]).clamp(0, in_size - 1)
    onehot = F.one_hot(idx, in_size).to(dtype)
    return (onehot * weights[:, :, None]).sum(dim=1)


def interpolate_bicubic(x, size):
    h_in, w_in = x.shape[-2], x.shape[-1]
    wy = bicubic_matrix(h_in, size[0], x.dtype)
    wx = bicubic_matrix(w_in, size[1], x.dtype)
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
    from transformers.models.yolos import modeling_yolos
    saved = modeling_yolos.nn
    modeling_yolos.nn = _NN()
    try:
        yield
    finally:
        modeling_yolos.nn = saved


if __name__ == "__main__":
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
