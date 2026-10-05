"""Verify publisher-reference handling and speaker/timestamp selection offline."""
import sys
import json
import tempfile

import numpy as np
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from acquire_evaluation_corpus import clean_reference, eligible_segments, recording_group, build_combined_manifests


class AcquisitionSelectionTests(unittest.TestCase):
    def test_conversation_channels_share_one_recording_group(self):
        self.assertEqual(recording_group("conversation_0"), recording_group("conversation_1"))
        self.assertNotEqual(recording_group("conversation_0"), recording_group("other_0"))

    def test_publisher_style_cleanup_retains_indic_words(self):
        self.assertEqual(clean_reference("[uhh] అది మన యూనివర్సిటీ [university] ।"), "అది మన యూనివర్సిటీ")
        self.assertEqual(clean_reference("హెల్లో (hello) ఎలా ఉన్నారు?"), "హెల్లో ఎలా ఉన్నారు")

    def example(self):
        return {"speaker_id": "anonymous-01", "verbatim": [
            {"speaker_id": 0, "text": "ఇది ఒక వాక్యం", "start": 1., "end": 5.}],
            "normalized": [{"speaker_id": 0, "text": "ఇది ఒక వాక్యం", "start": 1., "end": 5.}]}

    def test_target_speaker_and_human_reference_are_retained(self):
        results = eligible_segments(self.example(), 2, 15)
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["text"], "ఇది ఒక వాక్యం")
        self.assertEqual(results[0]["source_normalized_text"], "ఇది ఒక వాక్యం")
        self.assertEqual(results[0]["start_seconds"], 1.)

    def test_excludes_interlocutor_or_unknown_speaker(self):
        data = self.example()
        data["verbatim"][0]["speaker_id"] = 1
        self.assertEqual(eligible_segments(data, 2, 15), [])
        data = self.example()
        del data["speaker_id"]
        self.assertEqual(eligible_segments(data, 2, 15), [])

    def test_excludes_short_ambiguous_or_misaligned_references(self):
        for change in ("short", "unclear", "misaligned", "empty", "negative"):
            data = self.example()
            if change == "short": data["verbatim"][0]["end"] = 1.5
            if change == "unclear": data["normalized"][0]["text"] += " [unintelligible]"
            if change == "misaligned": data["normalized"][0]["end"] = 7
            if change == "empty": data["normalized"][0]["text"] = "[noise]"
            if change == "negative": data["verbatim"][0]["start"] = -1
            with self.subTest(change=change):
                self.assertEqual(eligible_segments(data, 2, 15), [])


class CombinedManifestTests(unittest.TestCase):
    def make_corpus(self, root, overlap=False):
        for index, split in enumerate(("calibration", "evaluation")):
            directory = root / "te" / split
            directory.mkdir(parents=True)
            np.save(directory / "features.npy", np.full((1, 80, 4), index, dtype=np.float32))
            row = {"language": "te", "text": "ఇది ఒక వాక్యం", "duration_seconds": 1.,
                   "id": f"sample-{index}", "features": "features.npy", "audio": "audio.wav",
                   "speaker_id": "same" if overlap else f"speaker-{index}",
                   "recording_id": f"recording-{index}_0", "audio_sha256": f"audio-{index}"}
            (directory / "manifest.jsonl").write_text(json.dumps(row), encoding="utf-8")
        (root / "te" / "summary.json").write_text("{}")

    def test_paths_are_rebased_and_partial_corpus_is_not_all_22(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_corpus(root)
            result = build_combined_manifests(root)
            self.assertFalse(result["all_22_languages"])
            self.assertFalse(result["release_ready"])
            row = json.loads((root / "evaluation.jsonl").read_text())
            self.assertEqual(row["features"], "te/evaluation/features.npy")
            self.assertEqual(result["evaluation_speakers"], 1)

    def test_global_speaker_leakage_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.make_corpus(root, overlap=True)
            with self.assertRaisesRegex(ValueError, "speaker_id"):
                build_combined_manifests(root)


if __name__ == "__main__":
    unittest.main()
