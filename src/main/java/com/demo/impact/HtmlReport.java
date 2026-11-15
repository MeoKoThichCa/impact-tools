package com.demo.impact;

import java.nio.file.Path;
import java.util.*;

/** Renders only the Markdown dialect emitted by ImpactQuery, never arbitrary HTML. */
final class HtmlReport {
    private HtmlReport() {}

    static Path outputPath(Path markdown) {
        String name = markdown.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String html = (dot > 0 ? name.substring(0, dot) : name) + ".html";
        // Preserve the Markdown destination even when --out itself ends in .html.
        if (html.equalsIgnoreCase(name)) html = name + ".html";
        return markdown.resolveSibling(html);
    }

    static String render(String markdown) {
        StringBuilder out = new StringBuilder(HEADER);
        List<String> lines = markdown.lines().toList();
        boolean tree = false;
        int treeLevel = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("## ") || line.startsWith("# ")) {
                if (tree) {
                    while (treeLevel >= 0) { out.append("</details></li></ul>"); treeLevel--; }
                    out.append("</div>");
                }
                tree = line.equals("## Cây lời gọi ngược");
                int heading = line.startsWith("## ") ? 2 : 1;
                out.append("<h").append(heading).append('>').append(inline(line.substring(heading + 1)))
                        .append("</h").append(heading).append('>');
                if (tree) out.append("<div class=controls><button type=button data-open=true>Mở tất cả</button> <button type=button data-open=false>Thu gọn tất cả</button></div><div class=tree>");
            } else if (tree && line.stripLeading().startsWith("- ")) {
                int level = (line.length() - line.stripLeading().length()) / 4;
                if (level > treeLevel) out.append("<ul>");
                else {
                    while (treeLevel > level) { out.append("</details></li></ul>"); treeLevel--; }
                    out.append("</details></li>");
                }
                treeLevel = level;
                out.append("<li><details open><summary>").append(inline(line.stripLeading().substring(2))).append("</summary>");
            } else if (line.startsWith("|")) {
                out.append("<div class=table-wrap><table><thead><tr>");
                appendCells(out, line, "th");
                out.append("</tr></thead><tbody>");
                i++; // The generated table's separator row.
                while (i + 1 < lines.size() && lines.get(i + 1).startsWith("|")) {
                    out.append("<tr>"); appendCells(out, lines.get(++i), "td"); out.append("</tr>");
                }
                out.append("</tbody></table></div>");
            } else if (!line.isBlank()) {
                out.append("<p>").append(inline(line.startsWith("- ") ? line.substring(2) : line)).append("</p>");
            }
        }
        if (tree) {
            while (treeLevel >= 0) { out.append("</details></li></ul>"); treeLevel--; }
            out.append("</div>");
        }
        return out.append(FOOTER).toString();
    }

    private static void appendCells(StringBuilder out, String row, String tag) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 1; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '\\' && i + 1 < row.length()) { cell.append(c).append(row.charAt(++i)); }
            else if (c == '|') { cells.add(cell.toString().strip()); cell.setLength(0); }
            else cell.append(c);
        }
        for (String value : cells) out.append('<').append(tag).append('>').append(inline(value))
                .append("</").append(tag).append('>');
    }

    private static String inline(String value) {
        StringBuilder out = new StringBuilder();
        boolean bold = false;
        for (int i = 0; i < value.length();) {
            if (value.charAt(i) == '\\' && i + 1 < value.length()) {
                out.append(escape(value.substring(i + 1, i + 2))); i += 2;
            } else if (value.startsWith("**", i)) {
                out.append(bold ? "</strong>" : "<strong>"); bold = !bold; i += 2;
            } else if (value.startsWith("<br>", i)) {
                out.append("<br>"); i += 4;
            } else if (value.startsWith("&amp;", i)) { out.append("&amp;"); i += 5;
            } else if (value.startsWith("&lt;", i) || value.startsWith("&gt;", i)) {
                out.append(value, i, i + 4); i += 4;
            } else { out.append(escape(value.substring(i, i + 1))); i++; }
        }
        if (bold) out.append("</strong>");
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final String HEADER = """
            <!doctype html><html lang="vi"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Báo cáo ảnh hưởng thay đổi method</title>
            <style>
            *{box-sizing:border-box}body{margin:0;background:#f3f6fa;color:#203047;font:15px/1.6 system-ui,sans-serif}
            main{max-width:1500px;margin:auto;padding:32px}h1{font-size:28px}h2{margin-top:36px;color:#153e70}
            p{overflow-wrap:anywhere}strong{color:#9b4313}.table-wrap{overflow:auto;background:white;border:1px solid #d5dfeb;border-radius:8px}
            table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;padding:12px;border-bottom:1px solid #dde5ef;min-width:100px;overflow-wrap:anywhere}
            th{background:#eaf0f8;white-space:nowrap}button{padding:8px 14px;border:1px solid #a8bbd3;border-radius:6px;background:white;cursor:pointer}
            button:focus-visible,summary:focus-visible{outline:3px solid #2782da}.tree{overflow:auto;padding:20px 0}
            .tree ul{list-style:none;margin:0;padding-left:32px}.tree>ul{padding-left:0}
            .tree li{position:relative;padding:8px 0 8px 24px;border-left:2px solid #9db5d2}
            .tree li:before{content:"";position:absolute;left:0;top:30px;width:24px;border-top:2px solid #9db5d2}
            .tree li:last-child{border-left-color:transparent}.tree li:last-child:after{content:"";position:absolute;top:0;left:-2px;height:30px;border-left:2px solid #9db5d2}
            .tree>ul>li{padding-left:0;border:0}.tree>ul>li:before,.tree>ul>li:after{display:none}
            summary{background:white;border:1px solid #b6c9df;border-left:4px solid #3576b9;border-radius:8px;padding:12px 16px;cursor:pointer;max-width:1050px;overflow-wrap:anywhere;box-shadow:0 2px 5px #2030470a}
            details:not(:has(>ul))>summary{list-style:none;cursor:default}
            @media(max-width:600px){main{padding:16px}.tree ul{padding-left:16px}}
            </style></head><body><main>
            """;
    private static final String FOOTER = """
            </main><script>
            document.querySelectorAll('[data-open]').forEach(button=>button.addEventListener('click',()=>{
              document.querySelectorAll('.tree details').forEach(branch=>branch.open=button.dataset.open==='true');
            }));
            </script></body></html>
            """;
}
