package com.dimsumdetours.osm.routing;

import com.dimsumdetours.sim.state.GameState;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Immutable CSR-style street graph for A* pathfinding. Each node carries a
 * lat/lon and a contiguous slice of outgoing edges; each edge stores its target
 * node-index and segment length in metres. Built once by {@link OsmHighwayLoader}
 * and read by {@link OsmRouter} on the simulation thread.
 *
 * <p>CSR layout (compressed sparse row):
 * <ul>
 *   <li>{@code nodeLats[i]}, {@code nodeLons[i]} — node {@code i}'s coordinates</li>
 *   <li>{@code edgeOffsets[i] .. edgeOffsets[i+1]-1} — slice of edges leaving node
 *       {@code i} into {@code edgeTargets} / {@code edgeLengths}</li>
 * </ul>
 *
 * <p>Phase-16: nearest-node lookup is now O(1)-ish via a uniform-grid spatial
 * index (≈ {@link #SPATIAL_GRID_DEGREES}° square buckets ≈ 1 km on the equator).
 * The previous linear scan was O(n) per spawn — fine for Bellingham's ~150 k
 * nodes, ~30 ms per spawn for Hong Kong's ~450 k. The grid keeps each bucket
 * scan tiny and only walks 9 buckets (the home bucket + 8 neighbours) so spawns
 * stay well under a millisecond.
 */
public final class OsmStreetGraph {

	/** Spatial-index bucket size in degrees. ~1.1 km on the equator. */
	private static final double SPATIAL_GRID_DEGREES = 0.01;

	public static final OsmStreetGraph EMPTY = new OsmStreetGraph(
		new double[0], new double[0], new int[]{0}, new int[0], new double[0],
		new Long2ObjectOpenHashMap<>());

	private final double[] nodeLats;
	private final double[] nodeLons;
	/** {@code edgeOffsets[i]} is the start index in {@link #edgeTargets} / {@link #edgeLengths}
	 * for node {@code i}'s outgoing edges; {@code edgeOffsets[nodeCount]} = total edges. */
	private final int[] edgeOffsets;
	private final int[] edgeTargets;
	private final double[] edgeLengths;
	/** Bucket key (packed {@code (latBucket, lonBucket)}) → list of node indices in
	 * that bucket. Built once by {@link #build}. */
	private final Long2ObjectOpenHashMap<int[]> nodesByBucket;

	OsmStreetGraph(double[] nodeLats, double[] nodeLons, int[] edgeOffsets,
		int[] edgeTargets, double[] edgeLengths,
		Long2ObjectOpenHashMap<int[]> nodesByBucket) {
		this.nodeLats = nodeLats;
		this.nodeLons = nodeLons;
		this.edgeOffsets = edgeOffsets;
		this.edgeTargets = edgeTargets;
		this.edgeLengths = edgeLengths;
		this.nodesByBucket = nodesByBucket;
	}

	public int nodeCount() {
		return nodeLats.length;
	}

	public int edgeCount() {
		return edgeTargets.length;
	}

	public double nodeLat(int index) {
		return nodeLats[index];
	}

	public double nodeLon(int index) {
		return nodeLons[index];
	}

	public int edgeStart(int nodeIndex) {
		return edgeOffsets[nodeIndex];
	}

	public int edgeEnd(int nodeIndex) {
		return edgeOffsets[nodeIndex + 1];
	}

	public int edgeTarget(int edgeIndex) {
		return edgeTargets[edgeIndex];
	}

	public double edgeLength(int edgeIndex) {
		return edgeLengths[edgeIndex];
	}

	/**
	 * Nearest-node lookup via a uniform-grid spatial index. Walks the home bucket
	 * plus its 8 neighbours so the answer is correct for any query within
	 * ½ × {@value #SPATIAL_GRID_DEGREES}° of a node — well over our 1 km snap
	 * threshold. If no node lives within that 3×3 window, falls back to a full
	 * linear scan so we still return a {@link #EMPTY}-graph-aware answer instead
	 * of a wrong nearest-bucket guess.
	 */
	public int nearestNode(double lat, double lon) {
		if (nodeLats.length == 0) {
			return -1;
		}
		int latBucket = (int) Math.floor(lat / SPATIAL_GRID_DEGREES);
		int lonBucket = (int) Math.floor(lon / SPATIAL_GRID_DEGREES);
		int best = -1;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int dLat = -1; dLat <= 1; dLat++) {
			for (int dLon = -1; dLon <= 1; dLon++) {
				int[] bucket = nodesByBucket.get(bucketKey(latBucket + dLat, lonBucket + dLon));
				if (bucket == null) {
					continue;
				}
				for (int nodeIdx : bucket) {
					double distance = GameState.haversineMetres(
						lat, lon, nodeLats[nodeIdx], nodeLons[nodeIdx]);
					if (distance < bestDistance) {
						best = nodeIdx;
						bestDistance = distance;
					}
				}
			}
		}
		if (best >= 0) {
			return best;
		}
		// Sparse-graph fallback: query is in a region with no nearby buckets
		// populated. Linear scan rather than miss; OSM_MAX_SNAP_METERS will
		// still gate whether the answer is accepted.
		for (int i = 0; i < nodeLats.length; i++) {
			double distance = GameState.haversineMetres(lat, lon, nodeLats[i], nodeLons[i]);
			if (distance < bestDistance) {
				best = i;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** Distance in metres from {@code (lat, lon)} to the nearest graph node. */
	public double nearestNodeDistance(double lat, double lon, int nearestNodeIndex) {
		if (nearestNodeIndex < 0) {
			return Double.POSITIVE_INFINITY;
		}
		return GameState.haversineMetres(
			lat, lon, nodeLats[nearestNodeIndex], nodeLons[nearestNodeIndex]);
	}

	/**
	 * Build a graph from a parallel-array node list and a list of (fromIdx, toIdx,
	 * lengthMeters) directed edges. Edges are bucketed by source node and sorted into
	 * CSR layout. Caller passes raw arrays so the loader can use fastutil primitive
	 * lists without an interface boundary copy.
	 */
	public static OsmStreetGraph build(
		DoubleArrayList nodeLats,
		DoubleArrayList nodeLons,
		IntArrayList edgeFrom,
		IntArrayList edgeTo,
		DoubleArrayList edgeLengths
	) {
		int nodeCount = nodeLats.size();
		int edgeCount = edgeFrom.size();
		int[] offsets = new int[nodeCount + 1];
		// Counting pass.
		for (int i = 0; i < edgeCount; i++) {
			offsets[edgeFrom.getInt(i) + 1]++;
		}
		// Prefix-sum pass.
		for (int i = 1; i <= nodeCount; i++) {
			offsets[i] += offsets[i - 1];
		}
		int[] targets = new int[edgeCount];
		double[] lengths = new double[edgeCount];
		int[] cursor = new int[nodeCount];
		for (int i = 0; i < edgeCount; i++) {
			int from = edgeFrom.getInt(i);
			int slot = offsets[from] + cursor[from]++;
			targets[slot] = edgeTo.getInt(i);
			lengths[slot] = edgeLengths.getDouble(i);
		}

		// Phase-16: build the spatial index. Two-pass: count per bucket so we
		// can size each int[] exactly, then fill.
		Long2IntOpenHashMap bucketCounts = new Long2IntOpenHashMap();
		bucketCounts.defaultReturnValue(0);
		for (int i = 0; i < nodeCount; i++) {
			long key = bucketKey(
				(int) Math.floor(nodeLats.getDouble(i) / SPATIAL_GRID_DEGREES),
				(int) Math.floor(nodeLons.getDouble(i) / SPATIAL_GRID_DEGREES));
			bucketCounts.addTo(key, 1);
		}
		Long2ObjectOpenHashMap<int[]> buckets = new Long2ObjectOpenHashMap<>(bucketCounts.size());
		for (var entry : bucketCounts.long2IntEntrySet()) {
			buckets.put(entry.getLongKey(), new int[entry.getIntValue()]);
		}
		Long2IntOpenHashMap fillCursor = new Long2IntOpenHashMap();
		fillCursor.defaultReturnValue(0);
		for (int i = 0; i < nodeCount; i++) {
			long key = bucketKey(
				(int) Math.floor(nodeLats.getDouble(i) / SPATIAL_GRID_DEGREES),
				(int) Math.floor(nodeLons.getDouble(i) / SPATIAL_GRID_DEGREES));
			int slot = fillCursor.addTo(key, 1);
			buckets.get(key)[slot] = i;
		}

		return new OsmStreetGraph(
			nodeLats.toDoubleArray(),
			nodeLons.toDoubleArray(),
			offsets,
			targets,
			lengths,
			buckets);
	}

	/** Pack a {@code (latBucket, lonBucket)} pair into one 64-bit key. */
	private static long bucketKey(int latBucket, int lonBucket) {
		return (((long) latBucket) << 32) | (lonBucket & 0xFFFFFFFFL);
	}

	/** Used by the loader; no public utility. Kept package-private to avoid leaking. */
	static Long2IntOpenHashMap newOsmIdIndex(int expected) {
		Long2IntOpenHashMap m = new Long2IntOpenHashMap(expected);
		m.defaultReturnValue(-1);
		return m;
	}
}

