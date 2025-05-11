package pl.pw.goegame

import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

data class Player (
    val id: String,
    var location: GeoPoint? = null,
    val team: String,
    var marker: Marker? = null
)