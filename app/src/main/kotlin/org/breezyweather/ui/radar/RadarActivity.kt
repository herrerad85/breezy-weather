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
import android.view.View
import androidx.core.view.updatePadding
import org.breezyweather.R
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.common.extensions.doOnApplyWindowInsets
import org.breezyweather.databinding.ActivityRadarBinding
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.cachemanager.CacheManager
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
 * frame, toggling which one is enabled.
 *
 * Because every frame is its own tile source, naively animating means osmdroid fetches a
 * frame's tiles the first time it is shown, which is choppy for the first passes and
 * again after each zoom. To avoid that, every frame's tiles for the current view are
 * pre-downloaded (osmdroid [CacheManager]) before the loop starts, behind a progress
 * label, and the view is re-warmed in the background after a zoom or pan.
 */
class RadarActivity : BreezyActivity() {

    private lateinit var binding: ActivityRadarBinding
    private val handler = Handler(Looper.getMainLooper())

    private var frames: List<String> = emptyList()
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

    private val backgroundPrefetch = Runnable { prefetch(initial = false) }

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
            // The loop drives FRAME_COUNT frames, each its own tile source. osmdroid's
            // stock 2 download threads and 40-request queue can't keep up: excess tile
            // requests are dropped. Widen throughput and hold more tiles in memory.
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

        // Pre-download all frames for the initial view before playing, then re-warm the
        // view in the background whenever the user zooms or pans.
        binding.mapView.addOnFirstLayoutListener { _, _, _, _, _ -> prefetch(initial = true) }
        binding.mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleBackgroundPrefetch()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                scheduleBackgroundPrefetch()
                return false
            }
        })
    }

    /**
     * Pre-download every frame's tiles for the current viewport. On the [initial] pass a
     * progress label is shown and the loop only starts once all frames are cached; later
     * (background) passes just warm the cache after the view changes.
     */
    private fun prefetch(initial: Boolean) {
        if (frames.isEmpty()) return
        val bbox = binding.mapView.boundingBox
        val zoom = binding.mapView.zoomLevelDouble.toInt()
        val writer = binding.mapView.tileProvider.tileWriter
        val total = frames.size
        var completed = 0

        if (initial) {
            handler.removeCallbacks(advanceFrame)
            showLoading(0, total)
        }

        val onDone = {
            completed++
            if (initial) {
                showLoading(completed, total)
                if (completed >= total) {
                    binding.loadingOverlay.visibility = View.GONE
                    ready = true
                    showFrame(frames.lastIndex)
                    if (playing) startLoop()
                }
            }
        }

        frames.forEach { time ->
            val callback = object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete() = runOnUiThread { onDone() }
                override fun onTaskFailed(errors: Int) = runOnUiThread { onDone() }
                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) = Unit
                override fun downloadStarted() = Unit
                override fun setPossibleTilesInArea(total: Int) = Unit
            }
            try {
                // NoUI: fire only our callback, never osmdroid's built-in progress
                // dialog (10 frames would otherwise stack 10 dialogs on every pan/zoom).
                CacheManager(NexradWmsTileSource(time), writer, zoom, zoom)
                    .downloadAreaAsyncNoUI(this, bbox, zoom, zoom, callback)
            } catch (e: Exception) {
                // Bulk download refused or unavailable for this frame: count it done so
                // the loop still starts; that frame just loads on demand as before.
                runOnUiThread { onDone() }
            }
        }
    }

    private fun showLoading(done: Int, total: Int) {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.loadingText.text = getString(R.string.radar_loading, done, total)
    }

    private fun scheduleBackgroundPrefetch() {
        handler.removeCallbacks(backgroundPrefetch)
        handler.postDelayed(backgroundPrefetch, REPREFETCH_DEBOUNCE_MS)
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
        if (playing && ready) startLoop()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(advanceFrame)
        handler.removeCallbacks(backgroundPrefetch)
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(advanceFrame)
        handler.removeCallbacks(backgroundPrefetch)
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

        // Tile pipeline sized for the multi-frame loop (osmdroid defaults are 2 / 40 / 9).
        private const val DOWNLOAD_THREADS: Short = 8
        private const val DOWNLOAD_QUEUE: Short = 200
        private const val MEMORY_TILE_CACHE: Short = 96

        // Loop the last hour: 10 frames at 5-minute steps, backing off ~10 min so the
        // newest requested frame has actually been published.
        private const val STEP_MS = 5 * 60 * 1000L
        private const val FRAME_COUNT = 10
        private const val LAG_SLOTS = 2
        private const val FRAME_DELAY_MS = 800L

        // Wait for the map to settle after a gesture before re-warming the cache.
        private const val REPREFETCH_DEBOUNCE_MS = 600L
    }
}
