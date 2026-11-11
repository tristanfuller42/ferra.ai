# Inkwell

A tiny black-and-white drawing app for Android (built for Pixel Fold). No libraries, plain Java.

## Install on your phone
1. On the phone, open the repo's **Releases** page → **Latest build** → tap `inkwell.apk`.
2. Allow "install unknown apps" for your browser when asked. Install.

## Use
- **Draw**: finger or stylus (stylus pressure changes line width).
- **Brush / Erase**: switch tool. Slider = size. A stylus eraser end or side button also erases.
- **Two fingers**: pinch to zoom, drag to move.
- **Two-finger tap** = undo. **Three-finger tap** = redo.
- Once a stylus is used, single fingers only move the canvas (palm rejection).
- **Fit**: reset view. **Save**: PNG to `Pictures/Inkwell`. Drawing autosaves when you leave the app.

## Build
`./gradlew assembleRelease` (needs Android SDK). GitHub Actions builds on every push.
The APK is signed with a debug key so it installs directly; use a real key before any store release.
