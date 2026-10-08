# Changelog

Full version history for `litter-robot-cleanup-manager.groovy`. The file itself
only keeps its header's CHANGELOG section for the current version — this is
the complete record.

## 2.4.0
Status light now boosts to at least 20% brightness (RESET_NOTIFY_LEVEL,
only a floor -- a higher configured statusLightLevel still wins)
right after a reset clears back to CLEAN, whether from the manual
reset button or the v2.3.0 fault auto-recovery. At a low normal
brightness (e.g. 5%), the color change back to green was hard to
notice. The boost holds at green until either the phase changes
away from idle (next real color change) or the existing
statusLightAutoOffMinutes timer turns the light off -- whichever
happens first -- then reverts to the normal configured brightness.

## 2.3.1
Fixed the status light sometimes not resetting (staying red/blinking)
after a manual reset or the new v2.3.0 auto-recovery. faultActive()
was reading back faultDev()'s switch attribute right after this app
itself sent it an off() command in the same handler -- not
guaranteed to be reflected yet, especially when autoCreateOutputs is
off and that's a real physical switch. resetToIdle() then calls
unscheduleAll(), killing the self-correcting blink job before it got
a chance to re-check. faultActive() now reads state.lastFault
instead, which this app sets/clears synchronously itself.

## 2.3.0
Auto-recover from a fault: while a fault is active, motion is now
ignored entirely (no WAIT/PULSE runs) instead of starting a normal
sequence. Watching the drum contacts directly, if a full rotation
(contacts open) followed by home (contacts close) is seen while
faulted, that's treated as the user having fixed the robot by hand
and run a clean cycle on it directly -- the fault clears and the
app returns to CLEAN automatically, the same as a manual reset but
without pressing anything in Hubitat. A fault that started with the
drum already mid-rotation (e.g. "never returned home") is handled
too, by seeding from whichever contacts are already open at the
moment the fault fires.

## 2.2.2
Fixed a 2.2.0 bug: the button number/action inputs on the reset
button section only appear once resetButtonDevice is set, but the
input was missing submitOnChange: true, so Hubitat never redrew
the page after picking a device -- the follow-up fields were
invisible until some other unrelated save happened to trigger a
refresh. Added submitOnChange: true to the device picker.

## 2.2.1
Changed WAIT's color from purple to orange (2.2.0 shipped purple).

## 2.2.0
Two changes:
1. Reworked the status light color scheme -- red is now reserved
   for "cat sensor is actively pulsed" only (PULSE/REASSERT). WAIT
   gets its own new purple color instead of sharing red. An
   unresolved fault now flashes red (alternating red/off every
   second) instead of a steady red, so it can never be confused with
   a normal pulse. COUNTDOWN/CYCLING stay yellow, IDLE stays green.
2. Added an optional reset button device (Devices page) --
   pick any button controller, a button number, and an action
   (push/hold/double-tap) to trigger the same reset as the Status
   page's "Reset to CLEAN now" button. Both now share one
   performManualReset() function.

## 2.1.9
Status light: added a brightness setting (statusLightLevel, 1-100%,
previously hardcoded to 100) and an auto-off timer that turns the
light off after N idle/clean minutes (statusLightAutoOffMinutes,
0 = never, default). Auto-off only ever applies to the green idle
state -- any phase change, fault, or new motion turns the light
back on immediately via the existing unconditional statusLight.on()
in updateStatusLight().

## 2.1.8
Reverted 2.1.7. Turned out to target the wrong phase -- "pulse the
cat sensor immediately on motion" for what the user calls the
"LR wait" actually meant COUNTDOWN (displayed as "LR-TIMER"), not
the earlier WAIT phase 2.1.7 touched. COUNTDOWN already does this:
motion there fires an immediate reassert pulse via enterReassert(),
gated by the existing "Fire a reassert pulse if motion returns
during the countdown" toggle (reassertOnCountdown, default on).
Nothing new needed -- removed the unused 2.1.7 setting instead of
leaving it in place.

## 2.1.7 (reverted in 2.1.8)
Added an optional "immediate pulse on wait motion" setting for the
WAIT phase, default off.

## 2.1.6
Fixed the 2.1.4 race guard: the atomicState-backed latch was a
check-then-set, and log evidence from 2026-09-20 (23:57:41.617)
showed both drum-contact "open" events landing in the same
millisecond -- both executions' reads of the latch happened
before either's write committed, so the guard didn't fire and
the app logged the COUNTDOWN -> CYCLING transition twice anyway.
Replaced the inline latch with a deferred confirmRotationDetected()
job scheduled via runIn(1, ..., [overwrite: true]): Hubitat's own
job-name dedup collapses any number of same-tick schedule calls
into one queued job, so the actual phase transition now only ever
runs once regardless of how many contactHandler() executions
raced to schedule it. Harmless in practice either way (duplicate
history line only, cycle still completed), but now closed properly.

## 2.1.5
Added a "Reset all timing settings to recommended defaults" button
on the Timing page. Writes every timing setting back to this
app's built-in defaults via app.updateSetting() -- clears out
stale values left over from earlier versions or manual hub-side
tweaks (e.g. a renamed setting that silently fell back to null,
or a value like the 131s cycleTimeoutSec found this session).

## 2.1.4
Fixed a race: the two drum contacts can open within milliseconds
of each other at rotation start, and Hubitat can run their event
handlers as overlapping executions that each read state.phase as
COUNTDOWN before either's setPhase() write lands -- logging the
COUNTDOWN -> CYCLING transition twice. Added an atomicState-backed
one-shot latch (atomicState writes are immediately visible across
executions, unlike buffered state) so only the first event wins;
unscheduleAll() clears it whenever a sequence ends. Found while
investigating a false "never returned home" fault -- the real
cause of that fault was a stale hub-side cycleTimeoutSec (131s,
fixed on the Timing page, not a code change).

## 2.1.3
Added a "Turn auto-clean on" button on the Status page -- appears
only while the app enabled switch is off, and turns it back on
directly rather than requiring the user to find and toggle the
switch device itself. Pairs with the 2.1.2 disabled indicator.

## 2.1.2
The app enabled switch being off wasn't reflected anywhere in the
Status page or app label -- Phase still showed CLEAN/normal with
no indication motion was being ignored. Added an "Enabled: No"
status line and a gray "disabled" suffix on the app label,
refreshed immediately on either direction of the switch (not just
on the next phase change). Also renamed the "Reset to idle now"
button to "Reset to CLEAN now" to match the phase display rename.

## 2.1.1
Corrected the "Release -> rotation" default from 300s (3-minute
Clean Cycle Wait assumption) to 540s -- log analysis confirmed
this robot's actual wait setting is 7 minutes, so the old default
was firing a spurious retry pulse roughly 2 minutes before the
drum actually started rotating on every cycle.

## 2.1.0
Added battery monitoring for the drum position contact sensors.
Notifies (via the existing notification devices) the first time a
sensor's reported battery drops below a configurable threshold
(default 50%), and again if it recovers and drops below a second
time. Checked on every battery report the sensor sends, plus once
on every save/boot to catch a sensor already low at startup.

## 2.0.4
User-facing phase names cleaned up: IDLE displays as "CLEAN" and
COUNTDOWN displays as "LR-TIMER" on the Status page, in History,
and in notifications. Internal phase values (used by the state
machine's own logic) are unchanged; WAIT/PULSE/REASSERT/CYCLING
still display under their existing names.

## 2.0.3
Removed the author's real name from the doc header and definition()
metadata (author/namespace now "ShiftyBuild") -- this repo is public.

## 2.0.2
Status section's "next" line now shows a single next expected action
with both its clock time and a countdown (e.g. "pulses cat sensor at
9:32:49 PM (in 12m)"), instead of listing every pending timer.

## 2.0.1
Fixed a crash introduced by the 2.0.0 settings rename/additions: Hubitat
does not backfill a new or renamed input's defaultValue into a running
app's settings until that input's page is opened and saved -- a code-only
paste leaves it null. waitMinutes, initialPulseSec, reassertPulseSec,
retryPulseSec (and every other numeric setting) now fall back to their
documented default wherever cast to int, so the app can never crash on
a not-yet-saved setting again. If you hit "GroovyCastException ... to
class 'int'" on 2.0.0, open the Timing page and press Done once to clear
it immediately; 2.0.1 makes that step unnecessary going forward.

## 2.0.0
Pulse-based cat sensor control, replacing continuous assertion -- holding
the robot's cat sensor on for extended periods was found to interfere with
its internal system. WAIT (renamed from HOLD) now leaves the sensor OFF for
the whole wait (quietMinutes renamed waitMinutes, default 5 -> 15 min).
Once the wait elapses, a new PULSE phase asserts the sensor for a short
fixed pulse (initialPulseSec, default 60s, capped at 90s) before handing
off to COUNTDOWN as before. Motion during COUNTDOWN no longer forces a full
new wait -- it now fires an immediate REASSERT pulse (reassertPulseSec,
default 90s, capped at 90s) and resumes COUNTDOWN once it ends. maxHold
default raised 20 -> 45 min and watchdog default raised 60 -> 90 min to
match the longer wait window. Status light and "next event" status line
updated for the new phases.

## 1.4.0
Optional status light (capability.colorControl): green while idle,
red while holding (motion/cat sensor asserted) or on an unresolved
fault, yellow while the robot's own countdown/cycle is running.
Status section now shows an estimated cycle time (rolling average
of confirmed cycles, plus a configured best-case estimate) and the
next scheduled event (release/timeout/watchdog ETA) while a
sequence is active.

## 1.3.2
Lowered default timing to match real single/multi-cat visits: quiet
window 20 -> 5 min, maximum hold 120 -> 20 min, watchdog 180 -> 60 min.
Added worked examples to the quiet window, maximum hold, and watchdog
descriptions on the Timing page.

## 1.3.1
Clearer device labels: "Cat sensor remote" -> "Litter-Robot cat presence
switch", "Enable switch" -> "App enabled switch". App enabled switch
moved to the top of the Devices page as the master control.

## 1.3.0
Optional child-device creation for the four status outputs, so the app
can be self-sufficient instead of requiring hand-made virtual switches.
Existing devices can still be selected instead. Child cleanup on
uninstall, plus a manual remove button.

## 1.2.0
Multi-page configuration UI. Mode and quiet-hours restrictions with
optional deferral. Configurable rotation/home detection logic (any vs
all contacts). Optional cycle confirmation, retry and countdown
re-assert. Range validation on all numeric inputs.

## 1.1.0
Debug logging auto-off after 30 min; separate descriptive logging toggle;
version tracking with upgrade detection; in-app event history; manual
reset and clear-history buttons; renamed notify() to avoid colliding
with java.lang.Object.notify().

## 1.0.0
Initial release. Four-phase state machine replacing the Rule Machine
implementation (App 725 + rules 902/904).
