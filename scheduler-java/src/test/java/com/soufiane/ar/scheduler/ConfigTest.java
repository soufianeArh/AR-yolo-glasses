package com.soufiane.ar.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigTest {

    @Test
    void defaultsAndPerModelDeadlines() {
        Config c = Config.parse(new String[]{"--deadline-ms", "300", "--model-deadline", "plant=2500"});
        assertEquals(300, c.deadlineFor("default"));
        assertEquals(2500, c.deadlineFor("plant"));
        assertEquals(300, c.deadlineFor("anything-else"));
    }

    @Test
    // the plant model is way slower so it needs its own deadline
    void plantHasA3500msDeadlineByDefault() {
        Config c = Config.parse(new String[]{});
        assertEquals(300, c.deadlineFor("default"));
        assertEquals(3500, c.deadlineFor("plant"));
    }

    @Test
    void severalModelsAndRepeatedOption() {
        Config c = Config.parse(new String[]{"--model-deadline", "plant=2500, fire=800", "--model-deadline",
                "default=400"});
        assertEquals(2500, c.deadlineFor("plant"));
        assertEquals(800, c.deadlineFor("fire"));
        assertEquals(400, c.deadlineFor("default"));
    }

    @Test
    void badValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[]{"--model-deadline", "plant"}));
        assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[]{"--model-deadline", "plant=0"}));
        assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[]{"--model-deadline", "=100"}));
        assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[]{"--model-deadline"}));
    }
}
