"""Focused source contracts; behavioral bridge/AEAD tests accompany these."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
def text(path):
    return (ROOT / path).read_text(encoding="utf-8-sig")

def test_both_real_runtimes_freeze_declaration_and_send_exact_session():
    for name in ("OalCarPlayRuntime", "OalUsbCarPlayRuntime"):
        source = text(f"app/src/main/java/com/openautolink/app/carplay/{name}.kt")
        assert "drivingRestrictions = " in source and "restrictionPolicy.enabled" in source
        assert "restrictionPolicy = " in source
        assert "session::setLimitedUIIfEventReady" in source

def test_declaration_is_opt_in_initially_restricted_and_display_scoped():
    config = text("carplay-lab/src/main/java/com/shilapi/xcertplay/airplay/AirPlayConfig.kt")
    info = text("carplay-lab/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt")
    assert "val drivingRestrictions: Boolean = false" in config
    assert 'entry["limitedUI"] = true' in info
    assert 'entry["limitedUIElements"]' in info
    assert '"softKeyboard"' in info and '"softPhoneKeypad"' in info

def test_real_status_read_lane_covers_actual_gear_without_altering_aa_override():
    source = text("app/src/main/java/com/openautolink/app/input/VehicleDataForwarderImpl.kt")
    assert 'CARPLAY_STATUS_PROPERTIES = CARPLAY_EV_PROPERTIES + "GEAR_SELECTION"' in source
    assert 'statusReadElapsedMs =' in source
    bridge = ROOT / "app/src/main/java/com/openautolink/app/carplay/CarPlayDrivingRestrictionBridge.kt"
    assert bridge.exists(), "No source-qualified driving restriction bridge"
    policy = bridge.read_text()
    assert "gearRaw != 4" in policy
    assert '"user_override"' in policy and ".driving" not in policy
    assert "AppPreferences" not in policy
