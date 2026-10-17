"""Launcher UI source guards, not rendered Activity/OEM execution proof.

Runtime new-intent behavior: MainActivityLauncherEntryTest (Robolectric).
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / "src/main/java/com/openautolink/app"


class LauncherRecoveryContract(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_activity_has_no_automatic_or_restored_launcher_modal(self):
        activity = self.source("MainActivity.kt")
        self.assertNotIn("LauncherRecovery", activity)
        self.assertNotIn("launcherRecovery", activity)
        self.assertIn("AppNavHost()", activity)
        self.assertIn("setIntent(intent)", activity)
        self.assertIn("onSystemWake()", activity)
        self.assertFalse((ROOT / "LauncherRecovery.kt").exists())

    def test_navigation_does_not_gate_projection_with_launcher_controls(self):
        nav = self.source("ui/navigation/AppNavHost.kt")
        for obsolete in ("launcherRecovery", "onLauncherRecoveryDismiss", "Projection controls", "OalAlertDialog"):
            self.assertNotIn(obsolete, nav)
        self.assertIn("startDestination = AppDestinations.PROJECTION", nav)
        self.assertIn("ProjectionScreen(", nav)
        self.assertIn("settingsOverlay =", nav)

    def test_explicit_single_logo_controls_keep_settings_and_position_reset(self):
        projection = self.source("ui/projection/ProjectionScreen.kt")
        self.assertIn("ProjectionOverlayControls(", projection)
        self.assertIn("onSettings = { showSettings = true }", projection)
        self.assertIn("settingsOverlay(", projection)
        controls = self.source("ui/components/ProjectionOverlayControls.kt")
        self.assertIn('OverlayControl("Settings", Icons.Default.Settings, onSettings)', controls)
        self.assertIn('Text("Restore button positions")', controls)
        self.assertIn("OverlayButtonPositionStore.resetAll(prefs)", controls)


if __name__ == "__main__":
    unittest.main()
