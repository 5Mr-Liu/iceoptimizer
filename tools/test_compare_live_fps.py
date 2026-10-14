"""Evidence-gate regressions found in the ordinary-play renderer comparison."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("compare_live_fps", Path(__file__).with_name("compare-live-fps.py"))
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)


class SurvivalViewGateTest(unittest.TestCase):
    def test_equivalent_wrapped_camera_and_small_tick_drift(self):
        a = {"position": "1,80,1", "camera": "179,8"}
        b = {"position": "1.1,80,1", "camera": "-179,8.1"}
        self.assertEqual([], compare.survival_view_warnings(a, b))

    def test_observed_cold_off_run_view_is_rejected(self):
        a = {"position": "518.4473910603976,80,415.6921268495652", "camera": "85.35694,8"}
        b = {"position": "518.363263511703,80,414.3997131391471", "camera": "-3.7249117,8"}
        warnings = compare.survival_view_warnings(a, b)
        self.assertEqual(2, len(warnings))
        self.assertTrue(any("position" in item for item in warnings))
        self.assertTrue(any("camera" in item for item in warnings))

    def test_missing_malformed_or_nonfinite_views_cannot_pass(self):
        valid = {"position": "0,80,0", "camera": "0,0"}
        for invalid in ({}, {"position": "0,80", "camera": "nan,0"},
                        {"position": "inf,80,0", "camera": None}):
            with self.subTest(invalid=invalid):
                self.assertEqual(2, len(compare.survival_view_warnings(valid, invalid)))


class FixedCameraPathTest(unittest.TestCase):
    def view(self, position="513,76,411"):
        return {"render_camera_path": "fixed-v1", "render_camera_anchor": "513,76,411",
                "render_camera_position": position, "render_camera_angles": "-276,17"}

    def test_scripted_camera_ignores_player_pushes_but_rejects_camera_drift(self):
        a, b = self.view(), self.view("513.01,76,411")
        a["position"], b["position"] = "1,80,1", "8,80,4"
        self.assertEqual([], compare.fixed_path_view_warnings(a, b))
        self.assertTrue(compare.fixed_path_view_warnings(a, self.view("513.2,76,411")))

    def test_missing_or_nonfinite_camera_and_changed_route_cannot_pass(self):
        for invalid in ({}, self.view("nan,76,411"), dict(self.view(), render_camera_path="other")):
            self.assertTrue(compare.fixed_path_view_warnings(self.view(), invalid))


if __name__ == "__main__":
    unittest.main()
