package com.example.mapstyleeditor.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How spoken (or phone-typed) requests are read: the system recognizer's and Vosk's styles alike. */
class VoiceCommandTest {
    private fun place(heard: String) = (parseVoiceCommand(heard) as? VoiceCommand.Navigate)?.query

    @Test
    fun navigatePhrasings() {
        assertEquals("starbucks", place("Navigate to Starbucks."))
        assertEquals("starbucks", place("navigate to starbucks"))
        assertEquals("12 main street", place("Take me to 12 Main Street, please"))
        assertEquals("walmart", place("hey minmap drive to walmart"))
        assertEquals("home depot", place("Directions to the Home Depot"))
        assertEquals("lowe's", place("get me to Lowe's"))
        assertEquals("joe's pizza", place("Joe's Pizza"))
    }

    @Test
    fun nearestMeansJustTheKindOfPlace() {
        assertEquals("gas station", place("take me to the nearest gas station"))
        assertEquals("coffee", place("find coffee near me"))
        assertEquals("ev charger", place("navigate to a nearby ev charger"))
    }

    @Test
    fun stopPhrasings() {
        listOf("Stop navigation", "cancel", "end the route", "OK MinMap, stop navigating", "exit navigation").forEach {
            assertEquals(it, VoiceCommand.Stop, parseVoiceCommand(it))
        }
        // A place that merely starts with "stop" is still a place.
        assertEquals("stop and shop", place("navigate to Stop and Shop"))
    }

    @Test
    fun nothingUsefulHeard() {
        assertNull(parseVoiceCommand(""))
        assertNull(parseVoiceCommand("navigate to"))
        assertNull(parseVoiceCommand("hey minmap"))
    }
}
