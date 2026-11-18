"""
Markdown document -> PDF, for the project's short documents (V1.md, V1_zh.md, ...).

  python tools/md_to_pdf.py V1_zh.md            -> V1_zh.pdf next to it
  python tools/md_to_pdf.py V1.md out/V1.pdf

How: a small converter for the Markdown these documents use (headings, paragraphs, **bold**,
tables, images, ```mermaid blocks, <details>) -> one HTML page -> Microsoft Edge prints it to PDF
(headless). Edge has the Chinese fonts (Microsoft YaHei) and colour emojis, and draws the Mermaid
diagrams with mermaid.js (loaded from the jsdelivr CDN: needs internet). No pip packages needed.
"""
import html
import os
import re
import subprocess
import sys

EDGE = [r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"]

CSS = """
@page { size: A4; margin: 16mm 14mm; }
body { font-family: "Segoe UI", "Microsoft YaHei", "SimHei", sans-serif; font-size: 10.5pt; color: #1a1a1a;
       line-height: 1.55; }
h1 { font-size: 22pt; margin: 0 0 6mm; padding-bottom: 3mm; border-bottom: 3px solid #2e7d32; color: #1b3d20; }
h2 { font-size: 14pt; margin: 8mm 0 3mm; color: #1b3d20; page-break-after: avoid; }
p { margin: 0 0 3mm; }
table { border-collapse: collapse; width: 100%; margin: 2mm 0 4mm; font-size: 9pt; page-break-inside: auto; }
tr { page-break-inside: avoid; }
th { background: #e8f5e9; color: #1b3d20; text-align: left; }
th, td { border: 1px solid #b0bec5; padding: 2mm 2.4mm; vertical-align: top; }
th:first-child, td:first-child { min-width: 26mm; }   /* names must not break mid-word */
tbody tr:nth-child(even) td { background: #fafafa; }
img { max-width: 100%; }
.mermaid { text-align: center; margin: 3mm 0 4mm; }
details > summary { display: none; }      /* diagram source blocks: hidden in the PDF */
details { display: none; }
.legend { font-size: 9pt; color: #555; }
"""


def inline(text: str) -> str:
    t = html.escape(text, quote=False)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    return t


def convert(md: str, base_dir: str) -> str:
    out, lines, i = [], md.splitlines(), 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("```mermaid"):
            j = i + 1
            while not lines[j].startswith("```"):
                j += 1
            out.append('<pre class="mermaid">' + html.escape("\n".join(lines[i + 1:j])) + "</pre>")
            i = j + 1
        elif line.startswith("```"):
            j = i + 1
            while not lines[j].startswith("```"):
                j += 1
            out.append("<pre><code>" + html.escape("\n".join(lines[i + 1:j])) + "</code></pre>")
            i = j + 1
        elif line.startswith("<details>"):                           # source blocks: not printed
            while not lines[i].startswith("</details>"):
                i += 1
            i += 1
        elif re.match(r"^#{1,6} ", line):
            level = len(line) - len(line.lstrip("#"))
            out.append(f"<h{level}>{inline(line[level + 1:])}</h{level}>")
            i += 1
        elif line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            head, body = rows[0], [r for r in rows[2:]]
            t = "<table><thead><tr>" + "".join(f"<th>{inline(c)}</th>" for c in head) + "</tr></thead><tbody>"
            t += "".join("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>" for r in body)
            out.append(t + "</tbody></table>")
        elif m := re.match(r"^!\[(.*?)\]\((.+?)\)", line):
            src = m[2] if re.match(r"^[a-z]+:", m[2]) else "file:///" + os.path.abspath(os.path.join(base_dir, m[2])).replace("\\", "/")
            out.append(f'<p><img alt="{html.escape(m[1])}" src="{src}"></p>')
            i += 1
        elif line.strip():
            para = [line]
            i += 1
            while i < len(lines) and lines[i].strip() and not re.match(r"^(#|\||```|<details|!\[)", lines[i]):
                para.append(lines[i])
                i += 1
            text = " ".join(para)
            cls = ' class="legend"' if text.startswith("✅") else ""
            out.append(f"<p{cls}>{inline(text)}</p>")
        else:
            i += 1
    return "\n".join(out)


def main():
    src = os.path.abspath(sys.argv[1])
    pdf = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else os.path.splitext(src)[0] + ".pdf"
    md = open(src, encoding="utf-8").read()
    title = next((l[2:] for l in md.splitlines() if l.startswith("# ")), os.path.basename(src))
    page = f"""<!doctype html><html><head><meta charset="utf-8"><title>{html.escape(title)}</title>
<style>{CSS}</style>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js"></script>
</head><body>
{convert(md, os.path.dirname(src))}
<script>mermaid.initialize({{ startOnLoad: true, theme: "default", flowchart: {{ htmlLabels: true }} }});</script>
</body></html>"""
    html_path = os.path.splitext(pdf)[0] + ".print.html"
    open(html_path, "w", encoding="utf-8").write(page)
    edge = next((e for e in EDGE if os.path.exists(e)), None)
    if not edge:
        sys.exit("Microsoft Edge not found")
    subprocess.run([edge, "--headless", "--disable-gpu", "--no-pdf-header-footer", "--virtual-time-budget=15000",
                    "--run-all-compositor-stages-before-draw", f"--print-to-pdf={pdf}",
                    "file:///" + html_path.replace("\\", "/")], check=True, timeout=120,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.remove(html_path)
    print(f"{pdf}: {os.path.getsize(pdf) // 1024} KB")


if __name__ == "__main__":
    main()
