package com.ovigia.app.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A barra precisa subir de verdade a cada nível — senão arrastar a bolinha é enfeite. */
public class HapticStrengthTest {

    @Test
    public void everyLevelIsStrongerThanTheOneBefore() {
        for (int level = HapticStrength.MIN_LEVEL + 1; level <= HapticStrength.MAX_LEVEL; level++) {
            HapticStrength weaker = HapticStrength.of(level - 1);
            HapticStrength stronger = HapticStrength.of(level);
            assertTrue("nível " + level + " bate mais forte", stronger.amplitude > weaker.amplitude);
            assertTrue("nível " + level + " dura pelo menos o mesmo", stronger.durationMs >= weaker.durationMs);
            assertTrue("nível " + level + " sem amplitude dura pelo menos o mesmo",
                    stronger.flatDurationMs >= weaker.flatDurationMs);
        }
        assertTrue("do mais fraco ao mais forte a duração cresce",
                HapticStrength.of(HapticStrength.MAX_LEVEL).durationMs > HapticStrength.of(HapticStrength.MIN_LEVEL).durationMs);
    }

    @Test
    public void amplitudesStayInsideTheApiRange_andTheTopLevelHitsTheCeiling() {
        for (int level = HapticStrength.MIN_LEVEL; level <= HapticStrength.MAX_LEVEL; level++) {
            int amplitude = HapticStrength.of(level).amplitude;
            assertTrue("nível " + level + " abaixo de 1", amplitude >= 1);
            assertTrue("nível " + level + " acima de 255", amplitude <= 255);
        }
        assertEquals("o último nível usa a força máxima do motor", 255,
                HapticStrength.of(HapticStrength.MAX_LEVEL).amplitude);
    }

    @Test
    public void hasMoreThanThreeLevels_andTheDefaultIsOneOfThem() {
        assertTrue(HapticStrength.MAX_LEVEL - HapticStrength.MIN_LEVEL + 1 > 3);
        assertEquals(HapticStrength.DEFAULT_LEVEL, HapticStrength.of(HapticStrength.DEFAULT_LEVEL).level);
    }

    @Test
    public void levelsOutsideTheBar_snapToTheNearestEnd() {
        assertSame(HapticStrength.of(HapticStrength.MIN_LEVEL), HapticStrength.of(-3));
        assertSame(HapticStrength.of(HapticStrength.MAX_LEVEL), HapticStrength.of(99));
    }
}
