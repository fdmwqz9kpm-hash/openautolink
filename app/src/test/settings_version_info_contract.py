"""Source wiring guard only; rendered behavior is in SettingsVersionInfoTest.

Run: python -m unittest discover -s app/src/test -p settings_version_info_contract.py
"""
from pathlib import Path
import re
import unittest

SETTINGS = Path(__file__).resolve().parents[2] / "src/main/java/com/openautolink/app/ui/settings"


def info_is_first_scroll_item(source):
    # Bound to the actual connection function, not a similarly named preview/helper.
    connection = source.split("private fun ConnectionTab(", 1)[1]
    return re.match(
        r"[^\n]*\{\s*Column\(\s*modifier = Modifier\s*"
        r"\.fillMaxSize\(\)\s*\.verticalScroll\(rememberScrollState\(\)\),\s*"
        r"\)\s*\{\s*SettingsVersionInfo\(\)",
        connection,
    ) is not None


class SettingsVersionInfoContract(unittest.TestCase):
    def test_initial_connection_page_starts_with_scrollable_version_info(self):
        source = (SETTINGS / "SettingsScreen.kt").read_text()
        self.assertIn("var selectedTab by remember { mutableStateOf(SettingsTab.CONNECTION) }", source)
        self.assertIn("SettingsTab.CONNECTION -> ConnectionTab(viewModel, uiState)", source)
        self.assertTrue(info_is_first_scroll_item(source), "Info must be first inside ConnectionTab's scroll column")
        screen = source.split("private fun ConnectionTab(", 1)[0]
        self.assertNotIn("SettingsVersionInfo()", screen, "Do not add a fixed header above the scroll viewport")

    def test_wiring_guard_rejects_missing_late_or_non_scroll_info(self):
        fixture = """private fun ConnectionTab(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsVersionInfo()
        SectionHeader("Normal connections")
    }
}"""
        self.assertTrue(info_is_first_scroll_item(fixture))
        for mutation in (
            fixture.replace("SettingsVersionInfo()", ""),
            fixture.replace(".verticalScroll(rememberScrollState())", ""),
            fixture.replace("SettingsVersionInfo()", 'SectionHeader("Normal connections")\nSettingsVersionInfo()'),
            fixture.replace("SettingsVersionInfo()", "if (false) { SettingsVersionInfo() }"),
        ):
            self.assertFalse(info_is_first_scroll_item(mutation))


if __name__ == "__main__":
    unittest.main()
