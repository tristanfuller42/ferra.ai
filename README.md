# Inkwell

A tiny greyscale drawing app for Android (built for Pixel Fold). No libraries, plain Java.

## Install on your phone
1. On the phone, open the repo's **Releases** page → **Latest build** → tap `inkwell.apk`.
2. Allow "install unknown apps" for your browser when asked. Install.

## Use
Layout matches Procreate so you can switch between the two easily:
- **Top-left**: Actions (files, canvas, text, drawing guides, brushes, help), Adjustments (wand), Selection (lasso), Transform (arrow).
- **Top-right**: Brush, Smudge, Eraser, Layers, Colour. Tap the active brush/smudge/eraser again for the brush library.
- **Sidebar** (left, or right via Actions): brush size, eyedropper, brush opacity, undo, redo.

### Tools
- **Selection**: Automatic (tap; slide left/right to set threshold), Freehand, Rectangle, Ellipse; Add/Subtract,
  Invert, Copy & Paste, Cut & Paste, Feather, Clear. Brushes, smudge, fills, adjustments and transforms stay inside the selection.
- **Transform**: drag/pinch/twist (Uniform), Distort (drag corners), Flip H/V, Rotate 90°, Reset. Moves only the selection if there is one.
- **Adjustments**: Gaussian Blur, Motion Blur, Perspective Blur, Noise, Sharpen, Bloom, Glitch, Halftone, Liquify
  (push, twirl, pinch, expand, reconstruct), Clone, Brightness/Contrast, Curves, plus Threshold, Posterize, Invert.
  Slide left/right on the canvas to change the amount. Colour-only adjustments (hue, colour balance, recolor,
  chromatic aberration, gradient map) are left out on purpose: Inkwell is greyscale.
- **Smudge**: uses the current brush shape.
- **ColorDrop**: drag the colour circle onto the canvas to fill an area.
- **Layers**: tap the selected layer for options: rename, select, copy, fill, clear, alpha lock, clipping mask,
  invert, merge down, blend mode (12 modes), delete. Tap the letter on a layer to change its blend mode.
- **Text**: Actions → Add text.
- **Drawing guides**: 2D grid; symmetry vertical, horizontal, quadrant, radial.

- **Brushes**: tap the active brush button (or Erase) again to pick a brush and set brush opacity.
  - Size only from pressure: Ink pen, Brush pen (strong taper), Fine liner (no pressure), Marker (see-through).
  - Size + opacity from pressure: Dry ink, HB pencil, 6B pencil, Charcoal (paper grain), Soft shading, Airbrush.
  - Opacity only from pressure: Hard round.
  - **Import brushes**: in the brush list tap "+ Import brushes". Supports Procreate `.brush` / `.brushset`
    and Photoshop `.abr` (sampled tips). Opening a brush file via Open/Share also imports it.
    Imported: tip shape, grain texture (Procreate), spacing and pressure size/opacity (Procreate).
    Not imported: other Procreate/Photoshop dynamics (jitter, tilt, rotation, wet mix, etc.).
    Long-press an imported brush to delete it.
  - The eraser uses the selected brush's shape. Stylus eraser end or side button also erases.
- **Gestures** (like Procreate):
  - Two fingers: pinch zoom, drag pan, twist rotate. **Quick pinch-in** = fit canvas to screen.
  - Two-finger tap = undo. Two-finger **hold** = keep undoing. Three-finger tap = redo.
  - Three-finger **scrub** (side to side) = clear layer. Three-finger **swipe down** = copy & paste menu.
  - Four-finger tap = hide/show toolbar (full screen).
  - Touch and **hold** with a finger = eyedropper (pick a grey); keep dragging to adjust.
  - Draw and **hold** at the end = QuickShape: snaps to a straight line (keep holding and move to adjust),
    or an ellipse if the stroke ends near where it started.
- **Move tool**: drag moves the current layer; pinch/twist scales and rotates it. Applied when you pick another tool.
- **Layers**: add, copy, delete, reorder, merge down, clear, hide/show, opacity.
- Once a stylus is used, single fingers only pan (palm rejection).
- **Canvas**: start a new drawing at any size (presets: screen, square, A4/Letter/A5 at 300 dpi, comic page, HD, 4K, Instagram, or custom), or resize the current one (scale artwork, or crop/extend). Max about 9 megapixels.
- Drawing autosaves (with layers) when you leave the app.

## Files
- **Open / Import as layer**: PSD (layers kept), PNG, JPEG, WebP, HEIC, AVIF, GIF, BMP, PDF (page 1).
  Colours are converted to greyscale.
- **Export**: PNG, JPEG, WebP, PDF, PSD (with layers).
- You can also "Open with" / "Share to" Inkwell from other apps.

### From Procreate (iPad)
`.procreate` files can't be read. In Procreate: **Actions → Share → PSD**, then send the file to your
phone (Google Drive, email, etc.) and open it in Inkwell. Layers come across.
Limits: 8-bit RGB/greyscale PSD; layer masks, blend modes and groups are dropped (layers inside groups are kept).
Very large canvases are scaled down to about 9 megapixels.

## Build
`./gradlew assembleRelease` (needs Android SDK). GitHub Actions builds on every push.
The APK is signed with a debug key so it installs directly; use a real key before any store release.
