package com.example.mapstyleeditor.car

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import com.example.mapstyleeditor.nav.RouteStep
import org.junit.Assert.assertEquals
import com.mapbox.bindgen.Value
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Android Auto pieces that don't need a car: turn types, distances, the night map, geo links. */
class CarTest {
    @Before
    fun usLocale() = Locale.setDefault(Locale.US)

    private fun type(type: String, modifier: String? = null, exit: Int? = null, side: String? = "right") =
        carManeuverType(RouteStep(emptyList(), 0.0, type = type, modifier = modifier, exit = exit, drivingSide = side))

    @Test
    fun turnsMapToTheCarsManeuvers() {
        assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, type("turn", "right"))
        assertEquals(Maneuver.TYPE_TURN_SLIGHT_LEFT, type("end of road", "slight left"))
        assertEquals(Maneuver.TYPE_U_TURN_LEFT, type("turn", "uturn"))
        assertEquals(Maneuver.TYPE_U_TURN_RIGHT, type("turn", "uturn", side = "left"))
        assertEquals(Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, type("off ramp", "slight right"))
        assertEquals(Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, type("on ramp", "left"))
        assertEquals(Maneuver.TYPE_FORK_LEFT, type("fork", "slight left"))
        assertEquals(Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED, type("merge"))
        assertEquals(Maneuver.TYPE_NAME_CHANGE, type("new name", "straight"))
        assertEquals(Maneuver.TYPE_DESTINATION_RIGHT, type("arrive", "right"))
        assertEquals(Maneuver.TYPE_DEPART, type("depart"))
    }

    @Test
    fun roundaboutsTurnTheRightWayAndCarryTheirExit() {
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, type("roundabout", "right", exit = 2))
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, type("roundabout", "left", exit = 2, side = "left"))
        // No exit number from Mapbox: a plain "enter", which Android Auto accepts without one.
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, type("rotary", exit = null))
        assertTrue(needsExitNumber(type("roundabout", exit = 3)))
    }

    @Test
    fun distancesUseTheCarsUnits() {
        assertEquals(Distance.create(350.0, Distance.UNIT_FEET), carDistance(100.0))
        assertEquals(Distance.UNIT_MILES_P1, carDistance(2_000.0).displayUnit)
        assertEquals(Distance.create(19.0, Distance.UNIT_MILES), carDistance(30_000.0))
        Locale.setDefault(Locale.GERMANY)
        assertEquals(Distance.create(140.0, Distance.UNIT_METERS), carDistance(137.0))
        assertEquals(Distance.UNIT_KILOMETERS_P1, carDistance(1_480.0).displayUnit)
    }

    @Test
    fun nightModeDarkensADaytimeStyleOnly() {
        assertEquals("night", carStyleConfig(null, dark = true)["lightPreset"]?.contents)
        assertEquals("day", carStyleConfig(null, dark = false)["lightPreset"]?.contents)
        assertEquals("dusk", carStyleConfig("""{"lightPreset":"dusk"}""", dark = true)["lightPreset"]?.contents)
        val retro = carStyleConfig("""{"colorWater":"#B9D3C2","showTransitLabels":false}""", dark = false)
        assertEquals("#B9D3C2", retro["colorWater"]?.contents)
        assertEquals(false, retro["showTransitLabels"]?.contents)
    }

    @Test
    fun switchingStylesUndoesTheLastOne() {
        // Standard's defaults stand in for whatever the new style doesn't set.
        val defaults = mapOf("colorWater" to Value("#8AB4F8"), "colorLand" to Value("#F0EDE5"))
        val plain = carStyleConfig(null, dark = false, defaults)
        assertEquals("#8AB4F8", plain["colorWater"]?.contents)
        assertEquals(true, plain["showTransitLabels"]?.contents)
        assertEquals("default", plain["theme"]?.contents)
        assertEquals(3L, plain["densityPointOfInterestLabels"]?.contents)
        val retro = carStyleConfig("""{"colorWater":"#B9D3C2"}""", dark = false, defaults)
        assertEquals("#B9D3C2", retro["colorWater"]?.contents)
        assertEquals("#F0EDE5", retro["colorLand"]?.contents)
    }

    @Test
    fun geoLinks() {
        assertEquals((37.42 to -122.08) to null, parseGeo("37.42,-122.08"))
        assertEquals(null to "Starbucks", parseGeo("0,0?q=Starbucks"))
        assertEquals((37.42 to -122.08) to "Home", parseGeo("37.42,-122.08?q=Home"))
        assertEquals((40.75 to -73.98) to null, parseGeo("0,0?q=40.75,-73.98"))
    }
}
