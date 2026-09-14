"""Analyze native ORT outputs when fed production Rust and browser features."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parent / "browser-assets"
REFERENCE = ROOT / "reference"


def decode(model_id, values):
    blank = 5632
    if model_id == "indicconformer-te":
        mask = np.arange(5633)
        tokens = (ROOT / model_id / "tokenizer_te.vocab").read_text().splitlines()[1:]
        tokens = [line.split("\t")[0] for line in tokens]
        token_map = {5121 + index: token for index, token in enumerate(tokens)}
    else:
        masks = json.loads((ROOT / model_id / "language_masks.json").read_text())
        vocab = json.loads((ROOT / model_id / "vocab.json").read_text())["te"]
        mask = np.flatnonzero(masks["te"])
        token_map = dict(zip(mask.tolist(), vocab))
    token_map[blank] = "<blank>"
    restricted = values[0, :, mask].T
    top2 = np.argsort(restricted, axis=1)[:, -2:]
    selected = mask[top2[:, 1]]
    runner_up = mask[top2[:, 0]]
    margin = restricted[np.arange(len(top2)), top2[:, 1]] - restricted[np.arange(len(top2)), top2[:, 0]]
    events = []
    previous = blank
    for frame, prediction in enumerate(selected.tolist()):
        if prediction != blank and prediction != previous:
            token = token_map.get(prediction, "")
            if token and token != "<unk>":
                events.append({"frame": frame, "class": prediction, "token": token,
                               "margin": float(margin[frame]), "runnerUpClass": int(runner_up[frame]),
                               "runnerUpToken": token_map.get(int(runner_up[frame]), "<blank>" if runner_up[frame] == blank else "")})
        previous = prediction
    return {"transcript": "".join(e["token"] for e in events).replace("▁", " ").strip(),
            "events": events}, selected, runner_up, margin, token_map


def main():
    rust_features = np.fromfile(REFERENCE / "telugu-16k-features.f32le", dtype="<f4")
    browser_path = REFERENCE / "browser-features.f32le"
    result = {"featureComparison": None, "models": []}
    if browser_path.exists():
        browser_features = np.fromfile(browser_path, dtype="<f4")
        difference = browser_features.astype(np.float64) - rust_features.astype(np.float64)
        result["featureComparison"] = {"values": len(difference),
            "maxAbsDifference": float(np.max(np.abs(difference))),
            "meanAbsDifference": float(np.mean(np.abs(difference))),
            "rmsDifference": float(np.sqrt(np.mean(difference**2))),
            "fractionExactlyEqual": float(np.mean(difference == 0)),
            "rustMinMax": [float(np.min(rust_features)), float(np.max(rust_features))],
            "browserMinMax": [float(np.min(browser_features)), float(np.max(browser_features))]}
    reports = json.loads((REFERENCE / "native-logits-report.json").read_text())
    for model_id in ["indicconformer-te", "indicconformer-multi"]:
        decoded = {}
        arrays = {}
        details = {}
        for report in reports:
            if report["id"] != model_id:
                continue
            values = np.fromfile(report["logitsFile"], dtype="<f4").reshape(report["shape"])
            source = report["featureSource"]
            decoded[source], *details[source] = decode(model_id, values)
            arrays[source] = values
        entry = {"id": model_id, "decoded": decoded}
        if "rust" in details and "browser" in details:
            rust_ids, rust_second, rust_margins, token_map = details["rust"]
            browser_ids, browser_second, browser_margins, _ = details["browser"]
            changes = np.flatnonzero(rust_ids != browser_ids)
            entry["changedArgmaxFrames"] = [{"frame": int(frame),
                "rustClass": int(rust_ids[frame]), "rustToken": token_map.get(int(rust_ids[frame]), "<blank>"),
                "browserClass": int(browser_ids[frame]), "browserToken": token_map.get(int(browser_ids[frame]), "<blank>"),
                "rustMargin": float(rust_margins[frame]), "browserMargin": float(browser_margins[frame])}
                for frame in changes]
            entry["logitMaxAbsDifference"] = float(np.max(np.abs(arrays["rust"]-arrays["browser"])))
        result["models"].append(entry)
    output = REFERENCE / "browser-feature-comparison.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("FEATURES", json.dumps(result["featureComparison"]))
    for entry in result["models"]:
        print(entry["id"])
        for source, decoded in entry["decoded"].items():
            print(source, decoded["transcript"])
        print("CHANGED", json.dumps(entry.get("changedArgmaxFrames"), ensure_ascii=False))
        print("LOGIT_MAX_DIFF", entry.get("logitMaxAbsDifference"))
    print(output)


if __name__ == "__main__":
    main()
