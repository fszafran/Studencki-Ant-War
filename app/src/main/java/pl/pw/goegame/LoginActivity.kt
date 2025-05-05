package pl.pw.goegame

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.osmdroid.config.Configuration.getInstance
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    companion object {
        const val TAG = "PLAYER_SAVING"
    }

    private val playerService = PlayerService()
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.any { !it.value } or !userHasBTandLocationEnabled()) {
                Toast.makeText(
                    this,
                    "Bez przydzielenia niezbędnych uprawnień aplikacja nie będzie działać prawidłowo.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            else {
                savePlayerAndSwitchToMap()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
        setUpUI()
        populateTeamSpinner()
        val loginButton = findViewById<Button>(R.id.loginButton)
        loginButton.setOnClickListener {
            login()
        }
    }

    private fun setUpUI() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.login)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun populateTeamSpinner () {
        val teamSpinner: Spinner = findViewById(R.id.teamSpinner)
        val teams = arrayOf("Geoinformatycy", "Geodeci")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, teams)
        teamSpinner.adapter = adapter
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
        if (allPermissionsGranted(permissions) && userHasBTandLocationEnabled()) {
            savePlayerAndSwitchToMap()
        }
        else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun savePlayerAndSwitchToMap() {
        val playerName = getUsername()
        val team = getTeam()

        lifecycleScope.launch {
            try {
                val playerId = playerService.addPlayer(playerName, team)

                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Player $playerId saved successfully, switching to MapActivity.")
                    switchToMapActivity(playerId)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error saving player data to DB: ${e.message}", e) // Added error logging
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LoginActivity,
                        "Błąd podczas zapisywania danych gracza.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
    private fun getUsername(): String{
      return findViewById<EditText>(R.id.nicknameEditText)
          .text.toString()
          .trim()
    }

    private fun getTeam(): String {
        return findViewById<Spinner>(R.id.teamSpinner)
            .selectedItem
            .toString()
    }

    private fun login() {
        val playerName = getUsername()

        if (playerName.isEmpty()) {
            Toast.makeText(this, "Please enter your player name", Toast.LENGTH_SHORT).show()
            return
        }

        requestRequiredPermissions()

    }

    private fun userHasBTandLocationEnabled(): Boolean{
        val locationManager = this.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationIsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        val bluetoothManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        val btIsEnabled = bluetoothAdapter.isEnabled

        return locationIsEnabled && btIsEnabled
    }

    private fun switchToMapActivity(playerId: String) {
        val intent = Intent(this, MapActivity::class.java)
        intent.putExtra("PLAYER_ID_EXTRA", playerId)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}