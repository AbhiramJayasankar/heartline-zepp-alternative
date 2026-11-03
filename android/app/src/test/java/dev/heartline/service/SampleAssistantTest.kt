package dev.heartline.service

import dev.heartline.service.SampleAssistant.Ask
import java.time.LocalTime
import org.junit.Test
import org.junit.Assert.assertEquals

class SampleAssistantTest {
    private fun parse(s: String) = SampleAssistant.parse(s)

    @Test fun timers() {
        assertEquals(Ask.Timer(300), parse("Set a timer for 5 minutes"))
        assertEquals(Ask.Timer(90), parse("timer for one minute and thirty seconds"))
        assertEquals(Ask.Timer(1800), parse("set a timer for half an hour"))
        assertEquals(Ask.CancelTimer, parse("cancel the timer"))
        assertEquals(Ask.Timer(60), parse("said i am a for one minute")) // heard by Vosk on the watch, 2026-11-03
    }

    @Test fun alarms() {
        assertEquals(Ask.Alarm(LocalTime.of(7, 0)), parse("Set an alarm for 7 a.m."))
        assertEquals(Ask.Alarm(LocalTime.of(18, 30)), parse("wake me up at 6:30 pm"))
        assertEquals(Ask.Alarm(LocalTime.of(19, 0)), parse("alarm at seven pm"))
    }

    @Test fun reminders() {
        assertEquals(Ask.Reminder(120, "drink water"), parse("Remind me in 2 minutes to drink water"))
        assertEquals(Ask.Reminder(600, "call mom"), parse("remind me to call mom in 10 minutes"))
    }

    @Test fun questions() {
        assertEquals(Ask.Time, parse("What time is it?"))
        assertEquals(Ask.HeartRate, parse("What's my heart rate"))
        assertEquals(Ask.Steps, parse("how many steps have I done"))
        assertEquals(Ask.Weather, parse("what's the weather like"))
        assertEquals(Ask.Weather, parse("whether"))
        assertEquals(Ask.Weather, parse("weber"))
        assertEquals(Ask.Help, parse("what can you do"))
        assertEquals(Ask.Chat, parse("let's chat"))
        assertEquals(Ask.Echo("Hello, can you hear me?"), parse("Hello, can you hear me?"))
    }

    @Test fun spokenNumbers() {
        assertEquals("timer for 25 minutes", SampleAssistant.normalizeNumbers("timer for twenty five minutes"))
        assertEquals("alarm at 9:30 am", SampleAssistant.normalizeNumbers("alarm at nine thirty am"))
        assertEquals("wake me at 7:00", SampleAssistant.normalizeNumbers("wake me at seven o'clock"))
        assertEquals("1 minute and 30 seconds", SampleAssistant.normalizeNumbers("one minute and thirty seconds"))
        assertEquals("150 steps", SampleAssistant.normalizeNumbers("one hundred fifty steps"))
        // Heard on the watch, 2026-11-04: a bare hour means whichever of 7:00 and 19:00 comes first.
        assertEquals(Ask.Alarm(LocalTime.of(7, 0), either = true), parse("alarm at seven"))
        assertEquals(Ask.Alarm(LocalTime.of(9, 30), either = true), parse("set an alarm for nine thirty"))
        assertEquals(Ask.Timer(300), parse("timer for five minutes"))
        assertEquals(Ask.Reminder(1200, "stretch"), parse("remind me in twenty minutes to stretch"))
        assertEquals(Ask.Echo("set an alarm for tomorrow my and thirty am"), parse("set an alarm for tomorrow my and thirty am"))
    }

    @Test fun spokenDurations() {
        assertEquals("1 hour 30 minutes", SampleAssistant.spoken(5400))
        assertEquals("45 seconds", SampleAssistant.spoken(45))
    }
}
