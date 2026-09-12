package com.example.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.data.model.SlideEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfExporter {

    /**
     * Compiles selected slides in order into a single PDF document.
     */
    suspend fun exportSlidesToPdf(
        context: Context,
        sessionTitle: String,
        slides: List<SlideEntity>,
        pageWidth: Int = 1190, // A4 landscape at 144 DPI: 1190 x 842
        pageHeight: Int = 842,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        val safeTitle = sessionTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Presentation" }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val outputFile = File(outputDir, "${safeTitle}_$timeStamp.pdf")

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 18f
        }

        try {
            val total = slides.size
            slides.forEachIndexed { index, slide ->
                onProgress(index + 1, total)

                val file = File(slide.filePath)
                if (!file.exists()) return@forEachIndexed

                // Load and downscale bitmap if huge to prevent OutOfMemory
                val bitmap = decodeSampledBitmap(file.absolutePath, pageWidth, pageHeight) ?: return@forEachIndexed

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                // Fill clean page background
                canvas.drawColor(Color.WHITE)

                // Fit slide bitmap inside margins
                val margin = 24f
                val footerHeight = 32f
                val availableWidth = pageWidth - (margin * 2)
                val availableHeight = pageHeight - (margin * 2) - footerHeight

                val imgRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val targetRatio = availableWidth / availableHeight

                val drawWidth: Float
                val drawHeight: Float
                if (imgRatio > targetRatio) {
                    drawWidth = availableWidth
                    drawHeight = availableWidth / imgRatio
                } else {
                    drawHeight = availableHeight
                    drawWidth = availableHeight * imgRatio
                }

                val drawX = margin + (availableWidth - drawWidth) / 2f
                val drawY = margin + (availableHeight - drawHeight) / 2f

                val destRect = RectF(drawX, drawY, drawX + drawWidth, drawY + drawHeight)
                canvas.drawBitmap(bitmap, null, destRect, paint)

                // Page numbering & session footer
                val footerText = "Slide ${index + 1} of $total  •  $sessionTitle"
                canvas.drawText(footerText, margin, pageHeight - 14f, textPaint)

                pdfDocument.finishPage(page)
                bitmap.recycle()
            }

            FileOutputStream(outputFile).use { outStream ->
                pdfDocument.writeTo(outStream)
            }
        } finally {
            pdfDocument.close()
        }

        outputFile
    }

    /**
     * Builds a share Intent for the exported PDF.
     */
    fun createShareIntent(context: Context, pdfFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, pdfFile.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Builds a view Intent to open the exported PDF in an external PDF reader.
     */
    fun createViewIntent(context: Context, pdfFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun decodeSampledBitmap(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)

        var inSampleSize = 1
        val height = options.outHeight
        val width = options.outWidth

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(filePath, decodeOptions)
    }
}
