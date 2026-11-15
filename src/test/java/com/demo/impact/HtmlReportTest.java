package com.demo.impact;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class HtmlReportTest {
    @Test void escapesSnapshotTextAndKeepsTreeStructure() {
        String hostile = "</summary><script>alert('x')</script>|\\*";
        String html = HtmlReport.render("# Report\n\n## API\n\n| Value |\n|---|\n| "
                + ImpactQuery.escape(hostile) + " |\n\n## Cây lời gọi ngược\n\n"
                + "- p.A#root()\n    - p.B#caller() — **Có thể**\n"
                + "        - p.A#root() — **Gọi vòng**\n    - p.C#other()\n"
                + "\n## Diagnostics và giới hạn\n\n" + ImpactQuery.escape(hostile));
        assertFalse(html.contains("<script>alert"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("<strong>Có thể</strong>"));
        assertTrue(html.contains("<strong>Gọi vòng</strong>"));
        assertEquals(4, html.split("<details open>", -1).length - 1);
        assertEquals(4, html.split("</details>", -1).length - 1);
        assertTrue(html.contains("border-left:2px"));
        assertFalse(html.contains("<script src="));
    }

    @Test void companionPathPreservesMarkdownDestination() {
        assertEquals(Path.of("reports/impact.html"), HtmlReport.outputPath(Path.of("reports/impact.md")));
        assertEquals(Path.of("impact.html"), HtmlReport.outputPath(Path.of("impact")));
        assertEquals(Path.of("impact.html.html"), HtmlReport.outputPath(Path.of("impact.html")));
    }
}
