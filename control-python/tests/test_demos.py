"""Step 8.9: the demo catalog API (list, video download, timeline), on a temporary catalog."""
import json

import pytest
from fastapi.testclient import TestClient

from ar_control.api import app


@pytest.fixture()
def demos(tmp_path, monkeypatch):
    (tmp_path / "v").mkdir()
    (tmp_path / "v" / "a.mp4").write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"x" * 1000)
    (tmp_path / "v" / "a.timeline.json").write_text(json.dumps({"holds": [{"hold_start_s": 0.5, "expected": "Corn rust leaf"}]}))
    (tmp_path / "secret.txt").write_text("not a demo")
    catalog = {"demos": [
        {"id": "a", "title": "Demo A", "crop": "corn", "mode": "inspect", "video": "v/a.mp4", "timeline": "v/a.timeline.json"},
        {"id": "b", "title": "Demo B", "crop": "-", "mode": "stream", "video": "v/missing.mp4"},
    ]}
    (tmp_path / "demos.json").write_text(json.dumps(catalog))
    monkeypatch.setenv("AR_DEMO_CATALOG", str(tmp_path / "demos.json"))
    monkeypatch.setenv("AR_DEMO_ROOT", str(tmp_path))
    return TestClient(app)


def test_list(demos):
    r = demos.get("/demos").json()
    assert [d["id"] for d in r] == ["a", "b"]
    a, b = r
    assert a == {"id": "a", "title": "Demo A", "title_zh": "", "crop": "corn", "mode": "inspect", "file": "a.mp4",
                 "size_bytes": 1012, "has_timeline": True, "available": True}
    assert b["available"] is False and b["size_bytes"] == 0 and b["has_timeline"] is False


def test_video_download(demos):
    r = demos.get("/demos/a/video")
    assert r.status_code == 200
    assert r.headers["content-type"] == "video/mp4"
    assert len(r.content) == 1012 and r.content[4:8] == b"ftyp"


def test_timeline(demos):
    assert demos.get("/demos/a/timeline").json()["holds"][0]["expected"] == "Corn rust leaf"
    assert demos.get("/demos/b/timeline").status_code == 404


def test_unknown_or_missing(demos):
    assert demos.get("/demos/nope/video").status_code == 404
    assert demos.get("/demos/b/video").status_code == 404           # in the catalog, file not on server
    # only catalog ids are served: no way to reach other files
    assert demos.get("/demos/..%2Fsecret.txt/video").status_code == 404
    assert demos.get("/demos/../secret.txt/video").status_code == 404
