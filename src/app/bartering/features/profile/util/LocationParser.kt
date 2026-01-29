package app.bartering.features.profile.util

import java.sql.ResultSet

/**
 * Utility object for parsing location data from database results.
 */
object LocationParser {
    
    /**
     * Parses a PostGIS POINT geography type from a ResultSet into longitude/latitude pair.
     * 
     * The PostGIS POINT format is: "POINT(longitude latitude)"
     * 
     * @param rs The ResultSet containing a "location" column with PostGIS POINT data
     * @return Pair of (longitude, latitude) or (null, null) if location is not present or invalid
     * 
     * @example
     * ```kotlin
     * val (longitude, latitude) = LocationParser.parseLocation(rs)
     * ```
     */
    fun parseLocation(rs: ResultSet): Pair<Double?, Double?> {
        // Parse location from string
        val lStr = rs.getObject("location")?.toString()
        if (lStr != null && lStr.contains("POINT")) {
            val locationStr =
                lStr.substring(lStr.indexOf("POINT") + 6, lStr.indexOf(")"))
            val coords = locationStr.split(" ")
            return Pair(coords[0].trim().toDouble(), coords[1].trim().toDouble())
        } else {
            return Pair(null, null)
        }
    }
    
    /**
     * Parses a PostGIS POINT geography type from a ResultSet with a custom column name.
     * 
     * @param rs The ResultSet containing PostGIS POINT data
     * @param columnName The name of the column containing the location data
     * @return Pair of (longitude, latitude) or (null, null) if location is not present or invalid
     */
    fun parseLocation(rs: ResultSet, columnName: String): Pair<Double?, Double?> {
        val lStr = rs.getObject(columnName)?.toString()
        if (lStr != null && lStr.contains("POINT")) {
            val locationStr =
                lStr.substring(lStr.indexOf("POINT") + 6, lStr.indexOf(")"))
            val coords = locationStr.split(" ")
            return Pair(coords[0].trim().toDouble(), coords[1].trim().toDouble())
        } else {
            return Pair(null, null)
        }
    }
}
