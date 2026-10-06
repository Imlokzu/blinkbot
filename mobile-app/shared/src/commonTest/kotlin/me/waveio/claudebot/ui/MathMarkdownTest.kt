package me.waveio.claudebot.ui

import kotlin.test.*

class MathMarkdownTest {
    @Test fun recognizesInlineAndDisplayDelimitersWithExactSource() {
        val source = "Inline ${'$'}x^2${'$'} and \\(y_1\\).\n\n${'$'}${'$'}\\frac{1}{2}${'$'}${'$'}\n\\[\\sqrt{9}\\]"
        val result = mathMarkdown(source)
        assertEquals(listOf("x^2", "y_1", "\\frac{1}{2}", "\\sqrt{9}"), result.formulas.values.map { it.latex })
        assertEquals(listOf(false, false, true, true), result.formulas.values.map { it.display })
        result.formulas.values.forEach { assertTrue(it.source in source); assertTrue(it.marker in result.markdown) }
    }

    @Test fun codeSpansFencesEscapesUrlsAndCurrencyStayLiteral() {
        for (source in listOf(
            "`${'$'}x${'$'}`", "```latex\n${'$'}${'$'}x${'$'}${'$'}\n```", "~~~\n\\(x\\)\n~~~",
            "Pay ${'$'}5 or ${'$'}10.", "Escaped \\${'$'}x\\${'$'}", "[file](https://example.com/${'$'}x${'$'})",
            "<https://example.com/${'$'}x${'$'}>", "https://example.com/${'$'}x${'$'}",
            "```\nAn unfinished code block ${'$'}x${'$'}"
        )) {
            val result = mathMarkdown(source)
            val expected = if (source.startsWith("Pay ")) source.replace("${'$'}", "\\${'$'}") else source
            assertEquals(expected, result.markdown, source)
            assertTrue(result.formulas.isEmpty(), source)
        }
    }

    @Test fun streamsIncompleteFormulasAsReadableSourceUntilClosed() {
        val incomplete = "Result: ${'$'}${'$'}\\frac{1}{2}"
        assertEquals(incomplete.replace("${'$'}", "\\${'$'}"), mathMarkdown(incomplete).markdown)
        val final = mathMarkdown(incomplete + "${'$'}${'$'}")
        assertEquals("\\frac{1}{2}", final.formulas.values.single().latex)
    }

    @Test fun surroundingMarkdownAndTablesRetainTheirStructure() {
        val source = "**Answer**: ${'$'}x${'$'} with [a source](https://example.org).\n\n| A | B |\n|---|---|\n| ${'$'}y${'$'} | 2 |"
        val result = mathMarkdown(source)
        assertTrue(result.markdown.startsWith("**Answer**: "))
        assertTrue("[a source](https://example.org)" in result.markdown)
        assertTrue("| A | B |\n|---|---|" in result.markdown)
        assertEquals(2, result.formulas.size)
    }

    @Test fun unsupportedOrExcessiveFormulasStayVisibleInsteadOfCrashing() {
        for (formula in listOf("\\def\\x{a}\\x", "{".repeat(40) + "x" + "}".repeat(40), "x".repeat(4097), "\\frac{1}{")) {
            val source = "${'$'}${'$'}$formula${'$'}${'$'}"
            assertEquals(source.replace("${'$'}", "\\${'$'}"), mathMarkdown(source).markdown)
            assertTrue(mathMarkdown(source).formulas.isEmpty())
        }
    }

    @Test fun currencyBeforeCodeAndIncompleteFormulaDoesNotStealDelimiters() {
        val source = "Price ${'$'}5 or ${'$'}10.\n\n`${'$'}x^2${'$'}`\n\n${'$'}${'$'}\\frac{1}{2}"
        assertEquals("Price \\${'$'}5 or \\${'$'}10.\n\n`${'$'}x^2${'$'}`\n\n\\${'$'}\\${'$'}\\frac{1}{2}", mathMarkdown(source).markdown)
        assertTrue(mathMarkdown(source).formulas.isEmpty())
        val complete = mathMarkdown(source + "${'$'}${'$'}")
        assertEquals(1, complete.formulas.size)
        assertEquals("\\frac{1}{2}", complete.formulas.values.single().latex)
    }

    @Test fun unmatchedInlineBacktickDoesNotHideALaterDisplayFormula() {
        val result = mathMarkdown("An unmatched ` character.\n\n${'$'}${'$'}x^2${'$'}${'$'}")
        assertEquals(1, result.formulas.size)
    }

    @Test fun currencyBeforeAMathExpressionDoesNotBecomePartOfTheFormula() {
        val result = mathMarkdown("Price ${'$'}5 or ${'$'}10; solve ${'$'}x^2${'$'}.")
        assertEquals(listOf("x^2"), result.formulas.values.map { it.latex })
        assertTrue(result.markdown.startsWith("Price \\${'$'}5 or \\${'$'}10; solve "))
        assertEquals("    ${'$'}x^2${'$'}", mathMarkdown("    ${'$'}x^2${'$'}").markdown)
        assertTrue(mathMarkdown("    ${'$'}x^2${'$'}").formulas.isEmpty())
    }

    @Test fun existingPrivateUseTextCannotImpersonateAFormulaMarker() {
        val source = "\uE000abcd:0\uE001 and ${'$'}x${'$'}"
        assertEquals(source.replace("${'$'}", "\\${'$'}"), mathMarkdown(source).markdown)
        assertTrue(mathMarkdown(source).formulas.isEmpty())
    }
}
