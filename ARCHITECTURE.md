# Ultimate PDF Architecture & Technical Deep-Dive 📐

This document provides a comprehensive technical exploration of the internal subsystems of Ultimate PDF, detailing the mathematical algorithms, data structures, and cryptographic protocols that power each feature.

---

## 1. High-Performance PDF Rendering Architecture

### Dual-Engine Strategy
Ultimate PDF adopts a hybrid rendering pipeline:
1. **Primary Display**: Google Android's native `android.graphics.pdf.PdfRenderer` backed by native hardware acceleration. It converts vector and bitmap PDF commands directly into hardware-backed Android `Bitmap` buffers.
2. **Structural Manipulation**: `com.tom_roush.pdfbox` (PdfBox-Android) handles object-level manipulation (AcroForms, structural re-writing, digital signatures).

### Memory Management & LRU Caching
Rendering multi-page documents at 300+ DPI on high-density mobile and tablet screens can easily exhaust the Android heap. Ultimate PDF resolves this using an `LruCache<String, Bitmap>`:
- Cache key schema: `"{pageIndex}-{width}x{height}-inv={invertColors}"`
- Dynamic aspect-ratio bounding:
  $$\text{scale} = \min\left(\frac{W_{\text{target}}}{W_{\text{page}}}, \frac{H_{\text{target}}}{H_{\text{page}}}\right)$$
- Native page memory buffers are recycled immediately via `page.close()` after rasterizing into the target bitmap.

### Color Inversion (Accessibility / Dark Mode)
Instead of re-rasterizing the document with altered color dictionaries, color inversion is performed in hardware using a $4 \times 5$ color transformation matrix applied during display:

$$\begin{bmatrix}
R' \\ G' \\ B' \\ A'
\end{bmatrix} =
\begin{bmatrix}
-1 & 0 & 0 & 0 & 255 \\
0 & -1 & 0 & 0 & 255 \\
0 & 0 & -1 & 0 & 255 \\
0 & 0 & 0 & 1 & 0
\end{bmatrix}
\begin{bmatrix}
R \\ G \\ B \\ A \\ 1
\end{bmatrix}$$

This produces zero overhead on the CPU while delivering instantaneous night reading.

---

## 2. Non-Destructive Editing Pipeline (`EditPlan`)

Inspired by **AnonPDF**, Ultimate PDF avoids destructive in-place writes. Modifying a PDF file directly corrupts incremental revisions, breaks cryptographic signatures, and incurs expensive flash storage I/O.

### The Operation Folding Pattern
Every user gesture is recorded as an immutable `EditOp`:

```kotlin
sealed interface EditOp {
    fun describe(): String
}
```

Concrete operations include:
- `RotatePages(degrees: Int, targets: Set<Int>)`
- `CropPages(insets: CropInsets, targets: Set<Int>)`
- `DeletePages(targets: Set<Int>)`
- `RestorePages(targets: Set<Int>)`
- `MovePage(sourcePosition: Int, targetPosition: Int)`
- `InsertPages(sourceUri: Uri, targetPosition: Int, pageCount: Int, label: String)`
- `ReversePages`
- `SetWatermark(options: WatermarkOptions?)`
- `SetPageNumbers(options: PageNumberOptions?)`

The `EditPlanBuilder` folds the history using functional reduction:

```kotlin
fun build(pageCount: Int, ops: List<EditOp>): EditPlan =
    ops.fold(identity(pageCount)) { plan, op -> apply(plan, op) }
```

### Commit Execution (`EditPlanProcessor`)
When the user triggers **Export**, `EditPlanProcessor`:
1. Instantiates a clean target `PDDocument`.
2. Iterates over `plan.activePages`.
3. Imports pages from the base document or auxiliary spliced documents using `targetDoc.importPage(sourcePage)`.
4. Applies accumulated rotation deltas:
   $$\theta_{\text{final}} = (\theta_{\text{original}} + \Delta\theta) \pmod{360}$$
5. Applies normalized crop insets directly onto `PDPage.setCropBox(PDRectangle)`.
6. Appends watermark text streams with `PDExtendedGraphicsState.setNonStrokingAlphaConstant(opacity)`.
7. Appends page numbers centered at the bottom margin.

---

## 3. Computer Vision & Dual-Layer Searchable PDF Generation

Inspired by **MakeACopy** and **FairScan**, Ultimate PDF features an end-to-end paper document scanning pipeline.

### Quadrilateral Corner Detection (`DocumentDetector`)
1. **Luminance Profiling**: Downsamples the capture to $200 \times 200$ pixels and analyzes the luminance function:
   $$Y = 0.299R + 0.587G + 0.114B$$
2. Paper document edges are detected where sharp contrast deltas exceed the background surface threshold ($Y > 130$).
3. **Corner Sorting Math**: The 4 candidate corners are partitioned by Y-axis into top-pair and bottom-pair, then sorted by X-axis to yield the canonical clockwise order: $[\text{Top-Left}, \text{Top-Right}, \text{Bottom-Right}, \text{Bottom-Left}]$.

### Perspective Rectification (`PerspectiveCorrector`)
Camera captures suffer from perspective foreshortening. Ultimate PDF calculates target dimensions based on Euclidean distances:

$$W = \max\left(\sqrt{(x_{TR} - x_{TL})^2 + (y_{TR} - y_{TL})^2},\, \sqrt{(x_{BR} - x_{BL})^2 + (y_{BR} - y_{BL})^2}\right)$$
$$H = \max\left(\sqrt{(x_{BL} - x_{TL})^2 + (y_{BL} - y_{TL})^2},\, \sqrt{(x_{BR} - x_{TR})^2 + (y_{BR} - y_{TR})^2}\right)$$

A projective transformation matrix $M$ is computed such that:
$$M \cdot \begin{bmatrix} x_i \\ y_i \\ 1 \end{bmatrix} = \begin{bmatrix} x'_i \\ y'_i \\ 1 \end{bmatrix}$$
mapping source corners to $[(0,0), (W,0), (W,H), (0,H)]$.

### Searchable PDF with Invisible Text Layer (`SearchablePdfGenerator`)
To produce a PDF that appears as a crisp image scan but allows full text selection and copying:
1. The scanned bitmap is drawn as the page background at 300 DPI via `JPEGFactory.createFromImage()`.
2. A transparent content stream is appended over the image.
3. The text rendering mode is explicitly switched to **ISO 32000 Mode 3**:
   ```java
   cs.setRenderingMode(RenderingMode.NEITHER); // Neither fill nor stroke text
   ```
4. Each OCR token is transformed to PDF coordinate space (72 points/inch, origin at bottom-left):
   $$x_{\text{pdf}} = x_{\text{img}} \times \frac{W_{\text{pdf}}}{W_{\text{img}}}$$
   $$y_{\text{pdf}} = H_{\text{pdf}} - \left(y_{\text{img}} + H_{\text{box}}\right) \times \frac{H_{\text{pdf}}}{H_{\text{img}}}$$
5. The text matrix is configured with an explicit horizontal scaling factor:
   $$\text{scaleX} = \frac{\text{Width}_{\text{box}}}{\text{FontWidth}_{\text{estimated}}}$$
   ```java
   cs.setTextMatrix(new Matrix(scaleX, 0f, 0f, 1f, xPdf, yPdf));
   cs.showText(word.text);
   ```

Any standard PDF reader (Adobe Acrobat, Google Chrome, Evince) detects this text layer for indexing, highlighting, and clipboard copy.

---

## 4. Cryptographic Digital Signatures (PAdES / PKCS#7)

Inspired by **Open-PDF-Sign**, Ultimate PDF integrates Bouncy Castle to implement real cryptographic digital signatures.

### Cryptographic Workflow
1. The user specifies signer metadata (Name, Reason, Location).
2. The user draws their handwritten signature on the smooth Bézier curve canvas.
3. A visual stamp box is drawn onto the target page combining the handwritten raster image with standard certification text.
4. If a PKCS#12 keystore (`.p12` / `.pfx`) is loaded:
   - The private key and X.509 certificate chain are extracted from the keystore.
   - A `PDSignature` dictionary is registered on the `PDDocument` with filter `adbe.pkcs7.detached`.
   - The document's byte-range content stream is hashed with SHA-256.
   - Bouncy Castle's `CMSSignedDataGenerator` produces a CMS / PKCS#7 signed data structure using `JcaContentSignerBuilder("SHA256withRSA")`.
   - The detached signature is embedded into the `/Contents` hex string of the signature dictionary.

---

## 5. Responsive UI for Smartphone and Tablet

Ultimate PDF adapts seamlessly across form factors using Jetpack Compose and `WindowSizeClass`:

| Component | Smartphone (<600dp width) | Tablet (≥600dp width) |
|---|---|---|
| **Primary Navigation** | Bottom `NavigationBar` (thumb-friendly) | Left-side persistent `NavigationRail` |
| **Document Viewer** | Single viewport with bottom horizontal thumbnail carousel | **Two-Pane Master-Detail**: permanent vertical page list on the left, high-res canvas on the right |
| **Page Organizer** | 3-column page grid | 5-column wide-screen page grid |
| **Annotation Tools** | Bottom drawer tool selector | Side floating palette with stylus palm rejection |
| **PDF Tools Grid** | 2-column quick action cards | 3-column spacious action cards |
