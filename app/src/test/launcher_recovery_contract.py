"""Source wiring guards; not Activity/Compose/OEM launcher execution proof.

Run without Gradle: python -m unittest discover -s app/src/test -p launcher_recovery_contract.py
Intent behavior is covered separately by LauncherRecoveryTest (Robolectric).
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / "src/main/java/com/openautolink/app"


class LauncherRecoveryContract(unittest.TestCase):
    def source(self, path):
        source = ROOT / path
        self.assertTrue(source.exists(), f"Missing recovery implementation: {path}")
        return source.read_text()

    def test_classifier_requires_both_launcher_parts_and_saved_state_wins(self):
        helper = self.source("LauncherRecovery.kt")
        self.assertIn("intent?.action == Intent.ACTION_MAIN", helper)
        self.assertIn("intent.hasCategory(Intent.CATEGORY_LAUNCHER)", helper)
        self.assertIn("savedInstanceState?.getBoolean(SAVED_VISIBLE) ?: false", helper)
        self.assertIn("visible || isLauncherIntent(intent)", helper)

    def test_activity_owns_visibility_and_only_create_and_new_intent_classify(self):
        activity = self.source("MainActivity.kt")
        self.assertIn("private var launcherRecoveryVisible by mutableStateOf(false)", activity)
        self.assertIn("outState.putBoolean(LauncherRecovery.SAVED_VISIBLE, launcherRecoveryVisible)", activity)
        self.assertIn("launcherRecoveryVisible = LauncherRecovery.onCreate(intent, savedInstanceState)", activity)
        self.assertIn("launcherRecoveryVisible = LauncherRecovery.onNewIntent(launcherRecoveryVisible, intent)", activity)
        self.assertIn("setIntent(intent)", activity)
        self.assertIn("launcherRecoveryVisible = launcherRecoveryVisible", activity)
        self.assertIn("onLauncherRecoveryDismiss = { launcherRecoveryVisible = false }", activity)
        resume = activity.split("override fun onResume()", 1)[1].split("override fun onPause()", 1)[0]
        self.assertNotIn("launcherRecovery", resume)
        self.assertIn("onSystemWake()", resume)

    def test_dialog_keeps_navhost_mounted_and_uses_only_ui_actions(self):
        nav = self.source("ui/navigation/AppNavHost.kt")
        self.assertIn("launcherRecoveryVisible: Boolean = false", nav)
        self.assertIn("onLauncherRecoveryDismiss: () -> Unit = {}", nav)
        self.assertIn("if (launcherRecoveryVisible)", nav)
        recovery = nav.split("if (launcherRecoveryVisible)", 1)[1]
        self.assertIn("OalAlertDialog(", recovery)
        self.assertIn("onDismissRequest = onLauncherRecoveryDismiss", recovery)
        for label in ("Open Settings", "Restore button positions", "Continue projection"):
            self.assertIn(f'Text("{label}")', recovery)
        self.assertIn("navController.navigate(AppDestinations.SETTINGS)", recovery)
        self.assertIn("launchSingleTop = true", recovery)
        confirm = recovery.split("confirmButton =", 1)[1].split("dismissButton =", 1)[0]
        self.assertIn("navController.popBackStack(AppDestinations.PROJECTION, inclusive = false)", confirm)
        self.assertIn("onLauncherRecoveryDismiss()", confirm)
        self.assertIn("OverlayButtonPositionStore.resetAll(overlayPositionPrefs)", recovery)
        for forbidden in ("saveAndReconnect", "stop(", "reconnectCarPlay", "finish(", "pointerInput", "key(launcherRecovery"):
            self.assertNotIn(forbidden, recovery)
        self.assertLess(nav.index("NavHost("), nav.index("if (launcherRecoveryVisible)"))
        self.assertIn('getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)', nav)
        dialog = self.source("ui/components/OalAlertDialog.kt")
        self.assertIn("OalUiScale {", dialog)
        self.assertIn("verticalScroll(rememberScrollState())", dialog)


if __name__ == "__main__":
    unittest.main()
