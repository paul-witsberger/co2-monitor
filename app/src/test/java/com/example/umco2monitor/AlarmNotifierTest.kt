package com.example.umco2monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AlarmNotifierTest {

    private lateinit var mockContext: Context
    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var mockAudioManager: AudioManager

    @BeforeEach
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)
        mockAudioManager = mockk(relaxed = true)

        mockkConstructor(NotificationChannel::class)
        every { anyConstructed<NotificationChannel>().description = any() } returns Unit

        // 1. Intercept Intent chaining so it doesn't crash
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setPackage(any()) } answers { call.invocation.self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers { call.invocation.self as Intent }

        // 2. Intercept NotificationCompat.Builder completely so it never touches the Android Framework
        mockkConstructor(NotificationCompat.Builder::class)
        every { anyConstructed<NotificationCompat.Builder>().setSmallIcon(any<Int>()) } answers { call.invocation.self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setContentTitle(any()) } answers { call.invocation.self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setContentText(any()) } answers { call.invocation.self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setStyle(any()) } answers { call.invocation.self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setPriority(any()) } answers { call.invocation.self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setAutoCancel(any()) } answers { call.invocation.self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().addAction(any<Int>(), any(), any()) } answers { call.invocation.self as NotificationCompat.Builder }
        every { anyConstructed<NotificationCompat.Builder>().setDeleteIntent(any()) } answers { call.invocation.self as NotificationCompat.Builder }

        // Return a safe, fake Notification when build() is called at the end of the chain
        every { anyConstructed<NotificationCompat.Builder>().build() } returns mockk<Notification>(relaxed = true)
        every { mockContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockNotificationManager
        every { mockContext.getSystemService(Context.AUDIO_SERVICE) } returns mockAudioManager

        // Mock AudioManager
        every { mockAudioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM) } returns 10
        every { mockAudioManager.getStreamVolume(AudioManager.STREAM_ALARM) } returns 10

        // Mock Intents & PendingIntents
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)

        // Mock Audio/Media
        mockkStatic(RingtoneManager::class)
        every { RingtoneManager.getDefaultUri(any()) } returns mockk(relaxed = true)

        val mockAudioBuilder = mockk<AudioAttributes.Builder>(relaxed = true)
        mockkConstructor(AudioAttributes.Builder::class)
        every { anyConstructed<AudioAttributes.Builder>().setUsage(any()) } returns mockAudioBuilder
        every { mockAudioBuilder.setContentType(any()) } returns mockAudioBuilder
        every { mockAudioBuilder.build() } returns mockk(relaxed = true)

        mockkConstructor(MediaPlayer::class)
        every { anyConstructed<MediaPlayer>().setDataSource(any<Context>(), any<android.net.Uri>()) } returns Unit
        every { anyConstructed<MediaPlayer>().setAudioAttributes(any()) } returns Unit
        every { anyConstructed<MediaPlayer>().isLooping = any() } returns Unit
        every { anyConstructed<MediaPlayer>().prepare() } returns Unit
        every { anyConstructed<MediaPlayer>().start() } returns Unit
        every { anyConstructed<MediaPlayer>().stop() } returns Unit
        every { anyConstructed<MediaPlayer>().release() } returns Unit
        every { anyConstructed<MediaPlayer>().isPlaying } returns true // Simulate it playing
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun showAlarm_urgent_startsMediaPlayer() {
        val notifier = AlarmNotifier(mockContext)

        notifier.showAlarm("Urgent", "Message", AlertType.URGENT, 101)

        // Verifies the notification fired, AND the media player started
        verify { mockNotificationManager.notify(101, any()) }
        verify(exactly = 1) { anyConstructed<MediaPlayer>().start() }
    }

    @Test
    fun showAlarm_regular_doesNotStartMediaPlayer() {
        val notifier = AlarmNotifier(mockContext)

        notifier.showAlarm("Regular", "Message", AlertType.REGULAR, 102)

        verify { mockNotificationManager.notify(102, any()) }
        verify(exactly = 0) { anyConstructed<MediaPlayer>().start() }
    }

    @Test
    fun resolveUrgentAlarm_stopsMediaPlayer_whenRegistryIsEmpty() {
        val notifier = AlarmNotifier(mockContext)

        // Start two urgent alarms
        notifier.showAlarm("Urgent 1", "Msg", AlertType.URGENT, 101)
        notifier.showAlarm("Urgent 2", "Msg", AlertType.URGENT, 102)

        // Resolve one - Player should NOT stop yet
        notifier.resolveUrgentAlarm(101)
        verify(exactly = 0) { anyConstructed<MediaPlayer>().stop() }

        // Resolve the last one - Player SHOULD stop
        notifier.resolveUrgentAlarm(102)
        verify(exactly = 1) { anyConstructed<MediaPlayer>().stop() }
    }
}