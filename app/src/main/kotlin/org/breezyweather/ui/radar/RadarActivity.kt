/*
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.ui.radar

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.breezyweather.R
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.common.extensions.doOnApplyWindowInsets
import org.breezyweather.databinding.ActivityRadarBinding
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Animated weather radar over an OpenStreetMap base.
 *
 * Radar comes from a self-hosted [LibreWXR](https://github.com/JoshuaKimsey/LibreWXR)
 * instance, which pre-renders the US NEXRAD (NOAA MRMS) composite as RainViewer-format
 * XYZ tiles. Because the tiles are already rendered, the loop loads fast and stays smooth
 * over a full hour of frames, unlike an on-demand WMS source.
 *
 * The frame list is read from the instance's `weather-maps.json`; each frame becomes one
 * osmdroid overlay, and the loop toggles which one is enabled.
 */
class RadarActivity : BreezyActivity() {

    private data class Frame(val timeSeconds: Long, val path: String)

    private lateinit var binding: ActivityRadarBinding
    private val handler = Handler(Looper.getMainLooper())

    private var host: String = RADAR_BASE_URL
    private var frames: List<Frame> = emptyList()
    private var frameOverlays: List<TilesOverlay> = emptyList()
    private var currentFrame = 0
    private var playing = true
    private var ready = false

    private val advanceFrame = object : Runnable {
        override fun run() {
            if (frameOverlays.isNotEmpty()) {
                showFrame((currentFrame + 1) % frameOverlays.size)
            }
            if (playing) handler.postDelayed(this, FRAME_DELAY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid reads its Configuration when the MapView is inflated, so set it first.
        // Keep the tile cache app-private (cacheDir) so no storage permission is needed,
        // and widen the tile pipeline (defaults 2 / 40 / 9) for the multi-frame loop.
        val appPackage = packageName
        val cacheRoot = cacheDir
        Configuration.getInstance().apply {
            userAgentValue = appPackage
            osmdroidBasePath = File(cacheRoot, OSMDROID_CACHE_DIR)
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
            tileDownloadThreads = DOWNLOAD_THREADS
            tileDownloadMaxQueueSize = DOWNLOAD_QUEUE
            cacheMapTileCount = MEMORY_TILE_CACHE
        }

        binding = ActivityRadarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appBar.injectDefaultSurfaceTintColor()
        binding.toolbar.setNavigationOnClickListener { finish() }

        val latitude = intent.getDoubleExtra(KEY_LATITUDE, DEFAULT_LATITUDE)
        val longitude = intent.getDoubleExtra(KEY_LONGITUDE, DEFAULT_LONGITUDE)

        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(INITIAL_ZOOM)
            controller.setCenter(GeoPoint(latitude, longitude))
        }

        binding.playPause.setOnClickListener { togglePlay() }
        binding.controlBar.doOnApplyWindowInsets { view, insets ->
            view.updatePadding(bottom = insets.bottom)
        }

        loadRadar()
    }

    /** Fetch the frame list from LibreWXR, then build one overlay per frame and loop. */
    private fun loadRadar() {
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching { fetchFrames() }.getOrNull() }
            if (fetched == null || fetched.second.isEmpty()) {
                binding.timestamp.text = getString(R.string.radar_unavailable)
                return@launch
            }
            host = fetched.first
            frames = fetched.second
            frameOverlays = frames.map { frame ->
                val source = XYTileSource(
                    "librewxr-${frame.timeSeconds}",
                    0,
                    MAX_ZOOM,
                    256,
                    "/$COLOR_SCHEME/1_1.png",
                    arrayOf("$host${frame.path}/256/")
                )
                TilesOverlay(MapTileProviderBasic(applicationContext, source), this@RadarActivity).apply {
                    setLoadingBackgroundColor(Color.TRANSPARENT)
                    setLoadingLineColor(Color.TRANSPARENT)
                    isEnabled = false
                }
            }
            frameOverlays.forEach { binding.mapView.overlays.add(it) }
            ready = true
            showFrame(frames.lastIndex)
            startLoop()
        }
    }

    private fun fetchFrames(): Pair<String, List<Frame>> {
        val connection = (URL("$RADAR_BASE_URL/public/weather-maps.json").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        val resolvedHost = root.optString("host", RADAR_BASE_URL).ifBlank { RADAR_BASE_URL }
        val past = root.getJSONObject("radar").getJSONArray("past")
        val list = (0 until past.length()).map { i ->
            val f = past.getJSONObject(i)
            Frame(f.getLong("time"), f.getString("path"))
        }
        return resolvedHost to list
    }

    private fun showFrame(index: Int) {
        currentFrame = index
        frameOverlays.forEachIndexed { i, overlay -> overlay.isEnabled = i == index }
        binding.mapView.invalidate()
        binding.timestamp.text = formatFrameTime(frames[index].timeSeconds)
    }

    private fun togglePlay() {
        playing = !playing
        if (playing) startLoop() else handler.removeCallbacks(advanceFrame)
        updatePlayIcon()
    }

    private fun startLoop() {
        if (!ready) return
        handler.removeCallbacks(advanceFrame)
        handler.postDelayed(advanceFrame, FRAME_DELAY_MS)
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        binding.playPause.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun formatFrameTime(timeSeconds: Long): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timeSeconds * 1000))
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (playing && ready) startLoop()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(advanceFrame)
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(advanceFrame)
        binding.mapView.onDetach()
    }

    companion object {
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"

        // Self-hosted LibreWXR instance serving pre-rendered US radar tiles. Change this
        // to point the fork at a different instance.
        private const val RADAR_BASE_URL = "https://radar.loafsupport.com"

        // RainViewer color scheme id (0-8). 2 = universal blue; swap for a different look.
        private const val COLOR_SCHEME = 2

        // Fallback view: geographic center of the contiguous US, used when no location
        // is supplied (radar coverage is US-only).
        private const val DEFAULT_LATITUDE = 39.5
        private const val DEFAULT_LONGITUDE = -98.35
        private const val INITIAL_ZOOM = 7.0
        private const val MAX_ZOOM = 12
        private const val OSMDROID_CACHE_DIR = "osmdroid"

        // Tile pipeline sized for the multi-frame loop (osmdroid defaults are 2 / 40 / 9).
        private const val DOWNLOAD_THREADS: Short = 8
        private const val DOWNLOAD_QUEUE: Short = 200
        private const val MEMORY_TILE_CACHE: Short = 96

        private const val FRAME_DELAY_MS = 800L
    }
}
