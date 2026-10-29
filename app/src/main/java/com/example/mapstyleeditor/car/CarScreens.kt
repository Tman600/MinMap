package com.example.mapstyleeditor.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.example.mapstyleeditor.R
import com.example.mapstyleeditor.account.MapboxAccount
import com.example.mapstyleeditor.learn.TripHistory
import com.example.mapstyleeditor.places.PlaceSearch
import com.example.mapstyleeditor.places.SearchHit
import com.example.mapstyleeditor.places.formatDistance
import com.example.mapstyleeditor.ui.Place
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.androidauto.mapboxMapInstaller
import com.mapbox.maps.extension.androidauto.MapboxCarMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The car's main screen: the map, and over it either nothing (browsing) or the drive (next turn,
 * arrival time). Search and End live in the action strip; the map's own strip pans, zooms and
 * re-centres.
 */
class MapScreen(
    carContext: CarContext,
    mapboxCarMap: MapboxCarMap,
    private val map: CarMap,
    private val navigator: () -> CarNavigator,
) : Screen(carContext) {
    init {
        mapboxMapInstaller(mapboxCarMap).onCreated(map).gestureHandler(map.gestures).install()
    }

    override fun onGetTemplate(): Template {
        val nav = navigator()
        val drive = nav.drive
        val progress = drive?.progress
        val actions = ActionStrip.Builder()
        if (drive != null || nav.starting) {
            actions.addAction(
                Action.Builder().setTitle("End").setFlags(Action.FLAG_PRIMARY).setOnClickListener { nav.end() }.build(),
            )
        } else {
            actions.addAction(
                Action.Builder().setTitle("Search").setIcon(icon(R.drawable.ic_car_search))
                    .setOnClickListener { screenManager.push(SearchScreen(carContext, nav) { map.here }) }.build(),
            )
        }

        val template = NavigationTemplate.Builder()
            .setActionStrip(actions.build())
            .setMapActionStrip(mapActions())
            .setPanModeListener { }
        when {
            nav.starting -> template.setNavigationInfo(MessageInfo.Builder("Finding a route…").build())
            drive?.arrived == true -> template.setNavigationInfo(
                MessageInfo.Builder("You have arrived").apply { nav.destination?.let { setText(it.name) } }.build(),
            )
            progress != null -> {
                template.setNavigationInfo(
                    RoutingInfo.Builder().setCurrentStep(nav.step(progress), carDistance(progress.toTurnMeters)).build(),
                )
                template.setDestinationTravelEstimate(nav.estimate(progress))
            }
            drive != null -> template.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
        }
        return template.build()
    }

    private fun mapActions(): ActionStrip {
        val strip = ActionStrip.Builder().addAction(Action.PAN)
        if (!map.following) {
            strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_recenter)).setOnClickListener { map.follow() }.build())
        }
        strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_in)).setOnClickListener { map.zoom(1.0) }.build())
        strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_out)).setOnClickListener { map.zoom(-1.0) }.build())
        return strip.build()
    }

    private fun icon(id: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, id)).setTint(CarColor.DEFAULT).build()
}

/**
 * Search on the car: places you've driven to before while the box is empty, Mapbox's suggestions
 * as you type (once you pause; the car only allows typing while parked), and categories ("coffee")
 * opening into the nearest places of that kind. Picking one starts the drive.
 */
class SearchScreen(
    carContext: CarContext,
    private val navigator: CarNavigator,
    /** Where you are, so results near you come first. */
    private val here: () -> Point?,
) : Screen(carContext) {
    private val search = PlaceSearch(MapboxAccount.token)
    private var query = ""
    private var hits: List<SearchHit> = emptyList()
    private var loading = false
    private var pending: Job? = null

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        if (query.isBlank()) {
            recentPlaces().take(limit).forEach { place ->
                list.addItem(Row.Builder().setTitle(place.name).addText("Driven before").setOnClickListener { drive(place) }.build())
            }
            if (recentPlaces().isEmpty()) list.setNoItemsMessage("Search for a place")
        } else {
            hits.take(limit).forEach { hit ->
                val detail = listOfNotNull(hit.distance?.let(::formatDistance), hit.detail.ifBlank { null }).joinToString(" · ")
                list.addItem(
                    Row.Builder().setTitle(hit.name).apply { if (detail.isNotEmpty()) addText(detail) }
                        .setOnClickListener { pick(hit) }.build(),
                )
            }
            if (!loading && hits.isEmpty()) list.setNoItemsMessage("No places found")
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = find(searchText, wait = true)
            override fun onSearchSubmitted(searchText: String) = find(searchText, wait = false)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Where to?")
            .setInitialSearchText(query)
            .setShowKeyboardByDefault(false)
            .apply { if (loading) setLoading(true) else setItemList(list.build()) }
            .build()
    }

    private fun find(text: String, wait: Boolean) {
        query = text
        pending?.cancel()
        if (text.isBlank()) {
            hits = emptyList()
            loading = false
            invalidate()
            return
        }
        pending = lifecycleScope.launch {
            // Only once typing pauses: each lookup counts on the Mapbox account.
            if (wait) delay(450)
            loading = true
            invalidate()
            hits = search.suggest(text, here(), here())
            loading = false
            invalidate()
        }
    }

    private fun pick(hit: SearchHit) {
        lifecycleScope.launch {
            val category = hit.category
            if (category != null) {
                query = hit.name
                hits = search.category(category, here(), here())
                invalidate()
            } else {
                search.open(hit)?.let(::drive)
            }
        }
    }

    private fun drive(place: Place) {
        navigator.start(place)
        screenManager.pop()
    }

    /** Distinct places from your past drives, most recent first. */
    private fun recentPlaces(): List<Place> = TripHistory(carContext).all()
        .sortedByDescending { it.startedAt }
        .distinctBy { it.name.lowercase() }
        .map { Place(it.name, "", it.destination.longitude(), it.destination.latitude()) }
}

/** A message with one action, for what has to happen on the phone first (sign in, allow location). */
class MessageScreen(
    carContext: CarContext,
    private val title: String,
    private val message: String,
    private val actionTitle: String,
    private val onAction: (Screen) -> Unit,
) : Screen(carContext) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder(message)
        .setTitle(title)
        .setHeaderAction(Action.APP_ICON)
        .addAction(Action.Builder().setTitle(actionTitle).setOnClickListener { onAction(this) }.build())
        .build()
}
