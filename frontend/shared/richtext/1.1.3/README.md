# Rich-text component 1.1.3

Implements public rich-text API 1.1 with the unchanged `advanced-v1` document format. Existing API 1.0 callers remain compatible.

- Adds a native rear-camera photo picker beside the image picker and in the advanced ribbon. Browser and operating-system support determines whether a camera or file chooser opens.
- `editor.capturePhoto()` synchronously requests that picker from a user click after `editor.ready`. It returns false during image processing/upload and throws before readiness or after disposal.
- Accepts photos up to 20 MiB and 40 megapixels. Large photos are oriented, resized to a maximum edge of 2560 pixels and compressed as JPEG within the existing 4 MiB resource limit. Small JPEG/PNG/WebP files are preserved.
- Processing participates in `isUploading()` and `flush()`. Cancellation preserves the answer; failures are reported and reject flush until a successful retry. Stale uploads do not insert into replaced documents or disposed editors.
- Photos use the existing image nodes and resource hashes. There is no camera-specific storage, extension-state migration, or change to frozen history.

Integration instructions: [rich-text service](../../../docs/RICHTEXT_SERVICE.md). Build future versions with `node scripts/build-richtext-advanced.mjs --version <new-version>` from `core/`; published component directories are immutable.
