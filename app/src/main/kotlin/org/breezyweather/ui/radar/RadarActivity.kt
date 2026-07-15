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
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.common.extensions.doOnApplyWindowInsets
import org.breezyweather.databinding.ActivityRadarBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Animated weather radar over an OpenStreetMap base.
 *
 * Radar is the US NEXRAD base-reflectivity (N0Q) composite from the Iowa Environmental
 * Mesonet, no API key. The last hour is loaded as [FRAME_COUNT] timestamped frames from
 * the WMS-T endpoint (see [NexradWmsTileSource]) and looped, one osmdroid overlay per
 * frame, toggling which one is enabled. osmdroid fetches a frame's tiles the first time
 * it is shown, so the first pass through the loop warms the on-device tile cache and
 * later passes are smooth.
 */
class RadarActivity : BreezyActivity() {

    private lateinit var binding: ActivityRadarBinding
    private val handler = Handler(Looper.getMainLooper())

    private var frames: List<String> = emptyList()
    private var frameOverlays: List<TilesOverlay> = emptyList()
    private var currentFrame = 0
    private var playing = true

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
        // Keep the tile cache app-private (cacheDir) so no storage permission is needed.
        val appPackage = packageName
        val cacheRoot = cacheDir
        Configuration.getInstance().apply {
            userAgentValue = appPackage
            osmdroidBasePath = File(cacheRoot, OSMDROID_CACHE_DIR)
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }

        binding = ActivityRadarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appBar.injectDefaultSurfaceTintColor()
        binding.toolbar.setNavigationOnClickListener { finish() }

        val latitude = intent.getDoubleExtra(KEY_LATITUDE, DEFAULT_LATITUDE)
        val longitude = intent.getDoubleExtra(KEY_LONGITUDE, DEFAULT_LONGITUDE)

        frames = radarFrames()

        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(INITIAL_ZOOM)
            controller.setCenter(GeoPoint(latitude, longitude))
        }

        frameOverlays = frames.map { time ->
            val provider = MapTileProviderBasic(applicationContext, NexradWmsTileSource(time))
            TilesOverlay(provider, this).apply {
                // Don't paint the default grey "loading" backdrop over the base map.
                setLoadingBackgroundColor(Color.TRANSPARENT)
                setLoadingLineColor(Color.TRANSPARENT)
                isEnabled = false
            }
        }
        frameOverlays.forEach { binding.mapView.overlays.add(it) }

        binding.playPause.setOnClickListener { togglePlay() }
        binding.controlBar.doOnApplyWindowInsets { view, insets ->
            view.updatePadding(bottom = insets.bottom)
        }

        // Start on the most recent frame so current radar shows immediately, then loop.
        showFrame(frames.lastIndex)
        startLoop()
    }

    private fun showFrame(index: Int) {
        currentFrame = index
        frameOverlays.forEachIndexed { i, overlay -> overlay.isEnabled = i == index }
        binding.mapView.invalidate()
        binding.timestamp.text = formatFrameTime(frames[index])
    }

    private fun togglePlay() {
        playing = !playing
        if (playing) startLoop() else handler.removeCallbacks(advanceFrame)
        updatePlayIcon()
    }

    private fun startLoop() {
        handler.removeCallbacks(advanceFrame)
        handler.postDelayed(advanceFrame, FRAME_DELAY_MS)
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        binding.playPause.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    /** Frame timestamps: 5-min, clock-aligned, UTC, backed off the composite's publish lag. */
    private fun radarFrames(count: Int = FRAME_COUNT, lagSlots: Int = LAG_SLOTS): List<String> {
        val newest = System.currentTimeMillis() / STEP_MS * STEP_MS - lagSlots * STEP_MS
        val format = isoUtcFormat()
        return (count - 1 downTo 0).map { i -> format.format(Date(newest - i * STEP_MS)) }
    }

    private fun formatFrameTime(iso: String): String {
        val local = SimpleDateFormat("h:mm a", Locale.getDefault())
        return runCatching { local.format(isoUtcFormat().parse(iso)!!) }.getOrDefault(iso)
    }

    private fun isoUtcFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (playing) startLoop()
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

        // Fallback view: geographic center of the contiguous US, used when no location
        // is supplied (radar coverage is US-only anyway).
        private const val DEFAULT_LATITUDE = 39.5
        private const val DEFAULT_LONGITUDE = -98.35
        private const val INITIAL_ZOOM = 7.0
        private const val OSMDROID_CACHE_DIR = "osmdroid"

        // Loop the last hour: 10 frames at 5-minute steps, backing off ~10 min so the
        // newest requested frame has actually been published.
        private const val STEP_MS = 5 * 60 * 1000L
        private const val FRAME_COUNT = 10
        private const val LAG_SLOTS = 2
        private const val FRAME_DELAY_MS = 800L
    }
}
