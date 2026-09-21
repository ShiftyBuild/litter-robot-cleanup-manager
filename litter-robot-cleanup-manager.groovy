/**
 *  Litter Robot Cleanup Manager
 *
 *  Waits out motion, then PULSES the Litter-Robot's cat sensor -- never holds it --
 *  and confirms the drum actually completed a cycle.
 *
 *  Holding the cat sensor asserted for extended periods interferes with the robot's
 *  own internal system, so the sensor is only ever asserted in short, fixed pulses
 *  (tens of seconds), never continuously.
 *
 *  State machine:
 *
 *    IDLE ──motion──> WAIT ──wait window elapsed──> PULSE ──pulse ends──> COUNTDOWN ──rotation──> CYCLING
 *                                                                            │    ^                    │
 *                                                                            │    └─ REASSERT pulse ───┘
 *                                                                            └──────── motion returns ──┘
 *
 *    IDLE <──────────────────────── drum returned home + debounce ─────────────────────────────────────┘
 *
 *  WAIT       cat sensor OFF. Needs this many minutes of continuous no-motion before
 *             pulsing. Rolling: any motion restarts the wait window from zero.
 *  PULSE      cat sensor ON for a short, fixed pulse -- the trigger the robot actually
 *             needs. Never held continuously.
 *  COUNTDOWN  cat sensor off; robot's own internal timer running toward rotation.
 *             Motion here interrupts with a REASSERT pulse rather than a full new wait.
 *  REASSERT   cat sensor pulsed ON again (longer than the initial pulse) because a cat
 *             came back during COUNTDOWN. Returns to COUNTDOWN with a fresh rotation
 *             timeout once it ends.
 *  CYCLING    drum is physically rotating. Motion is deliberately ignored -- rotation
 *             cannot be stopped, and pulsing here would orphan the sequence.
 *
 *  ---------------------------------------------------------------------------
 *  CHANGELOG
 *  ---------------------------------------------------------------------------
 *  2.1.7  Added an optional "immediate pulse on wait motion" setting
 *         (Behavior options page, default off). Normally motion during WAIT
 *         just restarts the wait window from zero and leaves the sensor
 *         off. With this on, any motion while waiting pulses the cat
 *         sensor right away instead of waiting it out -- for setups where
 *         asserting the sensor the instant a cat is detected is preferred
 *         over leaving it off during a long wait window.
 *  2.1.6  Fixed the 2.1.4 race guard: the atomicState-backed latch was a
 *         check-then-set, and log evidence from 2026-09-20 (23:57:41.617)
 *         showed both drum-contact "open" events landing in the same
 *         millisecond -- both executions' reads of the latch happened
 *         before either's write committed, so the guard didn't fire and
 *         the app logged the COUNTDOWN -> CYCLING transition twice anyway.
 *         Replaced the inline latch with a deferred confirmRotationDetected()
 *         job scheduled via runIn(1, ..., [overwrite: true]): Hubitat's own
 *         job-name dedup collapses any number of same-tick schedule calls
 *         into one queued job, so the actual phase transition now only ever
 *         runs once regardless of how many contactHandler() executions
 *         raced to schedule it. Harmless in practice either way (duplicate
 *         history line only, cycle still completed), but now closed properly.
 *  2.1.5  Added a "Reset all timing settings to recommended defaults" button
 *         on the Timing page. Writes every timing setting back to this
 *         app's built-in defaults via app.updateSetting() -- clears out
 *         stale values left over from earlier versions or manual hub-side
 *         tweaks (e.g. a renamed setting that silently fell back to null,
 *         or a value like the 131s cycleTimeoutSec found this session).
 *  2.1.4  Fixed a race: the two drum contacts can open within milliseconds
 *         of each other at rotation start, and Hubitat can run their event
 *         handlers as overlapping executions that each read state.phase as
 *         COUNTDOWN before either's setPhase() write lands -- logging the
 *         COUNTDOWN -> CYCLING transition twice. Added an atomicState-backed
 *         one-shot latch (atomicState writes are immediately visible across
 *         executions, unlike buffered state) so only the first event wins;
 *         unscheduleAll() clears it whenever a sequence ends. Found while
 *         investigating a false "never returned home" fault -- the real
 *         cause of that fault was a stale hub-side cycleTimeoutSec (131s,
 *         fixed on the Timing page, not a code change).
 *  2.1.3  Added a "Turn auto-clean on" button on the Status page -- appears
 *         only while the app enabled switch is off, and turns it back on
 *         directly rather than requiring the user to find and toggle the
 *         switch device itself. Pairs with the 2.1.2 disabled indicator.
 *  2.1.2  The app enabled switch being off wasn't reflected anywhere in the
 *         Status page or app label -- Phase still showed CLEAN/normal with
 *         no indication motion was being ignored. Added an "Enabled: No"
 *         status line and a gray "disabled" suffix on the app label,
 *         refreshed immediately on either direction of the switch (not just
 *         on the next phase change). Also renamed the "Reset to idle now"
 *         button to "Reset to CLEAN now" to match the phase display rename.
 *  2.1.1  Corrected the "Release -> rotation" default from 300s (3-minute
 *         Clean Cycle Wait assumption) to 540s -- log analysis confirmed
 *         this robot's actual wait setting is 7 minutes, so the old default
 *         was firing a spurious retry pulse roughly 2 minutes before the
 *         drum actually started rotating on every cycle.
 *  2.1.0  Added battery monitoring for the drum position contact sensors.
 *         Notifies (via the existing notification devices) the first time a
 *         sensor's reported battery drops below a configurable threshold
 *         (default 50%), and again if it recovers and drops below a second
 *         time. Checked on every battery report the sensor sends, plus once
 *         on every save/boot to catch a sensor already low at startup.
 *  2.0.4  User-facing phase names cleaned up: IDLE displays as "CLEAN" and
 *         COUNTDOWN displays as "LR-TIMER" on the Status page, in History,
 *         and in notifications. Internal phase values (used by the state
 *         machine's own logic) are unchanged; WAIT/PULSE/REASSERT/CYCLING
 *         still display under their existing names.
 *  2.0.3  Removed the author's real name from the doc header and definition()
 *         metadata (author/namespace now "ShiftyBuild") -- this repo is public.
 *  2.0.2  Status section's "next" line now shows a single next expected action
 *         with both its clock time and a countdown (e.g. "pulses cat sensor at
 *         9:32:49 PM (in 12m)"), instead of listing every pending timer.
 *  2.0.1  Fixed a crash introduced by the 2.0.0 settings rename/additions: Hubitat
 *         does not backfill a new or renamed input's defaultValue into a running
 *         app's settings until that input's page is opened and saved -- a code-only
 *         paste leaves it null. waitMinutes, initialPulseSec, reassertPulseSec,
 *         retryPulseSec (and every other numeric setting) now fall back to their
 *         documented default wherever cast to int, so the app can never crash on
 *         a not-yet-saved setting again. If you hit "GroovyCastException ... to
 *         class 'int'" on 2.0.0, open the Timing page and press Done once to clear
 *         it immediately; 2.0.1 makes that step unnecessary going forward.
 *  2.0.0  Pulse-based cat sensor control, replacing continuous assertion -- holding
 *         the robot's cat sensor on for extended periods was found to interfere with
 *         its internal system. WAIT (renamed from HOLD) now leaves the sensor OFF for
 *         the whole wait (quietMinutes renamed waitMinutes, default 5 -> 15 min).
 *         Once the wait elapses, a new PULSE phase asserts the sensor for a short
 *         fixed pulse (initialPulseSec, default 60s, capped at 90s) before handing
 *         off to COUNTDOWN as before. Motion during COUNTDOWN no longer forces a full
 *         new wait -- it now fires an immediate REASSERT pulse (reassertPulseSec,
 *         default 90s, capped at 90s) and resumes COUNTDOWN once it ends. maxHold
 *         default raised 20 -> 45 min and watchdog default raised 60 -> 90 min to
 *         match the longer wait window. Status light and "next event" status line
 *         updated for the new phases.
 *  1.4.0  Optional status light (capability.colorControl): green while idle,
 *         red while holding (motion/cat sensor asserted) or on an unresolved
 *         fault, yellow while the robot's own countdown/cycle is running.
 *         Status section now shows an estimated cycle time (rolling average
 *         of confirmed cycles, plus a configured best-case estimate) and the
 *         next scheduled event (release/timeout/watchdog ETA) while a
 *         sequence is active.
 *  1.3.2  Lowered default timing to match real single/multi-cat visits: quiet
 *         window 20 -> 5 min, maximum hold 120 -> 20 min, watchdog 180 -> 60 min.
 *         Added worked examples to the quiet window, maximum hold, and watchdog
 *         descriptions on the Timing page.
 *  1.3.1  Clearer device labels: "Cat sensor remote" -> "Litter-Robot cat presence
 *         switch", "Enable switch" -> "App enabled switch". App enabled switch
 *         moved to the top of the Devices page as the master control.
 *  1.3.0  Optional child-device creation for the four status outputs, so the app
 *         can be self-sufficient instead of requiring hand-made virtual switches.
 *         Existing devices can still be selected instead. Child cleanup on
 *         uninstall, plus a manual remove button.
 *  1.2.0  Multi-page configuration UI. Mode and quiet-hours restrictions with
 *         optional deferral. Configurable rotation/home detection logic (any vs
 *         all contacts). Optional cycle confirmation, retry and countdown
 *         re-assert. Range validation on all numeric inputs.
 *  1.1.0  Debug logging auto-off after 30 min; separate descriptive logging toggle;
 *         version tracking with upgrade detection; in-app event history; manual
 *         reset and clear-history buttons; renamed notify() to avoid colliding
 *         with java.lang.Object.notify().
 *  1.0.0  Initial release. Four-phase state machine replacing the Rule Machine
 *         implementation (App 725 + rules 902/904).
 *  ---------------------------------------------------------------------------
 */

import groovy.transform.Field

@Field static final String APP_VERSION = "2.1.7"
@Field static final Integer HISTORY_MAX = 25
@Field static final Integer CYCLE_HISTORY_MAX = 10

// Status outputs the app can create for itself. Deliberately excludes the cat
// sensor remote and the enable switch: those interface with real hardware and
// with the user, so an app-created copy would be wired to nothing.
@Field static final List CHILD_SPECS = [
    [key: "armed",    label: "LR Armed"],
    [key: "sequence", label: "LR Sequence Active"],
    [key: "dirty",    label: "LR Dirty"],
    [key: "fault",    label: "LR Cycle Fault"],
]

// Hue/saturation for the optional status light. Keyed by name rather than
// relying on a driver-specific named-color lookup, so any capability.colorControl
// device works the same way.
@Field static final Map STATUS_COLORS = [
    green:  [hue: 33, saturation: 100, level: 100],
    yellow: [hue: 16, saturation: 100, level: 100],
    red:    [hue: 0,  saturation: 100, level: 100],
]

definition(
    name: "Litter Robot Cleanup Manager",
    namespace: "ShiftyBuild",
    author: "ShiftyBuild",
    description: "Waits out motion, then pulses the Litter-Robot cat sensor and confirms the drum cycled.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    // Set this to your own raw GitHub URL and Hubitat's "Import" button will pull
    // updates in place, which is the closest thing the platform has to real VCS.
    importUrl: "https://raw.githubusercontent.com/ShiftyBuild/litter-robot-cleanup-manager/main/litter-robot-cleanup-manager.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "devicePage")
    page(name: "timingPage")
    page(name: "optionsPage")
    page(name: "restrictionPage")
    page(name: "historyPage")
}

// ============================================================================
//  Page: main
// ============================================================================

def mainPage() {
    dynamicPage(name: "mainPage", title: "Litter Robot Cleanup Manager", install: true, uninstall: true) {

        section("Status") {
            paragraph statusText()
            input "btnReset", "button", title: "Reset to CLEAN now"
            if (enableSwitch && !isEnabled()) {
                input "btnEnable", "button", title: "Turn auto-clean on"
            }
        }

        section("Configuration") {
            href name: "hrefDevices", page: "devicePage",
                title: "Devices",
                description: deviceSummary(),
                state: devicesReady() ? "complete" : null
            href name: "hrefTiming", page: "timingPage",
                title: "Timing",
                description: timingSummary(),
                state: "complete"
            href name: "hrefOptions", page: "optionsPage",
                title: "Behavior options",
                description: optionsSummary(),
                state: "complete"
            href name: "hrefRestrictions", page: "restrictionPage",
                title: "Restrictions",
                description: restrictionSummary(),
                state: (allowedModes || quietHoursEnabled) ? "complete" : null
        }

        section("Diagnostics") {
            href name: "hrefHistory", page: "historyPage",
                title: "Event history",
                description: state.history ? "${state.history.size()} recent events" : "No events recorded"
            input "logEnable", "bool",
                title: "Debug logging — every event and timer, auto-disables after 30 minutes",
                defaultValue: true
            input "txtEnable", "bool",
                title: "Descriptive logging — phase changes and decisions, stays on",
                defaultValue: true
        }

        section {
            paragraph "<small>Version ${APP_VERSION}" +
                      (state.installedVersion && state.installedVersion != APP_VERSION
                          ? " — upgrading from ${state.installedVersion} on save" : "") +
                      "</small>"
        }
    }
}

// ============================================================================
//  Page: devices
// ============================================================================

def devicePage() {
    dynamicPage(name: "devicePage", title: "Devices") {

        section("Required — app control") {
            input "enableSwitch", "capability.switch",
                title: "App enabled switch",
                required: true
            paragraph "<small>The app does nothing while this is OFF. Turning it off mid-sequence " +
                      "aborts cleanly and releases the cat sensor. Not auto-created — this one " +
                      "is yours to control.</small>"
        }

        section("Required — sensors") {
            input "motionSensor", "capability.motionSensor",
                title: "Box motion sensor (cat presence)",
                required: true
            input "drumContacts", "capability.contactSensor",
                title: "Drum position contact sensors",
                multiple: true, required: true
            paragraph "<small>These must read <b>closed</b> when the drum is at rest. If yours " +
                      "read open at rest, invert them in the driver or use a virtual contact. " +
                      "How many must trip to count as rotation is set under Behavior options.</small>"
        }

        section("Required — Litter-Robot control") {
            input "catSensorRemote", "capability.switch",
                title: "Litter-Robot cat presence switch",
                required: true
            paragraph "<small>The app only ever pulses this ON briefly (tens of seconds, never held) " +
                      "to nudge the robot -- holding it on continuously interferes with the robot's " +
                      "own internal system. " +
                      "<b>The app will not create this for you</b> — it has to be the switch that " +
                      "actually reaches your robot.</small>"
        }

        section("Optional — status outputs") {
            paragraph "Purely for visibility: dashboards, Maker API, other automations. The app " +
                      "works with none of these selected."
            input "autoCreateOutputs", "bool",
                title: "Let the app create and manage these devices itself",
                defaultValue: false, submitOnChange: true

            if (autoCreateOutputs) {
                paragraph childStatusText()
                input "btnRemoveChildren", "button", title: "Remove app-created devices"
            } else {
                input "armedSwitch", "capability.switch",
                    title: "Armed indicator — ON when idle, OFF during a sequence", required: false
                input "sequenceSwitch", "capability.switch",
                    title: "Sequence-active indicator — ON for the whole wait + pulse + cycle", required: false
                input "dirtySwitch", "capability.switch",
                    title: "Dirty indicator — ON from trigger until a confirmed cycle", required: false
                input "faultSwitch", "capability.switch",
                    title: "Fault indicator — ON when a cycle could not be confirmed", required: false
                paragraph "<small>Select your existing virtual switches here if they already have " +
                          "dashboard tiles or other automations pointing at them.</small>"
            }
        }

        section("Optional — status light") {
            input "statusLight", "capability.colorControl",
                title: "Notification light — green idle, yellow while the robot's own " +
                        "timer/cycle is running, red while waiting/pulsing (motion detected or cat " +
                        "sensor pulsed) or on an unresolved fault",
                required: false
            paragraph "<small>Any color bulb works. The app turns it on and sets its color on " +
                      "every phase change; it does not turn it off. Leave blank to skip.</small>"
        }

        section("Optional — notifications") {
            input "notifiers", "capability.notification",
                title: "Notification devices", multiple: true, required: false
            input "notifySuccess", "bool",
                title: "Notify on a successfully confirmed cycle", defaultValue: false
            input "notifyForcedRelease", "bool",
                title: "Notify when a hold is force-released (max hold or restriction cap)",
                defaultValue: true
        }

        section("Optional — battery monitoring") {
            input "batteryMonitorEnabled", "bool",
                title: "Notify when a drum contact sensor's battery drops low",
                defaultValue: true
            input "batteryThreshold", "number",
                title: "Low battery threshold (%)",
                defaultValue: 50, required: true, range: "1..99"
            paragraph "<small>Checked whenever a drum contact sensor reports its battery level " +
                      "(most report this periodically on their own, not on every open/close) and " +
                      "once whenever this app's settings are saved. Notifies once when a sensor " +
                      "first drops below the threshold, then again if it recovers and later drops " +
                      "below it a second time. Uses the notification devices above.</small>"
        }
    }
}

private childStatusText() {
    def existing = getChildDevices()
    if (!existing) {
        return "<i>Devices will be created when you press Done.</i>"
    }
    def rows = existing.collect { "<li>${it.displayName} — currently <b>${it.currentValue('switch')}</b></li>" }
    return "<ul>${rows.join('')}</ul>" +
           "<small>These are children of this app. Removing the app removes them too.</small>"
}

// ============================================================================
//  Page: timing
// ============================================================================

def timingPage() {
    dynamicPage(name: "timingPage", title: "Timing") {

        section("Reset") {
            input "btnResetTiming", "button", title: "Reset all timing settings to recommended defaults"
            paragraph "<small>Resets every setting on this page back to this app's built-in defaults -- " +
                      "useful for clearing out stale values left over from an earlier version or a " +
                      "manual tweak (e.g. a renamed setting that silently fell back to null, or a value " +
                      "tuned for behavior a previous version no longer has). Does not touch device " +
                      "selections, notifications, or restrictions on the other pages.</small>"
        }

        section("The wait") {
            input "waitMinutes", "number",
                title: "Wait window (minutes)",
                defaultValue: 15, required: true, range: "1..720"
            paragraph "<small>Continuous no-motion required before pulsing the cat sensor. The " +
                      "sensor stays OFF for this entire wait -- rolling: any motion restarts it " +
                      "from zero, however many times." +
                      "<br><b>Example:</b> a cat visits for a minute, leaves, and the box stays quiet " +
                      "for the next 15 minutes straight — the app pulses the sensor at that point. " +
                      "If a second cat wanders in partway through those 15 minutes, the window " +
                      "restarts from zero the moment it leaves, however many times that happens.</small>"
            input "maxHoldMinutes", "number",
                title: "Maximum wait (minutes)",
                defaultValue: 45, required: true, range: "1..1440"
            paragraph "<small>Force a pulse after this long even if motion never settles. Set this " +
                      "to at least 3&times; the wait window (e.g. 45 min for a 15 min wait window) " +
                      "or it will fire routinely on ordinary multi-cat traffic." +
                      "<br><b>Example:</b> several cats trickle through back-to-back and motion never " +
                      "settles for a full wait window — once this many total minutes have passed " +
                      "since the sequence started, the app stops waiting and pulses anyway. If this " +
                      "fires often, that usually means a stuck sensor or a cat that won't leave, not " +
                      "just a busy box.</small>"
        }

        section("The pulse") {
            paragraph "<small>The Litter-Robot's own cat sensor input shouldn't be held for long " +
                      "periods -- doing so interferes with its internal system. The app only ever " +
                      "asserts it in short, fixed pulses, capped at 90 seconds.</small>"
            input "initialPulseSec", "number",
                title: "Initial pulse (seconds) — fires once the wait window elapses",
                defaultValue: 60, required: true, range: "5..90"
            input "reassertPulseSec", "number",
                title: "Reassert pulse (seconds) — fires immediately if a cat returns during the countdown",
                defaultValue: 90, required: true, range: "5..90"
        }

        section("The cycle") {
            input "rotateTimeoutSec", "number",
                title: "Release → rotation (seconds)",
                defaultValue: 540, required: true, range: "30..1800"
            paragraph "<small>The robot's own internal timer plus margin. Use ~300 for the 3-minute " +
                      "setting, ~540 for the 7-minute setting (confirmed via log analysis to be this " +
                      "robot's actual setting), ~1020 for the 15-minute setting.</small>"
            input "cycleTimeoutSec", "number",
                title: "Rotation → home (seconds)",
                defaultValue: 300, required: true, range: "30..1800"
            input "homeDebounceSec", "number",
                title: "Home debounce (seconds)",
                defaultValue: 30, required: true, range: "1..600"
            paragraph "<small>How long the drum must stay at home position to count as returned. " +
                      "This is what makes it a real confirmation rather than a single contact blip.</small>"
        }

        section("Retries and recovery") {
            input "maxAttempts", "number",
                title: "Release attempts before declaring a fault",
                defaultValue: 3, required: true, range: "1..10"
            input "retryPulseSec", "number",
                title: "Retry pulse (seconds)",
                defaultValue: 5, required: true, range: "1..90"
            paragraph "<small>If the robot never starts rotating after the initial pulse, how long " +
                      "to pulse the cat sensor again on a retry (assuming no cat is present).</small>"
            input "cooldownSec", "number",
                title: "Post-cycle cooldown (seconds)",
                defaultValue: 90, required: true, range: "0..900"
            paragraph "<small>Ignore motion this long after a cycle, so the drum's own movement " +
                      "cannot trigger a new sequence. Set to 0 to disable.</small>"
            input "watchdogMinutes", "number",
                title: "Watchdog (minutes)",
                defaultValue: 90, required: true, range: "5..1440"
            paragraph "<small>Force a reset if a sequence has not finished in this long, start to " +
                      "end — wait, pulse, countdown, and cycling together. Must exceed your maximum " +
                      "wait, or it will cut valid waits short before they ever reach that cap." +
                      "<br><b>Example:</b> with a 45-minute maximum wait, a 90-minute watchdog leaves " +
                      "room for the wait cap itself plus the pulse, the robot's own countdown, and " +
                      "cycle time, while still catching a sequence that's genuinely stuck — a jammed " +
                      "drum or a sensor that stopped reporting.</small>"
        }
    }
}

// Keep in sync with the defaultValue: on each input in timingPage() above --
// this is what "Reset all timing settings to recommended defaults" writes.
private void resetTimingDefaults() {
    [
        waitMinutes:      [value: "15",  type: "number"],
        maxHoldMinutes:   [value: "45",  type: "number"],
        initialPulseSec:  [value: "60",  type: "number"],
        reassertPulseSec: [value: "90",  type: "number"],
        rotateTimeoutSec: [value: "540", type: "number"],
        cycleTimeoutSec:  [value: "300", type: "number"],
        homeDebounceSec:  [value: "30",  type: "number"],
        maxAttempts:      [value: "3",   type: "number"],
        retryPulseSec:    [value: "5",   type: "number"],
        cooldownSec:      [value: "90",  type: "number"],
        watchdogMinutes:  [value: "90",  type: "number"],
    ].each { key, cfg -> app.updateSetting(key, cfg) }
    logWarn "All timing settings reset to recommended defaults"
    addHistory("Timing settings reset to recommended defaults")
}

// ============================================================================
//  Page: behavior options
// ============================================================================

def optionsPage() {
    dynamicPage(name: "optionsPage", title: "Behavior options") {

        section("Contact sensor logic") {
            input "rotationDetect", "enum",
                title: "Count rotation as started when...",
                options: ["any": "ANY drum contact opens (redundant sensors, or one home sensor)",
                          "all": "ALL drum contacts have opened (sensors at different positions)"],
                defaultValue: "any", required: true
            input "homeDetect", "enum",
                title: "Count the drum as home when...",
                options: ["all": "ALL drum contacts are closed",
                          "any": "ANY drum contact is closed"],
                defaultValue: "all", required: true
            paragraph "<small>If your two sensors sit at different points in the rotation, " +
                      "<b>all</b>/<b>all</b> gives the strictest confirmation. If they are redundant " +
                      "sensors on the same position, use <b>any</b>/<b>all</b>.</small>"
        }

        section("What to confirm") {
            input "confirmCycle", "bool",
                title: "Confirm the drum completed a cycle",
                defaultValue: true
            paragraph "<small>Off: the app releases the cat sensor and returns to idle immediately, " +
                      "trusting the robot. You lose jam detection and retries.</small>"
            input "retryEnabled", "bool",
                title: "Retry the release if the drum never starts moving",
                defaultValue: true
            paragraph "<small>Off: a missed cycle goes straight to a fault with no retry.</small>"
        }

        section("Motion during the robot's countdown") {
            input "reassertOnCountdown", "bool",
                title: "Fire a reassert pulse if motion returns during the countdown",
                defaultValue: true
            paragraph "<small>A cat returning during the countdown fires an immediate reassert pulse " +
                      "(see the pulse length under Timing), then resumes the countdown once it ends -- " +
                      "no full new wait. Off: the countdown runs to completion regardless.</small>"
        }

        section("Motion during the wait") {
            input "immediatePulseOnWaitMotion", "bool",
                title: "Pulse the cat sensor immediately on any motion during the wait",
                defaultValue: false
            paragraph "<small>Default (off): motion during the wait just restarts the wait window from " +
                      "zero -- the sensor stays off until the box has been quiet for the full wait " +
                      "window (see Timing). On: any motion while waiting pulses the cat sensor right " +
                      "away instead of waiting it out, on the theory that asserting the sensor the " +
                      "moment a cat is detected is safer than leaving it off. Skips straight from WAIT " +
                      "to PULSE exactly as if the wait window had already elapsed.</small>"
        }
    }
}

// ============================================================================
//  Page: restrictions
// ============================================================================

def restrictionPage() {
    dynamicPage(name: "restrictionPage", title: "Restrictions") {

        section("Modes") {
            input "allowedModes", "mode",
                title: "Only run in these modes (leave empty for all modes)",
                multiple: true, required: false
        }

        section("Quiet hours") {
            input "quietHoursEnabled", "bool",
                title: "Suppress cycles during a time window",
                defaultValue: false, submitOnChange: true
            if (quietHoursEnabled) {
                input "quietStart", "time", title: "Quiet hours start", required: true
                input "quietEnd", "time", title: "Quiet hours end", required: true
                input "quietHoursAction", "enum",
                    title: "During quiet hours...",
                    options: ["defer": "Keep holding, cycle once quiet hours end",
                              "skip":  "Give up on this sequence and reset to idle"],
                    defaultValue: "defer", required: true
                input "deferCapMinutes", "number",
                    title: "Maximum deferral (minutes) — force the cycle after this long",
                    defaultValue: 600, required: true, range: "10..1440"
            }
        }

        section {
            paragraph "<small>Restrictions block <i>starting</i> a sequence and block the release. " +
                      "A sequence already in progress is never abandoned mid-rotation.</small>"
        }
    }
}

// ============================================================================
//  Page: history
// ============================================================================

def historyPage() {
    dynamicPage(name: "historyPage", title: "Event history") {
        section {
            paragraph historyText()
            input "btnClearHistory", "button", title: "Clear history"
        }
    }
}

// ============================================================================
//  Page summaries
// ============================================================================

private boolean devicesReady() {
    return motionSensor && drumContacts && catSensorRemote && enableSwitch
}

private deviceSummary() {
    if (!devicesReady()) return "Not yet configured"
    def bits = ["${motionSensor.displayName}", "${drumContacts.size()} drum contact(s)"]
    if (autoCreateOutputs) bits << "${getChildDevices().size()} app-managed output(s)"
    if (statusLight) bits << "status light: ${statusLight.displayName}"
    if (notifiers) bits << "${notifiers.size()} notifier(s)"
    if (batteryMonitorEnabled != false) bits << "battery alert <${batteryThreshold ?: 50}%"
    return bits.join(" · ")
}

private timingSummary() {
    return "${waitMinutes ?: 15} min wait · ${initialPulseSec ?: 60}s/${reassertPulseSec ?: 90}s pulses · " +
           "max wait ${maxHoldMinutes ?: 45} min · ${maxAttempts ?: 3} attempts"
}

private optionsSummary() {
    def bits = []
    bits << ((confirmCycle == false) ? "no cycle confirmation" : "confirm cycle")
    bits << "rotation: ${rotationDetect ?: 'any'} · home: ${homeDetect ?: 'all'}"
    if (reassertOnCountdown == false) bits << "no countdown re-assert"
    if (immediatePulseOnWaitMotion) bits << "immediate pulse on wait motion"
    if (retryEnabled == false) bits << "no retries"
    return bits.join(" · ")
}

private restrictionSummary() {
    def bits = []
    if (allowedModes) bits << "modes: ${allowedModes.join(', ')}"
    if (quietHoursEnabled) bits << "quiet hours (${quietHoursAction == 'skip' ? 'skip' : 'defer'})"
    return bits ? bits.join(" · ") : "No restrictions"
}

private statusText() {
    def lines = []
    lines << "<b>Version:</b> ${APP_VERSION}"
    lines << "<b>Phase:</b> ${phaseLabel(state.phase ?: 'IDLE')}"
    if (enableSwitch && !isEnabled()) {
        lines << "<b>Enabled:</b> No — ${enableSwitch.displayName} is off, motion is being ignored"
    }

    def estBits = []
    def avg = avgCycleDurationSec()
    if (avg != null) {
        estBits << "~${fmtSecs(avg)} avg (last ${state.cycleDurations.size()} confirmed)"
    }
    def cfg = configuredCycleEstimateSec()
    if (cfg != null) estBits << "~${fmtSecs(cfg)} best case (current settings)"
    if (estBits) lines << "<b>Estimated cycle time:</b> ${estBits.join(' · ')}"

    if (state.phase && state.phase != "IDLE") {
        lines << "<b>Release attempts:</b> ${state.attempts ?: 0} of ${maxAttempts}"
        if (state.sequenceStarted) {
            lines << "<b>Sequence started:</b> ${fmt(state.sequenceStarted)} " +
                     "(${elapsed(state.sequenceStarted)} ago)"
        }
        if (state.deferredReason) lines << "<b>Deferred:</b> ${state.deferredReason}"
        def upcoming = upcomingEventsText()
        if (upcoming) lines << upcoming
    } else if (state.cooldownUntil && state.cooldownUntil > now()) {
        lines << "<b>Cooldown:</b> motion ignored for another ${fmtRemaining(state.cooldownUntil)} " +
                 "(until ${fmt(state.cooldownUntil)})"
    }

    if (state.lastSuccess) {
        def tookText = state.lastCycleDurationSec != null ? " (took ${fmtSecs(state.lastCycleDurationSec)})" : ""
        lines << "<b>Last confirmed cycle:</b> ${fmt(state.lastSuccess)}${tookText}"
    }
    if (state.lastFault)   lines << "<b>Last fault:</b> ${state.lastFaultReason} — ${fmt(state.lastFault)}"
    if (state.lowBatteryDevices) {
        def bits = state.lowBatteryDevices.collect { k, v -> "${v.name} (${v.pct}%)" }
        lines << "<b>Low battery:</b> ${bits.join(', ')}"
    }
    return lines.join("<br>")
}

// The single soonest scheduled event for the sequence in progress, from the
// deadlines scheduleTimer() recorded, with both its clock time and a countdown.
// Only ever considers timers relevant to the current phase -- a stale deadline
// from a phase we've since left is never a candidate.
private String upcomingEventsText() {
    def candidates = []
    def add = { String label, Long epoch ->
        if (epoch == null || epoch <= now()) return
        candidates << [label: label, epoch: epoch]
    }

    switch (state.phase) {
        case "WAIT":
            add("pulses cat sensor", state.deadlines?.waitElapsed)
            add("max-wait forces pulse", state.deadlines?.holdCapReached)
            break
        case "PULSE":
            add("pulse ends, watching for rotation begins", state.deadlines?.pulseDone)
            break
        case "COUNTDOWN":
            add("retry/fault if no rotation", state.deadlines?.rotateTimeout)
            add("retry pulse ends", state.deadlines?.retryPulseDone)
            break
        case "REASSERT":
            add("reassert pulse ends", state.deadlines?.reassertPulseDone)
            break
        case "CYCLING":
            add("fault if drum doesn't return home", state.deadlines?.cycleTimeout)
            add("confirming home", state.deadlines?.confirmHome)
            break
        default:
            return null
    }
    add("watchdog forces reset", state.deadlines?.watchdog)

    if (!candidates) return null
    def next = candidates.min { it.epoch }
    def secs = ((next.epoch - now()) / 1000) as int
    return "<b>Next expected action:</b> ${next.label} at ${fmt(next.epoch)} (in ${fmtSecs(secs)})"
}

// Rolling average of how long the last few confirmed cycles actually took.
private Integer avgCycleDurationSec() {
    def list = state.cycleDurations
    if (!list) return null
    return (list.sum() / list.size()) as int
}

// Best-case total for a single clean visit under the current settings: the
// full wait window, the initial pulse, and the robot's own release-to-home
// timing budget.
private Integer configuredCycleEstimateSec() {
    if (!waitMinutes || !initialPulseSec || !rotateTimeoutSec || !cycleTimeoutSec) return null
    return ((waitMinutes as int) * 60) + (initialPulseSec as int) + (rotateTimeoutSec as int) +
           (cycleTimeoutSec as int) + ((homeDebounceSec ?: 0) as int)
}

private historyText() {
    if (!state.history) return "<i>No events recorded.</i>"
    def rows = state.history.collect { h ->
        "<tr><td style='padding-right:12px;white-space:nowrap;vertical-align:top'>" +
        "<small>${fmt(h.t)}</small></td><td><small>${h.e}</small></td></tr>"
    }.join("")
    return "<table>${rows}</table>"
}

private fmt(Long epoch) {
    return new Date(epoch).format("MMM d, h:mm:ss a", location.timeZone)
}

private elapsed(Long since) {
    return fmtSecs(((now() - since) / 1000) as int)
}

private String fmtRemaining(Long epoch) {
    return fmtSecs(((epoch - now()) / 1000) as int)
}

private String fmtSecs(int secs) {
    if (secs < 0) secs = 0
    if (secs < 60) return "${secs}s"
    def mins = (secs / 60) as int
    return (mins < 60) ? "${mins}m" : "${(mins / 60) as int}h ${mins % 60}m"
}

// User-facing display name for a phase. Internal state.phase values (IDLE,
// WAIT, PULSE, COUNTDOWN, REASSERT, CYCLING) are unchanged -- this only
// affects what's shown on the Status page, in History, and in notifications.
@Field static final Map<String, String> PHASE_LABELS = [IDLE: "CLEAN", COUNTDOWN: "LR-TIMER"]

private String phaseLabel(String phase) {
    return PHASE_LABELS[phase] ?: phase
}

// ============================================================================
//  Lifecycle
// ============================================================================

def installed() {
    state.phase = "IDLE"
    state.attempts = 0
    state.installedVersion = APP_VERSION
    addHistory("App installed, version ${APP_VERSION}")
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()

    if (state.installedVersion != APP_VERSION) {
        def from = state.installedVersion ?: "unknown"
        logWarn "Upgraded from version ${from} to ${APP_VERSION}"
        addHistory("Upgraded ${from} &rarr; ${APP_VERSION}")
        state.installedVersion = APP_VERSION
    }

    initialize()
}

def uninstalled() {
    unschedule()
    removeChildren()
}

def initialize() {
    if (state.phase == null) state.phase = "IDLE"
    if (state.attempts == null) state.attempts = 0

    ensureChildDevices()

    subscribe(motionSensor, "motion", "motionHandler")
    subscribe(drumContacts, "contact", "contactHandler")
    subscribe(enableSwitch, "switch", "enableSwitchHandler")
    subscribe(drumContacts, "battery", "batteryHandler")
    checkBatteryLevels()

    // Hubitat convention: never leave debug logging on indefinitely.
    if (logEnable) {
        runIn(1800, "logsOff", [overwrite: true])
        logDebug "Debug logging will auto-disable in 30 minutes"
    }

    // Warn about settings that will misbehave together rather than failing quietly.
    if (((watchdogMinutes ?: 90) as int) <= ((maxHoldMinutes ?: 45) as int)) {
        logWarn "Watchdog (${watchdogMinutes} min) is not longer than max wait " +
                "(${maxHoldMinutes} min) -- valid waits will be cut short"
    }
    if (((maxHoldMinutes ?: 45) as int) < ((waitMinutes ?: 15) as int) * 2) {
        logWarn "Max wait (${maxHoldMinutes} min) is less than 2x the wait window " +
                "(${waitMinutes} min) -- forced pulses will be common"
    }

    // A settings change mid-sequence leaves timers unscheduled by updated().
    // Rather than try to rebuild them, reset to a known-good state.
    if (state.phase != "IDLE") {
        logWarn "Settings changed during a ${state.phase} sequence -- resetting to IDLE"
        addHistory("Config saved during ${phaseLabel(state.phase)}; forced reset")
        resetToIdle()
    } else {
        applyIdleOutputs()
    }

    updateLabel()
    logInfo "Initialized v${APP_VERSION}. Wait window ${waitMinutes} min, max wait ${maxHoldMinutes} min."
}

private updateLabel() {
    def phase = state.phase ?: "IDLE"
    def suffix = ""
    def color
    if (!isEnabled()) {
        color = "gray"
        suffix = " disabled"
    } else {
        color = (phase == "IDLE") ? "green" : "orange"
        if (phase == "IDLE" && faultActive()) color = "red"
    }
    app.updateLabel("Litter Robot Cleanup Manager <span style='color:${color}'>(${phaseLabel(phase)}${suffix})</span>")
}

private boolean faultActive() {
    return faultDev()?.currentValue("switch") == "on"
}

// ============================================================================
//  Child devices
// ============================================================================

private String dniFor(String key) {
    return "lrcm-${app.id}-${key}"
}

private void ensureChildDevices() {
    if (!autoCreateOutputs) return

    CHILD_SPECS.each { spec ->
        def dni = dniFor(spec.key)
        if (getChildDevice(dni)) return
        try {
            addChildDevice("hubitat", "Virtual Switch", dni,
                [name: "Virtual Switch", label: spec.label, isComponent: false])
            logInfo "Created child device '${spec.label}'"
            addHistory("Created child device ${spec.label}")
        } catch (e) {
            logWarn "Could not create child device '${spec.label}': ${e.message}. " +
                    "Create a Virtual Switch manually and select it instead."
        }
    }
}

private void removeChildren() {
    getChildDevices().each { dev ->
        try {
            deleteChildDevice(dev.deviceNetworkId)
            logInfo "Removed child device '${dev.displayName}'"
        } catch (e) {
            logWarn "Could not remove child device '${dev.displayName}': ${e.message}. " +
                    "It may be in use by a dashboard or another app."
        }
    }
}

// Each accessor returns the app-managed child when auto-create is on, or the
// user-selected device when it is off. Both may legitimately be null.
private armedDev()    { autoCreateOutputs ? getChildDevice(dniFor("armed"))    : armedSwitch }
private sequenceDev() { autoCreateOutputs ? getChildDevice(dniFor("sequence")) : sequenceSwitch }
private dirtyDev()    { autoCreateOutputs ? getChildDevice(dniFor("dirty"))    : dirtySwitch }
private faultDev()    { autoCreateOutputs ? getChildDevice(dniFor("fault"))    : faultSwitch }

// ============================================================================
//  Buttons
// ============================================================================

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnReset":
            logWarn "Manual reset requested"
            addHistory("Manual reset from ${phaseLabel(state.phase)}")
            faultDev()?.off()
            state.lastFault = null
            state.lastFaultReason = null
            resetToIdle()
            break
        case "btnEnable":
            logInfo "Auto-clean turned back on from the app's own Status page"
            addHistory("Auto-clean turned on (manual)")
            enableSwitch.on()
            break
        case "btnResetTiming":
            resetTimingDefaults()
            break
        case "btnClearHistory":
            state.history = []
            logInfo "Event history cleared"
            break
        case "btnRemoveChildren":
            logWarn "Manual removal of app-created devices requested"
            removeChildren()
            break
    }
}

// ============================================================================
//  Event handlers
// ============================================================================

def motionHandler(evt) {
    logDebug "motion ${evt.value} (phase ${state.phase})"

    if (evt.value == "active") {
        switch (state.phase) {

            case "IDLE":
                if (!isEnabled()) {
                    logDebug "Motion ignored -- ${enableSwitch.displayName} is off"
                    return
                }
                if (!modeAllowed()) {
                    logDebug "Motion ignored -- mode ${location.mode} is not permitted"
                    return
                }
                if (inCooldown()) {
                    logDebug "Motion ignored -- post-cycle cooldown for another " +
                             "${((state.cooldownUntil - now()) / 1000) as int}s"
                    return
                }
                startSequence()
                break

            case "WAIT":
                if (immediatePulseOnWaitMotion) {
                    logInfo "Motion during wait -- pulsing cat sensor immediately (immediate-pulse mode)"
                    addHistory("Motion during wait; pulsed cat sensor immediately")
                    beginPulse()
                } else {
                    // Rolling window: cancel the pending pulse. It gets re-armed when
                    // motion goes inactive again, giving a full fresh wait window.
                    clearTimer("waitElapsed")
                    state.deferredReason = null
                    logDebug "Wait extended -- window cancelled, will restart on inactive"
                }
                break

            case "PULSE":
                // The pulse is a short, fixed commitment -- extending it risks holding
                // the sensor past what the robot's hardware tolerates. Noted, not acted
                // on: if the cat is still there once it ends, the rotateTimeout retry
                // logic re-checks live motion before deciding what to do next.
                logDebug "Motion during initial pulse -- noted, pulse continues to completion"
                break

            case "COUNTDOWN":
                if (reassertOnCountdown == false) {
                    logDebug "Motion during countdown -- reassert disabled by config, ignoring"
                    return
                }
                logInfo "Cat returned during robot countdown -- firing reassert pulse"
                addHistory("Motion during countdown; pulsed cat sensor ${reassertPulseSec}s")
                enterReassert()
                break

            case "REASSERT":
                // Same reasoning as PULSE: let the fixed pulse finish rather than
                // extend it.
                logDebug "Motion during reassert pulse -- noted, pulse continues to completion"
                break

            case "CYCLING":
                // Deliberately ignored. The drum is mid-rotation and cannot be stopped.
                logDebug "Motion during rotation -- ignored by design"
                break
        }
        return
    }

    // evt.value == "inactive"
    if (state.phase == "WAIT") {
        scheduleTimer(((waitMinutes ?: 15) as int) * 60, "waitElapsed")
        logDebug "Motion clear -- pulsing in ${waitMinutes ?: 15} min unless motion returns"
    }
}

def contactHandler(evt) {
    logDebug "${evt.displayName} ${evt.value} (phase ${state.phase})"

    if (evt.value == "open") {
        if (state.phase == "COUNTDOWN") {
            noteContactOpened(evt.device.id as String)
            if (rotationStarted()) {
                // Two drum contacts can open within milliseconds of each other at
                // rotation start, and Hubitat can run their event handlers as
                // overlapping executions that each read state.phase as COUNTDOWN
                // before either's setPhase() write lands -- causing a duplicate
                // transition. A prior fix used an atomicState-backed latch here,
                // but that's still a check-then-set: two executions can both read
                // it as unset before either's write commits if the two contact
                // events land in the same tick (observed live 2026-09-20). Defer
                // to a scheduled job instead -- runIn's own overwrite:true
                // collapses any number of same-tick schedule calls into a single
                // queued job, so confirmRotationDetected() only ever runs once.
                runIn(1, "confirmRotationDetected", [overwrite: true])
            } else {
                logDebug "Rotation partially detected (${state.openedContacts?.size() ?: 0} of " +
                         "${drumContacts.size()}) -- waiting for the rest"
            }
        } else if (state.phase == "CYCLING") {
            clearTimer("confirmHome")
        }
        return
    }

    // evt.value == "closed"
    if (state.phase == "CYCLING" && drumIsHome()) {
        scheduleTimer((homeDebounceSec ?: 30) as int, "confirmHome")
        logDebug "Drum reads home -- confirming in ${homeDebounceSec}s"
    }
}

// Runs once per rotation no matter how many near-simultaneous drum-contact
// events raced to schedule it -- runIn(..., [overwrite: true]) guarantees only
// one queued invocation of this job name exists at a time.
def confirmRotationDetected() {
    if (state.phase != "COUNTDOWN") {
        logDebug "Rotation confirm job fired but phase is already ${state.phase} -- ignoring"
        return
    }
    logInfo "Drum rotation detected -- confirming completion"
    clearTimer("rotateTimeout")
    setPhase("CYCLING")
    scheduleTimer((cycleTimeoutSec ?: 300) as int, "cycleTimeout")
}

def enableSwitchHandler(evt) {
    if (evt.value == "off" && state.phase != "IDLE") {
        logWarn "${enableSwitch.displayName} turned off during a ${state.phase} sequence -- aborting"
        addHistory("Disabled during ${phaseLabel(state.phase)}; sequence aborted")
        resetToIdle()
    }
    // Covers both directions -- label shows "disabled" the moment the switch
    // goes off, and clears it the moment it comes back on, not just on the
    // next phase change.
    updateLabel()
}

def batteryHandler(evt) {
    if (batteryMonitorEnabled == false) return
    evaluateBattery(evt.device, evt.value as int)
}

// Runs once on every save/boot, since subscribe() only catches battery
// reports from here on -- a sensor already low when the app starts wouldn't
// otherwise be noticed until its next periodic report.
private void checkBatteryLevels() {
    if (batteryMonitorEnabled == false) return
    drumContacts?.each { dev ->
        def battery = dev.currentValue("battery")
        if (battery != null) evaluateBattery(dev, battery as int)
    }
}

// Notifies once when a sensor first drops below threshold, and again if it
// later recovers and drops below a second time -- state.lowBatteryDevices
// tracks which sensors are currently in the "already notified" state.
private void evaluateBattery(dev, int percent) {
    def threshold = (batteryThreshold ?: 50) as int
    def low = state.lowBatteryDevices ?: [:]
    def dni = "${dev.id}"
    if (percent < threshold) {
        if (low[dni] == null) {
            logWarn "${dev.displayName} battery at ${percent}% -- below ${threshold}% threshold"
            addHistory("${dev.displayName} battery low (${percent}%)")
            sendNotif "Litter Robot: ${dev.displayName} battery at ${percent}% -- below " +
                      "${threshold}% threshold, consider replacing."
        }
        low[dni] = [name: dev.displayName, pct: percent]
    } else if (low.containsKey(dni)) {
        addHistory("${low[dni].name} battery recovered (${percent}%)")
        low.remove(dni)
    }
    state.lowBatteryDevices = low
}

// ============================================================================
//  Scheduled callbacks
// ============================================================================

def logsOff() {
    log.warn "${app.label}: debug logging disabled automatically after 30 minutes"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

def waitElapsed() {
    if (state.phase != "WAIT") {
        logDebug "waitElapsed fired in phase ${state.phase} -- ignoring"
        return
    }
    if (motionSensor.currentValue("motion") == "active") {
        logDebug "waitElapsed fired but motion is active -- deferring"
        return
    }

    // Restrictions block the pulse, not the wait.
    def block = releaseBlockedReason()
    if (block) {
        if (quietHoursEnabled && quietHoursAction == "skip") {
            logInfo "Pulse blocked (${block}) and action is skip -- abandoning sequence"
            addHistory("Pulse skipped — ${block}")
            resetToIdle()
            return
        }
        if (state.deferStarted == null) state.deferStarted = now()
        def deferredFor = ((now() - state.deferStarted) / 60000) as int
        if (deferredFor >= ((deferCapMinutes ?: 600) as int)) {
            logWarn "Deferral cap of ${deferCapMinutes} min reached -- pulsing despite ${block}"
            addHistory("Deferral cap reached; forced pulse")
            if (notifyForcedRelease != false) {
                sendNotif "Litter Robot: cycling despite ${block} — deferred ${deferredFor} min."
            }
            beginPulse()
            return
        }
        state.deferredReason = "${block} (${deferredFor} min so far)"
        clearTimer("holdCapReached")   // do not count restriction time against the wait cap
        scheduleTimer(300, "waitElapsed")
        logInfo "Pulse deferred -- ${block}. Re-checking in 5 min."
        return
    }

    state.deferredReason = null
    state.deferStarted = null
    logInfo "Box quiet for ${waitMinutes} min -- starting pulse"
    beginPulse()
}

def holdCapReached() {
    if (state.phase != "WAIT") return
    logWarn "Maximum wait of ${maxHoldMinutes} min reached -- pulsing despite motion"
    addHistory("Max wait ${maxHoldMinutes}m reached; forced pulse")
    if (notifyForcedRelease != false) {
        sendNotif "Litter Robot: motion never settled in ${maxHoldMinutes} min. Pulsing anyway -- " +
                  "something may be triggering ${motionSensor.displayName} that is not a cat."
    }
    beginPulse()
}

def pulseDone() {
    if (state.phase != "PULSE") return
    release()
}

def reassertPulseDone() {
    if (state.phase != "REASSERT") return
    release()
}

def rotateTimeout() {
    if (state.phase != "COUNTDOWN") return

    state.attempts = (state.attempts ?: 0) + 1
    logWarn "Drum did not start moving within ${rotateTimeoutSec}s (attempt ${state.attempts})"
    addHistory("No rotation within ${rotateTimeoutSec}s (attempt ${state.attempts})")

    if (retryEnabled == false) {
        fault("Robot did not begin a cycle and retries are disabled")
        return
    }
    if (state.attempts >= ((maxAttempts ?: 3) as int)) {
        fault("Robot did not begin a cycle after ${state.attempts} release attempts")
        return
    }

    if (motionSensor.currentValue("motion") == "active") {
        logInfo "Retrying, but motion is active -- waiting for it to clear"
        enterWait()
    } else {
        logInfo "Retrying release (attempt ${state.attempts + 1})"
        catSensorRemote.on()
        scheduleTimer((retryPulseSec ?: 5) as int, "retryPulseDone")
    }
}

def retryPulseDone() {
    if (state.phase != "COUNTDOWN") return
    release()
}

def confirmHome() {
    if (state.phase != "CYCLING") return
    if (!drumIsHome()) {
        logDebug "confirmHome fired but the drum does not read home -- waiting"
        return
    }
    succeed()
}

def cycleTimeout() {
    if (state.phase != "CYCLING") return
    fault("Drum started rotating but never returned home -- check for a jam")
}

def watchdog() {
    if (state.phase == "IDLE") return
    logWarn "Watchdog fired -- sequence stuck in ${state.phase} for ${watchdogMinutes} min"
    addHistory("Watchdog reset from ${phaseLabel(state.phase)}")
    sendNotif "Litter Robot: cleanup sequence stalled in ${phaseLabel(state.phase)} and was reset automatically."
    resetToIdle()
}

// ============================================================================
//  State transitions
// ============================================================================

private startSequence() {
    logInfo "Motion detected -- starting cleanup sequence"
    addHistory("Sequence started")

    state.sequenceStarted = now()
    state.attempts = 0
    state.openedContacts = []
    state.deferStarted = null
    state.deferredReason = null

    armedDev()?.off()
    sequenceDev()?.on()
    dirtyDev()?.on()
    faultDev()?.off()

    // Cat sensor stays OFF here -- WAIT never asserts it, only PULSE/REASSERT do.
    scheduleTimer(((watchdogMinutes ?: 90) as int) * 60, "watchdog")
    enterWait()
}

private enterWait() {
    setPhase("WAIT")

    clearTimer("waitElapsed")
    clearTimer("rotateTimeout")
    clearTimer("retryPulseDone")

    // Hard cap on the wait phase, re-armed each time we enter it.
    scheduleTimer(((maxHoldMinutes ?: 45) as int) * 60, "holdCapReached")

    if (motionSensor.currentValue("motion") == "inactive") {
        scheduleTimer(((waitMinutes ?: 15) as int) * 60, "waitElapsed")
        logDebug "Waiting -- pulsing the cat sensor in ${waitMinutes ?: 15} min unless motion returns"
    } else {
        logDebug "Waiting -- motion active, wait timer starts when it clears"
    }
}

private beginPulse() {
    clearTimer("waitElapsed")
    clearTimer("holdCapReached")

    setPhase("PULSE")
    catSensorRemote.on()
    scheduleTimer((initialPulseSec ?: 60) as int, "pulseDone")
    logInfo "Wait complete -- pulsing cat sensor for ${initialPulseSec ?: 60}s"
}

private enterReassert() {
    setPhase("REASSERT")
    clearTimer("rotateTimeout")
    catSensorRemote.on()
    scheduleTimer((reassertPulseSec ?: 90) as int, "reassertPulseDone")
}

// Called whenever a cat-sensor pulse legitimately ends and control passes back
// to the robot: the initial pulse, a no-rotation retry pulse, or a mid-countdown
// reassert pulse all funnel through here.
private release() {
    state.openedContacts = []
    catSensorRemote.off()

    if (confirmCycle == false) {
        logInfo "Cat sensor released -- cycle confirmation disabled, returning to idle"
        addHistory("Released; confirmation disabled")
        state.cooldownUntil = now() + (((cooldownSec ?: 90) as int) * 1000)
        resetToIdleKeepingRemoteOff()
        return
    }

    setPhase("COUNTDOWN")
    scheduleTimer((rotateTimeoutSec ?: 540) as int, "rotateTimeout")
    logInfo "Cat sensor released -- robot's timer running, watching for rotation"
}

private succeed() {
    Integer durSec = state.sequenceStarted ? (((now() - state.sequenceStarted) / 1000) as int) : null
    def dur = durSec != null ? fmtSecs(durSec) : "unknown"
    logInfo "Cycle confirmed complete (sequence took ${dur})"
    addHistory("Cycle confirmed complete after ${dur}")

    state.lastSuccess = now()
    state.lastCycleDurationSec = durSec
    if (durSec != null) {
        state.cycleDurations = ((state.cycleDurations ?: []) + [durSec]).takeRight(CYCLE_HISTORY_MAX)
    }
    state.attempts = 0
    state.cooldownUntil = now() + (((cooldownSec ?: 90) as int) * 1000)
    state.lastFault = null
    state.lastFaultReason = null

    faultDev()?.off()
    if (notifySuccess) sendNotif "Litter Robot: cycle confirmed complete."

    resetToIdle()
}

private fault(String reason) {
    logWarn "FAULT: ${reason}"
    addHistory("<b>FAULT</b> — ${reason}")

    state.lastFault = now()
    state.lastFaultReason = reason
    faultDev()?.on()
    sendNotif "Litter Robot: ${reason}"

    // Clear timers and restore control, but leave the fault indicator set so the
    // status page and app label show that something needs attention.
    unscheduleAll()
    setPhase("IDLE")
    sequenceDev()?.off()
    dirtyDev()?.off()
    armedDev()?.on()
    catSensorRemote.off()
    state.attempts = 0
    state.deferredReason = null
    state.cooldownUntil = now() + (((cooldownSec ?: 90) as int) * 1000)
    updateLabel()
}

private resetToIdle() {
    unscheduleAll()
    setPhase("IDLE")
    state.attempts = 0
    state.deferStarted = null
    state.deferredReason = null
    applyIdleOutputs()
    updateLabel()
}

// Same as resetToIdle but does not re-issue an off() to the cat sensor, used on the
// confirmation-disabled path where we have just released it deliberately.
private resetToIdleKeepingRemoteOff() {
    unscheduleAll()
    setPhase("IDLE")
    state.attempts = 0
    state.deferStarted = null
    state.deferredReason = null
    armedDev()?.on()
    sequenceDev()?.off()
    dirtyDev()?.off()
    updateLabel()
}

private applyIdleOutputs() {
    armedDev()?.on()
    sequenceDev()?.off()
    dirtyDev()?.off()
    catSensorRemote.off()
    updateStatusLight()
}

private setPhase(String p) {
    if (state.phase != p) {
        logInfo "Phase ${state.phase} -> ${p}"
        addHistory("${phaseLabel(state.phase)} &rarr; ${phaseLabel(p)}")
        state.phase = p
        updateLabel()
    }
    // Outside the change check: a fault clearing or being raised without a phase
    // change (e.g. the manual reset button) still needs to be reflected.
    updateStatusLight()
}

// Green: idle and no unresolved fault. Red: motion has been detected and we're
// waiting it out, or the cat sensor is actively pulsed, or a fault hasn't been
// cleared yet -- fault wins over IDLE so the light doesn't quietly go green
// while the fault switch is still on. Yellow: the robot's own countdown or
// cycle is running and out of the app's hands.
private void updateStatusLight() {
    if (!statusLight) return
    String color
    if (faultActive()) {
        color = "red"
    } else {
        switch (state.phase) {
            case "WAIT":
            case "PULSE":
            case "REASSERT":           color = "red";    break
            case "COUNTDOWN":
            case "CYCLING":            color = "yellow"; break
            default:                   color = "green"
        }
    }
    try {
        statusLight.on()
        statusLight.setColor(STATUS_COLORS[color])
    } catch (e) {
        logWarn "Could not set status light (${statusLight.displayName}) to ${color}: ${e.message}"
    }
}

private unscheduleAll() {
    ["waitElapsed", "holdCapReached", "pulseDone", "rotateTimeout", "retryPulseDone",
     "reassertPulseDone", "confirmRotationDetected", "confirmHome", "cycleTimeout",
     "watchdog"].each { clearTimer(it) }
}

// ============================================================================
//  Timer bookkeeping (drives the "next event" status line)
// ============================================================================

// runIn()/unschedule() wrappers that also remember each deadline as an absolute
// epoch, since Hubitat has no general API to ask "how long until job X fires."
private void scheduleTimer(int seconds, String handler) {
    runIn(seconds, handler, [overwrite: true])
    if (state.deadlines == null) state.deadlines = [:]
    state.deadlines[handler] = now() + (seconds * 1000L)
}

private void clearTimer(String handler) {
    unschedule(handler)
    state.deadlines?.remove(handler)
}

// ============================================================================
//  Contact logic
// ============================================================================

private void noteContactOpened(String devId) {
    if (state.openedContacts == null) state.openedContacts = []
    if (!state.openedContacts.contains(devId)) {
        state.openedContacts = state.openedContacts + [devId]
    }
}

private boolean rotationStarted() {
    if ((rotationDetect ?: "any") == "all") {
        return (state.openedContacts?.size() ?: 0) >= drumContacts.size()
    }
    return (state.openedContacts?.size() ?: 0) >= 1
}

private boolean drumIsHome() {
    if ((homeDetect ?: "all") == "any") {
        return drumContacts.any { it.currentValue("contact") == "closed" }
    }
    return drumContacts.every { it.currentValue("contact") == "closed" }
}

// ============================================================================
//  Restrictions
// ============================================================================

private boolean modeAllowed() {
    return !allowedModes || allowedModes.contains(location.mode)
}

private boolean inQuietHours() {
    if (!quietHoursEnabled || !quietStart || !quietEnd) return false
    return timeOfDayIsBetween(toDateTime(quietStart), toDateTime(quietEnd),
                              new Date(), location.timeZone)
}

// Returns a human-readable reason the release is blocked, or null if it may proceed.
private String releaseBlockedReason() {
    if (!modeAllowed()) return "mode is ${location.mode}"
    if (inQuietHours())  return "quiet hours"
    return null
}

// ============================================================================
//  Helpers
// ============================================================================

private boolean isEnabled() {
    return enableSwitch.currentValue("switch") == "on"
}

private boolean inCooldown() {
    return state.cooldownUntil != null && now() < state.cooldownUntil
}

private void addHistory(String entry) {
    if (state.history == null) state.history = []
    state.history = ([[t: now(), e: entry]] + state.history).take(HISTORY_MAX)
}

// NOTE: deliberately not named notify() -- java.lang.Object.notify() already exists
// and Groovy's dynamic dispatch can resolve to it in unexpected ways.
private void sendNotif(String msg) {
    notifiers?.each { it.deviceNotification(msg) }
}

private void logDebug(String msg) { if (logEnable) log.debug "${app.label}: ${msg}" }
private void logInfo(String msg)  { if (txtEnable != false) log.info "${app.label}: ${msg}" }
private void logWarn(String msg)  { log.warn "${app.label}: ${msg}" }
