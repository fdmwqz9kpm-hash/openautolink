# Microphone diagnostic

This opt-in tool measures the head unit's microphone capture. **It is not a microphone fix or a phone-microphone selector.** It does not send test audio to CarPlay or Android Auto.

## Run a parked test

1. Park safely. End calls, disconnect projection phones, and leave the iPhone unpaired from vehicle Bluetooth. If wireless CarPlay still owns a live session, stop it first. Android Auto must be idle.
2. Open **OAL Settings → Diagnostics → Open Diagnostics Dashboard → Microphone**.
3. Leave the panel visible and check the consent box. An automotive device requires fresh, valid actual Park information; the CarPlay Always-in-Park override does not qualify. A nonautomotive test tablet instead requires explicit stationary confirmation.
4. **Inspect inputs** performs a passive input/capability census without recording. The three separate comparisons each run control → test → control:
   - **Channels:** 24 kHz mono → two channels → mono.
   - **Rate:** mono 24 → 48 → 24 kHz.
   - **Input:** default → the uniquely exposed native named input → default, at 24 kHz mono. An absent/ambiguous input or rejected preference ends the test rather than guessing an ID.
5. Start with **Compare channels** and speak throughout the short comparison. Keep the source and environment unchanged. All comparisons use `VOICE_RECOGNITION`; they do not validate the telephony source.
6. Wait for completion and the summary-storage message, then use the existing **Upload Logs** menu action. **Upload before another comparison: only the latest completed numeric result is retained.** Upload authorization/configuration and internet access are still required.

The microphone permission must be granted to OAL. Non-normal audio mode, system mute, client silencing, unknown recording policy, changed route or changed eligibility aborts the comparison. Leaving the panel or backgrounding OAL cancels it. Each arm has a three-second deadline and the comparison a fifteen-second deadline; a stalled platform call can leave **Stopping** until actual recorder release. OAL does not pretend interruption means release and will not overlap another recorder.

## Interpret results

- Requested, recorder, active-client and device shapes are distinct. Two device channels do not by themselves establish physical stereo microphones or a required channel conversion.
- `No samples captured` is not the same as captured samples containing zeros.
- Nonzero samples establish local signal, not intelligible speech, phone receipt or caller audibility.
- Preferred-input setter acceptance is not proof of actual routing. Read the actual named-route match, stability and comparison with the first arm.
- A route change makes a channel/rate comparison inconclusive. Opposite channel polarity is only a hypothesis until the actual per-channel and sum/difference evidence supports it.
- Empty advertised capability arrays mean unconstrained support by the public API contract, not zero supported formats. Unknown/truncated values remain explicitly unknown/truncated.

## Privacy and scope

No live audio is recorded to a file, played, recognized or transmitted by the tool. It keeps only bounded numeric aggregates, finite classifications and Boolean observations. Device names, addresses, IDs and raw exception text are excluded. The latest completed summary is atomically saved as `microphone_diagnostic.log` in OAL's ordinary log directory for the existing manual uploader; retention and upload selection limits still apply.

Normal capture sources, rates, channels, effects and negotiated CarPlay formats are unchanged when the tool is idle. During testing, a process-wide reservation excludes OAL's normal microphone recorders until actual release. There are no audio-mode, output-device, Bluetooth/SCO or vendor-parameter writes.

A successful tablet test does not prove GM capture or CarPlay cellular-call audio. Final vehicle acceptance still requires isolated car-microphone speech reaching Siri or the far-end caller.
