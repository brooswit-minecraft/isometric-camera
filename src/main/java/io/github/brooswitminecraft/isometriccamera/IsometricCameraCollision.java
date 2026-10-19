package io.github.brooswitminecraft.isometriccamera;

/**
 * Pure collision-clamping math for the isometric camera (MINECRAFT-408), independent of any
 * Minecraft class so it can be unit tested without the game running.
 *
 * <p><b>History on this ticket, because each earlier attempt was wrong for a documented reason:</b>
 * <ol>
 * <li>Ray-casting from {@link IsometricCameraFocusAnchor}'s lowered focus point directly collapses
 * the result to near-zero for any standing player, because that point sits a few blocks inside
 * solid ground, and Minecraft's own ray-cast reports an immediate hit when the ray's own origin is
 * already inside a block -- regardless of what is actually in front of the camera.</li>
 * <li>Ray-casting from the player's eye instead (always clear, like vanilla's own detached camera
 * has always used) avoids that collapse, but validates the wrong line: the eye and the lowered
 * anchor trace two different parallel paths once the camera pulls back, so clearance on the eye's
 * path says nothing about the lowered path the camera actually ends up on.</li>
 * <li>Searching outward along the lowered anchor's own path for where it stops being embedded
 * fixes that, but doing so by re-using the jittered ray-cast clamp itself as an "is this point
 * embedded" heuristic (a short probe collapsing to near-zero) cannot tell "this point is inside a
 * block" apart from "this point is clear but a real obstruction sits just beyond the probe
 * length" -- a clear point under a ceiling only ~0.2 blocks above it reads as "embedded" under
 * that heuristic, and the search then keeps going *through* the ceiling looking for daylight on
 * the far side.</li>
 * <li>Using an exact {@link Occupancy} point query fixes that, but a found "clear" boundary plus a
 * fixed margin can still land inside a *second*, separate solid feature shortly after the first
 * (e.g. the ground, then a stone block a few tenths of a block further out with only a thin clear
 * gap in between): the margin itself can overshoot into it, or a too-coarse search step can
 * sample on both sides of the thin gap without ever landing inside it, merging the two solid
 * regions into what looks like one continuous embedding.</li>
 * </ol>
 * {@link #resolveZoomAroundPossiblyBuriedAnchor} is the fix for (4) on top of (3): after finding a
 * candidate exit boundary and its margin, it re-checks the margin point itself with {@code
 * occupancy} before trusting it -- if that point turns out to be solid too (a new feature just
 * past the first gap), it treats it as a newly discovered embedded region and keeps searching
 * outward from there, rather than handing a possibly-solid point to {@link
 * #resolveUnobstructedZoom}. This cannot *prove* no gap is ever missed by the coarse step (no
 * fixed sampling interval can, in general), but it can never end up reporting a point as safe
 * without that point itself (and a margin past it) having been directly, individually confirmed
 * clear -- so the failure mode of a missed thin gap is landing further out than geometrically
 * ideal, never landing inside solid geometry.
 *
 * <p>It is also possible for no point along the anchor's path within the requested distance to be
 * occupancy-clear at all (e.g. a cliff face immediately alongside the player, covering the same
 * height range the anchor's path climbs through) -- {@link #resolveZoomAroundPossiblyBuriedAnchor}
 * returns {@link #NO_CLEAR_POINT_FOUND} rather than guessing in that case; see its own javadoc.
 */
public final class IsometricCameraCollision {
    /** Coarse step used to search outward from a (possibly embedded) anchor for daylight. */
    private static final float OCCUPANCY_SEARCH_STEP = 0.1f;
    /** Refines the coarse step's bracket down to sub-centimeter precision; cheap, so generous. */
    private static final int OCCUPANCY_SEARCH_BISECTIONS = 10;
    /**
     * Extra distance pushed past a found occupancy boundary before handing off to {@link
     * #resolveUnobstructedZoom}, comfortably larger than that method's own 0.1-per-axis corner
     * jitter so its corners can't land back on the solid side of the boundary they just exited.
     * Re-checked with {@code occupancy} before being trusted -- see class javadoc.
     */
    private static final float OCCUPANCY_EXIT_MARGIN = 0.2f;
    /**
     * Bounds how many separate embedded regions a single call will search past, so a pathological
     * world (alternating solid/clear slivers all the way to {@code requestedDistance}) fails fast
     * with {@link #NO_CLEAR_POINT_FOUND} instead of looping indefinitely.
     */
    private static final int MAX_EMBEDDED_REGIONS = 24;

    /**
     * Sentinel returned by {@link #resolveZoomAroundPossiblyBuriedAnchor} when no point along the
     * anchor's path within the requested distance is clear of solid geometry at all.
     */
    public static final float NO_CLEAR_POINT_FOUND = -1.0f;

    private IsometricCameraCollision() {}

    /**
     * A single ray-cast against the world's block collision, mirroring what {@code
     * BlockGetter#clip} returns: the first solid hit along the segment from the origin to the end
     * point, or {@code null} if nothing was hit.
     */
    public interface RayCast {
        /** @return {@code {x, y, z}} of the first hit, or {@code null} if the ray hit nothing */
        double[] firstHit(double originX, double originY, double originZ, double endX, double endY, double endZ);
    }

    /** Whether a single point is inside solid (collidable) block geometry. */
    public interface Occupancy {
        boolean isSolidAt(double x, double y, double z);
    }

    /**
     * Mirrors vanilla {@code Camera#getMaxZoom} (private, so not callable directly) exactly: same
     * eight {@code (+-0.1, +-0.1, +-0.1)} jittered ray origins, same "clamp to the closest hit,
     * measured from the unjittered center" rule. Assumes {@code center} is itself clear of solid
     * geometry -- see {@link #resolveZoomAroundPossiblyBuriedAnchor} when it might not be.
     *
     * @param rayCast performs the actual block collision check (real {@code BlockGetter#clip} in
     *     production, a fake in tests)
     * @param centerX the unjittered ray-cast center's X
     * @param centerY the unjittered ray-cast center's Y
     * @param centerZ the unjittered ray-cast center's Z
     * @param forwardX the camera's (unit-length) look direction's X; the ray-cast end points are
     *     {@code center - forward * requestedDistance}, matching vanilla's own {@code
     *     vec3.add(forwards.scale(-distance))}
     * @param forwardY the camera's look direction's Y -- see {@code forwardX}
     * @param forwardZ the camera's look direction's Z -- see {@code forwardX}
     * @param requestedDistance the unobstructed distance to clamp down from
     * @return {@code requestedDistance}, or the distance to the closest of the eight jittered
     *     rays' hits (measured from the unjittered center), whichever is smaller
     */
    public static float resolveUnobstructedZoom(
            RayCast rayCast,
            double centerX,
            double centerY,
            double centerZ,
            double forwardX,
            double forwardY,
            double forwardZ,
            float requestedDistance) {
        float maxZoom = requestedDistance;
        for (int i = 0; i < 8; i++) {
            double jitterX = ((i & 1) * 2 - 1) * 0.1;
            double jitterY = (((i >> 1) & 1) * 2 - 1) * 0.1;
            double jitterZ = (((i >> 2) & 1) * 2 - 1) * 0.1;
            double originX = centerX + jitterX;
            double originY = centerY + jitterY;
            double originZ = centerZ + jitterZ;
            double endX = originX - forwardX * maxZoom;
            double endY = originY - forwardY * maxZoom;
            double endZ = originZ - forwardZ * maxZoom;

            double[] hit = rayCast.firstHit(originX, originY, originZ, endX, endY, endZ);
            if (hit != null) {
                double dx = hit[0] - centerX;
                double dy = hit[1] - centerY;
                double dz = hit[2] - centerZ;
                double hitDistanceSq = dx * dx + dy * dy + dz * dz;
                if (hitDistanceSq < (double) maxZoom * maxZoom) {
                    maxZoom = (float) Math.sqrt(hitDistanceSq);
                }
            }
        }
        return maxZoom;
    }

    /**
     * Like {@link #resolveUnobstructedZoom}, but safe to call with an {@code anchor} that is itself
     * inside solid geometry (as {@link IsometricCameraFocusAnchor}'s lowered anchor always is for a
     * standing player): searches outward along the anchor's own path, using {@code occupancy} (an
     * exact point-in-geometry query, not a ray-cast -- see class javadoc for why a ray-cast-based
     * heuristic here previously misidentified a clear point near a close obstruction as embedded),
     * for the point where it stops being embedded, then clamps normally via {@link
     * #resolveUnobstructedZoom} from there for the remaining distance -- so a low ceiling, a
     * floating block, or a wall further down that same path is still caught, because it is just
     * whatever that normal clamp finds first from a point now known to be clear.
     *
     * @param rayCast see {@link #resolveUnobstructedZoom}
     * @param occupancy reports whether a given point is inside solid geometry (real {@code
     *     BlockState#getCollisionShape} in production, a fake in tests)
     * @param anchorX the (possibly embedded) anchor's X
     * @param anchorY the (possibly embedded) anchor's Y
     * @param anchorZ the (possibly embedded) anchor's Z
     * @param forwardX see {@link #resolveUnobstructedZoom}
     * @param forwardY see {@link #resolveUnobstructedZoom}
     * @param forwardZ see {@link #resolveUnobstructedZoom}
     * @param requestedDistance see {@link #resolveUnobstructedZoom}
     * @return the resulting zoom, measured from the anchor, exactly like {@link
     *     #resolveUnobstructedZoom} returns when the anchor was already clear; or {@link
     *     #NO_CLEAR_POINT_FOUND} if no point along the anchor's path within {@code
     *     requestedDistance} is clear of solid geometry at all (e.g. a cliff face immediately
     *     alongside the player) -- callers must not treat that as "fully unobstructed" and should
     *     fall back to a known-safe computation instead (see {@link
     *     IsometricCameraClientEvents#placeCameraAroundLoweredAnchor})
     */
    public static float resolveZoomAroundPossiblyBuriedAnchor(
            RayCast rayCast,
            Occupancy occupancy,
            double anchorX,
            double anchorY,
            double anchorZ,
            double forwardX,
            double forwardY,
            double forwardZ,
            float requestedDistance) {
        if (requestedDistance <= 0.0f) {
            return 0.0f;
        }

        float searchFloor = 0.0f;
        for (int region = 0; region < MAX_EMBEDDED_REGIONS; region++) {
            if (!isSolidAtDistance(occupancy, anchorX, anchorY, anchorZ, forwardX, forwardY, forwardZ, searchFloor)) {
                if (searchFloor == 0.0f) {
                    return resolveUnobstructedZoom(
                            rayCast, anchorX, anchorY, anchorZ, forwardX, forwardY, forwardZ, requestedDistance);
                }
            } else {
                float embeddedBound = searchFloor;
                float clearBound = -1.0f;
                for (float probe = searchFloor + OCCUPANCY_SEARCH_STEP; probe <= requestedDistance; probe += OCCUPANCY_SEARCH_STEP) {
                    if (!isSolidAtDistance(occupancy, anchorX, anchorY, anchorZ, forwardX, forwardY, forwardZ, probe)) {
                        clearBound = probe;
                        break;
                    }
                    embeddedBound = probe;
                }
                if (clearBound < 0.0f) {
                    return NO_CLEAR_POINT_FOUND;
                }

                for (int i = 0; i < OCCUPANCY_SEARCH_BISECTIONS; i++) {
                    float mid = (embeddedBound + clearBound) / 2.0f;
                    if (isSolidAtDistance(occupancy, anchorX, anchorY, anchorZ, forwardX, forwardY, forwardZ, mid)) {
                        embeddedBound = mid;
                    } else {
                        clearBound = mid;
                    }
                }
                searchFloor = clearBound;
            }

            // searchFloor is clear, but close enough to the boundary it was found at that
            // resolveUnobstructedZoom's own +-0.1-per-axis corner jitter could land one of its
            // eight corners back on the solid side, re-triggering the embedded-origin degenerate
            // hit *inside that call itself* (observed: it clamped to the jitter's own ~0.17-block
            // artifact instead of the real obstruction further out). A margin past it avoids that
            // -- but the margin point itself needs checking too: it can land inside a *second*,
            // separate solid feature shortly after the first (observed: a stone block a few tenths
            // of a block past the ground's own exit boundary). If so, that is a newly discovered
            // embedded region, not a safe hand-off point -- loop again and search past it instead
            // of trusting it.
            float searchExit = Math.min(searchFloor + OCCUPANCY_EXIT_MARGIN, requestedDistance);
            if (isSolidAtDistance(occupancy, anchorX, anchorY, anchorZ, forwardX, forwardY, forwardZ, searchExit)) {
                searchFloor = searchExit;
                continue;
            }

            float remaining = requestedDistance - searchExit;
            if (remaining <= 0.0f) {
                return searchExit;
            }
            double exitX = anchorX - forwardX * searchExit;
            double exitY = anchorY - forwardY * searchExit;
            double exitZ = anchorZ - forwardZ * searchExit;
            return searchExit + resolveUnobstructedZoom(rayCast, exitX, exitY, exitZ, forwardX, forwardY, forwardZ, remaining);
        }
        return NO_CLEAR_POINT_FOUND;
    }

    private static boolean isSolidAtDistance(
            Occupancy occupancy,
            double anchorX,
            double anchorY,
            double anchorZ,
            double forwardX,
            double forwardY,
            double forwardZ,
            float distance) {
        return occupancy.isSolidAt(anchorX - forwardX * distance, anchorY - forwardY * distance, anchorZ - forwardZ * distance);
    }
}
