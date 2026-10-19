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
 * <li>Re-checking the margin point and, if it is solid too, *searching past it* (treating it as
 * just another embedded region) seems like the fix for (4), but it is not: once the search has
 * skipped over a real obstruction this way, the final {@link #resolveUnobstructedZoom} call is
 * handed a center already on its far side, so it never gets a chance to clamp against it either --
 * the reported final position ends up clear of that obstruction without the path ever having been
 * validated against it, i.e. the obstruction is skipped rather than respected.</li>
 * </ol>
 * {@link #resolveZoomAroundPossiblyBuriedAnchor} is the fix for (5): it escapes *only* the single
 * embedded region touching the anchor itself (the first solid-to-clear transition along the
 * path), using a deliberately fine search step so a thin gap immediately followed by a second
 * obstruction is still found rather than merged into one apparent region with it. If the margin
 * past that escape point is itself solid -- meaning the gap was too narrow to safely hand off to
 * {@link #resolveUnobstructedZoom} at all -- this returns {@link #NO_CLEAR_POINT_FOUND} rather
 * than searching further: any later obstruction is {@link #resolveUnobstructedZoom}'s job to
 * clamp against (it does this correctly and exactly, being a real, continuous ray-cast once run
 * from a genuinely clear point), not something this method should itself search past.
 *
 * <p>It is also possible for no point along the anchor's path within the requested distance to be
 * occupancy-clear at all (e.g. a cliff face immediately alongside the player, covering the same
 * height range the anchor's path climbs through) -- {@link #resolveZoomAroundPossiblyBuriedAnchor}
 * returns {@link #NO_CLEAR_POINT_FOUND} rather than guessing in that case too.
 */
public final class IsometricCameraCollision {
    /**
     * Coarse step used to search outward from a (possibly embedded) anchor for daylight.
     * Deliberately fine (rather than, say, half a block) so a thin gap immediately followed by a
     * second obstruction is still found as its own transition instead of being merged with that
     * obstruction into what looks like one continuous embedding -- see class javadoc. This no
     * longer runs the real ray-cast (only cheap {@link Occupancy} point queries), so a fine step
     * over the handful of blocks a standing player's own anchor is typically embedded by is not
     * costly.
     */
    private static final float OCCUPANCY_SEARCH_STEP = 0.02f;
    /** Refines the coarse step's bracket down to sub-millimeter precision; cheap, so generous. */
    private static final int OCCUPANCY_SEARCH_BISECTIONS = 10;
    /**
     * Extra distance pushed past the found occupancy boundary before handing off to {@link
     * #resolveUnobstructedZoom}, comfortably larger than that method's own 0.1-per-axis corner
     * jitter so its corners can't land back on the solid side of the boundary they just exited.
     * Re-checked with {@code occupancy} before being trusted -- see class javadoc.
     */
    private static final float OCCUPANCY_EXIT_MARGIN = 0.2f;

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
        if (!occupancy.isSolidAt(anchorX, anchorY, anchorZ)) {
            return resolveUnobstructedZoom(rayCast, anchorX, anchorY, anchorZ, forwardX, forwardY, forwardZ, requestedDistance);
        }

        float embeddedBound = 0.0f;
        float clearBound = -1.0f;
        for (float probe = OCCUPANCY_SEARCH_STEP; probe <= requestedDistance; probe += OCCUPANCY_SEARCH_STEP) {
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

        // clearBound is clear, but close enough to the boundary it was found at that
        // resolveUnobstructedZoom's own +-0.1-per-axis corner jitter could land one of its eight
        // corners back on the solid side, re-triggering the embedded-origin degenerate hit *inside
        // that call itself* (observed: it clamped to the jitter's own ~0.17-block artifact instead
        // of the real obstruction further out). A margin past it avoids that -- but the margin
        // point itself needs checking too: it can land inside a *second*, separate solid feature
        // shortly after the first, with only a thin clear gap in between (observed: a stone block a
        // few tenths of a block past the ground's own exit boundary). If so, the gap is too narrow
        // to safely hand off to resolveUnobstructedZoom at all -- report that rather than searching
        // past this second obstruction too (see class javadoc for why that would be wrong).
        float searchExit = Math.min(clearBound + OCCUPANCY_EXIT_MARGIN, requestedDistance);
        if (isSolidAtDistance(occupancy, anchorX, anchorY, anchorZ, forwardX, forwardY, forwardZ, searchExit)) {
            return NO_CLEAR_POINT_FOUND;
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
