# Changelog

## V0.2.4
- Normalize full-width OCR digits and punctuation with Unicode NFKC.
- Add transaction/order-number numeric evidence.
- Tighten PROBABLE_RECEIPT to require two independent structured fields: amount + time, amount + transaction number, or time + transaction number. A single structured field remains REVIEW.
- Persist transaction-number evidence in SQLite schema v3, with v1/v2 migration paths.
- Add 12 pure-Java heuristic tests.
- No printing functionality.

## V0.2.3
- Tightened QR + OCR receipt classification with numeric amount/time evidence.
- Added OCR line bounding-box vertical-spread analysis.
- Added SQLite v1-to-v2 migration and persisted evidence fields.
- Expanded pure-Java heuristic tests.
- Still no printing functionality.

## V0.2.2
- Added persistent recognition history, retry handling, EXIF rotation and image downsampling.
- Added SQLite-backed deduplication across service restarts.
- Added QR-first Chinese OCR filtering and notification review flow.
