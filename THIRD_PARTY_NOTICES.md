# FloWave licensing and third-party notices

FloWave is distributed under the GNU General Public License, version 3 or
later (GPL-3.0-or-later). The complete license text is in [`LICENSE`](LICENSE).
FloWave source files remain copyright their original authors unless a file says
otherwise.

## GPL-3.0 source projects consulted and integrated

### Velune

* Project: [nikhilvishwakarma00/Velune](https://github.com/nikhilvishwakarma00/Velune)
* License: GPL-3.0 (upstream `LICENSE`), copyright notices retained upstream.
* Revision studied: [`ae6d627`](https://github.com/nikhilvishwakarma00/Velune/commit/ae6d627).
* Origin in FloWave: the streaming resolver follows Velune's documented
  client-fallback, URL-cache, refresh-on-expiry, and player-request structure.
  FloWave's `InnerTubeRepository` is an independent adapter and does not copy
  Velune's source files or branding.

### Seal

* Project: [JunkFood02/Seal](https://github.com/JunkFood02/Seal)
* License: GPL-3.0 (upstream `LICENSE`), copyright notices retained upstream.
* Revision studied: [`44e0d2e`](https://github.com/JunkFood02/Seal/commit/44e0d2e).
* Origin in FloWave: `SealStyleDownloadEngine` is a FloWave adapter around the
  same embedded Android yt-dlp lifecycle pattern used by Seal. No Seal source,
  icons, name, or branding is copied into the application.

Seal's upstream README requests that derivatives do not use the Seal name as
the name of a downloader application. FloWave is independently named and does
not imply endorsement by Seal or its authors.

## Runtime dependencies

* [`io.github.junkfood02.youtubedl-android:library:0.17.3`](https://github.com/yausername/youtubedl-android)
* [`io.github.junkfood02.youtubedl-android:ffmpeg:0.17.3`](https://github.com/yausername/youtubedl-android)

These artifacts are GPL-3.0 licensed and are declared explicitly in
`gradle/libs.versions.toml`. Their own notices are distributed by Gradle in
the dependency metadata and remain applicable to the packaged application.
FFmpeg retains its upstream notices inside the Android artifact; FloWave does
not relicence that component. Aria2c is intentionally not bundled because the
current downloader does not invoke it; removing that unused native payload
keeps architecture-specific APKs small without changing user-facing features.

## Compliance notes

The complete corresponding source for FloWave is this repository, including
the build files and this notice. When distributing a binary, provide this
repository (or an equivalent source offer) and preserve `LICENSE` and this
file. Do not remove upstream copyright or license notices from any source or
binary dependency.
