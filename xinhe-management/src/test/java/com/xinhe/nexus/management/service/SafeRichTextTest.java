package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SafeRichTextTest {
    @Test
    void dispatchRemarkKeepsFormattingAndRemovesExecutableContent() {
        String safe = SafeRichText.sanitize(
            "<p onclick=\"run()\">易碎品<strong>轻放</strong></p><script>run()</script>"
        );

        assertEquals("<p>易碎品<strong>轻放</strong></p>", safe);
        assertEquals("易碎品轻放", SafeRichText.plainText(safe));
    }
}
