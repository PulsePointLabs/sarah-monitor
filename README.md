# Sarah Monitor 💜

A bedside-style live monitor for Android (phone, tablet, Samsung DeX) that brings every sensor onto one
dashboard, in the same visual language as the SarahVS telemetry monitor.

## Sensors

| Sensor | What you get |
| --- | --- |
| **Polar H10** | HR, ECG sweep (25 mm/s), RMSSD / SDNN / pNN50 (2-min rolling), Load, HR & HRV trends |
| **CIVET pelvic pressure** | Live pressure above rest, % of reference hold, hold timer, pulses/min, 30 s average/max, live waveform, pressure trend |
| **Omron BP cuff** | Readings land on the dashboard as soon as the cuff finishes, with category colour and trend |
| **Glucose** | CSV import (OneTouch Reveal and generic meter exports), Health Connect, or manual entry |

Every sensor is remembered and reconnects by itself, including while the app is minimised (foreground service).

## Dashboard

- 24-column snap grid that fills the screen. Drag a card to move it, drag its corner to resize it. Other cards make
  room and gaps close up. Lock the layout from the top bar.
- Text scales with each card: bigger cards get bigger readings; small cards shrink text but never below 11 sp.
- On a phone the cards stack in one column.

## Overlays

Independent floating overlays for **HR**, **BP** and **Pelvic pressure**. Each can be dragged and resized on its
own, remembers its position and size, and follows the app onto the DeX display.

## Pelvic pressure processing

CIVET protocol follows Sarah (`PulsePointLabs/Sarah`): pressure notifications on `0x150B` (int16 LE at bytes 8–9,
0.01 kPa), streaming started with `50 01 D0 …` on `0x150A`, optional hardware zero only with the sensor deflated
and outside the body. Monitor processing is in `core/PelvicProcessor.kt`:

- **Rest**: a 5 s relaxed capture (rejected if noisy), otherwise an auto rest from the 20th percentile of the last 30 s.
- **Reference**: optional 5 s comfortable hold → readings shown as % of reference.
- **Holds/pulses**: hysteresis on pressure above rest (threshold = max(0.3 kPa, 5 × rest noise)), 200 ms release.

Readings are mechanical pressure above rest, not calibrated muscle force.

## Releases

Every push builds, runs the unit tests, signs with the `DEBUG_KEYSTORE_BASE64` repo secret, checks the signature
and publishes a GitHub release with a direct APK link (`main` = release, other branches = pre-release).
