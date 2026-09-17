/**
 *  Litter Robot Cleanup Manager
 *
 *  Holds the Litter-Robot's cat sensor asserted until the box has been quiet for a
 *  rolling window, then releases it and confirms the drum actually completed a cycle.
 *
 *  State machine:
 *
 *    IDLE ──motion──> HOLD ──quiet window elapsed──> COUNTDOWN ──rotation──> CYCLING
 *                      ^                                  │                     │
 *                      └────────── motion returns ────────┘                     │
 *                                                                               │
 *    IDLE <──── drum returned home + debounce ──────────────────────────────────┘
 *
 *  HOLD       cat sensor asserted; robot will not cycle. Any motion restarts the window.
 *  COUNTDOWN  cat sensor released; robot's internal timer running. Motion here re-asserts
 *             and returns to HOLD. No drum movement before the timeout means a retry.
 *  CYCLING    drum is physically rotating. Motion is deliberately ignored -- rotation
 *             cannot be stopped, and re-asserting here would orphan the sequence.
 *
 *  Author: Dan Schaff
 *
 *  ---------------------------------------------------------------------------
 *  CHANGELOG
 *  ---------------------------------------------------------------------------
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

@Field static final String APP_VERSION = "1.3.2"
@Field static final Integer HISTORY_MAX = 25

// Status outputs the app can create for itself. Deliberately excludes the cat
// sensor remote and the enable switch: those interface with real hardware and
// with the user, so an app-created copy would be wired to nothing.
@Field static final List CHILD_SPECS = [
    [key: "armed",    label: "LR Armed"],
    [key: "sequence", label: "LR Sequence Active"],
    [key: "dirty",    label: "LR Dirty"],
    [key: "fault",    label: "LR Cycle Fault"],
]

definition(
    name: "Litter Robot Cleanup Manager",
    namespace: "dschaff",
    author: "Dan Schaff",
    description: "Holds the Litter-Robot cat sensor until the box has been quiet, then confirms the drum cycled.",
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
            input "btnReset", "button", title: "Reset to idle now"
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
            paragraph "<small>ON asserts &quot;cat present&quot; and suppresses the cycle. " +
                      "OFF releases and starts the robot's own internal timer. " +
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
                    title: "Sequence-active indicator — ON for the whole hold + cycle", required: false
                input "dirtySwitch", "capability.switch",
                    title: "Dirty indicator — ON from trigger until a confirmed cycle", required: false
                input "faultSwitch", "capability.switch",
                    title: "Fault indicator — ON when a cycle could not be confirmed", required: false
                paragraph "<small>Select your existing virtual switches here if they already have " +
                          "dashboard tiles or other automations pointing at them.</small>"
            }
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

        section("The hold") {
            input "quietMinutes", "number",
                title: "Quiet window (minutes)",
                defaultValue: 5, required: true, range: "1..720"
            paragraph "<small>Continuous no-motion required before releasing. Rolling: any motion " +
                      "restarts it from zero, however many times." +
                      "<br><b>Example:</b> a cat visits for a minute, leaves, and the box stays quiet " +
                      "for the next 5 minutes straight — the app releases at that point. If a second " +
                      "cat wanders in partway through those 5 minutes, the window restarts from zero " +
                      "the moment it leaves, however many times that happens.</small>"
            input "maxHoldMinutes", "number",
                title: "Maximum hold (minutes)",
                defaultValue: 20, required: true, range: "1..1440"
            paragraph "<small>Force the release after this long even if motion never settles. Set " +
                      "this to at least 3&times; the quiet window (e.g. 15-20 min for a 5 min quiet " +
                      "window) or it will fire routinely on ordinary multi-cat traffic." +
                      "<br><b>Example:</b> several cats trickle through back-to-back and motion never " +
                      "settles for a full quiet window — once this many total minutes have passed " +
                      "since the sequence started, the app stops waiting and releases anyway. If this " +
                      "fires often, that usually means a stuck sensor or a cat that won't leave, not " +
                      "just a busy box.</small>"
        }

        section("The cycle") {
            input "rotateTimeoutSec", "number",
                title: "Release → rotation (seconds)",
                defaultValue: 300, required: true, range: "30..1800"
            paragraph "<small>The robot's own internal timer plus margin. Use ~300 for the 3-minute " +
                      "setting, ~540 for the 7-minute setting.</small>"
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
            input "reassertSec", "number",
                title: "Re-assert pulse (seconds)",
                defaultValue: 5, required: true, range: "1..120"
            paragraph "<small>How long to hold the cat sensor on a retry when no cat is present.</small>"
            input "cooldownSec", "number",
                title: "Post-cycle cooldown (seconds)",
                defaultValue: 90, required: true, range: "0..900"
            paragraph "<small>Ignore motion this long after a cycle, so the drum's own movement " +
                      "cannot trigger a new sequence. Set to 0 to disable.</small>"
            input "watchdogMinutes", "number",
                title: "Watchdog (minutes)",
                defaultValue: 60, required: true, range: "5..1440"
            paragraph "<small>Force a reset if a sequence has not finished in this long, start to " +
                      "end — hold, countdown, and cycling together. Must exceed your maximum hold, " +
                      "or it will cut valid holds short before they ever reach that cap." +
                      "<br><b>Example:</b> with a 20-minute maximum hold, a 60-minute watchdog leaves " +
                      "room for the hold cap itself plus the robot's own countdown and cycle time, " +
                      "while still catching a sequence that's genuinely stuck — a jammed drum or a " +
                      "sensor that stopped reporting.</small>"
        }
    }
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
                title: "Re-assert the cat sensor if motion returns during the countdown",
                defaultValue: true
            paragraph "<small>A cat returning in the 3-minute window pushes the cycle back out by " +
                      "a full quiet window. Off: the countdown runs to completion regardless.</small>"
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
    if (notifiers) bits << "${notifiers.size()} notifier(s)"
    return bits.join(" · ")
}

private timingSummary() {
    return "${quietMinutes ?: 20} min quiet window · max hold ${maxHoldMinutes ?: 120} min · " +
           "${maxAttempts ?: 3} attempts"
}

private optionsSummary() {
    def bits = []
    bits << ((confirmCycle == false) ? "no cycle confirmation" : "confirm cycle")
    bits << "rotation: ${rotationDetect ?: 'any'} · home: ${homeDetect ?: 'all'}"
    if (reassertOnCountdown == false) bits << "no countdown re-assert"
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
    lines << "<b>Phase:</b> ${state.phase ?: 'IDLE'}"
    if (state.phase && state.phase != "IDLE") {
        lines << "<b>Release attempts:</b> ${state.attempts ?: 0} of ${maxAttempts}"
        if (state.sequenceStarted) {
            lines << "<b>Sequence started:</b> ${fmt(state.sequenceStarted)} " +
                     "(${elapsed(state.sequenceStarted)} ago)"
        }
        if (state.deferredReason) lines << "<b>Deferred:</b> ${state.deferredReason}"
    }
    if (state.lastSuccess) lines << "<b>Last confirmed cycle:</b> ${fmt(state.lastSuccess)}"
    if (state.lastFault)   lines << "<b>Last fault:</b> ${state.lastFaultReason} — ${fmt(state.lastFault)}"
    return lines.join("<br>")
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
    def secs = ((now() - since) / 1000) as int
    if (secs < 60) return "${secs}s"
    def mins = (secs / 60) as int
    return (mins < 60) ? "${mins}m" : "${(mins / 60) as int}h ${mins % 60}m"
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
    subscribe(enableSwitch, "switch.off", "enableOffHandler")

    // Hubitat convention: never leave debug logging on indefinitely.
    if (logEnable) {
        runIn(1800, "logsOff", [overwrite: true])
        logDebug "Debug logging will auto-disable in 30 minutes"
    }

    // Warn about settings that will misbehave together rather than failing quietly.
    if ((watchdogMinutes as int) <= (maxHoldMinutes as int)) {
        logWarn "Watchdog (${watchdogMinutes} min) is not longer than max hold " +
                "(${maxHoldMinutes} min) -- valid holds will be cut short"
    }
    if ((maxHoldMinutes as int) < (quietMinutes as int) * 2) {
        logWarn "Max hold (${maxHoldMinutes} min) is less than 2x the quiet window " +
                "(${quietMinutes} min) -- forced releases will be common"
    }

    // A settings change mid-sequence leaves timers unscheduled by updated().
    // Rather than try to rebuild them, reset to a known-good state.
    if (state.phase != "IDLE") {
        logWarn "Settings changed during a ${state.phase} sequence -- resetting to IDLE"
        addHistory("Config saved during ${state.phase}; forced reset")
        resetToIdle()
    } else {
        applyIdleOutputs()
    }

    updateLabel()
    logInfo "Initialized v${APP_VERSION}. Quiet window ${quietMinutes} min, max hold ${maxHoldMinutes} min."
}

private updateLabel() {
    def phase = state.phase ?: "IDLE"
    def color = (phase == "IDLE") ? "green" : "orange"
    if (phase == "IDLE" && faultActive()) color = "red"
    app.updateLabel("Litter Robot Cleanup Manager <span style='color:${color}'>(${phase})</span>")
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
            addHistory("Manual reset from ${state.phase}")
            faultDev()?.off()
            state.lastFault = null
            state.lastFaultReason = null
            resetToIdle()
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

            case "HOLD":
                // Rolling window: cancel the pending release. It gets re-armed when
                // motion goes inactive again, giving a full fresh quiet window.
                unschedule("quietElapsed")
                state.deferredReason = null
                logDebug "Hold extended -- quiet window cancelled, will restart on inactive"
                break

            case "COUNTDOWN":
                if (reassertOnCountdown == false) {
                    logDebug "Motion during countdown -- re-assert disabled by config, ignoring"
                    return
                }
                logInfo "Cat returned during robot countdown -- re-asserting cat sensor"
                addHistory("Motion during countdown; re-asserted cat sensor")
                unschedule("rotateTimeout")
                catSensorRemote.on()
                enterHold()
                break

            case "CYCLING":
                // Deliberately ignored. The drum is mid-rotation and cannot be stopped.
                logDebug "Motion during rotation -- ignored by design"
                break
        }
        return
    }

    // evt.value == "inactive"
    if (state.phase == "HOLD") {
        runIn((quietMinutes as int) * 60, "quietElapsed", [overwrite: true])
        logDebug "Motion clear -- releasing in ${quietMinutes} min unless motion returns"
    }
}

def contactHandler(evt) {
    logDebug "${evt.displayName} ${evt.value} (phase ${state.phase})"

    if (evt.value == "open") {
        if (state.phase == "COUNTDOWN") {
            noteContactOpened(evt.device.id as String)
            if (rotationStarted()) {
                logInfo "Drum rotation detected -- confirming completion"
                unschedule("rotateTimeout")
                setPhase("CYCLING")
                runIn(cycleTimeoutSec as int, "cycleTimeout", [overwrite: true])
            } else {
                logDebug "Rotation partially detected (${state.openedContacts?.size() ?: 0} of " +
                         "${drumContacts.size()}) -- waiting for the rest"
            }
        } else if (state.phase == "CYCLING") {
            unschedule("confirmHome")
        }
        return
    }

    // evt.value == "closed"
    if (state.phase == "CYCLING" && drumIsHome()) {
        runIn(homeDebounceSec as int, "confirmHome", [overwrite: true])
        logDebug "Drum reads home -- confirming in ${homeDebounceSec}s"
    }
}

def enableOffHandler(evt) {
    if (state.phase != "IDLE") {
        logWarn "${enableSwitch.displayName} turned off during a ${state.phase} sequence -- aborting"
        addHistory("Disabled during ${state.phase}; sequence aborted")
        resetToIdle()
    }
}

// ============================================================================
//  Scheduled callbacks
// ============================================================================

def logsOff() {
    log.warn "${app.label}: debug logging disabled automatically after 30 minutes"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

def quietElapsed() {
    if (state.phase != "HOLD") {
        logDebug "quietElapsed fired in phase ${state.phase} -- ignoring"
        return
    }
    if (motionSensor.currentValue("motion") == "active") {
        logDebug "quietElapsed fired but motion is active -- deferring"
        return
    }

    // Restrictions block the release, not the hold.
    def block = releaseBlockedReason()
    if (block) {
        if (quietHoursEnabled && quietHoursAction == "skip") {
            logInfo "Release blocked (${block}) and action is skip -- abandoning sequence"
            addHistory("Release skipped — ${block}")
            resetToIdle()
            return
        }
        if (state.deferStarted == null) state.deferStarted = now()
        def deferredFor = ((now() - state.deferStarted) / 60000) as int
        if (deferredFor >= ((deferCapMinutes ?: 600) as int)) {
            logWarn "Deferral cap of ${deferCapMinutes} min reached -- releasing despite ${block}"
            addHistory("Deferral cap reached; forced release")
            if (notifyForcedRelease != false) {
                sendNotif "Litter Robot: cycling despite ${block} — deferred ${deferredFor} min."
            }
            release()
            return
        }
        state.deferredReason = "${block} (${deferredFor} min so far)"
        unschedule("holdCapReached")   // do not count restriction time against the hold cap
        runIn(300, "quietElapsed", [overwrite: true])
        logInfo "Release deferred -- ${block}. Re-checking in 5 min."
        return
    }

    state.deferredReason = null
    state.deferStarted = null
    logInfo "Box quiet for ${quietMinutes} min -- releasing cat sensor"
    release()
}

def holdCapReached() {
    if (state.phase != "HOLD") return
    logWarn "Maximum hold of ${maxHoldMinutes} min reached -- releasing despite motion"
    addHistory("Max hold ${maxHoldMinutes}m reached; forced release")
    if (notifyForcedRelease != false) {
        sendNotif "Litter Robot: motion never settled in ${maxHoldMinutes} min. Releasing anyway -- " +
                  "something may be triggering ${motionSensor.displayName} that is not a cat."
    }
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
    if (state.attempts >= (maxAttempts as int)) {
        fault("Robot did not begin a cycle after ${state.attempts} release attempts")
        return
    }

    if (motionSensor.currentValue("motion") == "active") {
        logInfo "Retrying, but motion is active -- returning to hold"
        catSensorRemote.on()
        enterHold()
    } else {
        logInfo "Retrying release (attempt ${state.attempts + 1})"
        catSensorRemote.on()
        runIn(reassertSec as int, "reassertDone", [overwrite: true])
    }
}

def reassertDone() {
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
    addHistory("Watchdog reset from ${state.phase}")
    sendNotif "Litter Robot: cleanup sequence stalled in ${state.phase} and was reset automatically."
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

    catSensorRemote.on()
    runIn((watchdogMinutes as int) * 60, "watchdog", [overwrite: true])
    enterHold()
}

private enterHold() {
    setPhase("HOLD")

    unschedule("quietElapsed")
    unschedule("rotateTimeout")
    unschedule("reassertDone")

    // Hard cap on the hold phase, re-armed each time we enter it.
    runIn((maxHoldMinutes as int) * 60, "holdCapReached", [overwrite: true])

    if (motionSensor.currentValue("motion") == "inactive") {
        runIn((quietMinutes as int) * 60, "quietElapsed", [overwrite: true])
        logDebug "Holding -- releasing in ${quietMinutes} min unless motion returns"
    } else {
        logDebug "Holding -- motion active, quiet window starts when it clears"
    }
}

private release() {
    unschedule("quietElapsed")
    unschedule("holdCapReached")

    state.openedContacts = []
    catSensorRemote.off()

    if (confirmCycle == false) {
        logInfo "Cat sensor released -- cycle confirmation disabled, returning to idle"
        addHistory("Released; confirmation disabled")
        state.cooldownUntil = now() + ((cooldownSec as int) * 1000)
        resetToIdleKeepingRemoteOff()
        return
    }

    setPhase("COUNTDOWN")
    runIn(rotateTimeoutSec as int, "rotateTimeout", [overwrite: true])
    logInfo "Cat sensor released -- robot's timer running, watching for rotation"
}

private succeed() {
    def dur = state.sequenceStarted ? elapsed(state.sequenceStarted) : "unknown"
    logInfo "Cycle confirmed complete (sequence took ${dur})"
    addHistory("Cycle confirmed complete after ${dur}")

    state.lastSuccess = now()
    state.attempts = 0
    state.cooldownUntil = now() + ((cooldownSec as int) * 1000)
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
    state.cooldownUntil = now() + ((cooldownSec as int) * 1000)
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
}

private setPhase(String p) {
    if (state.phase != p) {
        logInfo "Phase ${state.phase} -> ${p}"
        addHistory("${state.phase} &rarr; ${p}")
        state.phase = p
        updateLabel()
    }
}

private unscheduleAll() {
    ["quietElapsed", "holdCapReached", "rotateTimeout",
     "reassertDone", "confirmHome", "cycleTimeout", "watchdog"].each { unschedule(it) }
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
