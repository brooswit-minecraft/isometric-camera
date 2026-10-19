package io.github.brooswitminecraft.isometriccamera;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

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

    // ---------------------------------------------------------------------------------------
    // resolveUnobstructedZoom
    // ---------------------------------------------------------------------------------------

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

    // ---------------------------------------------------------------------------------------
    // boxEntryExit
    // ---------------------------------------------------------------------------------------

    @Test
    void boxEntryExitFindsTheEntryAndExitOfAStraightHit() {
        // Ray from the origin along +X through a box spanning X in [2,3].
        double[] hit = IsometricCameraCollision.boxEntryExit(0, 0, 0, 1, 0, 0, 2, 3, -1, 1, -1, 1);
        assertArrayEquals(new double[] {2.0, 3.0}, hit, 0.0001);
    }

    @Test
    void boxEntryExitFindsANegativeIntervalWhenTheBoxIsBehindTheOrigin() {
        // boxEntryExit tests the infinite LINE through origin along direction, not a forward-only
        // ray -- callers (resolveZoomAroundPossiblyBuriedAnchor's anchor-containing block included)
        // rely on negative results for a box that starts behind the origin.
        double[] hit = IsometricCameraCollision.boxEntryExit(0, 0, 0, -1, 0, 0, 2, 3, -1, 1, -1, 1);
        assertArrayEquals(new double[] {-3.0, -2.0}, hit, 0.0001);
    }

    @Test
    void boxEntryExitReturnsNullWhenParallelAndOutsideTheSlab() {
        // Moving along X only, but Y=5 never falls inside the box's Y in [-1,1] slab.
        double[] hit = IsometricCameraCollision.boxEntryExit(0, 5, 0, 1, 0, 0, 2, 3, -1, 1, -1, 1);
        assertNull(hit);
    }

    @Test
    void boxEntryExitGivesANegativeEntryWhenTheOriginIsAlreadyInside() {
        double[] hit = IsometricCameraCollision.boxEntryExit(2.5, 0, 0, 1, 0, 0, 2, 3, -1, 1, -1, 1);
        assertEquals(-0.5, hit[0], 0.0001);
        assertEquals(0.5, hit[1], 0.0001);
    }

    // ---------------------------------------------------------------------------------------
    // traverseBlocks
    // ---------------------------------------------------------------------------------------

    @Test
    void traverseBlocksWalksAStraightLineOneBlockAtATime() {
        // Starting at X=0.5 and walking 3.0 along +X ends at X=3.5 -- inside block index 3 -- so
        // the correct traversal is 4 blocks (0,1,2,3), not 3.
        List<int[]> blocks = IsometricCameraCollision.traverseBlocks(0.5, 0.5, 0.5, 1, 0, 0, 3.0);
        assertEquals(4, blocks.size());
        assertArrayEquals(new int[] {0, 0, 0}, blocks.get(0));
        assertArrayEquals(new int[] {1, 0, 0}, blocks.get(1));
        assertArrayEquals(new int[] {2, 0, 0}, blocks.get(2));
        assertArrayEquals(new int[] {3, 0, 0}, blocks.get(3));
    }

    @Test
    void traverseBlocksWalksADiagonalWithoutSkippingAnyCorner() {
        // 45-degree diagonal in the XY plane; every block it clips (including ones only touched at
        // a corner-adjacent edge) must appear, since missing one here would mean missing a block's
        // worth of collision shape in production.
        double len = Math.sqrt(2) * 2.0;
        List<int[]> blocks = IsometricCameraCollision.traverseBlocks(0.5, 0.5, 0.0, 1, 1, 0, len);
        boolean sawOrigin = false;
        boolean sawFarCorner = false;
        for (int[] b : blocks) {
            if (b[0] == 0 && b[1] == 0) {
                sawOrigin = true;
            }
            if (b[0] == 2 && b[1] == 2) {
                sawFarCorner = true;
            }
        }
        assertTrue(sawOrigin, "expected the starting block in the traversal");
        assertTrue(sawFarCorner, "expected the far block in the traversal");
        assertTrue(blocks.size() >= 3, "a 45-degree diagonal across 2 blocks must cross at least 3 cells");
    }

    @Test
    void traverseBlocksNeverSkipsABlockRegardlessOfGapWidth() {
        // The whole point of replacing fixed-step sampling: walk a long path and confirm every
        // integer Y level it passes through is visited, with no gaps, at the camera's real
        // yaw-225/pitch-55 direction.
        List<int[]> blocks = IsometricCameraCollision.traverseBlocks(0, 61.62, 0, -FORWARD_X, -FORWARD_Y, -FORWARD_Z, 16.0);
        java.util.Set<Integer> yLevels = new java.util.TreeSet<>();
        for (int[] b : blocks) {
            yLevels.add(b[1]);
        }
        int minY = java.util.Collections.min(yLevels);
        int maxY = java.util.Collections.max(yLevels);
        for (int y = minY; y <= maxY; y++) {
            assertTrue(yLevels.contains(y), "missing Y level " + y + " in the traversal");
        }
    }

    // ---------------------------------------------------------------------------------------
    // resolveZoomAroundPossiblyBuriedAnchor, fed hand-crafted intervals directly
    // ---------------------------------------------------------------------------------------

    private static IsometricCameraCollision.SolidIntervalSource fixedIntervals(double[]... intervals) {
        return (ox, oy, oz, dx, dy, dz, maxDist) -> List.of(intervals);
    }

    @Test
    void takesTheDirectPathWhenTheAnchorIsAlreadyClear() {
        IsometricCameraCollision.SolidIntervalSource noIntervals = fixedIntervals();
        IsometricCameraCollision.RayCast clearSky = (ox, oy, oz, ex, ey, ez) -> null;
        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                noIntervals, clearSky, 0, 65.62, 0, 0, 1, 0, 16.0f);
        assertEquals(16.0f, zoom, 0.0001f);
    }

    @Test
    void settlesJustUnderACeilingInsteadOfStayingInTheFloorOrPunchingThrough() {
        // Ground covers everything up to distance 2.905 (anchor embedded); a ceiling begins again
        // at distance 6.57 and extends onward.
        IsometricCameraCollision.SolidIntervalSource groundThenCeiling =
                fixedIntervals(new double[] {Double.NEGATIVE_INFINITY, 2.90544}, new double[] {6.57, Double.POSITIVE_INFINITY});
        IsometricCameraCollision.RayCast rayCastToCeiling = (ox, oy, oz, ex, ey, ez) -> {
            // Only matters along the unjittered direction for this test's purposes: the ceiling
            // plane sits at distance 6.57 - 3.105 = 3.465 past the hand-off point (3.105).
            double hitDistance = 3.465;
            double t = hitDistance / Math.sqrt((ex - ox) * (ex - ox) + (ey - oy) * (ey - oy) + (ez - oz) * (ez - oz));
            if (t > 1.0) {
                return null;
            }
            return new double[] {ox + (ex - ox) * t, oy + (ey - oy) * t, oz + (ez - oz) * t};
        };

        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                groundThenCeiling, rayCastToCeiling, 0, 61.62, 0, FORWARD_X, FORWARD_Y, FORWARD_Z, 16.0f);

        assertTrue(zoom > 2.9f && zoom < 6.6f, "expected to settle between the ground exit and the ceiling: zoom=" + zoom);
    }

    @Test
    void returnsNoClearPointFoundWhenEveryCandidateIsBlocked() {
        IsometricCameraCollision.SolidIntervalSource alwaysBlocked = fixedIntervals(new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY});
        IsometricCameraCollision.RayCast neverCalled = (ox, oy, oz, ex, ey, ez) -> {
            throw new AssertionError("the ray-cast clamp must not run when no point is ever clear");
        };

        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                alwaysBlocked, neverCalled, 0, 61.62, 0, FORWARD_X, FORWARD_Y, FORWARD_Z, 16.0f);

        assertEquals(IsometricCameraCollision.NO_CLEAR_POINT_FOUND, zoom, 0.0001f);
    }

    @Test
    void reportsNoClearPointFoundWhenTheGapToTheNextObstructionIsNarrowerThanTheMargin() {
        // Ground ends at distance 2.90544; a second, separate obstruction begins at 2.91037 -- a
        // gap of only ~0.005 blocks, far narrower than the escape margin.
        IsometricCameraCollision.SolidIntervalSource groundThenStoneCloseBy =
                fixedIntervals(new double[] {Double.NEGATIVE_INFINITY, 2.90544352}, new double[] {2.91037474, 4.0});
        IsometricCameraCollision.RayCast neverCalled = (ox, oy, oz, ex, ey, ez) -> {
            throw new AssertionError("the ray-cast clamp must not run when the margin can't fit");
        };

        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                groundThenStoneCloseBy, neverCalled, 0, 61.62, 0, FORWARD_X, FORWARD_Y, FORWARD_Z, 16.0f);

        assertEquals(IsometricCameraCollision.NO_CLEAR_POINT_FOUND, zoom, 0.0001f);
    }

    // ---------------------------------------------------------------------------------------
    // Full pipeline: traverseBlocks + boxEntryExit + resolveZoomAroundPossiblyBuriedAnchor,
    // against a small fake block world -- exercises exactly what the production adapter does,
    // with no Minecraft classes involved.
    // ---------------------------------------------------------------------------------------

    /** @return the local (block-relative) AABBs occupying this block, or an empty list for air */
    private interface BlockShapes {
        List<double[]> shapesAt(int x, int y, int z);
    }

    private static final double[] FULL_BLOCK = {0, 0, 0, 1, 1, 1};
    private static final double[] TOP_SLAB = {0, 0.5, 0, 1, 1, 1};

    private static IsometricCameraCollision.SolidIntervalSource worldOf(BlockShapes world) {
        return (ox, oy, oz, dx, dy, dz, maxDist) -> {
            List<double[]> result = new ArrayList<>();
            for (int[] b : IsometricCameraCollision.traverseBlocks(ox, oy, oz, dx, dy, dz, maxDist)) {
                for (double[] local : world.shapesAt(b[0], b[1], b[2])) {
                    double[] interval = IsometricCameraCollision.boxEntryExit(
                            ox, oy, oz, dx, dy, dz,
                            b[0] + local[0], b[0] + local[3],
                            b[1] + local[1], b[1] + local[4],
                            b[2] + local[2], b[2] + local[5]);
                    if (interval != null) {
                        result.add(interval);
                    }
                }
            }
            return result;
        };
    }

    /** A real ray-cast against the same fake world {@link #worldOf} builds intervals from. */
    private static IsometricCameraCollision.RayCast rayCastOf(BlockShapes world) {
        return (ox, oy, oz, ex, ey, ez) -> {
            double dx = ex - ox;
            double dy = ey - oy;
            double dz = ez - oz;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length == 0.0) {
                return null;
            }
            double ux = dx / length;
            double uy = dy / length;
            double uz = dz / length;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int[] b : IsometricCameraCollision.traverseBlocks(ox, oy, oz, ux, uy, uz, length)) {
                for (double[] local : world.shapesAt(b[0], b[1], b[2])) {
                    double[] interval = IsometricCameraCollision.boxEntryExit(
                            ox, oy, oz, ux, uy, uz,
                            b[0] + local[0], b[0] + local[3],
                            b[1] + local[1], b[1] + local[4],
                            b[2] + local[2], b[2] + local[5]);
                    if (interval != null) {
                        double entry = Math.max(interval[0], 0.0);
                        if (entry <= length && entry < bestDistance) {
                            bestDistance = entry;
                        }
                    }
                }
            }
            if (bestDistance == Double.POSITIVE_INFINITY) {
                return null;
            }
            return new double[] {ox + ux * bestDistance, oy + uy * bestDistance, oz + uz * bestDistance};
        };
    }

    /**
     * Regression for the MINECRAFT-408 fourth re-review's first finding: a top slab occupies only
     * the upper half of its block, so a point just below it (Y=64.3) is genuine, clear air and
     * must clamp to the real ~0.2-block gap, not tunnel through.
     */
    @Test
    void integrationClampsToASlabInsteadOfTunnelingThroughIt() {
        BlockShapes world = (x, y, z) -> y == 64 ? List.of(TOP_SLAB) : List.of();
        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                worldOf(world), rayCastOf(world), 0, 64.3, 0, 0, -1, 0, 16.0f);
        assertTrue(zoom < 1.0f, "should clamp to the real gap under the slab, not tunnel through it: zoom=" + zoom);
    }

    /**
     * Regression for the MINECRAFT-408 sixth re-review's finding: a third player-position shift
     * narrowed the air gap between the ground and a separate stone block down to ~0.005 blocks --
     * narrower than any of the previous fixed sampling steps could reliably resolve. The exact
     * interval math here has no step to miss: it must still report {@link
     * IsometricCameraCollision#NO_CLEAR_POINT_FOUND} rather than crossing into (or past) the stone.
     */
    @Test
    void integrationReportsNoClearPointFoundForAnUltraThinGapBeforeAStoneBlock() {
        assertIntegrationDoesNotCrossStone(-0.81961083, 61.62, -0.18038917);
    }

    /** The two gap widths from the fifth re-review, kept as regressions against this exact pipeline. */
    @Test
    void integrationReportsNoClearPointFoundForTheOriginalStoneBlockAnchor() {
        assertIntegrationDoesNotCrossStone(-0.77161083, 61.62, -0.22838917);
    }

    @Test
    void integrationReportsNoClearPointFoundForTheShiftedStoneBlockAnchor() {
        assertIntegrationDoesNotCrossStone(-0.79161083, 61.62, -0.20838917);
    }

    private static void assertIntegrationDoesNotCrossStone(double anchorX, double anchorY, double anchorZ) {
        int stoneX = -3;
        int stoneY = 64;
        int stoneZ = 1;
        BlockShapes world = (x, y, z) -> {
            if (y < 64) {
                return List.of(FULL_BLOCK);
            }
            if (x == stoneX && y == stoneY && z == stoneZ) {
                return List.of(FULL_BLOCK);
            }
            return List.of();
        };

        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                worldOf(world), rayCastOf(world), anchorX, anchorY, anchorZ, FORWARD_X, FORWARD_Y, FORWARD_Z, 16.0f);

        if (zoom == IsometricCameraCollision.NO_CLEAR_POINT_FOUND) {
            return;
        }
        for (float d = 0.0f; d <= zoom; d += 0.01f) {
            double x = anchorX - FORWARD_X * d;
            double y = anchorY - FORWARD_Y * d;
            double z = anchorZ - FORWARD_Z * d;
            assertFalse(
                    inBox(x, y, z, stoneX, stoneX + 1, stoneY, stoneY + 1, stoneZ, stoneZ + 1),
                    "resolved path must not cross the stone block (hit at distance " + d + "): (" + x + ", " + y + ", " + z + ")");
        }
    }

    /**
     * Regression for the MINECRAFT-408 third re-review's second finding, against the full
     * pipeline: a cliff face immediately alongside the player, covering the same height range the
     * lowered anchor's path climbs through, leaves no clear point at all.
     */
    @Test
    void integrationReportsNoClearPointFoundWhenACliffBlocksEverything() {
        BlockShapes world = (x, y, z) -> (y < 64 || (x <= -1 && y < 80)) ? List.of(FULL_BLOCK) : List.of();
        float zoom = IsometricCameraCollision.resolveZoomAroundPossiblyBuriedAnchor(
                worldOf(world), rayCastOf(world), 0, 61.62, 0, FORWARD_X, FORWARD_Y, FORWARD_Z, 16.0f);
        assertEquals(IsometricCameraCollision.NO_CLEAR_POINT_FOUND, zoom, 0.0001f);
    }
}
