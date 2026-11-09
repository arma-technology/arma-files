# ARMA Files

**English** | [Polski](README.pl.md)

A file manager for Android with a built-in PDF and CAD viewer. Device storage,
SD cards and USB drives, Google Drive, OneDrive, Dropbox, FTP/FTPS, SFTP, SMB
and WebDAV in one place, with previews for most files without leaving the app.

**[Download the latest version (APK)](https://github.com/arma-technology/arma-files/releases/latest)** · requires Android 8.0 or newer

Changelog: [CHANGELOG.md](CHANGELOG.md) (in Polish) · roadmap: [ROADMAP.md](ROADMAP.md) · [privacy](PRIVACY.md)

## Features

**Browsing**
- **Depth**: each folder opens as a layer over the previous one, cards show
  live contents, long-press opens a quick preview, an action wheel copies,
  shares and deletes without menus, and a drawer collects files from different
  folders and drops them where you need them
- Night and Paper themes with an accent colour, layer blur and a
  “Calm motion” setting that shortens or turns off animations
- “My device” screen: pinned folders, internal storage, SD cards, connected
  network and cloud locations
- Back returns to where you came from; tabs and history survive a restart

**Files**
- copy and move between any sources in the background, trash, rename,
  SHA-256, Recent and Favourites
- **archives** open like folders and extract in the background: ZIP (including
  ZipCrypto/AES passwords), 7z (including passwords), RAR and RAR5, tar and
  tar.gz/bz2/xz/zst/lz4/br, gz, bz2, xz, zst, lzma, ISO, CAB, LHA, XAR, ar/deb,
  cpio, arj; on request also APK, DOCX, ODT, EPUB and other ZIP-based files;
  create ZIP (with AES-256 password), 7z, tar, tar.gz, tar.xz, tar.zst
- **storage map**: a drive shown as rectangles sized by the space they take,
  zoom into folders
- **duplicate finder**: where each copy lives, thumbnails, delete to trash
- offline files open instantly from the local copy, the server is checked in
  the background
- file index with search and folder statistics; sort order remembered per folder

**Viewers**
- images (including SVG) with finger-following pan and zoom, text files with
  encoding detection, Markdown, spreadsheets (xlsx, CSV) as tables
- audio and video player (Media3) with cover art, queue and many formats;
  network files download with a progress bar
- **PDF**: zoom up to 6400%, text search, layers, comments (view, add, save),
  measurements
- **CAD**: DXF natively, DWG through [LibreDWG](https://www.gnu.org/software/libredwg/);
  layers, line types, hatches, dimensions, text search
- **measurements in PDF and CAD**: snapping to endpoints, intersections,
  midpoints and perpendiculars, axis mode for distances from a wall; scale
  read from the file, the title block or calibration

**Languages:** English, Polish, Albanian, Belarusian, Bosnian, Bulgarian,
Croatian, Czech, Estonian, Lithuanian, Latvian, Macedonian, Dutch, German,
Russian, Romanian, Serbian, Slovak, Slovenian, Ukrainian, Hungarian.
Translation fixes are welcome — see the `res/values-*/strings.xml` files.

The PDF and CAD viewer are separate modules (`viewer/`, `dwg/`) that can be
reused in another app — see [viewer/README.md](viewer/README.md).

## Building

Requirements: JDK 17, Android SDK 36, NDK 28.2 and CMake 3.31 (for the `dwg`
and `libarchive` modules), [Git LFS](https://git-lfs.com) — the prebuilt
LibreDWG and libarchive libraries are stored in LFS.

```bash
git lfs install
git clone https://github.com/arma-technology/arma-files.git
cd arma-files
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
scripts/test.sh                # unit tests
```

The libraries can be built from source: `dwg/build-libredwg.sh`,
`libarchive/build-libarchive.sh`.

Optional settings in `local.properties` (kept out of the repository):

| Key | Purpose |
|---|---|
| `storeFile`, `storePassword`, `keyAlias`, `keyPassword` | release signing |
| `sourceUrl` | source code URL shown in the app (for forks) |

## Permissions

On Android 11+ the app asks for “All files access” (`MANAGE_EXTERNAL_STORAGE`),
because managing files is its core function. Without it, the app works in a
limited mode through the system folder picker (SAF). FTP/SFTP/SMB passwords are
encrypted with AES-GCM using a key from the Android Keystore.

## Reporting bugs

Report bugs and ideas in [Issues](https://github.com/arma-technology/arma-files/issues),
in English or Polish. For viewer bugs, the file that triggers the problem helps
most — if it can't be public, a description, the app version and the phone
model are enough. You can also report straight from the app: *Settings → About →
Suggest a feature or report a bug*.

## Support

ARMA Files is free, open source and has no ads. If it's useful to you, you can
support its development on [Ko-fi](https://ko-fi.com/armatechnology) — the same
link is in the app under *Settings → About* and in the ⋮ menu.

[![Support ARMA Files on Ko-fi](https://img.shields.io/badge/Ko--fi-support%20ARMA%20Files-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/armatechnology)

## License

Copyright © ARMA

This program is free software: you can redistribute it and/or modify it under
the terms of the [GNU General Public License](LICENSE) version 3 or (at your
option) any later version. It is distributed without any warranty.

Bundled libraries are under GPL-3.0-compatible licenses: LibreDWG (GPL-3.0+),
PdfBox-Android, AndroidX, Coil, OkHttp, Commons Net, SSHJ, Commons Compress,
zip4j (Apache 2.0), jcifs-ng (LGPL-2.1), SLF4J, Brotli (MIT), libarchive,
zstd-jni (BSD-2-Clause), XZ for Java (0BSD).
