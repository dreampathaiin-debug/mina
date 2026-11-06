---
name: documents
display: Work with PDFs
description: Read PDF text by page, read info, split and merge
aliases: ["read PDF", "PDF to text", "merge PDF"]
terms: ["PDF", "document", "extract text", "by page", "merge", "split", "scanned copy"]
---
## Entry

First find where the document is:
- A public link on the web: open it with `web_navigate`; when the built-in browser can show it directly, reading the page is enough.
- An attachment the user sent: in `/workspace/inbox/`.
- A file downloaded from a page: in `/workspace/downloads/`, listed by `web_downloads`.

## Steps

1. For text PDFs run `scripts/extract_pdf.py` with `run_bundled_script`, set `source` to `skills/documents`, and write paths relative to `/workspace`:
   - Whole text: `args: ["inbox/a.pdf"]`
   - Chosen pages (counting from 1; `3`, `1-5`, `1,4,7-9` are all valid): `args: ["inbox/a.pdf", "--pages", "1-5"]`
   - Title, author, page count and other info (JSON): `args: ["inbox/a.pdf", "--metadata"]`
   - For a long document, save the text first: `args: ["inbox/a.pdf", "--out", "a.txt"]`, then read it in segments with `read_file`, or find keywords with `search_files`.
   - For a password-protected one, add `--password` and the password.
2. Each page in the output starts with `--- page N ---`; cite page numbers by it.
3. For splitting, merging and pulling pages out, write a short Python snippet with `run_command` using pypdf; put new files under `/workspace`.
4. Hand the finished file to the user with `share_file`.

## Where it goes wrong

- Exit code 3 with no extractable text means the PDF is a scan of images; there is no text recognition on this device, so either look at the page images or tell the user this. When some pages have no text, the script reports those pages separately.
- Tables and multi-column layouts often come out in the wrong order; check against the original page before quoting a number from a table.
- A page range beyond the document's page count errors out at once; check the page count with `--metadata` first.

## What counts as done (verification evidence)

The text given maps page by page to the script's output, with page numbers written; the handed-over file was shared by `share_file` successfully, and its page count and content match expectations (run `--metadata` again on the new file to check the count).

## When to stop and hand back

Ask the user first when the document has a password they did not give, or when the user must decide which pages to keep or what order to merge in.
