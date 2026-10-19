package io.github.brooswitminecraft.isometriccamera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IsometricCameraCollisionTest {
    /**
     * The isometric camera's fixed look direction at {@code ISOMETRIC_YAW}/{@code
     * ISOMETRIC_PITCH} (yaw 225, pitch 55) -- unit length, matching what {@code
     * Camera#getLookVector} returns in production. Used here so the regression tests below
     * reproduce the MINECRAFT-408 review's own numbers under the camera's actual geometry, not an
     * arbitrary direction.
     */
    private static final double FORWARD_X = 0.40571;
    private static final double FORWARD_Y = -0.81915;
    private static final double FORWARD_Z = -0.40571;

    private static boolean inBox(
            double x, double y, double z, double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        return x > minX && x < maxX && y > minY && y < maxY && z > minZ && z < maxZ;
    }

    /** A flat world: solid ground at every Y strictly below this; open air at or above it. */
    private static IsometricCameraCollision.RayCast flatGroundAt(double groundY) {
        return (ox, oy, oz, ex, ey, ez) -> {
            if (oy < groundY) {
                // Mirrors real Minecraft: a ray-cast whose own origin is already inside a solid
                // block reports an immediate hit at that origin, before even considering the end
                // point.
                return new double[] {ox, oy, oz};
            }
            if (ey >= groundY) {
                return null;
            }
            double t = (oy - groundY) / (oy - ey);
            return new double[] {ox + (ex - ox) * t, groundY, oz + (ez - oz) * t};
        };
    }

    /** Solid below {@code floorY}, solid at/above {@code ceilingY}, clear in between. */
    private static IsometricCameraCollision.RayCast floorAndCeiling(double floorY, double ceilingY) {
        return (ox, oy, oz, ex, ey, ez) -> {
            if (oy < floorY || oy >= ceilingY) {
                return new double[] {ox, oy, oz};
            }
            if (ey < floorY) {
                double t = (floorY - oy) / (ey - oy);
                return new double[] {ox + (ex - ox) * t, floorY, oz + (ez - oz) * t};
            }
            if (ey >= ceilingY) {
                double t = (ceilingY - oy) / (ey - oy);
                return new double[] {ox + (ex - ox) * t, ceilingY, oz + (ez - oz) * t};
            }
            return null;
        };
    }

    private static IsometricCameraCollision.Occupancy floorAndCeilingOccupancy(double floorY, double ceilingY) {
        return (x, y, z) -> y < floorY || y >= ceilingY;
    }

    /** Entry point of the segment into the given axis-aligned box, or {@code null} if it misses. */
    private static double[] aabbHit(
            double ox,
            double oy,
            double oz,
            double ex,
            double ey,
            double ez,
            double minX,
            double maxX,
            double minY,
            double maxY,
            double minZ,
            double maxZ) {
        double[] origin = {ox, oy, oz};
        double[] delta = {ex - ox, ey - oy, ez - oz};
        double[] lo = {minX, minY, minZ};
        double[] hi = {maxX, maxY, maxZ};
        double tMin = 0.0;
        double tMax = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            double d = delta[axis];
            if (Math.abs(d) < 1e-12) {
                if (origin[axis] < lo[axis] || origin[axis] > hi[axis]) {
                    return null;
                }
                continue;
            }
            double t1 = (lo[axis] - origin[axis]) / d;
            double t2 = (hi[axis] - origin[axis]) / d;
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
        return new double[] {ox + delta[0] * tMin, oy + delta[1] * tMin, oz + delta[2] * tMin};
    }

    private static double sq(double v) {
        return v * v;
    }

    @Test
    void grantsTheFullRequestedDistanceWhenNothingIsInTheWay() {
        IsometricCameraCollision.RayCast clearSky = (ox, oy, oz, ex, ey, ez) -> null;
        float zoom = IsometricCameraCollision.resolveUnobstructedZoom(clearSky, 0, 65.62, 0, 0, -1, 0, 16.0f);
        assertEquals(16.0f, zoom, 0.0001f);
    }

    @Test
    void clampsToAnObstructionCloserThanTheRequestedDistance() {
        // A wall at world Z=5, straight ahead, should clamp a 16-block request down to ~5 (exactly
        // 5 at the unjittered center; the jittered corners land a hair over 5 since their hit still
        // lands on the X/Y-jittered ray but at the same wall plane).
        double wallZ = 5.0;
        IsometricCameraCollision.RayCast wallAtFive = (ox, oy, oz, ex, ey, ez) -> {
            if (oz <= wallZ && ez >= wallZ) {
                return new double[] {ox, oy, wallZ};
            }
            return null;
        };
        float zoom = IsometricCameraCollision.resolveUnobstructedZoom(wallAtFive, 0, 65.0, 0, 0, 0, -1, 16.0f);
        assertEquals(5.0f, zoom, 0.01f);
    }

    /**
     * Demonstrates why {@link IsometricCameraCollision#resolveUnobstructedZoom} alone cannot be
     * fed the lowered focus anchor directly (feet Y=64, eye Y=65.62, ground solid below Y=64, from
     * the MINECRAFT-408 review): the anchor (eye - 4 = 61.62) is already inside solid ground, and
     * ray-casting from it collapses to near-zero -- motivating {@link
     * IsometricCameraCollision#resolveZoomAroundPossiblyBuriedAnchor} below.
     */
    @Test
    void feedingTheRawResolveMethodAnEmbeddedAnchorCollapses() {
        double groundY = 64.0;
        double loweredAnchorY = 65.62 - 4.0;
        IsometricCameraCollision.RayCast groundAtPlayerFeet = flatGroundAt(groundY);

        float zoom = IsometricCameraCollision.resolveUnobstructedZoom(groundAtPlayerFeet, 0, loweredAnchorY, 0, 0, -1, 0, 16.0f);
        assertTrue(zoom < 1.0f, "expected the embedded-origin collapse this review reported");
    }

    /**
     * Regression for the MINECRAFT-408 re-review: feet Y=64, eye Y=65.62, floor solid below Y=64,
     * a ceiling solid at/above Y=67. {@link
     * IsometricCameraCollision#resolveZoomAroundPossiblyBuriedAnchor}, run along the lowered
     * anchor's own (embedded-at-the-start) path, must neither stay stuck in the floor nor punch
     * through the ceiling -- it should settle just under the ceiling.
     */
    @Test
    void settlesJustUnderACeilingInsteadOfStayingInTheFloorOrPunchingThrough() {
        double eyeY = 65.62;
        double loweredAnchorY = eyeY - 4.0;
        IsometricCameraCollision.RayCast floorAndLowCeiling = floorAndCeiling(64.0, 67.0);
        IsometricCameraCollision.Occupancy occupancy = floorAndCeilingOccupancy(64.0, 67.0);

        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                floorAndLowCeiling, occupancy, 0, loweredAnchorY, 0, FORWARD_X, FORWARD_Y, FORWARD_Z, 16.0f);

        double finalY = loweredAnchorY - FORWARD_Y * zoom;
        assertTrue(finalY >= 64.0, "must not stay inside the floor: finalY=" + finalY);
        assertTrue(finalY <= 67.0001, "must not punch through the ceiling: finalY=" + finalY);
        assertEquals(67.0, finalY, 0.2, "should settle right at the ceiling's underside");
    }

    /**
     * Regression for the MINECRAFT-408 re-review's second finding: ray-casting the lowered
     * anchor's path only from the player's eye (an earlier revision of this fix) missed a block
     * that the actual lowered path flies into near the full requested distance. Reproduces the
     * review's own block (X=[-7,-6], Y=[74,75], Z=[6,7]) with the eye/anchor numbers from its
     * first finding (feet Y=64, eye Y=65.62) and the camera's real yaw/pitch, and asserts the fix
     * does not end up inside it.
     */
    @Test
    void doesNotEndUpInsideADistantBlockTheLoweredPathFliesInto() {
        double eyeY = 65.62;
        double loweredAnchorY = eyeY - 4.0;
        double floorY = 64.0;

        IsometricCameraCollision.RayCast floorAndDistantBlock = (ox, oy, oz, ex, ey, ez) -> {
            if (oy < floorY) {
                return new double[] {ox, oy, oz};
            }
            double[] floorHit = null;
            if (ey < floorY) {
                double t = (floorY - oy) / (ey - oy);
                floorHit = new double[] {ox + (ex - ox) * t, floorY, oz + (ez - oz) * t};
            }
            double[] blockHit = aabbHit(ox, oy, oz, ex, ey, ez, -7, -6, 74, 75, 6, 7);
            if (floorHit == null) {
                return blockHit;
            }
            if (blockHit == null) {
                return floorHit;
            }
            double floorDistSq = sq(floorHit[0] - ox) + sq(floorHit[1] - oy) + sq(floorHit[2] - oz);
            double blockDistSq = sq(blockHit[0] - ox) + sq(blockHit[1] - oy) + sq(blockHit[2] - oz);
            return floorDistSq <= blockDistSq ? floorHit : blockHit;
        };
        IsometricCameraCollision.Occupancy occupancy =
                (x, y, z) -> y < floorY || inBox(x, y, z, -7, -6, 74, 75, 6, 7);

        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                floorAndDistantBlock, occupancy, 0, loweredAnchorY, 0, FORWARD_X, FORWARD_Y, FORWARD_Z, 16.0f);

        assertTrue(zoom < 16.0f, "the distant block should have clamped the full 16-block request");
        double finalX = 0 - FORWARD_X * zoom;
        double finalY = loweredAnchorY - FORWARD_Y * zoom;
        double finalZ = 0 - FORWARD_Z * zoom;
        boolean insideBlock = inBox(finalX, finalY, finalZ, -7, -6, 74, 75, 6, 7);
        assertFalse(insideBlock, "final camera position must not be inside the distant block: (" + finalX + ", " + finalY + ", " + finalZ + ")");
    }

    /**
     * Regression for the MINECRAFT-408 third re-review's first finding: a clear anchor sitting
     * only ~0.2 blocks under a real ceiling must clamp to that ~0.2 blocks, not be misidentified as
     * "embedded" (an earlier revision's heuristic -- a short ray-cast probe collapsing to near-zero
     * -- could not tell a clear point near a close obstruction apart from a point actually inside
     * one) and searched past, through the ceiling, for daylight on the far side.
     */
    @Test
    void clearAnchorNearACeilingClampsWithoutTunnelingThroughIt() {
        double ceilingY = 65.0;
        IsometricCameraCollision.RayCast ceilingOnly = (ox, oy, oz, ex, ey, ez) -> {
            if (ey >= ceilingY) {
                double t = (ceilingY - oy) / (ey - oy);
                return new double[] {ox + (ex - ox) * t, ceilingY, oz + (ez - oz) * t};
            }
            return null;
        };
        IsometricCameraCollision.Occupancy occupancy = (x, y, z) -> y >= ceilingY && y < ceilingY + 1.0;

        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                ceilingOnly, occupancy, 0, 64.8, 0, 0, -1, 0, 16.0f);

        assertTrue(zoom < 1.0f, "should clamp to the real ~0.2-block gap, not the full 16 blocks: zoom=" + zoom);
        double finalY = 64.8 - (-1) * zoom;
        // The jittered corner clamp itself can land a little past the exact boundary (the same
        // jitter-induced overshoot covered by clampsToAnObstructionCloserThanTheRequestedDistance
        // above) -- the regression this guards against is tunneling all the way through to the
        // far side of the ceiling, not that small overshoot.
        assertTrue(finalY <= ceilingY + 0.3, "must not tunnel through the ceiling to its far side: finalY=" + finalY);
    }

    /**
     * Regression for the MINECRAFT-408 third re-review's second finding: standing player feet
     * (0,64,0) with ground solid below Y=64 and a cliff face (solid for X<=-1, Y<80) covering the
     * entire height range the lowered anchor's path climbs through within the requested distance.
     * No point along that path is clear, so {@link
     * IsometricCameraCollision#resolveZoomAroundPossiblyBuriedAnchor} must report {@link
     * IsometricCameraCollision#NO_CLEAR_POINT_FOUND} rather than silently granting the full
     * requested distance (which the review showed lands inside the cliff).
     */
    @Test
    void reportsNoClearPointFoundWhenTheEntirePathIsBlocked() {
        double eyeY = 65.62;
        double loweredAnchorY = eyeY - 4.0;
        IsometricCameraCollision.Occupancy groundAndCliff = (x, y, z) -> y < 64.0 || (x <= -1.0 && y < 80.0);
        IsometricCameraCollision.RayCast neverCalled = (ox, oy, oz, ex, ey, ez) -> {
            throw new AssertionError("the ray-cast clamp must not run when no point is ever clear");
        };

        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                neverCalled, groundAndCliff, 0, loweredAnchorY, 0, FORWARD_X, FORWARD_Y, FORWARD_Z, 16.0f);

        assertEquals(IsometricCameraCollision.NO_CLEAR_POINT_FOUND, zoom, 0.0001f);
    }
}
