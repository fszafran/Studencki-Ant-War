package pl.pw.goegame

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "pw.MainActivity"
    }
    private lateinit var bluetoothStateReceiver: BluetoothStateReceiver
    private lateinit var gpsStateReceiver: GpsStateReceiver

    private var bluetoothStateFlag: Boolean = false
    private var gpsStateFlag: Boolean = false

    private lateinit var beaconManager: BeaconManager
    private val region = Region("all-beacons-region", null, null, null)
    private val knownBeacons = HashMap<String, Pair<Double, Double>>()

    private lateinit var showMapButton: Button
    private lateinit var map: MapView

    private var userMarker: Marker? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.any { !it.value }) {
                Toast.makeText(
                    this,
                    "Bez przydzielenia niezbędnych uprawnień aplikacja nie będzie działać prawidłowo.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                listenForConnectionChanges()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        setUpUI()
        loadKnownBeacons()
        setUpBeaconManager()
        requestRequiredPermissions()
        showMapButton = findViewById(R.id.show_map_btn)

        showMapButton.setOnClickListener {
            startScanningIfPossible()
        }
        initializeMap()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        listenForPlayerData()
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
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
        if (allPermissionsGranted(permissions)) {
            listenForConnectionChanges()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun allPermissionsGranted(permissions: Array<String>): Boolean {
        permissions.forEach { permissionName ->
            if (ContextCompat.checkSelfPermission(this, permissionName) == PackageManager.PERMISSION_DENIED) {
                return false
            }
        }
        return true
    }

    private fun listenForConnectionChanges() {
        Toast.makeText(this, "Upewnij się, że masz włączony GPS oraz Bluetooth.", Toast.LENGTH_SHORT).show()
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

    private fun canScan(): Boolean {
        return gpsStateFlag && bluetoothStateFlag
    }

    private fun initializeMap() {
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        val mapController = map.controller
        mapController.setZoom(14.0)
        val startPoint = GeoPoint(41.237665716, -85.841496634)
        mapController.setCenter(startPoint)
    }

    private fun showMap() {
        map.visibility = View.VISIBLE
    }

    private fun startScanningIfPossible() {
        if(canScan()) {
            showMap()
            Toast.makeText(this, "Skanowanie rozpoczęte  :)", Toast.LENGTH_SHORT).show()
            scanForBeacons()
        }
        else {
            Toast.makeText(this, "Upewnij się że włączyłeś GPS i BT", Toast.LENGTH_SHORT).show()
            return
        }
    }

    private fun scanForBeacons() {
        beaconManager.addRangeNotifier { beacons, _ ->
            val threeClosestBeacons = beacons.sortedBy { it.distance }.take(3)
            if(threeClosestBeacons.size == 3){
                updateUserLocation(threeClosestBeacons)
            }
        }
        beaconManager.startRangingBeacons(region)
    }

    private fun updateUserLocation(beacons: List<Beacon>) {
        val beaconSpecs = getBeaconsSpecs(beacons)
        if (beaconSpecs.size == 3){
            val userLocation = trilateration(beaconSpecs)
            val mapController = map.controller
            mapController.setZoom(20.0)
            mapController.setCenter(userLocation)

            userMarker?.let { map.overlayManager.remove(it) }

            val customIcon = ContextCompat.getDrawable(this, R.drawable.shrak)
            val marker = createMarker(userLocation, customIcon)

            map.overlayManager.add(marker)
            userMarker = marker
        }
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

    private fun createMarker(location: GeoPoint, customIcon: Drawable?): Marker {
        val marker = Marker(map).apply {
            position = location
            title = "Tu jesteś:\n" + formatGeoPoint(location)
            isDraggable = false
            icon = customIcon
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        return marker
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

    private fun listenForPlayerData() {
        val db = Firebase.firestore
        val docRef = db.collection("players").document("userId")
        docRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w(TAG, "Listen failed.", e)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                Log.d(TAG, "Current data: ${snapshot.data}")
            } else {
                Log.d(TAG, "Current data: null")
            }
        }
    }
}