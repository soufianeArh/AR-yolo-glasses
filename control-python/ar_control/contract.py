"""
The shared contract (proto/ar/v1/detection.proto), as Python classes.

Generated on first import into ar_control/_gen, so the Python side can never drift from the
.proto the Java scheduler and the C++ workers use.
"""
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_PROTO_DIR = os.path.normpath(os.path.join(_HERE, "..", "..", "proto"))
_PROTO = os.path.join(_PROTO_DIR, "ar", "v1", "detection.proto")
_GEN = os.path.join(_HERE, "_gen")


def _generate():
    from grpc_tools import protoc
    os.makedirs(_GEN, exist_ok=True)
    stamp = os.path.join(_GEN, "ar", "v1", "detection_pb2.py")
    if os.path.exists(stamp) and os.path.getmtime(stamp) >= os.path.getmtime(_PROTO):
        return
    if protoc.main(["protoc", f"-I{_PROTO_DIR}", f"--python_out={_GEN}", _PROTO]) != 0:
        raise RuntimeError(f"protoc failed on {_PROTO}")


_generate()
if _GEN not in sys.path:
    sys.path.insert(0, _GEN)

from ar.v1.detection_pb2 import Detection, FrameResult, ResultStatus  # noqa: E402,F401

STATUS_NAME = {v: k for k, v in ResultStatus.items()}
