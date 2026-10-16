package io.github.brooswitminecraft.isometriccamera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.isometriccamera.PerspectiveCycle.Phase;

class PerspectiveCycleTest {
    @Test
    void cyclesThroughAllFourPhasesAndWrapsToFirstPerson() {
        Phase phase = Phase.FIRST_PERSON;
        phase = PerspectiveCycle.next(phase);
        assertEquals(Phase.THIRD_PERSON_BACK, phase);
        phase = PerspectiveCycle.next(phase);
        assertEquals(Phase.THIRD_PERSON_FRONT, phase);
        phase = PerspectiveCycle.next(phase);
        assertEquals(Phase.ISOMETRIC, phase);
        phase = PerspectiveCycle.next(phase);
        assertEquals(Phase.FIRST_PERSON, phase);
    }

    @Test
    void everyPhaseHasExactlyOneSuccessor() {
        for (Phase phase : Phase.values()) {
            assertEquals(1, java.util.Set.of(PerspectiveCycle.next(phase)).size());
        }
    }
}
