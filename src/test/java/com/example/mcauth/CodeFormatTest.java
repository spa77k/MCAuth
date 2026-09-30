package com.example.mcauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeFormatTest {
    @Test
    void normalizeRemovesSpacesAndHyphens() {
        assertEquals("123456", CodeFormat.normalize(" 123 456 "));
        assertEquals("123456", CodeFormat.normalize("123-456"));
    }

    @Test
    void normalizeConvertsFullWidthDigits() {
        assertEquals("1234", CodeFormat.normalize("１２３４"));
        assertEquals("123456", CodeFormat.normalize("１２３　４５６"));
    }

    @Test
    void displayGroupsEvenLengths() {
        assertEquals("123 456", CodeFormat.display("123456", 3));
        assertEquals("1234 5678", CodeFormat.display("12345678", 4));
    }

    @Test
    void displayLeavesUnevenOrDisabledCodesAlone() {
        assertEquals("1234", CodeFormat.display("1234", 3));
        assertEquals("12345", CodeFormat.display("12345", 3));
        assertEquals("123456", CodeFormat.display("123456", 0));
        assertEquals("123", CodeFormat.display("123", 3));
    }
}
