package io.github.brooswitminecraft.isometriccamera;

/**
 * Pure re-implementation of vanilla {@code Camera#getMaxZoom}'s 8-corner jitter/clamp algorithm
 * (MINECRAFT-408), independent of any Minecraft class so it can be unit tested without the game
 * running.
 *
 * <p>{@code Camera#getMaxZoom} is {@code private}, so {@link IsometricCameraClientEvents} cannot
 * call it directly; this reproduces its exact behavior -- same eight {@code (+-0.1, +-0.1, +-0.1)}
 * jittered ray origins, same "clamp to the closest hit, measured from the unjittered center" rule
 * -- through an injected {@link RayCast} so the real {@code BlockGetter}/{@code ClipContext} call
 * (which needs a running game) and this clamping math can be tested separately.
 *
 * <p><b>Why {@code centerX,Y,Z} must be the player's eye, not {@link
 * IsometricCameraFocusAnchor}'s lowered focus point:</b> a review of this ticket found that feeding
 * the lowered point in as the center instead collapses the result to near-zero for any standing
 * player, because that point sits a few blocks inside solid ground, and Minecraft's own ray-cast
 * reports an immediate hit when the ray's own origin is already inside a block -- regardless of
 * what is actually in front of the camera. The eye is the point vanilla's own (unmodified) detached
 * camera has always safely used as this origin, so {@link IsometricCameraClientEvents} calls this
 * with the eye as the center and only lowers the focus point afterward, for where the resulting
 * unobstructed/clamped zoom is actually measured from -- see {@code IsometricCameraCollisionTest}
 * for a regression using the exact numbers from that review.
 */
public final class IsometricCameraCollision {
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
     * @param rayCast performs the actual block collision check (real {@code BlockGetter#clip} in
     *     production, a fake in tests)
     * @param centerX the unjittered ray-cast center's X -- must be a point known to be clear of
     *     solid geometry (see class javadoc)
     * @param centerY the unjittered ray-cast center's Y -- see {@code centerX}
     * @param centerZ the unjittered ray-cast center's Z -- see {@code centerX}
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
}
