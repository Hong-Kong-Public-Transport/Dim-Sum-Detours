package com.dimsumdetours.gtfs;

/**
 * Geographic bounding box in WGS84 lat/lon. Serialized straight to JSON for the frontend.
 *
 * @param south south latitude (min lat)
 * @param west  west longitude (min lon)
 * @param north north latitude (max lat)
 * @param east  east longitude (max lon)
 */
public record BoundingBox(double south, double west, double north, double east) {
}
