import unittest
import numpy as np
from make_audio_stress_set import perturb


class AudioStressTests(unittest.TestCase):
    def setUp(self):
        self.rate = 16000
        self.audio = (.05 * np.sin(2 * np.pi * 220 * np.arange(self.rate) / self.rate)).astype(np.float32)

    def test_quiet_condition_does_not_get_normalized_back_up(self):
        changed = perturb(self.audio, self.rate, "quiet_24db", 3)
        ratio = np.sqrt(np.mean(changed ** 2) / np.mean(self.audio ** 2))
        self.assertAlmostEqual(20 * np.log10(ratio), -24, places=4)

    def test_noise_is_deterministic_at_requested_snr(self):
        changed = perturb(self.audio, self.rate, "noise_10db", 42)
        self.assertTrue(np.array_equal(changed, perturb(self.audio, self.rate, "noise_10db", 42)))
        snr = 10 * np.log10(np.mean(self.audio ** 2) / np.mean((changed - self.audio) ** 2))
        self.assertAlmostEqual(snr, 10, places=4)

    def test_room_keeps_tail_and_finite_samples_without_clipping(self):
        changed = perturb(self.audio, self.rate, "room_350ms", 42)
        self.assertEqual(len(changed), len(self.audio) + int(self.rate * .35) - 1)
        self.assertTrue(np.isfinite(changed).all())
        self.assertLessEqual(np.abs(changed).max(), 1)

    def test_invalid_audio_is_rejected(self):
        for audio in (np.array([]), np.array([np.nan]), np.zeros((2, 3))):
            with self.assertRaises(ValueError):
                perturb(audio, self.rate, "quiet_12db", 1)


if __name__ == "__main__":
    unittest.main()
