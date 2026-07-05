package io.github.shymoy.termagent.tui.tea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgramTest {

    @Test
    void renderedLineBreaksCountsExplicitNewlines() {
        assertEquals(2, Program.renderedLineBreaks("first\nsecond\nthird", 80));
    }

    @Test
    void renderedLineBreaksCountsTerminalWrapping() {
        assertEquals(0, Program.renderedLineBreaks("12345", 5));
        assertEquals(1, Program.renderedLineBreaks("123456", 5));
        assertEquals(2, Program.renderedLineBreaks("123456\nnext", 5));
    }

    @Test
    void renderedLineBreaksUsesDisplayWidthForAnsiAndChineseText() {
        assertEquals(1, Program.renderedLineBreaks("\033[31m123456\033[0m", 5));
        assertEquals(1, Program.renderedLineBreaks("你好啊", 5));
    }
}
