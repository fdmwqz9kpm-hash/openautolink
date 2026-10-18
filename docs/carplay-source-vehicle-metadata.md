# CarPlay source-backed vehicle metadata

Implementation baseline: `10dc2286cb3944201edfca18ddb1c7251215ac64` (fetched main).
This is a production-source integration, not an EV model simulator or proof of Apple Maps EV routing.

## Contract and scope

The real process VHAL snapshot now feeds one immutable capability/identity receipt per iAP2 owner. USB freezes it **before bootstrap identification** and passes that same receipt into the real `OalCarPlayIntakeLink`; wireless freezes it at exact-link construction. No extra VHAL subscriber, phone reader, worker or lifecycle owner is introduced. Source values arriving later cannot expand that link's offer; reconnect is required.

- `INFO_MAKE`, `INFO_MODEL`, `INFO_MODEL_YEAR`: compose a bounded `year make model` label, with missing/invalid pieces omitted. At least make or model is required. Names go only into **group20 String children6/8** (display/Maps display names). Generic OAL accessory manufacturer/model and component names remain unchanged. Internal model strings such as **C234 stay C234**; there is no marketing-name lookup.
- Static INFO reads must return AVAILABLE status and the expected runtime type before caching. Cached static identity/fuel/connector values are withheld from newly published snapshots while the process source is inactive or CAR_INFO is denied. Existing exact-link identification remains frozen, not rewritten halfway through authentication.
- Engine type **electric2 was already implemented**, qualified by pure-electric VHAL fuel type10. This patch retains that gate. It does not advertise hybrid/combustion telemetry or infer an engine from a name.
- `INFO_EV_CONNECTOR_TYPE`: explicit translation of VHAL1/2/5/6/10/11 to iAP2 2/4/0/1/6/5. Final group20 uses one **repeated U8 child11** per known connector, sorted and deduplicated. No fixed default inventory, implied adapter support, Tesla/NACS mapping or connector power is sent. Unknown and currently unmapped types are omitted.
- `ENV_OUTSIDE_TEMPERATURE`: source-qualified Celsius rounded to whole degrees, advertised as group21 void child4, subscribed with A100 void selector4, sent as A101 **signed I16 field4**. Negative and zero are real values; absent/invalid is omission. It uses the same source/status/registration/receipt validity and confirmed STATIC/ONCHANGE status-read lane as existing energy/range. A discovered CONTINUOUS/unknown mode does not gain an old-source exemption. It remains inside the existing pure-EV vehicle plane.

No dedicated wire fields for vehicle make/model/year were established. Do not reinterpret accessory model identifiers, color or connector-power fields as those values.

## Independent protocol evidence

1. Representative **iPhone16,1 / 24A446** accessoryd, not the owner's exact iPhone firmware: `research/carplay-ios/analysis/24A446/vehicle-data-audit/vehicle-info-identification-jump.json`, `vehicle-identification-a.txt`, `vehicle-identification-b.txt`. Table at `0x100018d20` contains signed offsets from `0x100167888`:
   - child6 -> `0x100167ae0`, CFString, error label `displayName`;
   - child8 -> `0x100167b18`, CFString, `mapDisplayName`;
   - child11 -> `0x100167a1c`, U8 getter and CFArrayAppendValue at `0x100167c80`;
   - child10 is **vehicleColorHexCode**, children12–20 are connector powers; children3/4/5/7/9 reach unknown-subparameter handling. None establishes make/model/year fields.
2. Retained reference `carplay-lab/src/main/java/com/shilapi/xcertplay/transport/Iap2VehicleStatus.kt` L25–30,55–63; [immutable upstream](https://github.com/shihabal3amri/DiPlay/blob/e2fd8eadf5a6b705666f292055e72aa93387533d/shared/src/main/java/com/shilapi/xcertplay/transport/Iap2VehicleStatus.kt) supplies connector enum semantics. The production transformer is tested separately; presence in this reference does not mean it already shipped.
3. [AOSP Android12 VHAL types.hal](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/android12-release/automotive/vehicle/2.0/types.hal): `INFO_EV_CONNECTOR_TYPE` is INT32_VEC/STATIC and uses the **HAL** `EvConnectorType` enum. Do not use the deprecated Java `android.car.EvConnectorType`/CarInfoManager ordinals: its CCS/CHAdeMO values differ from the raw property. Fuel10 is ELECTRIC. [Current property API](https://developer.android.com/reference/android/car/VehiclePropertyIds) describes source units/status access.
4. Representative firmware `vehicle-start-wire.txt`, `0x1001672b0–2b4`: void selector4; `vehicle-status-schema.json` and update disassembly: field4 I16. Actual GM producer `com.gm.server.iap2.feature.VehicleStatus.report()` rounds VHAL outside-temperature Float to short; evidence summarized in `/hermes_work/oal-gm-carplay-vehicle-data-audit.md` L60–67. Its privileged implementation is not imported and does not grant OAL permissions.
5. Historical vehicle INFO values are recorded in [AA inventory](ev-energy-model-reverse-engineering.md#real-car-vhal-data-chevrolet-blazer-ev-c234-2024): Chevrolet/C234/2024, fuel[10], connectors[1,5]. That document's old energy-model assumptions are **not** used. These old values are not substituted for missing live reads.

Full independent review and original evidence pointers: `/hermes_work/research/carplay-vehicle-metadata/contract.md`, `/hermes_work/carplay-vehicle-protocol-deep-audit.md`, `/hermes_work/carplay-vehicle-data-investigation.md`, `/hermes_work/oal-latest-car-ev-audit.md`.

## Inventory: implemented versus deliberately absent

| Source | CarPlay handling |
|---|---|
| Raw GGA/RMC GNSS | Already shipped: qualified provider, requested sentence cadence, bounded valid fixes. VHAL wheel speed is not RMC speed. |
| Range, current energy, actual usable maximum | Already shipped: requested A1013/11/22/23 with VHAL validity checks. No scaling or energy-source changes here. |
| Make/model/year | New truthful display label, not a dedicated identity schema or energy-model selector. |
| Electric engine | Already shipped; same pure-EV qualification retained. |
| Supported connectors | New mapped inventory only; unproven types/powers omitted. |
| Outside temperature | New signed whole-Celsius status path, with request and validity fencing. |
| Night mode | Already shipped appearance bridge; no appearance-policy changes. |
| Gear, driving, parking brake, VHAL speed | Known AA inputs, not added to CarPlay. PASCD/limited-UI/parked-video contracts are distinct; no fabricated wheel speed or Always-in-Park reuse. |
| Ignition | Existing local lifecycle ownership, not outgoing general telemetry. |
| Charge rate, port open/connected | Known sources, but not reliable proof of isCharging; no inferred A10125. |
| Nominal/design capacity, derived battery percentage | Never substituted for actual usable maximum, minimum reserve or displayed SOC. |
| Usable capacity, charge state/time/limits, battery temperature, odometer, motor metrics | Some read paths exist, but recent car audit lacks valid values for several. No invented values/new forwarding contract. |
| Units, IMU, RPM, steering/headlights/hazards | No new CarPlay contract; placeholders are not actual sources. |
| Charging/consumption models, model_id, minimum charge, displayed SOC, maximum range/warnings | Omitted: no qualified vehicle-specific source/model. No tablet-only experimental assets or suppliers are imported. |

Recent car captures still show usable maximum absent and range/current age rejection; this patch does not relabel those as repaired. Status-valid metadata and temperature do not create a calibrated Maps EV profile.

## Verification and future physical acceptance

Focused host tests execute the production adapter -> frozen USB bootstrap/non-frozen wireless intake -> actual identification serialization/reparse and authenticated reporter. They cover preserved accessory bytes, all capability combinations, connector translation/dedup, absent/malformed fields, late-source freezing, unchanged energy-only frames, signed/zero temperature, stale/error/permission omission, stop/revoke, actual VHAL reflection status revalidation and sanitizer -> reporter -> ZIP export. These are host contracts, not phone/vehicle acceptance.

No hardware operation is part of this change. For a separately authorized future test, use the ordinary real-source runtime (no synthetic selector), establish a **fresh session**, and capture final identification plus current source validity. `temperature=true` in the offer and completed A101 `fields` bit32 establish offer/local-write boundaries respectively; request `groups` bit8 is the temperature subscription. Name/connector values are not dumped into logs. Require a fresh phone Directions action and independent visible EV battery-at-arrival/charging-plan outcome before calling Maps integration successful. An ordinary map route or local transport write is not proof. Missing source, no completed request/write, or cached phone errors remain inconclusive; neither invent identity/model data nor change usable-capacity semantics to force a result.
