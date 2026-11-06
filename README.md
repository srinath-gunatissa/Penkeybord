# Pen Keyboard

A system-wide Android handwriting keyboard for the S Pen (built for a Galaxy Tab S10 FE).
Write in the pad; when you pause, Google ML Kit's on-device recognizer turns it into text in whatever app you're using.

- Pressure-sensitive ink, palm rejection ("Pen only")
- Suggestions bar: tap to swap the last word
- ⌫ removes the last stroke while writing; hold the S Pen button to erase strokes
- Uses the text before the cursor to improve recognition
- Model downloads once (~20 MB), then works offline

## Install
GitHub Actions builds the APK on every push to `main` and publishes it to the **latest** release.
Download `PenKeyboard.apk` on the tablet, install, open **Pen Keyboard**, then tap *Turn on* and *Switch to*.
