# Drive PDF Studio — Multi-Engine Protected PDF Extractor & Organizer

An advanced, reliable protected PDF extraction and compilation platform engineered with a **4-Tier Hybrid Multi-Engine Pipeline**, a **Built-in Native PDF Viewer & Page Organizer Studio**, and a **Clean & Minimal Material 3 Productivity Interface** so that any view-only or download-restricted document can be captured at full fidelity without cutoffs or missing pages.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> Below is a summary of your confirmed preferences from our clarification interview, along with our recommended technical defaults for any remaining details.

- **Confirmed Decision 1 — All-in-One 4-Tier Hybrid Multi-Engine Extraction**: All four extraction engines will be active simultaneously and orchestrated automatically (with manual engine override chips available):
  1. **Direct Network Stream & Blob/Fetch Interceptor Engine** (intercepts raw PDF streams, `blob:` URLs, `ArrayBuffer` responses, and direct Drive export streams).
  2. **High-Resolution Canvas & DOM Extraction Engine** (hooks HTML5 `<canvas>` `toDataURL`, WebP/PNG page tiles, and upgrades `=w3200` high-DPI Google Drive viewer endpoints).
  3. **Precision Page-by-Page Auto-Scroll Screenshot Engine** (locates exact page DOM bounding boxes, hides Drive chrome, scrolls sequentially, verifies image decode completion, and crops real-size pages with zero page splits).
  4. **Interactive Auth & Live Studio Engine** (allows signing into private/organization-restricted links or manually triggering batch page capture).
- **Confirmed Decision 2 — Built-in PDF Viewer & Page Organizer Studio**: Adds a dedicated in-app **PDF Viewer & Page Organizer Studio** powered by Android's native `PdfRenderer`, enabling users to inspect extracted pages at high zoom, reorder pages, rotate pages (90°/180°/270°), delete unwanted pages, apply clean document contrast filters, and re-export the updated PDF immediately.
- **Confirmed Decision 3 — Clean & Minimal Material 3 Productivity Interface**: Redesigns the app with an uncluttered, editorial Material 3 layout featuring clear visual hierarchy, a multi-engine status pill bar, segmented workspace navigation (**Extractor**, **Page Organizer**, **Library**), and distraction-free progress cards.

---

## 1. Overview & Core Concept

- **What It Does**: Accepts any Google Drive, Google Docs/Slides preview, or protected web-viewer PDF link, automatically probes and executes four parallel/cascading extraction technologies to bypass view-only restrictions, compiles a crisp native A4 PDF, and provides an integrated **PDF Viewer & Page Organizer Studio** to review, rearrange, rotate, or clean up pages before saving or sharing.
- **Target Audience / Persona**: Students, researchers, professionals, and archivists who need offline access to view-only lecture notes, reference manuals, and protected cloud documents on Android.
- **Key Value**: Eliminates failed downloads, blank pages, and cut-in-half screenshots by combining network-level stream/blob interception, DOM/Canvas hooking, high-DPI URL upgrading, and exact bounding-box page capture under a single clean Material 3 workspace.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Smart Link Input & Engine Selection**:
   - User pastes any protected PDF or Google Drive link (auto-detects clipboard links with a one-tap paste chip).
   - User can leave **Auto Hybrid Mode (Recommended)** active or explicitly pick a specific engine (*Network/Blob Stream*, *High-Res Canvas/DOM*, *Precision Page Scroller*, or *Interactive Sign-In*) and target DPI quality (*Standard 1200p*, *High 2000p*, *Ultra 3200p*).
2. **Live Multi-Engine Extraction Telemetry**:
   - An elevated M3 status card shows which engine tier is currently active, live page discovery badges, thumbnail strip previews of captured pages, and an instant **"Compile Captured Pages Now"** action at any point.
3. **Built-in PDF Viewer & Page Organizer Studio**:
   - Upon completion (or when selecting any item from the Library), the user can open the document directly inside the **Built-in PDF Viewer & Page Organizer**.
   - **Reader Mode**: Smooth vertical/horizontal page reading with pinch-to-zoom and page jump indicator.
   - **Organizer Mode**: Interactive grid of all pages where the user can **Rotate**, **Move Up/Down (Reorder)**, **Remove** blank/unwanted pages, toggle **Crisp Document Contrast**, and tap **Save Updated PDF**.

### Visual Identity & Theme

- **Aesthetic Direction**: Clean, minimal Material 3 productivity workspace inspired by modern document tools ( crisp surfaces, subtle tonal layering, generous 8dp grid spacing, zero visual clutter).
- **Color Palette & Mood**:
  - `--background` / `Surface`: Warm Alabaster (`#F8FAFC` light) / Deep Slate (`#0F172A` dark)
  - `--primary`: Cobalt Productivity Blue (`#1D4ED8` light / `#60A5FA` dark) for primary extraction actions and active engine indicators
  - `--secondary`: Emerald Verification Green (`#059669`) for verified page captures and completed PDFs
  - `--tertiary`: Amber Studio Accent (`#D97706`) for Page Organizer & Canvas tools
- **Typography & Hierarchy**:
  - Clear typographic scale using bold headlines for workspace sections, medium weight for document titles and engine pills, and monospace tabular numerals for live page counters (`Page 14 / 42 • 3200 DPI`).
- **Component Styling & Layout**:
  - Top Bar with workspace status badge and quick access to Interactive Sign-In.
  - Segmented M3 Navigation Bar / Tabs switching cleanly between **Extract**, **Studio & Viewer**, and **Saved Library**.
  - Adaptive cards using `tonalElevation` and `16.dp` rounded corners with minimum `48.dp` accessible touch targets on every icon and action button.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: 4-Tier Cascading & Parallel Hybrid Engine vs. Single Method**
  - *Chosen Approach*: Orchestrate all four extraction methods in a smart pipeline:
    - **Tier 1 (Direct Export & Raw Stream Probe)**: Checks if direct export or embedded `application/pdf` stream is accessible.
    - **Tier 2 (Blob, Fetch/XHR & Canvas Hook)**: Injects a deep JS interceptor into `XMLHttpRequest`, `window.fetch`, `URL.createObjectURL`, and `HTMLCanvasElement.prototype.toDataURL` to capture raw PDF binary buffers or rendered canvas bitmaps directly from memory.
    - **Tier 3 (High-Res Viewer Tile & DOM URL Upgrader)**: Intercepts Google Drive `/viewer` and `googleusercontent` page image requests and rewrites resolution parameters to `=w3200` Ultra HD.
    - **Tier 4 (Precision Bounding-Box Auto-Scroll Screenshot Engine)**: Scrolls page-by-page to exact DOM page containers, hides floating toolbars, verifies image decode completion, and crops the exact page rectangle so pages are never split or cut off.
  - *Why*: Protected PDFs use varying mechanisms (some render via PDF.js `<canvas>`, some stream `blob:` buffers, some use Drive paginated `<img>` tiles, and some block URL reuse outside the active viewport). Combining all four guarantees maximum compatibility.
- **Decision 2: Native On-Device `PdfRenderer` Viewer & Organizer**
  - *Chosen Approach*: Use Android's hardware-accelerated `android.graphics.pdf.PdfRenderer` paired with `PdfDocument` re-compilation for the built-in viewer and page organizer.
  - *Why*: Zero external native library bloat, 100% offline reliability, fast page rendering, and lossless page rotation/deletion/reordering.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```text
┌─────────────────────────────────────────────────────────────────────────┐
│               Clean Material 3 Productivity UI (Compose)                │
│  ┌───────────────────┐  ┌──────────────────────┐  ┌──────────────────┐  │
│  │  Extractor Tab    │  │ Built-in PDF Viewer  │  │  Saved Library   │  │
│  │ • Smart URL Input │  │ & Page Organizer     │  │ • Search & Sort  │  │
│  │ • Engine Selector │  │ • Zoomable Reader    │  │ • Open in Studio │  │
│  │ • Live Telemetry  │  │ • Reorder/Rotate/Del │  │ • Share / Export │  │
│  └─────────┬─────────┘  └──────────┬───────────┘  └────────┬─────────┘  │
└────────────┼───────────────────────┼───────────────────────┼────────────┘
             │                       │                       │
             ▼                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     MainViewModel (StateFlow Store)                     │
│  • extractionMode (Hybrid / Stream / Canvas / PrecisionScroll)          │
│  • downloadState & liveEngineTelemetry                                  │
│  • activePdfSession (Pages, Rotations, Order, ContrastFilter)           │
└────────────┬───────────────────────────────────────────────┬────────────┘
             │                                               │
             ▼                                               ▼
┌──────────────────────────────────────────┐  ┌───────────────────────────┐
│   Multi-Engine Protected PDF Extractor   │  │ PdfOrganizer & Compiler   │
│  ┌────────────────────────────────────┐  │  │ • Android PdfRenderer     │
│  │ Tier 1: Direct Stream & Export     │  │  │ • Page Reorder & Rotate   │
│  ├────────────────────────────────────┤  │  │ • Page Delete & Contrast  │
│  │ Tier 2: JS Blob / XHR / Canvas     │  │  │ • A4 Native PdfDocument   │
│  ├────────────────────────────────────┤  │  │ • MediaStore Downloads    │
│  │ Tier 3: High-DPI URL Upgrader      │  │  └───────────────────────────┘
│  ├────────────────────────────────────┤  │
│  │ Tier 4: Precision DOM Box Scroller │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

### Data Model & State

- **`ExtractionEngineMode`**: Enum representing `AUTO_HYBRID`, `STREAM_BLOB_INTERCEPTOR`, `HIGH_RES_CANVAS_DOM`, and `PRECISION_PAGE_SCROLLER`.
- **`EngineTelemetryState`**: Tracks the active tier, discovered page count, intercepted network streams/blobs, canvas hooks, and current DPI resolution.
- **`PdfPageEditModel`**: Represents an individual page in the Page Organizer Studio (`originalPageIndex`, `currentOrderIndex`, `rotationDegrees`, `isDeleted`, `enhanceContrast`, `thumbnailBitmap`).

### Interactive Component & State Mapping

- **Engine Mode Selector Chips**: Allows switching between `AUTO_HYBRID` and specific extraction engines before or during link inspection.
- **Live Telemetry & Thumbnail Strip**: Displays real-time page discovery and lets the user tap **"Compile Now"** or **"Open Interactive Auth"** if a document requires Google account login.
- **Built-in PDF Viewer & Page Organizer Studio**:
  - **View Toggle**: Switches between continuous high-res PDF reading (with pinch-to-zoom) and the 2-column Page Organizer grid.
  - **Page Actions**: Each page card in Organizer Mode provides one-tap **Rotate 90°**, **Move Left/Up**, **Move Right/Down**, **Delete/Restore**, and **Contrast Boost** controls.
  - **Apply & Export**: Rebuilds the native A4 PDF in the background with all page edits applied and saves it to `Downloads/DrivePDFs`.
