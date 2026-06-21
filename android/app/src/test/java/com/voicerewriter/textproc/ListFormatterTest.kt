package com.voicerewriter.textproc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins ListFormatter's conservative triggering: explicit marks and consecutive runs only. */
class ListFormatterTest {

    @Test fun digitListWithLeadIn() {
        assertEquals(
            "going to the store for\n1. apples\n2. bananas\n3. oranges",
            ListFormatter.format("going to the store for 1. apples 2. bananas 3. oranges"),
        )
    }

    @Test fun newLineMark() {
        assertEquals("hello\nworld", ListFormatter.format("hello new line world"))
    }

    @Test fun newParagraphMark() {
        assertEquals("intro\n\nbody", ListFormatter.format("intro new paragraph body"))
    }

    @Test fun singleMarkerIsNotAList() {
        assertEquals("step 1. do the thing", ListFormatter.format("step 1. do the thing"))
    }

    @Test fun nonConsecutiveMarkersAreNotAList() {
        assertEquals("a 1. x 3. y", ListFormatter.format("a 1. x 3. y"))
    }

    @Test fun bareNumberIsNotAList() {
        assertEquals("I have 1 apple", ListFormatter.format("I have 1 apple"))
    }

    @Test fun twoOrdinalsAreNotEnough() {
        // Ordinal words are ambiguous, so a list needs at least three.
        assertEquals("first, eat, second, sleep", ListFormatter.format("first, eat, second, sleep"))
    }

    @Test fun threeOrdinalsFormAList() {
        assertEquals(
            "1. eat\n2. sleep\n3. code",
            ListFormatter.format("first, eat, second, sleep, third, code"),
        )
    }
}
