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

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * osmdroid tile source for one timestamped NEXRAD N0Q frame from the Iowa Environmental
 * Mesonet WMS-T endpoint (no API key).
 *
 * osmdroid speaks XYZ; IEM's historical radar speaks WMS (bounding box), so each tile's
 * (z, x, y) is converted to its EPSG:3857 bounds and requested as a WMS GetMap. The
 * [time] is baked into the source name so osmdroid caches each frame under its own key
 * instead of colliding across frames.
 */
class NexradWmsTileSource(
    private val time: String,
) : OnlineTileSourceBase(
    "IEM-NEXRAD-N0Q-$time",
    0,
    MAX_ZOOM,
    TILE_SIZE,
    ".png",
    arrayOf("https://mesonet.agron.iastate.edu/cgi-bin/wms/nexrad/n0q-t.cgi")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)

        // Web Mercator (EPSG:3857) bounds for this tile.
        val span = WORLD_EDGE * 2 / (1 shl zoom)
        val minX = -WORLD_EDGE + x * span
        val maxX = minX + span
        val maxY = WORLD_EDGE - y * span
        val minY = maxY - span

        return baseUrl +
            "?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap" +
            "&LAYERS=nexrad-n0q-wmst&STYLES=&FORMAT=image/png&TRANSPARENT=true" +
            "&SRS=EPSG:3857&WIDTH=$TILE_SIZE&HEIGHT=$TILE_SIZE" +
            "&BBOX=$minX,$minY,$maxX,$maxY&TIME=$time"
    }

    companion object {
        private const val MAX_ZOOM = 12
        private const val TILE_SIZE = 256

        // Half of the Web Mercator world extent, in meters.
        private const val WORLD_EDGE = 20037508.342789244
    }
}
