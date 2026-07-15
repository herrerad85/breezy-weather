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
import androidx.core.view.updatePadding
import org.breezyweather.common.activities.BreezyActivity
import org.breezyweather.common.extensions.doOnApplyWindowInsets
import org.breezyweather.databinding.ActivityRadarBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/**
 * Live weather radar over an OpenStreetMap base.
 *
 * Radar is the US NEXRAD base-reflectivity (N0Q) composite, served as plain XYZ raster
 * tiles by the Iowa Environmental Mesonet with no API key. This renders the current
 * frame only; an animated past-hour loop (which needs the timestamped WMS-T endpoint)
 * is a planned follow-up.
 */
class RadarActivity : BreezyActivity() {

    private lateinit var binding: ActivityRadarBinding

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

        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(INITIAL_ZOOM)
            controller.setCenter(GeoPoint(latitude, longitude))
            overlays.add(buildRadarOverlay())
        }

        // Lift the attribution clear of a 3-button navigation bar.
        binding.attribution.doOnApplyWindowInsets { view, insets ->
            view.updatePadding(bottom = insets.bottom)
        }
    }

    private fun buildRadarOverlay(): TilesOverlay {
        val source = XYTileSource(
            "IEM-NEXRAD-N0Q",
            0,
            RADAR_MAX_ZOOM,
            256,
            ".png",
            arrayOf("https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/")
        )
        return TilesOverlay(MapTileProviderBasic(applicationContext, source), this).apply {
            // Don't paint the default grey "loading" backdrop over the base map where
            // there is simply no radar echo (transparent tiles).
            setLoadingBackgroundColor(Color.TRANSPARENT)
            setLoadingLineColor(Color.TRANSPARENT)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
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

        // IEM's N0Q composite is a national mosaic; tiles thin out past this zoom.
        private const val RADAR_MAX_ZOOM = 12
        private const val OSMDROID_CACHE_DIR = "osmdroid"
    }
}
