import json
import os

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))

router = APIRouter()


def _catalog_path() -> str:
    return os.getenv("AR_DEMO_CATALOG", os.path.join(REPO, "control-python", "demos.json"))


def _root() -> str:
    return os.getenv("AR_DEMO_ROOT", REPO)


def _load() -> list[dict]:
    with open(_catalog_path(), encoding="utf-8") as f:
        return json.load(f)["demos"]


def _file(rel: str | None) -> str | None:
    if not rel:
        return None
    p = os.path.join(_root(), rel)
    return p if os.path.isfile(p) else None


# only ids from the catalog are served so no path ever comes from the client
def _find(demo_id: str) -> dict:
    for d in _load():
        if d["id"] == demo_id:
            return d
    raise HTTPException(404, f"no demo '{demo_id}'")


# missing videos are listed as unavailable instead of failing the whole list
@router.get("/demos")
def list_demos():
    out = []
    for d in _load():
        video = _file(d.get("video"))
        out.append({
            "id": d["id"], "title": d["title"], "title_zh": d.get("title_zh", ""),
            "crop": d.get("crop", "-"), "mode": d.get("mode", "inspect"),
            "file": os.path.basename(d["video"]),
            "size_bytes": os.path.getsize(video) if video else 0,
            "has_timeline": _file(d.get("timeline")) is not None,
            "available": video is not None,
        })
    return out


@router.get("/demos/{demo_id}/video")
def demo_video(demo_id: str):
    d = _find(demo_id)
    video = _file(d.get("video"))
    if video is None:
        raise HTTPException(404, f"video of '{demo_id}' is not on this server")
    return FileResponse(video, media_type="video/mp4", filename=os.path.basename(video))


@router.get("/demos/{demo_id}/timeline")
def demo_timeline(demo_id: str):
    d = _find(demo_id)
    tl = _file(d.get("timeline"))
    if tl is None:
        raise HTTPException(404, f"'{demo_id}' has no timeline")
    with open(tl, encoding="utf-8") as f:
        return json.load(f)
