package com.example.umco2monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private enum class AlarmStatus { NORMAL, INITIAL_ALARM, URGENT_ALARM }
private data class AlarmState(var status: AlarmStatus = AlarmStatus.NORMAL, var triggeredAt: Instant? = null)

/**
 * Contains the business logic for analyzing sensor data and triggering alarms.
 *
 * @param scope The coroutine scope for background processing.
 * @param preferencesManager Manages user-selected alarm priorities.
 * @param notifier The interface to the Android OS for showing notifications.
 */
class AlarmMonitor(
    private val scope: CoroutineScope,
    private val preferencesManager: AlarmPreferencesManager,
    private val notifier: AlarmNotifier
) {
    private var currentPreferences: AlarmPreferences = AlarmPreferences()

    private val highCo2Alarm: AlarmState = AlarmState()
    private val lowCo2Alarm: AlarmState = AlarmState()

    // TODO: Implement user-customizable CO2 threshold below which the alarm will trigger.
    private val lowCo2Threshold: UShort = 400u
    private val highCo2Threshold: UShort = 2000u

    private var watchdogJob: Job? = null

    init {
        observePreferences()
        observeSensorData()
    }

    private fun observePreferences() {
        preferencesManager.preferencesFlow
            .onEach { preferences: AlarmPreferences ->
                currentPreferences = preferences
                Timber.d("Alarm preferences updated: $currentPreferences")
            }
            .launchIn(scope)
    }

    private fun observeSensorData() {
        BluetoothHandler.co2Value
            .onEach { co2: UShort? ->
                if (co2 != null) {
                    resetWatchdog()
                    checkAlarms(co2)
                }
            }
            .launchIn(scope)
    }

    private fun resetWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(15.seconds)
            notifier.showAlarm(
                title = "Sensor Connection Lost",
                message = "No CO2 data received for 15 seconds. Check device and battery.",
                alertType = currentPreferences.connectionLostType,
                notificationId = WATCHDOG_NOTIFICATION_ID
            )
        }
    }

    private fun checkAlarms(co2Reading: UShort) {
        val now: Instant = kotlin.time.Clock.System.now()
        val timeFormat: SimpleDateFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

        checkAlarmState(co2Reading, highCo2Threshold, true, highCo2Alarm, HIGH_CO2_NOTIFICATION_ID, now, timeFormat)
        checkAlarmState(co2Reading, lowCo2Threshold, false, lowCo2Alarm, LOW_CO2_NOTIFICATION_ID, now, timeFormat)
    }

    private fun <T: Comparable<T>> checkAlarmState(
        currentValue: T,
        threshold: T,
        isHighAlarm: Boolean,
        alarmState: AlarmState,
        notificationId: Int,
        now: Instant,
        timeFormat: SimpleDateFormat
    ) {
        val isOutOfRange: Boolean = if (isHighAlarm) currentValue > threshold else currentValue < threshold

        if (isOutOfRange) {
            when (alarmState.status) {
                AlarmStatus.NORMAL -> {
                    alarmState.status = AlarmStatus.INITIAL_ALARM
                    alarmState.triggeredAt = now
                    val timeString: String = timeFormat.format(Date(now.toEpochMilliseconds()))
                    val direction: String = if (isHighAlarm) "exceeded" else "dropped below"

                    notifier.showAlarm(
                        title = "CO2 Alert",
                        message = "CO2 level $direction $threshold ppm at $timeString.",
                        alertType = currentPreferences.initialOutageType,
                        notificationId = notificationId
                    )
                }
                AlarmStatus.INITIAL_ALARM -> {
                    val duration: kotlin.time.Duration = now - alarmState.triggeredAt!!
                    if (duration > 30.seconds) {
                        alarmState.status = AlarmStatus.URGENT_ALARM
                        val direction: String = if (isHighAlarm) "above" else "below"

                        notifier.showAlarm(
                            title = "Sustained CO2 Alert",
                            message = "CO2 level has been $direction $threshold ppm for over 30 seconds.",
                            alertType = currentPreferences.sustainedOutageType,
                            notificationId = notificationId
                        )
                    }
                }
                AlarmStatus.URGENT_ALARM -> { }
            }
        } else {
            if (alarmState.status != AlarmStatus.NORMAL) {
                val timeString: String = timeFormat.format(Date(now.toEpochMilliseconds()))
                notifier.showAlarm(
                    title = "CO2 Level Normal",
                    message = "CO2 level returned to normal range at $timeString.",
                    alertType = currentPreferences.returnToNormalType,
                    notificationId = notificationId
                )

                notifier.resolveUrgentAlarm(notificationId)

                // Reset the alarm state
                alarmState.status = AlarmStatus.NORMAL
                alarmState.triggeredAt = null
            }
        }
    }

    /**
     * Called when the user manually taps "DISMISS ALARM" on the notification.
     */
    fun muteAlarm(notificationId: Int) {
        // 1. Kill the loud noise
        notifier.resolveUrgentAlarm(notificationId)

        // 2. Remove the notification from the screen so the user knows it worked
        notifier.cancelNotification(notificationId)

        // Do NOT reset the state machine here. The state stays URGENT_ALARM.
        if (notificationId == WATCHDOG_NOTIFICATION_ID) {
            watchdogJob?.cancel()
        }
    }

    /**
     * Called when the user manually dismisses the alarm, or when conditions return to normal.
     */
    fun dismissAlarm(notificationId: Int) {
        notifier.resolveUrgentAlarm(notificationId)

        when (notificationId) {
            HIGH_CO2_NOTIFICATION_ID -> {
                highCo2Alarm.status = AlarmStatus.NORMAL
                highCo2Alarm.triggeredAt = null
            }
            LOW_CO2_NOTIFICATION_ID -> {
                lowCo2Alarm.status = AlarmStatus.NORMAL
                lowCo2Alarm.triggeredAt = null
            }
        }
    }

    companion object {
        const val HIGH_CO2_NOTIFICATION_ID: Int = 101
        const val LOW_CO2_NOTIFICATION_ID: Int = 102
        const val WATCHDOG_NOTIFICATION_ID: Int = 103
    }
}