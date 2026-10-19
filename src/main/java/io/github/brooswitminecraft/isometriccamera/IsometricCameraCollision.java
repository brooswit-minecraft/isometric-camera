package io.github.brooswitminecraft.isometriccamera;

import java.util.ArrayList;
import java.util.List;

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
 * fixes that, but doing so with a point-sampling heuristic (a short ray-cast probe, then exact
 * point-occupancy queries at a fixed step) cannot *prove* a thin gap between two separate solid
 * features is never missed: however fine the fixed step, the player's own position continuously
 * changes the gap's width and alignment against that step, so an adversarial (or simply unlucky)
 * position can always make the gap narrower than whatever step is chosen -- confirmed live, down
 * to a reported ~0.005-block gap defeating a 0.02-block step. "Search past" a sampling miss once
 * found doesn't fix this either: it just lets the search skip the missed obstruction entirely
 * (the final clamp then starts beyond it, so it's never validated against it at all).</li>
 * </ol>
 * {@link #resolveZoomAroundPossiblyBuriedAnchor} is the fix for all of these: no fixed-step or
 * probe-based sampling at all. {@link #boxEntryExit} and {@link #traverseBlocks} give {@link
 * IsometricCameraClientEvents#placeCameraAroundLoweredAnchor} what it needs to compute the exact
 * (continuous, not sampled) set of solid-geometry intervals the anchor's path crosses, directly
 * from each block's own real collision shape; {@link #resolveZoomAroundPossiblyBuriedAnchor} takes
 * that exact interval set and finds precisely where the single region containing the anchor itself
 * ends, with no discretization error to exploit -- a gap either fits the camera (and its safety
 * margin) between that region and whatever solid interval comes next, or it provably does not, in
 * which case this returns {@link #NO_CLEAR_POINT_FOUND} rather than guessing.
 */
public final class IsometricCameraCollision {
    /**
     * Extra distance required, past the end of the anchor's own embedded region, before handing
     * off to {@link #resolveUnobstructedZoom} -- comfortably larger than that method's own
     * 0.1-per-axis corner jitter so its corners can't land back on the solid side of the boundary
     * they just exited.
     */
    private static final double ESCAPE_MARGIN = 0.2;

    /**
     * Sentinel returned by {@link #resolveZoomAroundPossiblyBuriedAnchor} when no point along the
     * anchor's path within the requested distance is clear of solid geometry (with room for the
     * {@link #ESCAPE_MARGIN}) at all.
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

    /**
     * Supplies the exact set of solid-geometry intervals a straight path crosses. Each interval is
     * {@code {entryDistance, exitDistance}}, the (possibly negative, possibly beyond {@code
     * maxDistance}) distances along the path -- measured as {@code origin + direction * distance}
     * -- where it is inside one piece of solid collision geometry. Intervals may be returned in any
     * order and may overlap; {@link #resolveZoomAroundPossiblyBuriedAnchor} merges them.
     */
    public interface SolidIntervalSource {
        List<double[]> solidIntervals(
                double originX,
                double originY,
                double originZ,
                double directionX,
                double directionY,
                double directionZ,
                double maxDistance);
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
     * Safe to call with an {@code anchor} that is itself inside solid geometry (as {@link
     * IsometricCameraFocusAnchor}'s lowered anchor always is for a standing player): using the
     * exact solid-geometry intervals {@code intervalSource} reports for the anchor's path, finds
     * precisely where the single region containing the anchor ends, then clamps normally via
     * {@link #resolveUnobstructedZoom} from a point {@link #ESCAPE_MARGIN} past it for the
     * remaining distance -- so a low ceiling, a floating block, or a wall further down that path is
     * still caught, because it is just whatever that normal (real, continuous) ray-cast finds first
     * from a point now known to be clear.
     *
     * @param intervalSource see {@link SolidIntervalSource}
     * @param rayCast see {@link #resolveUnobstructedZoom}
     * @param anchorX the (possibly embedded) anchor's X
     * @param anchorY the (possibly embedded) anchor's Y
     * @param anchorZ the (possibly embedded) anchor's Z
     * @param forwardX see {@link #resolveUnobstructedZoom}
     * @param forwardY see {@link #resolveUnobstructedZoom}
     * @param forwardZ see {@link #resolveUnobstructedZoom}
     * @param requestedDistance see {@link #resolveUnobstructedZoom}
     * @return the resulting zoom, measured from the anchor, exactly like {@link
     *     #resolveUnobstructedZoom} returns when the anchor was already clear; or {@link
     *     #NO_CLEAR_POINT_FOUND} if the anchor is embedded and no point past that embedded region,
     *     with room for {@link #ESCAPE_MARGIN}, is clear within {@code requestedDistance} (e.g. a
     *     cliff face immediately alongside the player, or a gap too narrow to fit the camera) --
     *     callers must not treat that as "fully unobstructed" and should fall back to a known-safe
     *     computation instead (see {@link IsometricCameraClientEvents#placeCameraAroundLoweredAnchor})
     */
    public static float resolveZoomAroundPossiblyBuriedAnchor(
            SolidIntervalSource intervalSource,
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
        double directionX = -forwardX;
        double directionY = -forwardY;
        double directionZ = -forwardZ;

        List<double[]> merged =
                mergeIntervals(intervalSource.solidIntervals(anchorX, anchorY, anchorZ, directionX, directionY, directionZ, requestedDistance));

        if (merged.isEmpty() || merged.get(0)[0] > 0.0) {
            // No interval covers the anchor itself -- it is already clear.
            return resolveUnobstructedZoom(rayCast, anchorX, anchorY, anchorZ, forwardX, forwardY, forwardZ, requestedDistance);
        }

        double escapeDistance = merged.get(0)[1];
        if (escapeDistance >= requestedDistance) {
            // The embedded region itself never ends within the requested distance.
            return NO_CLEAR_POINT_FOUND;
        }
        double searchExit = Math.min(escapeDistance + ESCAPE_MARGIN, requestedDistance);
        if (merged.size() > 1 && merged.get(1)[0] < searchExit) {
            // The very next solid interval starts before the margin point -- the gap between the
            // anchor's own embedded region and whatever comes next is narrower than the camera's
            // safety margin needs. Report that rather than tunneling into (or past) it.
            return NO_CLEAR_POINT_FOUND;
        }

        float remaining = (float) (requestedDistance - searchExit);
        if (remaining <= 0.0f) {
            return (float) searchExit;
        }
        double exitX = anchorX + directionX * searchExit;
        double exitY = anchorY + directionY * searchExit;
        double exitZ = anchorZ + directionZ * searchExit;
        return (float) searchExit + resolveUnobstructedZoom(rayCast, exitX, exitY, exitZ, forwardX, forwardY, forwardZ, remaining);
    }

    /** Sorts by start and merges overlapping/touching intervals; does not mutate the input list. */
    private static List<double[]> mergeIntervals(List<double[]> raw) {
        List<double[]> sorted = new ArrayList<>(raw);
        sorted.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] interval : sorted) {
            if (!merged.isEmpty() && interval[0] <= merged.get(merged.size() - 1)[1]) {
                double[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], interval[1]);
            } else {
                merged.add(new double[] {interval[0], interval[1]});
            }
        }
        return merged;
    }

    /**
     * Exact (slab-method) *line*-axis-aligned-box intersection -- the infinite line through {@code
     * origin} along {@code direction}, not a forward-only ray, so both {@code entryDistance} and
     * {@code exitDistance} can be negative if the box lies entirely behind {@code origin} along
     * that direction. Parametrized directly in distance along {@code (directionX, directionY,
     * directionZ)} from {@code (originX, originY, originZ)} rather than the usual {@code [0, 1]}
     * fraction, so callers can compare results across different boxes without rescaling.
     *
     * @return {@code {entryDistance, exitDistance}} (entry may be negative if {@code origin} is
     *     already inside the box, or if the box is entirely behind {@code origin}), or {@code
     *     null} if the line along this direction never intersects the box at all (parallel and
     *     outside, for at least one axis)
     */
    public static double[] boxEntryExit(
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double minX,
            double maxX,
            double minY,
            double maxY,
            double minZ,
            double maxZ) {
        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;

        double[] origins = {originX, originY, originZ};
        double[] directions = {directionX, directionY, directionZ};
        double[] los = {minX, minY, minZ};
        double[] his = {maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            double direction = directions[axis];
            if (direction == 0.0) {
                if (origins[axis] < los[axis] || origins[axis] > his[axis]) {
                    return null;
                }
                continue;
            }
            double t1 = (los[axis] - origins[axis]) / direction;
            double t2 = (his[axis] - origins[axis]) / direction;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return null;
            }
        }
        return new double[] {tMin, tMax};
    }

    /**
     * Enumerates, in increasing order of distance, every integer block position {@code {floorX,
     * floorY, floorZ}} the segment from {@code (startX, startY, startZ)} to {@code start +
     * direction * maxDistance} passes through -- the standard Amanatides-Woo voxel traversal, so
     * unlike fixed-distance-step sampling it cannot skip a block no matter how far apart two
     * solid features are positioned or how thin the gap between them is: every block the segment
     * geometrically enters is visited, exactly once, with no discretization gap between them.
     *
     * @param directionX, directionY, directionZ need not be unit length, but must not all be zero
     */
    public static List<int[]> traverseBlocks(
            double startX, double startY, double startZ, double directionX, double directionY, double directionZ, double maxDistance) {
        List<int[]> blocks = new ArrayList<>();
        int[] current = {(int) Math.floor(startX), (int) Math.floor(startY), (int) Math.floor(startZ)};
        double[] start = {startX, startY, startZ};
        double[] direction = {directionX, directionY, directionZ};
        int[] step = new int[3];
        double[] tMax = new double[3];
        double[] tDelta = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            if (direction[axis] > 0.0) {
                step[axis] = 1;
                double boundary = current[axis] + 1.0;
                tMax[axis] = (boundary - start[axis]) / direction[axis];
                tDelta[axis] = 1.0 / direction[axis];
            } else if (direction[axis] < 0.0) {
                step[axis] = -1;
                double boundary = current[axis];
                tMax[axis] = (boundary - start[axis]) / direction[axis];
                tDelta[axis] = -1.0 / direction[axis];
            } else {
                step[axis] = 0;
                tMax[axis] = Double.POSITIVE_INFINITY;
                tDelta[axis] = Double.POSITIVE_INFINITY;
            }
        }

        blocks.add(current.clone());
        double t = 0.0;
        while (t < maxDistance) {
            int axis = tMax[0] <= tMax[1] && tMax[0] <= tMax[2] ? 0 : (tMax[1] <= tMax[2] ? 1 : 2);
            t = tMax[axis];
            if (t >= maxDistance) {
                break;
            }
            current[axis] += step[axis];
            tMax[axis] += tDelta[axis];
            blocks.add(current.clone());
        }
        return blocks;
    }
}
