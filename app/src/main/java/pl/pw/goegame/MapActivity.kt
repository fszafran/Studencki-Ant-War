package pl.pw.goegame

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Region
import org.osmdroid.config.Configuration.getInstance
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import pl.pw.goegame.data.model.KnownBeacon
import pl.pw.goegame.data.model.LocationBeacon
import java.io.BufferedReader
import java.lang.reflect.Type
import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

class MapActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "pw.MapActivity"
        private val region = Region("all-beacons-region", null, null, null)
    }

    private lateinit var bluetoothStateReceiver: BluetoothStateReceiver
    private lateinit var gpsStateReceiver: GpsStateReceiver

    private var bluetoothStateFlag: Boolean = false
    private var gpsStateFlag: Boolean = false

    private lateinit var beaconManager: BeaconManager

    private val knownBeacons = HashMap<String, Pair<Double, Double>>()

    private lateinit var map: MapView

    private lateinit var mainPlayer: Player
    private var otherPlayers: HashMap<String, Player>? = null

    private val playerService = PlayerService()
    private val gameService = GameService()
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        enableEdgeToEdge()
        setContentView(R.layout.activity_map)
        setUpUI()
        assignPlayerFromIntent()
        loadOtherPlayers()
        loadKnownBeacons()
        setUpBeaconManager()
        initializeMap()
        listenForConnectionChanges()
        startScanningIfPossible()
        listenForOtherPlayersData()

        Toast.makeText(this, "Hello ${mainPlayer.id}", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothStateReceiver)
        unregisterReceiver(gpsStateReceiver)
        cleanupBeaconManager()
        scope.launch {
            playerService.removePlayer(mainPlayer.id)
        }
        scope.cancel()
    }

    private fun setUpBeaconManager() {
        beaconManager = BeaconManager.getInstanceForApplication(this)
        listOf(
            BeaconParser.EDDYSTONE_TLM_LAYOUT,
            BeaconParser.EDDYSTONE_UID_LAYOUT,
            BeaconParser.EDDYSTONE_URL_LAYOUT
        ).forEach {
            beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(it))
        }
    }

    private fun cleanupBeaconManager() {
        beaconManager.stopRangingBeacons(region)
        beaconManager.removeAllRangeNotifiers()
    }

    private fun setUpUI() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.map)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun listenForConnectionChanges() {
        initializeConnectionFlags()

        bluetoothStateReceiver = BluetoothStateReceiver(
            onBluetoothEnabled = { onBluetoothEnabled() },
            onBluetoothDisabled = { onBluetoothDisabled() }
        )

        gpsStateReceiver = GpsStateReceiver(
            onGpsEnabled = { onGpsEnabled() },
            onGpsDisabled = { onGpsDisabled() }
        )

        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(gpsStateReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
    }

    private fun initializeConnectionFlags() {
        val locationManager = this.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        this.gpsStateFlag = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        val bluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        this.bluetoothStateFlag = bluetoothAdapter.isEnabled
    }

    private fun loadOtherPlayers() {
        scope.launch {
            val fetchedPlayers = playerService.getAllPlayers()
            otherPlayers = HashMap<String, Player>()

            fetchedPlayers.forEach { (id, player) ->
                if (id != mainPlayer.id) {
                    otherPlayers!![id] = player
                    if (player.location != null) {
                        drawMarker(player)
                    }
                }
            }
            Log.d(TAG, "Loaded ${otherPlayers!!.size} initial players")
        }
    }

    private fun canScan(): Boolean {
        return gpsStateFlag && bluetoothStateFlag
    }

    private fun initializeMap() {
        map = findViewById(R.id.mapView)
        map.setTileSource(TileSourceFactory.MAPNIK)
        val mapController = map.controller
        mapController.setZoom(14.0)
        val startPoint = GeoPoint(41.237665716, -85.841496634)
        mapController.setCenter(startPoint)
    }

    private fun showMap() {
        map.visibility = View.VISIBLE
    }

    private fun assignPlayerFromIntent() {
        val extras = intent.extras
        if (extras != null) {
            val id = extras.getString("PLAYER_ID_EXTRA").toString()
            val team = extras.getString("PLAYER_TEAM_EXTRA").toString()
            mainPlayer = Player(id, null, team, null)
        }
    }

    private fun startScanningIfPossible() {
        if(canScan()) {
            showMap()
            Toast.makeText(this, "Skanowanie rozpoczęte  :)", Toast.LENGTH_SHORT).show()
            scanForBeacons()
        }
        else {
            Toast.makeText(this, "To tu Upewnij się że włączyłeś GPS i BT", Toast.LENGTH_SHORT).show()
            return
        }
    }

    private fun scanForBeacons() {
        beaconManager.addRangeNotifier { beacons, _ ->
            val threeClosestBeacons = beacons.sortedBy { it.distance }.take(3)
//            if(threeClosestBeacons.size == 3){

//            }
        }
        updatePlayerLocation()
        beaconManager.startRangingBeacons(region)
    }

    private fun updatePlayerLocation() {
        val playerLocation = GeoPoint(52.0, 21.0)
        val mapController = map.controller
        mapController.setZoom(20.0)
        mapController.setCenter(playerLocation)
        mainPlayer.location = playerLocation
        drawMarker(mainPlayer)
        launchGameActivityIfPlayerNearby()
        scope.launch {
            playerService.updatePlayerPosition(mainPlayer)
        }

//        if (beaconSpecs.size == 3){
//            val playerLocation = trilateration(beaconSpecs)
//            val mapController = map.controller
//            mapController.setZoom(20.0)
//            mapController.setCenter(playerLocation)
//            mainPlayer.location = playerLocation
//            drawMarker(mainPlayer)
//            launchGameActivityIfPlayerNearby()
//            scope.launch {
//                playerService.updatePlayerPosition(mainPlayer)
//            }
    }


    private fun launchGameActivityIfPlayerNearby() {
        if (mainPlayer.location == null) {
            return
        }
        val playerNearby = playerNearby()
        if (playerNearby != null) {
            scope.launch {
               val gameId = gameService.createGame(mainPlayer, playerNearby)
               switchToGameActivity(gameId, mainPlayer.id, playerNearby.id)
            }
        }
    }

    private fun switchToGameActivity(gameId: String, player1Id: String, player2Id: String ) {
        Log.d(TAG, "Switching to game activity for players: $player1Id, $player2Id")
        val intent = Intent(this, GameActivity::class.java)
        intent.putExtra("GAME_ID", gameId)
        intent.putExtra("PLAYER_ID", player1Id)
        intent.putExtra("OPPONENT_ID", player2Id)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun drawMarker(player: Player) {
        player.marker?.let { map.overlayManager.remove(it) }
        val customIcon = ContextCompat.getDrawable(this, getIconForPlayer(player))
        val marker = Marker(map).apply {
            position = player.location
            title = player.id + "\n" + player.team + "\n" + player.location?.let { formatGeoPoint(it) }
            isDraggable = false
            icon = customIcon
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlayManager.add(marker)
        player.marker = marker
    }

    private fun getIconForPlayer(player: Player): Int =
        if (player.team == "Geoinformatycy") {
            R.drawable.shrak
        } else {
            R.drawable.shrak_deci
        }

    private fun formatGeoPoint(geoPoint: GeoPoint): String {
        val latitude = geoPoint.latitude
        val longitude = geoPoint.longitude

        val latDegrees = abs(latitude.toInt())
        val latMinutes = ((abs(latitude) - latDegrees) * 60).toInt()
        val latSeconds = (((abs(latitude) - latDegrees) * 60) - latMinutes) * 60

        val lonDegrees = abs(longitude.toInt())
        val lonMinutes = ((abs(longitude) - lonDegrees) * 60).toInt()
        val lonSeconds = (((abs(longitude) - lonDegrees) * 60) - lonMinutes) * 60

        val latDirection = if (latitude >= 0) "N" else "S"
        val lonDirection = if (longitude >= 0) "E" else "W"

        val decimalFormat = DecimalFormat("#.##")

        return "${latDegrees}° ${latMinutes}' ${decimalFormat.format(latSeconds)}\" $latDirection\n" +
                "${lonDegrees}° ${lonMinutes}' ${decimalFormat.format(lonSeconds)}\" $lonDirection"
    }

    private fun trilateration(locationBeacons: List<LocationBeacon>): GeoPoint {
        val refLat = locationBeacons[0].coords.first

        val latMetersPerDegree = 110574
        val longMetersPerDegree = latMetersPerDegree * cos(Math.toRadians(refLat))

        val r1 = locationBeacons[0].distance
        val x1 = (locationBeacons[0].coords.first) * latMetersPerDegree
        val y1 = (locationBeacons[0].coords.second) * longMetersPerDegree

        val r2 = locationBeacons[1].distance
        val x2 = (locationBeacons[1].coords.first) * latMetersPerDegree
        val y2 = (locationBeacons[1].coords.second) * longMetersPerDegree

        val r3 = locationBeacons[2].distance
        val x3 = (locationBeacons[2].coords.first) * latMetersPerDegree
        val y3 = (locationBeacons[2].coords.second) * longMetersPerDegree

        val c = r1.pow(2) - r2.pow(2) - x1.pow(2) + x2.pow(2) - y1.pow(2) + y2.pow(2)
        val f = r2.pow(2) - r3.pow(2) - x2.pow(2) + x3.pow(2) - y2.pow(2) + y3.pow(2)

        val a = -2*x1 + 2*x2
        val b = -2*y1 + 2*y2
        val d = -2*x2 + 2*x3
        val e = -2*y2 + 2*y3

        val userX = (c*e - f*b)/(e*a - b*d)
        val userY = (c*d - f*a)/(b*d - a*e)

        val userLat = userX/latMetersPerDegree
        val userLong = userY/longMetersPerDegree

        return GeoPoint(userLat, userLong)
    }

    private fun getBeaconsSpecs(beacons: List<Beacon>): List<LocationBeacon>{
        val locations = mutableListOf<LocationBeacon>()
        beacons.forEach { beacon ->
            val uid = beacon.bluetoothAddress
            val beaconDistance = beacon.distance
            val beaconCoords = knownBeacons[uid]
            if (beaconCoords != null) {
                locations.add(
                    LocationBeacon(beaconDistance, beaconCoords)
                )
            }
        }
        return locations
    }

    private fun loadKnownBeacons() {
        val gson = Gson()
        val files = this.assets.list("")
        files?.forEach { fileName ->
            if(fileName.endsWith(".txt")){
                val jsonString = this.assets.open(fileName).bufferedReader().use(BufferedReader::readText)
                val listType: Type = object : TypeToken<List<KnownBeacon>>() {}.type
                val jsonObject = gson.fromJson(jsonString, JsonObject::class.java)
                val beaconDataList: List<KnownBeacon> = gson.fromJson(jsonObject.getAsJsonArray("items"), listType)
                beaconDataList.forEach { beaconData ->
                    knownBeacons[beaconData.beaconUid] = Pair(beaconData.latitude, beaconData.longitude)
                }
            }
        }
    }

    private fun onBluetoothEnabled() {
        this.bluetoothStateFlag = true
    }

    private fun onBluetoothDisabled() {
        Toast.makeText(this, "Bluetooth is OFF", Toast.LENGTH_SHORT).show()
        this.bluetoothStateFlag = false
        cleanupBeaconManager()
    }

    private fun onGpsEnabled() {
        this.gpsStateFlag = true
    }

    private fun onGpsDisabled() {
        Toast.makeText(this, "GPS is OFF", Toast.LENGTH_SHORT).show()
        this.gpsStateFlag = false
        cleanupBeaconManager()
    }

    private fun playerNearby(): Player? {
        val PROXIMITY_METERS = 2
        Log.d(TAG, "Other players: ${otherPlayers.toString()}")
        otherPlayers?.forEach { (_, player) ->
            if (player.location == null || player.team == mainPlayer.team) {
                return@forEach
            }
            val distanceFromMainPlayer = mainPlayer.location?.distanceToAsDouble(player.location)
            if (distanceFromMainPlayer != null && distanceFromMainPlayer <= PROXIMITY_METERS) {
                return player
            }
        }
        return null
    }

    private fun listenForOtherPlayersData() {
        val db = Firebase.firestore
        val playersCollectionRef = playerService.playersCollection
        val gamesCollectionRef = gameService.gamesCollection

        playersCollectionRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w(TAG, "Listen failed for players collection.", e)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                for (dc in snapshot.documentChanges) {
                    val playerId = dc.document.id

                    if (playerId == mainPlayer.id) {
                        continue
                    }

                    val team = dc.document.getString("team") ?: "Unknown"
                    val locationFirestore = dc.document.getGeoPoint("location")
                    val location = locationFirestore?.let { GeoPoint(it.latitude, it.longitude) }

                    when (dc.type) {
                        com.google.firebase.firestore.DocumentChange.Type.ADDED,
                        com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                            if (otherPlayers != null) {
                                Log.d(TAG, "${otherPlayers!![playerId]}")
                                val player = otherPlayers!![playerId] ?: Player(id = playerId, team = team)
                                player.location = location
                                Log.d(TAG, "Called for: ${playerId}")
                                if (player.location != null) {
                                    drawMarker(player)
                                    otherPlayers!![playerId] = player
                                    if (player.team != mainPlayer.team) {
                                        launchGameActivityIfPlayerNearby()
                                    }
                                }
                            }
                        }

                        com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                            val removedPlayer = otherPlayers?.remove(playerId)
                            removedPlayer?.marker?.let { marker ->
                                map.overlayManager.remove(marker)
                            }
                        }
                    }
                }
            }
        }

        gamesCollectionRef
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w(TAG, "Listen failed for games collection.", e)
                    return@addSnapshotListener
                }

                snapshot?.documents?.forEach { doc ->
                    val player1 = doc.getString("player1")
                    val player2 = doc.getString("player2")

                    if (player1 == mainPlayer.id || player2 == mainPlayer.id){
                        val opponentId = if (player1 == mainPlayer.id) player2 else player1
                        if (opponentId != null) {
                            switchToGameActivity(doc.id, mainPlayer.id, opponentId)
                        }
                    }
                }

            }
    }


}