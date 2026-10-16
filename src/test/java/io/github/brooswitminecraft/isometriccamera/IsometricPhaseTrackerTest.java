package io.github.brooswitminecraft.isometriccamera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.isometriccamera.IsometricPhaseTracker.Decision;
import io.github.brooswitminecraft.isometriccamera.IsometricPhaseTracker.VanillaPerspective;

class IsometricPhaseTrackerTest {
    @Test
    void firstTwoPressesCycleNormallyWithoutActivatingIsometric() {
        Decision afterFirstPress =
                IsometricPhaseTracker.update(false, VanillaPerspective.FIRST_PERSON, VanillaPerspective.THIRD_PERSON_BACK);
        assertFalse(afterFirstPress.isometricActive());
        assertEquals(VanillaPerspective.THIRD_PERSON_BACK, afterFirstPress.cameraType());

        Decision afterSecondPress = IsometricPhaseTracker.update(
                false, VanillaPerspective.THIRD_PERSON_BACK, VanillaPerspective.THIRD_PERSON_FRONT);
        assertFalse(afterSecondPress.isometricActive());
        assertEquals(VanillaPerspective.THIRD_PERSON_FRONT, afterSecondPress.cameraType());
    }

    @Test
    void thirdPressInterceptsVanillasWrapAndEntersIsometricInstead() {
        Decision afterThirdPress =
                IsometricPhaseTracker.update(false, VanillaPerspective.THIRD_PERSON_FRONT, VanillaPerspective.FIRST_PERSON);
        assertTrue(afterThirdPress.isometricActive());
        assertEquals(VanillaPerspective.THIRD_PERSON_BACK, afterThirdPress.cameraType());
    }

    @Test
    void quietTicksWhileIsometricStayIsometric() {
        Decision quietTick = IsometricPhaseTracker.update(
                true, VanillaPerspective.THIRD_PERSON_BACK, VanillaPerspective.THIRD_PERSON_BACK);
        assertTrue(quietTick.isometricActive());
        assertEquals(VanillaPerspective.THIRD_PERSON_BACK, quietTick.cameraType());
    }

    @Test
    void fourthPressExitsIsometricToFirstPerson() {
        Decision afterFourthPress = IsometricPhaseTracker.update(
                true, VanillaPerspective.THIRD_PERSON_BACK, VanillaPerspective.THIRD_PERSON_FRONT);
        assertFalse(afterFourthPress.isometricActive());
        assertEquals(VanillaPerspective.FIRST_PERSON, afterFourthPress.cameraType());
    }

    @Test
    void fullCycleReturnsToFirstPersonAfterFourPresses() {
        VanillaPerspective tracked = VanillaPerspective.FIRST_PERSON;
        boolean active = false;

        Decision d1 = IsometricPhaseTracker.update(active, tracked, VanillaPerspective.THIRD_PERSON_BACK);
        active = d1.isometricActive();
        tracked = d1.cameraType();

        Decision d2 = IsometricPhaseTracker.update(active, tracked, VanillaPerspective.THIRD_PERSON_FRONT);
        active = d2.isometricActive();
        tracked = d2.cameraType();

        Decision d3 = IsometricPhaseTracker.update(active, tracked, VanillaPerspective.FIRST_PERSON);
        active = d3.isometricActive();
        tracked = d3.cameraType();
        assertTrue(active, "3rd press should have entered isometric");

        Decision d4 = IsometricPhaseTracker.update(active, tracked, VanillaPerspective.THIRD_PERSON_FRONT);
        assertFalse(d4.isometricActive());
        assertEquals(VanillaPerspective.FIRST_PERSON, d4.cameraType());
    }
}
