package com.example.mapstyleeditor.nav

import com.mapbox.geojson.Point
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * MinMap guiding by itself (aosp): a canned Directions response (1 km north on North St, right,
 * 500 m east on East St, arrive) driven 10 m at a time, offline. Checks every spoken cue comes once
 * and in order, the turn card, and arriving.
 */
class OwnGuidanceTest {
    @Before
    fun usLocale() = Locale.setDefault(Locale.US)

    private val directions = """
        {"routes":[{
          "geometry":{"coordinates":[[0,0],[0,0.003],[0,0.006],[0,0.009],[0.0015,0.009],[0.003,0.009],[0.0045,0.009]]},
          "legs":[{
            "annotation":{"duration":[20,20,20,10,10,10]},
            "steps":[
              {"name":"North St","maneuver":{"type":"depart","instruction":"Head north on North St","location":[0,0]},
               "voiceInstructions":[
                 {"distanceAlongGeometry":1000,"announcement":"Head north on North St."},
                 {"distanceAlongGeometry":300,"announcement":"In 1000 feet, turn right onto East St."},
                 {"distanceAlongGeometry":60,"announcement":"Turn right onto East St."}]},
              {"name":"East St","maneuver":{"type":"turn","modifier":"right","instruction":"Turn right onto East St","location":[0,0.009]},
               "voiceInstructions":[
                 {"distanceAlongGeometry":500,"announcement":"Continue for a quarter mile."},
                 {"distanceAlongGeometry":80,"announcement":"You have arrived at your destination."}]},
              {"name":"East St","maneuver":{"type":"arrive","instruction":"You have arrived","location":[0.0045,0.009]}}
            ]}]}]}
    """.trimIndent()

    @Test
    fun voiceCuesArePlacedAlongTheRoute() {
        val route = parseRoute(directions)!!
        val cues = route.steps.flatMap { it.voice }.map { it.alongMeters }
        val corner = route.steps[1].alongMeters
        val total = route.cumulative.last()
        assertEquals(1000.75, corner, 1.0)
        // Each cue sits its distance before the end of its own step.
        assertEquals(listOf(corner - 1000, corner - 300, corner - 60, total - 500, total - 80).map { it.coerceAtLeast(0.0) }, cues)
    }

    @Test
    fun drivingTheRouteSpeaksEachCueOnceAndArrives() = runBlocking {
        val route = parseRoute(directions)!!
        val spoken = mutableListOf<String>()
        var here = route.points.first()
        val session = DriveSession(
            token = "",
            destination = route.points.last(),
            startedAt = 0,
            vias = emptyList(),
            location = { here },
            bearing = { null },
            ownGuidance = true,
            initialRoute = route,
            speak = { spoken += it },
            arrowFor = { _, _ -> null },
        )

        session.tick()
        val first = session.guidance!!
        assertEquals("Turn right onto East St", first.instruction)
        assertEquals("East St", first.street)
        assertEquals("0.6 mi", first.distanceText)
        assertTrue(first.arrival!!.startsWith("Arrive "))

        var meters = 0.0
        while (!session.arrived && meters < 2_000) {
            meters += 10
            here = route.pointAt(meters)
            session.tick()
            if (meters == 900.0) assertEquals("350 ft", session.guidance!!.distanceText)
        }

        assertTrue("never arrived", session.arrived)
        assertEquals(
            listOf(
                "Head north on North St.",
                "In 1000 feet, turn right onto East St.",
                "Turn right onto East St.",
                "Continue for a quarter mile.",
                "You have arrived at your destination.",
            ),
            spoken,
        )
    }

    @Test
    fun aRouteJoinedHalfwaySkipsCuesAlreadyBehind() = runBlocking {
        val route = parseRoute(directions)!!
        val spoken = mutableListOf<String>()
        // Already past the turn (e.g. a reroute that starts mid-route): nothing old is read out.
        val here: Point = route.pointAt(1_300.0)
        val session = DriveSession(
            token = "", destination = route.points.last(), startedAt = 0, vias = emptyList(),
            location = { here }, bearing = { null }, ownGuidance = true, initialRoute = route,
            speak = { spoken += it }, arrowFor = { _, _ -> null },
        )
        session.tick()
        assertEquals(emptyList<String>(), spoken)
        assertEquals("You have arrived", session.guidance!!.instruction)
    }

    @Test
    fun distancesReadLikeACarDisplay() {
        assertEquals("50 ft", formatDistance(5.0))
        assertEquals("450 ft", formatDistance(137.0))
        assertEquals("0.3 mi", formatDistance(480.0))
        assertEquals("12 mi", formatDistance(19_500.0))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("140 m", formatDistance(137.0))
        assertEquals("1.5 km", formatDistance(1_480.0))
    }
}
