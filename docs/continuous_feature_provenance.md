# Continuous feature provenance

The [128-output source inventory](../artifacts/representation_v1/continuous_feature_source_inventory.json)
binds the normalizer source digest and identifies each output expression,
mapping line and assignments to its producer variable. It is a static code
inventory, not complete transitive dataflow or runtime branch evidence.

V2 acquisition records adapter status and field evidence for hardware sensors,
device state, connectivity and audio. Missing gyroscope zeros, audio-route
defaults, failed versus empty Wi-Fi scans and empty PCM defaults are distinct
from reported inputs. These labels survive rolling persistence and edge-frame
generation. They do not establish that all normalized dimensions were measured.

Several normalizer branches require particular care:

- GNSS visible-satellite density, C/N0 and PDOP have fixed fallbacks conditional
  on latitude presence. Latitude alone does not measure these quantities.
- Dual-microphone cross-correlation has a fallback using single-channel spectral
  entropy and silence summaries. This does not establish a dual-microphone measurement.
- Spectral warmth can fall back to time phase and light level, and ultrasonic
  micro-Doppler can fall back to touch and silence summaries.
- Digital interaction metrics include proxy estimates and defaults; modality
  availability is insufficient to certify each field.

Completion requires recording which branch supplied each normalized dimension,
including dependency evidence, missing-input defaults, history warm-up and
proxy limitations, and validating the corresponding acquisition capability.
Existing trajectories must retain their original evidence rather than receive
retrospectively invented source labels. The current-source candidate and final
deployment must also bind this behavior explicitly.

The four GNSS alias lookups now fall back only when the primary value is `None`.
Reported zero values retain priority; PDOP still uses its existing lower bound.
This corrects an `or` lookup that could replace an explicit zero with an alias
or proxy. The source inventory binds the corrected implementation. This is a
normalization semantic change for affected inputs: prior empirical reports and
installed models are not automatically revalidated by the code correction.
