# Comparative Analysis of Open-Source Android PDF Projects 📊

This document examines the 9 reference open-source projects evaluated during the creation of **Ultimate PDF**, detailing their architectural trade-offs, licensing implications, and exact contributions to this unified suite.

---

## 1. Project Analysis Matrix

| # | Project | Primary Domain | Technical Strengths | Architectural Limitations | License | Extracted Contribution |
|---|---|---|---|---|---|---|
| 1 | **MakeACopy** (`egdels/makeacopy`) | Scanner & OCR | Robust OpenCV edge detection, dual-layer searchable PDF generation with hidden text | Heavy C++ native dependencies, traditional Views architecture | Apache 2.0 | Dual-layer searchable PDF algorithm with `RenderingMode.NEITHER` & token matrix mapping |
| 2 | **FairScan** (`pynicolas/FairScan`) | Lightweight Scanner | Modern Jetpack Compose, CameraX integration, TFLite/LiteRT segmentation | Focuses exclusively on scanning; no viewer or document editing | GPL-3.0 | Modern Compose CameraX UX, 4-corner interactive cropping handle overlay |
| 3 | **AnonPDF** (`FanFeast/anonpdf`) | Non-Destructive Editing | Immutable `EditPlan` & `EditOp` history folding, memory-friendly operation replay | Lacks advanced form filling (AcroForms) and cryptographic PAdES signatures | GPL-3.0 | The core Page Organizer and non-destructive editing pipeline |
| 4 | **Images-to-PDF** (`Swati4star`) | Utility Suite | Immense catalog of utility tools: merge, split, password encrypt/decrypt, watermark, image extract | Legacy Android Views and older iText dependencies with licensing baggage | Apache 2.0 | Re-implemented utility catalog on modern PdfBox-Android (AES-256, merge, split, extract) |
| 5 | **Open PDF Studio** (`OpenAEC-Foundation`) | Annotations & Forms | Comprehensive vector annotations (highlight, underline, sticky notes, shapes), XFDF support | Desktop-first (Rust/Tauri backend); not directly portable to Android JNI | LGPL-3.0 | Annotation data models, vector shape specs, and stylus drawing interaction patterns |
| 6 | **PdfBox-Android** (`TomRoush`) | PDF Core | Complete native Java port of Apache PDFBox, deep AcroForm support, font embedding | Rendering is slower than native C++ engines like PDFium or PdfRenderer | Apache 2.0 | Primary PDF object model, AcroForm field extractor/serializer, cloner, security engine |
| 7 | **MuPDF** (`ArtifexSoftware`) | High-Speed Engine | Ultra-fast C/C++ rendering, comprehensive PDF spec compliance, tile rendering | Viral AGPL-3.0 license or expensive commercial license required | AGPL-3.0 / Commercial | Viewport tile-rendering concepts, viewport clipping and zoom scaling mathematics |
| 8 | **PdfiumAndroid** (`oothp`) | Graphic JNI Wrapper | Google's Chrome PDFium engine wrapped for Android, 16 KB page memory support | Complex NDK build setup across multiple ABIs (armeabi-v7a, arm64-v8a, x86_64) | Apache 2.0 | Coordinate transformation model between device touch pixels and 72-DPI PDF points |
| 9 | **Open-PDF-Sign** (`open-pdf-sign`) | Digital Signatures | PAdES baseline profile (B, T, LT, LTA), X.509 cert validation, TSA timestamping | CLI and server-focused Java application; no mobile UI | Apache 2.0 | Bouncy Castle CMS/PKCS#7 signing architecture adapted for Android with visual stamps |

---

## 2. Licensing & Decoupling Strategy

A key challenge when building on open-source projects is managing differing licenses:
- **Apache 2.0**: Very permissive. Used by PdfBox-Android, MakeACopy, Images-to-PDF, PdfiumAndroid, Open-PDF-Sign, and Ultimate PDF.
- **GPL-3.0 / LGPL-3.0**: FairScan and AnonPDF use GPL-3.0; Open PDF Studio uses LGPL-3.0.
- **AGPL-3.0**: MuPDF uses AGPL-3.0 which has viral copyleft implications.

### How Ultimate PDF Solves This:
1. **Clean Room Architectural Re-implementation**:
   - Rather than copying GPL code directly into our binary, we adopted the architectural patterns (e.g., AnonPDF's immutable `EditOp` folding and FairScan's interactive crop canvas design) and wrote clean, native Kotlin implementations from scratch.
2. **Replacing iText & MuPDF**:
   - In place of commercial/viral engines (iText, MuPDF), we unified the entire system on **PdfBox-Android (Apache 2.0)** and Android's native hardware-accelerated **`android.graphics.pdf.PdfRenderer` (Apache 2.0)**.
3. **Cryptographic Independence**:
   - We utilize **Bouncy Castle (MIT/BouncyCastle License)** directly for cryptographic signatures, avoiding proprietary cryptographic modules.

---

## 3. Tablet vs. Smartphone Usability Innovations

None of the original mobile applications provided a dedicated, optimized tablet experience. Ultimate PDF introduces:
- **Master-Detail Two-Pane Viewer**: Automatically engages on screens $\ge 600\text{ dp}$ wide. The user retains continuous context of the entire document through the left-pane thumbnail index while working on the right-pane canvas.
- **Adaptive Navigation**: Replaces cramped bottom navigation bars with a spacious, thumb-accessible `NavigationRail` on the left edge for tablets.
- **Stylus Pen & Palm Rejection Ready**: Annotation layers distinguish stylus touch events from finger panning gestures.
