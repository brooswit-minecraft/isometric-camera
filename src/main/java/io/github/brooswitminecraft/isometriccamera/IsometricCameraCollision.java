package io.github.brooswitminecraft.isometriccamera;

/**
 * Pure collision-clamping math for the isometric camera (MINECRAFT-408), independent of any
 * Minecraft class so it can be unit tested without the game running.
 *
 * <p><b>History on this ticket, because both earlier attempts were wrong for documented reasons:</b>
 * <ol>
 * <li>Ray-casting from {@link IsometricCameraFocusAnchor}'s lowered focus point directly collapses
 * the result to near-zero for any standing player, because that point sits a few blocks inside
 * solid ground, and Minecraft's own ray-cast reports an immediate hit when the ray's own origin is
 * already inside a block -- regardless of what is actually in front of the camera.</li>
 * <li>Ray-casting from the player's eye instead (always clear, like vanilla's own detached camera
 * has always used) avoids that collapse, but validates the wrong line: the eye and the lowered
 * anchor trace two different parallel paths once the camera pulls back, so clearance on the eye's
 * path says nothing about the lowered path the camera actually ends up on -- a low ceiling right
 * above the eye can clamp the eye's zoom small while that same small zoom, applied to the lowered
 * anchor, is still inside the floor; conversely an obstruction the eye's path narrowly misses can
 * still be squarely on the lowered path.</li>
 * </ol>
 * {@link #resolveZoomAroundPossiblyBuriedAnchor} is the fix: it ray-casts along the lowered
 * anchor's own actual path throughout, handling that path's own origin being embedded by searching
 * outward along it for the point where it stops being embedded, then clamping normally (via {@link
 * #resolveUnobstructedZoom}) for whatever is beyond that from then on -- so a low ceiling, a
 * floating block, or a wall further down the same lowered path are all still caught, because they
 * are all just "whatever {@link #resolveUnobstructedZoom} finds first" from a point now guaranteed
 * clear.
 */
public final class IsometricCameraCollision {
    /** Length of the probe ray-cast {@link #isEmbedded} uses to test a single point. */
    private static final float EMBEDDING_PROBE_DISTANCE = 0.5f;
    /** A probe collapsing to under half its own length means its own start was already blocked. */
    private static final float EMBEDDING_THRESHOLD = EMBEDDING_PROBE_DISTANCE * 0.5f;
    /** Coarse step used to search outward from a (possibly embedded) origin for daylight. */
    private static final float EMBEDDING_SEARCH_STEP = 0.5f;
    /** Refines the coarse step's bracket down to sub-centimeter precision; cheap, so generous. */
    private static final int EMBEDDING_SEARCH_BISECTIONS = 12;

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
     * standing player): searches outward along the anchor's own path for the point where it stops
     * being embedded, then clamps normally from there for the remaining distance, so any further
     * obstruction along that same path (a low ceiling, a floating block, a wall) is still caught.
     *
     * @param rayCast see {@link #resolveUnobstructedZoom}
     * @param anchorX the (possibly embedded) anchor's X
     * @param anchorY the (possibly embedded) anchor's Y
     * @param anchorZ the (possibly embedded) anchor's Z
     * @param forwardX see {@link #resolveUnobstructedZoom}
     * @param forwardY see {@link #resolveUnobstructedZoom}
     * @param forwardZ see {@link #resolveUnobstructedZoom}
     * @param requestedDistance see {@link #resolveUnobstructedZoom}
     * @return the resulting zoom, measured from the anchor, exactly like {@link
     *     #resolveUnobstructedZoom} returns when the anchor was already clear; if no point along
     *     the anchor's path within {@code requestedDistance} is clear of solid geometry at all
     *     (e.g. deep inside a mountain), returns {@code requestedDistance} rather than leaving the
     *     camera stuck at the (still embedded) anchor
     */
    public static float resolveZoomAroundPossiblyBuriedAnchor(
            RayCast rayCast,
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
        if (!isEmbedded(rayCast, anchorX, anchorY, anchorZ, forwardX, forwardY, forwardZ)) {
            return resolveUnobstructedZoom(rayCast, anchorX, anchorY, anchorZ, forwardX, forwardY, forwardZ, requestedDistance);
        }

        float embeddedBound = 0.0f;
        float clearBound = -1.0f;
        for (float probe = EMBEDDING_SEARCH_STEP; probe <= requestedDistance; probe += EMBEDDING_SEARCH_STEP) {
            double px = anchorX - forwardX * probe;
            double py = anchorY - forwardY * probe;
            double pz = anchorZ - forwardZ * probe;
            if (!isEmbedded(rayCast, px, py, pz, forwardX, forwardY, forwardZ)) {
                clearBound = probe;
                break;
            }
            embeddedBound = probe;
        }
        if (clearBound < 0.0f) {
            // Never found daylight within the requested distance at all -- give up rather than
            // leaving the camera stuck (still embedded) at the anchor.
            return requestedDistance;
        }

        for (int i = 0; i < EMBEDDING_SEARCH_BISECTIONS; i++) {
            float mid = (embeddedBound + clearBound) / 2.0f;
            double px = anchorX - forwardX * mid;
            double py = anchorY - forwardY * mid;
            double pz = anchorZ - forwardZ * mid;
            if (isEmbedded(rayCast, px, py, pz, forwardX, forwardY, forwardZ)) {
                embeddedBound = mid;
            } else {
                clearBound = mid;
            }
        }

        double exitX = anchorX - forwardX * clearBound;
        double exitY = anchorY - forwardY * clearBound;
        double exitZ = anchorZ - forwardZ * clearBound;
        float remaining = requestedDistance - clearBound;
        return clearBound + resolveUnobstructedZoom(rayCast, exitX, exitY, exitZ, forwardX, forwardY, forwardZ, remaining);
    }

    /** Whether a short probe ray-cast starting at this exact point is blocked almost immediately. */
    private static boolean isEmbedded(
            RayCast rayCast, double x, double y, double z, double forwardX, double forwardY, double forwardZ) {
        float probeZoom = resolveUnobstructedZoom(rayCast, x, y, z, forwardX, forwardY, forwardZ, EMBEDDING_PROBE_DISTANCE);
        return probeZoom < EMBEDDING_THRESHOLD;
    }
}
