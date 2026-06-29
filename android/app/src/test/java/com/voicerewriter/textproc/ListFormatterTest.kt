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

    @Test fun ordinalsMidSentenceWithoutPunctuationFormAList() {
        // Natural dictation: the lead-in isn't punctuated and the ordinals follow plain words.
        assertEquals(
            "We need to do three things\n1. the API\n2. the docs\n3. the tests",
            ListFormatter.format("We need to do three things first the API second the docs third the tests"),
        )
    }

    @Test fun cardinalsFormAList() {
        assertEquals(
            "The plan is\n1. set up the repo\n2. write the tests\n3. ship it",
            ListFormatter.format("The plan is one set up the repo two write the tests three ship it"),
        )
    }

    @Test fun twoCardinalsAreNotEnough() {
        assertEquals("one apple two oranges", ListFormatter.format("one apple two oranges"))
    }

    @Test fun cardinalsWithEmptyItemsAreNotAList() {
        // "one, two, three of those" has nothing between the markers — must stay prose.
        assertEquals("I'll take one, two, three of those", ListFormatter.format("I'll take one, two, three of those"))
    }

    @Test fun nonConsecutiveCardinalsAreNotAList() {
        // "one to three weeks" → markers 1 then 3, not consecutive → no list.
        assertEquals("one to three weeks of work", ListFormatter.format("one to three weeks of work"))
    }

    @Test fun bareDigitsFormAList() {
        // STT (e.g. Parakeet) renders spoken "one two three" as digits with no '.' separator.
        assertEquals(
            "the stages are\n1. prelinguistic\n2. crying\n3. cooing\n4. babbling\n5. gestures",
            ListFormatter.format("the stages are 1 prelinguistic, 2 crying, 3 cooing, 4 babbling, 5 gestures"),
        )
    }

    @Test fun twoBareDigitsAreNotEnough() {
        assertEquals("I have 1 apple 2 oranges", ListFormatter.format("I have 1 apple 2 oranges"))
    }

    @Test fun nonConsecutiveBareDigitsAreNotAList() {
        assertEquals("chapter 1 then chapter 5 later", ListFormatter.format("chapter 1 then chapter 5 later"))
    }
}
