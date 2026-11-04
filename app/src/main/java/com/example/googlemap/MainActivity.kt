package com.example.googlemap

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private lateinit var webViewMap: WebView
    private lateinit var tvAddress: TextView
    private lateinit var tvCoordinates: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvAltitude: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvStatusText: TextView
    private lateinit var dotStatus: View
    private lateinit var layoutPermissionState: LinearLayout
    private lateinit var tvPermissionTitle: TextView
    private lateinit var tvPermissionMessage: TextView
    private lateinit var btnGrantPermission: MaterialButton
    private lateinit var btnMyLocation: FloatingActionButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnMapLayer: ImageButton
    private lateinit var btnCopyCoordinates: MaterialButton
    private lateinit var btnShareLocation: MaterialButton

    private var currentLocation: Location? = null
    private var isMapLoaded = false
    private var currentMapLayerIndex = 0
    private val mapLayers = arrayOf("standard", "dark", "satellite")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            onLocationPermissionGranted()
        } else {
            showPermissionState(
                title = getString(R.string.status_no_permission),
                message = getString(R.string.permission_rationale),
                buttonText = getString(R.string.btn_grant_permission)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        applyEdgeToEdgePadding()
        initViews()
        setupWebView()
        initLocationServices()
        setupListeners()
        checkPermissionsAndStart()
    }

    private fun applyEdgeToEdgePadding() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.topHeader).setPadding(
                20,
                14 + systemBars.top,
                20,
                14
            )
            insets
        }
    }

    private fun initViews() {
        webViewMap = findViewById(R.id.webViewMap)
        tvAddress = findViewById(R.id.tvAddress)
        tvCoordinates = findViewById(R.id.tvCoordinates)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvAltitude = findViewById(R.id.tvAltitude)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvStatusText = findViewById(R.id.tvStatusText)
        dotStatus = findViewById(R.id.dotStatus)
        layoutPermissionState = findViewById(R.id.layoutPermissionState)
        tvPermissionTitle = findViewById(R.id.tvPermissionTitle)
        tvPermissionMessage = findViewById(R.id.tvPermissionMessage)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnMyLocation = findViewById(R.id.btnMyLocation)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnMapLayer = findViewById(R.id.btnMapLayer)
        btnCopyCoordinates = findViewById(R.id.btnCopyCoordinates)
        btnShareLocation = findViewById(R.id.btnShareLocation)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webViewMap.apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isMapLoaded = true
                    currentLocation?.let { updateMapLocation(it) }
                }
            }
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("file:///android_asset/map.html")
        }
    }

    private fun initLocationServices() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    onLocationUpdated(location)
                }
            }
        }
    }

    private fun setupListeners() {
        btnMyLocation.setOnClickListener {
            currentLocation?.let {
                updateMapLocation(it)
            } ?: fetchLastLocation()
        }

        btnRefresh.setOnClickListener {
            tvStatusText.text = getString(R.string.status_acquiring)
            dotStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_searching)
            fetchLastLocation()
        }

        btnMapLayer.setOnClickListener {
            currentMapLayerIndex = (currentMapLayerIndex + 1) % mapLayers.size
            val layer = mapLayers[currentMapLayerIndex]
            if (isMapLoaded) {
                webViewMap.evaluateJavascript("setMapLayer('$layer')", null)
            }
            val layerName = when (layer) {
                "dark" -> getString(R.string.map_layer_dark)
                "satellite" -> getString(R.string.map_layer_satellite)
                else -> getString(R.string.map_layer_standard)
            }
            Toast.makeText(this, "Map Theme: $layerName", Toast.LENGTH_SHORT).show()
        }

        btnCopyCoordinates.setOnClickListener {
            currentLocation?.let { loc ->
                val coordsText = "${loc.latitude}, ${loc.longitude}"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Coordinates", coordsText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
        }

        btnShareLocation.setOnClickListener {
            currentLocation?.let { loc ->
                val mapsUrl = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "My Live Location")
                    putExtra(Intent.EXTRA_TEXT, "Here is my current location:\n$mapsUrl")
                }
                startActivity(Intent.createChooser(shareIntent, "Share Location via"))
            }
        }

        btnGrantPermission.setOnClickListener {
            if (!hasLocationPermissions()) {
                requestLocationPermissions()
            } else {
                openLocationSettings()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (hasLocationPermissions()) {
            onLocationPermissionGranted()
        } else {
            requestLocationPermissions()
        }
    }

    private fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun onLocationPermissionGranted() {
        layoutPermissionState.visibility = View.GONE
        tvStatusText.text = getString(R.string.status_acquiring)
        dotStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_searching)

        fetchLastLocation()
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun fetchLastLocation() {
        if (!hasLocationPermissions()) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                onLocationUpdated(it)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermissions()) return
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun onLocationUpdated(location: Location) {
        currentLocation = location

        // Update telemetry status UI
        tvStatusText.text = getString(R.string.status_live)
        dotStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_live)

        val formattedCoords = String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
        tvCoordinates.text = formattedCoords

        tvAccuracy.text = if (location.hasAccuracy()) {
            String.format(Locale.US, "±%.1fm", location.accuracy)
        } else {
            "--"
        }

        tvAltitude.text = if (location.hasAltitude()) {
            String.format(Locale.US, "%.0fm", location.altitude)
        } else {
            "--"
        }

        tvSpeed.text = if (location.hasSpeed()) {
            String.format(Locale.US, "%.1f km/h", location.speed * 3.6f)
        } else {
            "0.0 km/h"
        }

        updateMapLocation(location)
        fetchAddress(location.latitude, location.longitude)
    }

    private fun updateMapLocation(location: Location) {
        if (isMapLoaded) {
            val script = "updateLocation(${location.latitude}, ${location.longitude}, ${location.accuracy}, true)"
            webViewMap.evaluateJavascript(script, null)
        }
    }

    private fun fetchAddress(latitude: Double, longitude: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@MainActivity, Locale.getDefault())

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                        val addressString = formatAddress(addresses)
                        lifecycleScope.launch(Dispatchers.Main) {
                            tvAddress.text = addressString
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    val addressString = formatAddress(addresses)
                    withContext(Dispatchers.Main) {
                        tvAddress.text = addressString
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    tvAddress.text = String.format(Locale.US, "Lat: %.5f, Lng: %.5f", latitude, longitude)
                }
            }
        }
    }

    private fun formatAddress(addresses: List<Address>?): String {
        if (addresses.isNullOrEmpty()) return "Unknown Location"
        val address = addresses[0]
        val sb = StringBuilder()
        val featureName = address.featureName
        val thoro = address.thoroughfare
        val subLocality = address.subLocality
        val locality = address.locality
        val adminArea = address.adminArea
        val country = address.countryName

        val line0 = address.getAddressLine(0)
        if (!line0.isNullOrEmpty()) {
            return line0
        }

        if (!thoro.isNullOrEmpty()) sb.append(thoro).append(", ")
        else if (!featureName.isNullOrEmpty()) sb.append(featureName).append(", ")

        if (!subLocality.isNullOrEmpty()) sb.append(subLocality).append(", ")
        if (!locality.isNullOrEmpty()) sb.append(locality).append(", ")
        if (!adminArea.isNullOrEmpty()) sb.append(adminArea).append(", ")
        if (!country.isNullOrEmpty()) sb.append(country)

        return if (sb.isNotEmpty()) sb.toString().trimEnd(',', ' ') else "Unknown Location"
    }

    private fun showPermissionState(title: String, message: String, buttonText: String) {
        layoutPermissionState.visibility = View.VISIBLE
        tvPermissionTitle.text = title
        tvPermissionMessage.text = message
        btnGrantPermission.text = buttonText
    }

    private fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermissions()) {
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onMapClicked(lat: Double, lng: Double) {
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    String.format(Locale.US, "Map Pin: %.4f, %.4f", lat, lng),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
