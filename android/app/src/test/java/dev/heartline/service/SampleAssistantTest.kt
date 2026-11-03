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

    @Test fun spokenDurations() {
        assertEquals("1 hour 30 minutes", SampleAssistant.spoken(5400))
        assertEquals("45 seconds", SampleAssistant.spoken(45))
    }
}
