# CarPlay return-to-car candidate (#177)

## Scope

Isolated branch `fix/carplay-return-to-car`, based on main `563983895b971fb3f05e314240d4e0f921e2c82d`. No merge, upload, signing, version allocation, device installation or GitHub issue closure is authorized/performed here. Do not mix with another session's EV or permission-retry work without reconciling its owner and source identity.

The receiver publishes OpenAutoLink's existing 192×192 logo and label in /info. A current authenticated session's generic requestUI (absent/empty URL) reaches a coalesced main-thread host dispatcher. That dispatcher backgrounds the registered MainActivity task, without stopping CarPlay transport/audio or changing focus. Reopening uses existing presentation reattachment. Unknown/non-string URLs do nothing; exact videoplayback remains a separate existing path. This URL rule is OAL's bounded policy, not an asserted universal protocol requirement.

The icon is the unchanged `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`, packaged as `carplay-lab/src/main/resources/oal_carplay_home.png` and loaded through the class loader. SHA256 `255a56f4ddd49bc4a767dde90d1a0a76d31516f5cc950e0123a2144029b3ab4e`. Java-resource packaging preserves the receiver's existing API28 USB unit fixtures without making Android binary test resources parse the production minimum SDK32 manifest.

## Verification and evidence

Canonical final readiness/evidence summary: `/hermes_work/.hermes/plans/oal-open-issue-triage/return-tile-readiness.md` (updated by primary after final gates). Raw runs live under `/hermes_work/return-tile-evidence/` and app TDD cycles under `/hermes_work/return-tile-app-evidence/`.

Executed compiling behavioral RED/GREEN covers app dispatch/cancellation/lifecycle, receiver callback, pre-auth host command suppression, unsupported URL suppression and missing production icon. Authenticated command fixtures seed the admitted flag; they do not replace real PairVerify or prove phone behavior. Supplemental wiring guards are explicitly source checks, not real phone integration.

Full debug: app1480 tests (one optional external EV archive replay skip), receiver506 tests, zero failures/errors. Release and artifact readiness must be taken from the final summary, not inferred from debug.

No audio-focus, Bluetooth routing, authentication provisioning, EV schema, vehicle-brand customization or companion behavior changes. Generalized preexisting command/video callback behavior is outside this narrow return callback scope.

## Physical acceptance before calling #177 fixed

While parked, independently for USB and wireless where available:
1. Start CarPlay normally. Confirm the tile shows the OAL logo and OpenAutoLink label.
2. Play music. Tap the tile. Confirm the OEM screen appears and audio/session remain active (unless normal OEM policy intentionally interrupts audio).
3. Reopen OAL from the car launcher. Confirm video/touch return on the SAME session without pairing, reconnect or USB permission flow. Verify audio by hearing it, not only track writes.
4. Repeat a tile tap rapidly; verify no navigation loop. Try from Settings within OAL and after reopening.
5. End/reconnect projection and verify no queued old-session request backgrounds the new UI.
6. Upload logs if tile is blank, tap has no effect, source changes unexpectedly or projection reconnects. Exact phone-emitted nonempty tile URLs remain unverified and are deliberately ignored until sourced.

`moveTaskToBack` is best-effort; the controller latches one action per foreground registration. A platform refusal may require normal foreground re-entry before retry; no forced HOME intents or task finishing were introduced.

## Release copy (draft, only after acceptance)

**Play (short):** Adds an OAL-branded CarPlay button to return to your car's interface without deliberately disconnecting projection.

**GitHub:** Use Unreleased changelog entry and include exact source/test/artifact identity, physical acceptance outcome and limitations. Do not claim this resolves the separate Bluetooth notification interruption or same-iPhone USB/BT pause loop.

## Other open issues

- #143 steering-wheel Previous: existing fix/reporter confirmation, no additional code needed. Closure remains a separate controller action.
- #144 late VHAL permission: owner-confirmed existing fix, no additional code needed. Closure remains a separate controller action.
- #145 forgotten duplicate phone: deferred pending latest supported-path reproduction; normal WPP bypasses the legacy chooser.
- #169/#170 audio coexistence: awaiting fresh owner reproduction/capture. Not included in this candidate.
- #176 launcher customization: already closed not planned; retain OAL branding. Does not close #177's functional request.
