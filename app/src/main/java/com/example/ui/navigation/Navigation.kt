package com.example.ui.navigation

object Routes {
    const val HOME = "home"
    const val REGION_SELECTOR = "region_selector"
    const val ACTIVE_CAPTURE = "active_capture"
    const val REVIEW_GALLERY = "review_gallery/{sessionId}"
    const val PDF_EXPORT = "pdf_export/{sessionId}"
    const val SETTINGS = "settings"

    fun reviewGallery(sessionId: Long) = "review_gallery/$sessionId"
    fun pdfExport(sessionId: Long) = "pdf_export/$sessionId"
}
