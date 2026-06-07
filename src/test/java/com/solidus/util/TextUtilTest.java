package com.solidus.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TextUtil} — only testing pure Java methods
 * that do not depend on Minecraft's Component/ChatFormatting classes.
 *
 * Methods tested:
 * - formatCurrency(double)
 * - sanitizeLegacyFormatting(String)
 *
 * Methods NOT tested (require Minecraft runtime):
 * - styled(), styledBold(), styledItalic(), styledBoldItalic()
 * - plain(), error(), success(), warning(), currency()
 * - shopTitle(), sectionHeader(), loreLine()
 * - buyPriceLore(), sellPriceLore()
 * - getMaterialName()
 */
@DisplayName("TextUtil (pure Java methods)")
class TextUtilTest {

    // ── formatCurrency ─────────────────────────────────────

    @Nested
    @DisplayName("formatCurrency()")
    class FormatCurrencyTest {

        @Test
        @DisplayName("formats whole numbers without decimals")
        void formatsWholeNumbers() {
            assertEquals("1,000 S$", TextUtil.formatCurrency(1000.0));
            assertEquals("500 S$", TextUtil.formatCurrency(500.0));
            assertEquals("0 S$", TextUtil.formatCurrency(0.0));
        }

        @Test
        @DisplayName("formats fractional amounts with one decimal")
        void formatsFractionalAmounts() {
            assertEquals("1.5 S$", TextUtil.formatCurrency(1.5));
            assertEquals("99.9 S$", TextUtil.formatCurrency(99.9));
        }

        @Test
        @DisplayName("includes currency symbol S$")
        void includesCurrencySymbol() {
            String result = TextUtil.formatCurrency(100.0);
            assertTrue(result.endsWith(" S$"));
        }

        @Test
        @DisplayName("formats large numbers with comma separators")
        void formatsLargeNumbers() {
            assertEquals("1,000,000 S$", TextUtil.formatCurrency(1_000_000.0));
            assertEquals("10,000,000 S$", TextUtil.formatCurrency(10_000_000.0));
        }

        @Test
        @DisplayName("formats starting balance correctly")
        void formatsStartingBalance() {
            assertEquals("500 S$", TextUtil.formatCurrency(CurrencyUtil.DEFAULT_STARTING_BALANCE));
        }

        @Test
        @DisplayName("handles very small amounts")
        void handlesSmallAmounts() {
            assertEquals("0.01 S$", TextUtil.formatCurrency(0.01));
        }

        @Test
        @DisplayName("difference from CurrencyUtil.format: uses 1 decimal instead of 2")
        void formatCurrencyVsCurrencyUtilFormat() {
            // TextUtil.formatCurrency uses %,.1f for non-whole numbers
            // CurrencyUtil.format uses %,.2f for non-whole numbers
            String textUtilResult = TextUtil.formatCurrency(1.55);
            String currencyUtilResult = CurrencyUtil.format(1.55);
            // TextUtil: "1.6 S$" (rounded to 1 decimal)
            // CurrencyUtil: "1.55 S$" (2 decimals)
            assertNotEquals(textUtilResult, currencyUtilResult);
        }
    }

    // ── sanitizeLegacyFormatting ───────────────────────────

    @Nested
    @DisplayName("sanitizeLegacyFormatting()")
    class SanitizeLegacyFormattingTest {

        @Test
        @DisplayName("removes section sign color codes")
        void removesColorCodes() {
            assertEquals("Hello", TextUtil.sanitizeLegacyFormatting("\u00A7aHello"));
            assertEquals("World", TextUtil.sanitizeLegacyFormatting("\u00A74World"));
        }

        @Test
        @DisplayName("removes multiple formatting codes")
        void removesMultipleCodes() {
            assertEquals("Bold", TextUtil.sanitizeLegacyFormatting("\u00A7l\u00A7cBold"));
        }

        @Test
        @DisplayName("removes formatting codes (k-o, r)")
        void removesFormatCodes() {
            assertEquals("text", TextUtil.sanitizeLegacyFormatting("\u00A7ktext"));
            assertEquals("reset", TextUtil.sanitizeLegacyFormatting("\u00A7rreset"));
            assertEquals("italic", TextUtil.sanitizeLegacyFormatting("\u00A7oitalic"));
        }

        @Test
        @DisplayName("returns empty string for null input")
        void handlesNull() {
            assertEquals("", TextUtil.sanitizeLegacyFormatting(null));
        }

        @Test
        @DisplayName("returns empty string for empty input")
        void handlesEmpty() {
            assertEquals("", TextUtil.sanitizeLegacyFormatting(""));
        }

        @Test
        @DisplayName("preserves text without formatting codes")
        void preservesCleanText() {
            assertEquals("Hello World", TextUtil.sanitizeLegacyFormatting("Hello World"));
        }

        @Test
        @DisplayName("handles uppercase formatting codes")
        void handlesUppercaseCodes() {
            assertEquals("Test", TextUtil.sanitizeLegacyFormatting("\u00A7ATest"));
            assertEquals("Bold", TextUtil.sanitizeLegacyFormatting("\u00A7LBold"));
        }

        @Test
        @DisplayName("handles mixed formatting and text")
        void handlesMixedFormattingAndText() {
            String input = "\u00A76Gold \u00A7lBold \u00A7rReset";
            assertEquals("Gold Bold Reset", TextUtil.sanitizeLegacyFormatting(input));
        }

        @Test
        @DisplayName("does not remove standalone section signs without valid codes")
        void preservesStandaloneSectionSign() {
            // \u00A7X is not a valid code (X is not 0-9, a-f, k-o, r)
            // But the regex matches \u00A7[0-9a-fk-orA-FK-OR]
            assertEquals("\u00A7ztext", TextUtil.sanitizeLegacyFormatting("\u00A7ztext"));
        }
    }
}
