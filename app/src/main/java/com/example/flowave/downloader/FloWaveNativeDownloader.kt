package com.example.flowave.downloader

/** Public download states shared by the foreground service and UI. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Initializing : DownloadState
    data object Preparing : DownloadState
    data class Downloading(val progress: Float, val speed: String, val eta: String) : DownloadState
    data class PostProcessing(val step: String) : DownloadState
    data class Success(val outputFilePath: String) : DownloadState
    data object Cancelled : DownloadState
    data class Error(val message: String) : DownloadState
}
