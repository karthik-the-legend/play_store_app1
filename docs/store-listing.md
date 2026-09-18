# Play Store listing (draft)

Paste-ready text for the Play Console listing, plus what each screenshot should show. Everything
here describes what the app actually does — Play removes listings that promise more than the app
delivers.

## Title (30 characters max)

```
Photo Resize in KB & PDF
```

24 characters. It leads with the words people type — "photo resize", "in kb" — rather than the
brand, because nobody searches for a new app by name. The name still appears in the short
description, the icon and the app itself.

Alternatives if you'd rather lead with the name:

- `FormKit: Photo Resize KB` (24)
- `FormKit – Resize Photo, PDF` (27)

## Short description (80 characters max)

```
Resize photos to an exact KB, make passport photos and PDFs. Works offline.
```

74 characters.

## Full description (4000 characters max)

Paste this into Play as it stands: each paragraph is one line, because Play keeps the line breaks
it is given and a wrapped paragraph would show up broken in the listing.

```
FormKit gets your documents ready for online forms — exam applications, job portals, government sites — without uploading anything. Every tool runs on your phone, so your photos and papers stay yours.

RESIZE A PHOTO TO AN EXACT KB SIZE
Forms ask for "photo under 50 KB" or "signature between 10 KB and 20 KB". Type the limit and FormKit lands just under it, first time. Set exact pixel dimensions too, like 200x230, when the form insists.

PASSPORT SIZE PHOTO MAKER
Turn any photo into a passport or stamp size photo with a clean white, blue or grey background, removed on your phone. Standard sizes are built in, including 35x45 mm, 2x2 inch and Indian exam sizes. Print sheets put several copies on 4x6 paper at 300 DPI.

SIGNATURE CLEANUP
Photograph your signature on paper and FormKit lifts it off the page: no shadows, no ruled lines, no grey background. Save it with a white or transparent background, at the KB size the form wants. You can also sign with your finger.

SCAN TO PDF
Photograph each page and FormKit finds its edges, straightens it and cleans it up, then makes a multi-page PDF that fits an upload limit. Original, greyscale, black & white and enhanced looks.

PDF TOOLS
• Compress a PDF to a target KB size
• Images to PDF, with page size and margins
• Merge PDFs in any order
• Split a PDF by page range, or pull out single pages
• PDF to images (JPEG or PNG) at the DPI you choose
• Password-protected PDFs open once you type the password

WHY FORMKIT
• Works offline. Airplane mode changes nothing.
• No account, no sign-in, no cloud storage.
• Your files are never uploaded — there is no server to upload them to.
• No clutter, and no watermark on anything except multi-photo print sheets.
• Dark mode, large-text support and TalkBack labels throughout.

FORMKIT PRO
One payment, no subscription. Pro removes every ad and the print sheet watermark, for good. The free version shows ads between operations; it never covers or interrupts a tool.
```

Two lines that were here are gone. "Small download" wasn't true of a 12 MB download, and a closing
"Useful for: photo resize, resize image in kb, …" list of search terms is keyword stuffing, which
Play's metadata policy rejects listings for. The words still earn their place in the sentences above.

## Graphics

| Asset | Size | Notes |
|---|---|---|
| App icon | 512 × 512 PNG | The launcher icon already in the app, exported at 512 px. |
| Feature graphic | 1024 × 500 PNG | Required. Deep indigo background, the app name, and three tool cards from Home. |
| Phone screenshots | at least 4, up to 8 | 1080 × 2400 from the emulator; see below. |

## The eight screenshots

Captions are the text to put above each screenshot; keep them to one line and use the app's own
indigo and white.

| # | Screen | Caption | What must be visible |
|---|---|---|---|
| 1 | Home | "Every form tool, offline" | The headline, the "Files never leave your phone" badge, and the tool grid. |
| 2 | Resize options with a 20 KB target | "Hit any KB limit exactly" | The size field with 20 KB typed, the preview, and the Resize button. |
| 3 | Resize result | "Under the limit, first time" | The "Under your 20 KB limit" badge, the new size, and the before/after line. |
| 4 | Passport result or print sheet | "Passport photos in seconds" | The passport photo with a clean background, its size in mm, and Save/Share. |
| 5 | Signature result | "Signatures without the paper" | The cleaned signature on white or transparent, with its KB size. |
| 6 | Scan to PDF, adjust page | "Scan straight to PDF" | The photo of a page with the outline found on it, and the Enhanced look selected. |
| 7 | Compress PDF result | "Squeeze a PDF under 200 KB" | The "Under your 200 KB limit" badge, the page count and the preview. |
| 8 | Settings | "No account. No uploads." | The privacy row, theme choice and the Pro card. |

Captured versions of all eight are in `docs/screenshots/`.
