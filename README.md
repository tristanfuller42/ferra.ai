# Inkwell

A tiny black-and-white drawing app for Android (built for Pixel Fold). No libraries, plain Java.

## Install on your phone
1. On the phone, open the repo's **Releases** page → **Latest build** → tap `inkwell.apk`.
2. Allow "install unknown apps" for your browser when asked. Install.

## Use
- **Brush / Erase / Move**: pick a tool. **● Black / ● White** switches ink colour. Slider = size.
- Stylus pressure changes line width. Stylus eraser end or side button erases.
- **Two fingers**: pinch to zoom, drag to pan, twist to rotate. **Fit** resets the view.
- **Two-finger tap** = undo, **three-finger tap** = redo. Up to 100 steps.
- **Move tool**: drag moves the current layer; pinch/twist scales and rotates it. Applied when you pick another tool.
- **Layers**: add, copy, delete, reorder, merge down, clear, hide/show, opacity.
- Once a stylus is used, single fingers only pan (palm rejection).
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
