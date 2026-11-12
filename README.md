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
- **Selection**: tap Done (or the Selection icon) to go back to drawing; the selection stays, shown with light stripes. Tap the Selection icon again to deselect. Automatic (tap; slide left/right to set threshold), Freehand, Rectangle, Ellipse; Add/Subtract,
  Invert, Copy & Paste, Cut & Paste, Feather, Clear. Brushes, smudge, fills, adjustments and transforms stay inside the selection.
- **Transform**: undo/redo (buttons or two/three-finger tap) step back through each move before you tap Done.
  Drag/pinch/twist (Uniform), Distort (drag corners), Flip H/V, Rotate 90°, Reset. Moves only the selection if there is one.
- **Adjustments**: Gaussian Blur, Motion Blur, Perspective Blur, Noise, Sharpen, Bloom, Glitch, Halftone, Liquify
  (push, twirl, pinch, expand, reconstruct), Clone, Brightness/Contrast, Curves, plus Threshold, Posterize, Invert.
  Slide left/right on the canvas to change the amount. Colour-only adjustments (hue, colour balance, recolor,
  chromatic aberration, gradient map) are left out on purpose: Inkwell is greyscale.
- **Smudge**: uses the current brush shape.
- **ColorDrop**: drag the colour circle onto the canvas to fill an area.
- **Background colour**: bottom row of the Layers panel, like Procreate. Tap to pick its grey, tap the dot to hide it
  (transparent PNG/WebP/PSD; JPEG/PDF get white). You never paint on it: every drawing starts with a blank Layer 1.
- **Layers**: tap the selected layer for options: rename, select, copy, fill, clear, alpha lock, clipping mask,
  invert, merge down, blend mode (12 modes), delete. Tap the letter on a layer to change its blend mode.
- **Text**: Actions → Add text.
- **Drawing guides** (Actions → Canvas → Edit drawing guide): editor with 2D Grid, Isometric, Perspective, Symmetry;
  opacity, thickness, grid size, lines per vanishing point (6–180). Perspective: tap to add a point (up to 3), tap a
  point to delete it, drag to move (also far outside the canvas; "Show all points" zooms out to fit them).
  The horizon stays level unless you switch on Tilt horizon; the square handle moves it up/down.
  Every change can be undone. Saved with each drawing.
  Earlier summary: perspective (1/2/3-point); symmetry vertical,
  horizontal, quadrant, radial. **Drawing assist** locks strokes to the guide lines.
- **Transform modes**: Uniform, Freeform (stretch with edge/corner handles), Distort, Warp (4×4 mesh).
- **Layer masks**: layer options → Add mask; tap the mask thumbnail to paint it (black hides, white shows).
  Apply, disable or delete the mask. Saved in PSD.
- **Layer groups**: Group with layer below / New group; collapse, hide, rename, group opacity, flatten. Saved in PSD.
- **Selections**: Save and Load in the selection bar (kept with each drawing). Selection changes can be undone like paint.
- **Reference window**: Actions → Reference. Shows the whole canvas live, or an imported image (tap it to pick a grey).
  Drag the title to move, ⤡ to resize.
- **Time-lapse**: every stroke is recorded. Actions → Video → replay (full screen; touch and hold to pause, drag left/right to scrub), or export MP4 (full length or 30 seconds).
- **Animation Assist**: Actions → Animation Assist. Each layer is a frame; timeline with play, onion skins,
  frames per second, background layer; export animated GIF or MP4.

### Procreate parity extras
- **Layers panel**: long-press and drag a row to reorder; swipe left for Lock / Duplicate / Delete; swipe right to
  multi-select (then Group / Delete); pinch two rows together to merge them. Layer options: Lock, Reference
  (ColorDrop and fills use that layer's lines), Edit text.
- **HUD**: size/opacity %, zoom % and angle, and "Undo: Brush stroke"-style banners.
- **Undo**: 250 steps.
- **Brush cursor**: outline of the brush when a stylus hovers (Prefs).
- **QuickShape**: line, ellipse or rectangle; after lifting, drag the blue handles to edit. Put a second finger down
  while holding to make a perfect circle / square or snap lines to 15°.
- **ColorDrop**: drag the colour circle onto the canvas (a dot shows where it will drop). Lift to fill, or **hold** to fill and then slide left/right to
  change the threshold (a bar at the top shows 0–100%). After a fill, a bar shows Threshold % and Continue filling (each tap fills).
- **Selection**: Polygon (tap corners, tap the first corner to close), Automatic threshold slider, marching ants.
- **Gallery**: Select → Stack / Unstack / Duplicate / Delete; drag to reorder; pinch out for full preview;
  tap a title to rename.
- **Text**: Actions → Add → Add text. Font, size, bold, alignment; edit later from layer options.
- **Animation**: tap a frame for hold duration, duplicate, delete; Loop / Ping-Pong / One shot.
- **Prefs**: interface size, brush cursor, global pressure curve and smoothing, rapid-undo delay.
- **Transform**: Snapping (to canvas centre/edges) and Smooth/Nearest interpolation.
- **Adjustments**: Layer or Pencil mode (Pencil = paint the adjustment with the brush).
- **Liquify**: Crystals and Edge modes, Momentum slider.
- **Camera**: Actions → Add → Take a photo (imported as a layer).
- **Stylus tilt**: pencils and charcoal get wider/lighter when the pen leans (Brush Studio → Dynamics → Tilt).
- **Eyedropper ring**: new grey on top, previous grey below.
- **QuickShape**: also arc, triangle and polyline (all with edit handles; second finger = equilateral / 15° segments).
- **QuickMenu**: 6 shortcuts in a ring. Prefs → Gesture controls: open it with touch-and-hold or the sidebar square button;
  long-press a slot to change it: an action, a brush ("A brush…") or an eraser ("An eraser…").
- **Gesture controls**: choose touch-and-hold action and delay; switch two/three/four-finger shortcuts on or off.
- **Layers**: two-finger swipe right on a layer = Alpha Lock; two-finger hold = select its contents.
- **Brush Library**: Recent set; drag ≡ to reorder; long-press a brush (or a set name) to export `.inkbrush` / `.inkbrushset`.
- **Page Assist**: Actions → Canvas. Each layer is a page; ‹ › to flip; export all pages as one PDF.
- **Fonts**: Text → ＋ Import font (.ttf / .otf).
- **Drag and drop**: drag images from another app (split screen) onto the canvas (new layer) or the Gallery (new drawing).
- **Transform**: orange rotation lever above the box (drag to rotate, angle shown; snaps to 15° with Snapping on), Rotate 45°, magnetics (with Snapping on: rotation sticks to 15°, size to 25% steps), Advanced mesh warp (5×5).
- **Gallery**: Sort (manual, name, newest, oldest); Select → Share or Export several (PNG/JPEG/PSD into a folder, or one PDF).
- **Share**: Actions → Share → a format opens the share sheet (send to any app); "Save to Files" saves a copy.
- **Back gesture**: closes what is open (panel, preview, tool mode); on the canvas it goes to the Gallery; in the Gallery it leaves the app.
- **Backup**: Gallery → Backup: everything (drawings, stacks, brushes, fonts, settings) in one .zip; Restore adds what's missing.
  Uninstalling the app deletes its drawings, so keep a backup in Drive. (Time-lapse recordings are not included.)
- **Crop & Resize**: Actions → Canvas → Crop & resize. Drag the box (it can go past the canvas edge to extend it),
  aspect presets, rotate, thirds grid; Settings… for exact numbers.
- **Brush size preview**: the real brush tip appears next to the size slider while you drag it.
- **Gallery preview**: pinch out on a drawing, then swipe left/right through the others.
- **Brush Studio**: Speed → thinner/lighter, Motion filtering (removes shake without lag), Rendering tab:
  Light/Heavy glaze and blend mode (Multiply, Darken, Lighten, Screen, Overlay).
- **Drawing Assist per layer**: the guide's Assisted drawing switch (and layer options) applies to the current layer.
- **Share**: PNG files (each layer, into a folder) and Animated PNG.
- **Text**: letter spacing, line spacing, underline, outline; layer options → Rasterize text.
- **Halftone brushes** (Textures): Halftone (stamped dots), plus Halftone grid and Halftone grid coarse: one dot grid fixed to the canvas; overlapping strokes
  never double up, they just reveal more of the same dots. Any brush can do this: Brush Studio → Rendering → Halftone grid.
- **More brushes** (about 50): new sets Calligraphy, Charcoals, Drawing, Textures, Spray paints, Elements.
- **Shape / Grain source**: Brush Studio → Shape or Grain → Import a picture (Invert, or go back to round / no grain).
- **Colour Fill**: Selection → Freehand, Rectangle or Ellipse with Colour Fill on: the shape is filled with the grey.
- **Rotational symmetry**: Drawing Guide → Symmetry → switch between mirrored and rotated copies.
- **Photo**: Gallery → Photo (new drawing) and Actions → Add → Insert a photo use Android's photo picker.
- **Liquify Distortion** slider; **Selection mask visibility** in Prefs.

### Gallery
Inkwell opens in the Gallery like Procreate. ＋ New (size presets), Import (any supported file), tap to open,
long-press to rename, duplicate or delete. Drawings save automatically.

- **Brush Library**: tap the active brush/smudge/eraser button. Brush sets on the left (Inking, Sketching, Painting,
  Airbrushing, Imported), previews on the right. ＋ makes a new brush; long-press a brush to duplicate, reset or delete.
- **Brush Studio**: tap the selected brush again. Settings per brush, saved: Stroke path (spacing, size/opacity jitter,
  scatter), Stabilization (StreamLine smoothing, stabilization), Taper (start/end), Shape (roundness, angle, rotate
  with stroke, hardness), Grain (depth, scale), Dynamics (pressure → size/opacity, pressure curve), Properties
  (maximum size/opacity), About (name, set). Live preview; Cancel restores.
- **Menus** are Procreate-style popovers. Actions has tabs: Add, Canvas, Share, Video, Prefs, Help.
  - Size only from pressure: Ink pen, Brush pen (strong taper), Fine liner (no pressure), Marker (see-through).
  - Size + opacity from pressure: Dry ink, HB pencil, 6B pencil, Charcoal (paper grain), Soft shading, Airbrush.
  - Opacity only from pressure: Hard round.
  - **Import brushes**: in the brush list tap "+ Import brushes". Supports Procreate `.brush` / `.brushset`
    and Photoshop `.abr` (sampled tips). Opening a brush file via Open/Share also imports it.
    Imported: tip shape, grain texture (Procreate), spacing and pressure size/opacity (Procreate).
    Not imported: other Procreate/Photoshop dynamics (jitter, tilt, rotation, wet mix, etc.).
    Long-press an imported brush to delete it.
  - Brush, Smudge, Eraser and Clone each remember their own brush, size and opacity, and every brush remembers its own size
    and opacity for each of them (like Procreate).
  - Stylus eraser end or side button also erases.
- **Gestures** (like Procreate):
  - Two fingers: pinch zoom, drag pan, twist rotate. **Quick pinch-in** = fit canvas to screen.
  - Two-finger tap = undo. Two-finger **hold** = keep undoing. Three-finger tap = redo.
  - Three-finger **scrub** (side to side) = clear layer. Three-finger **swipe down** = copy & paste menu.
  - Four-finger tap = hide/show toolbar (full screen).
  - Touch and **hold** with a finger = eyedropper (pick a grey); keep dragging to adjust.
  - Draw and **hold** at the end = QuickShape: snaps to a straight line (keep holding and move to adjust),
    or an ellipse if the stroke ends near where it started.
- **Move tool**: drag moves the current layer; pinch/twist scales and rotates it. Applied when you pick another tool.
- **Layers**: ＋ adds a layer. Long-press and drag to reorder (the other layers slide aside to show where it lands), swipe left for Lock/Duplicate/Delete, pinch rows to merge, tap the
  current layer for options (clear, merge down, mask, etc.), dot to hide/show, slider for opacity.
- Once a stylus is used, one finger **erases** (with the Eraser's brush and size). Prefs → Finger when using a stylus:
  Navigate / Paint / Erase. Two fingers still zoom and pan.
- **Canvas**: start a new drawing at any size (presets: screen, square, A4/Letter/A5 at 300 dpi, comic page, HD, 4K, Instagram, or custom in pixels, inches or centimetres with a DPI), or resize the current one (scale artwork, or crop/extend). Max about 9 megapixels.

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
