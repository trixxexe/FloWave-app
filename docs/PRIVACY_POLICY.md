# FloWave Privacy Policy (Draft)

_This document is a general implementation-based draft for review and is not legal advice._

## What FloWave does

FloWave is an Android music player. It can play audio imported from device storage, audio downloaded by the app, and online results resolved from YouTube-compatible services through the app's network clients.

## Information stored on the device

The app stores music metadata, persisted document URIs and playback/library state in its local Room database. Preferences such as appearance, playback and profile settings are stored with Android DataStore. Queue, favorites, playlists, listening statistics and recent-play information are local app data. Diagnostic logs and crash reports are also stored locally when generated.

FloWave does not require an account and does not intentionally collect names, contacts, credentials or private file contents. Imported audio is referenced through Android document/content URIs where possible; the app does not need to copy an imported file merely to index it.

## Network communication

Online search, artwork, lyrics, stream resolution and download operations can make network requests to YouTube/InnerTube-compatible endpoints, configured fallback services, and image hosts represented by returned metadata. Network requests can include the search or video identifier needed for the requested operation. Expiring media URLs are resolved at playback time and are not intended to be stored as library identity.

The app does not send its local library, playlists, listening statistics or diagnostic exports to FloWave servers. There is no FloWave account or analytics backend in the current implementation.

## Diagnostics and crash data

Diagnostic logs are kept on the device, rotated and cleaned according to the in-app diagnostics behavior. The structured logger sanitizes credentials, cookies, token-like values and URL query parameters before persistence. A user may export logs through Android's document picker. Exported files are under the user's control and should be handled carefully.

## Permissions and third parties

Android media/storage permissions and user-selected Storage Access Framework permissions are used to discover or import audio. Internet and foreground-media permissions support online playback and background playback. The app includes Media3, Coil, Room, DataStore, InnerTube/network clients and the yt-dlp/FFmpeg Android libraries; their own licenses and policies may also apply. FloWave does not claim ownership of third-party content.

## Changes and contact

This draft describes the repository implementation at publication time. Review the source and update this document before a public release, including a jurisdiction-specific contact and any future service or analytics changes.
