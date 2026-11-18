package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Inflater

data class PdfSearchHit(
    val pageIndex: Int,
    val snippet: String
)

/**
 * Native Android PDF rendering, thumbnail extraction, offline text search, and PrintManager integration.
 * Operates exclusively on verified locally-decrypted temporary files and never touches external services.
 */
class PdfReaderHelper {

    suspend fun getPageCount(pdfFile: File): Int = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.pageCount
            }
        }
    }

    suspend fun renderPageBitmap(
        pdfFile: File,
        pageIndex: Int,
        targetWidthPx: Int
    ): Bitmap = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val safeIndex = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                renderer.openPage(safeIndex).use { page ->
                    val aspect = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                    val width = targetWidthPx.coerceIn(300, 2400)
                    val height = (width * aspect).toInt().coerceAtLeast(100)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    /**
     * Lightweight offline PDF content-stream text search across uncompressed and FlateDecode streams.
     */
    suspend fun searchTextInPdf(pdfFile: File, query: String): List<PdfSearchHit> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.length < 2 || !pdfFile.exists()) return@withContext emptyList()
            val results = mutableListOf<PdfSearchHit>()
            val rawBytes = pdfFile.readBytes()
            val rawLatin = String(rawBytes, Charsets.ISO_8859_1)

            // Scan PDF content streams
            var pageApprox = 0
            val streamRegex = Regex("stream\\r?\\n([\\s\\S]*?)\\r?\\nendstream")
            val textLiteralRegex = Regex("\\(([^()\\\\]{2,200})\\)\\s*Tj")

            val matches = streamRegex.findAll(rawLatin)
            for (match in matches) {
                val streamContent = match.groupValues[1]
                val streamBytes = streamContent.toByteArray(Charsets.ISO_8859_1)
                val decodedText = tryInflate(streamBytes) ?: streamContent
                if (decodedText.contains("BT") && decodedText.contains("ET")) {
                    val extracted = StringBuilder()
                    for (lit in textLiteralRegex.findAll(decodedText)) {
                        extracted.append(lit.groupValues[1]).append(' ')
                    }
                    val pageText = extracted.toString()
                    if (pageText.contains(cleanQuery, ignoreCase = true)) {
                        val idx = pageText.indexOf(cleanQuery, ignoreCase = true)
                        val start = (idx - 32).coerceAtLeast(0)
                        val end = (idx + cleanQuery.length + 48).coerceAtMost(pageText.length)
                        results.add(
                            PdfSearchHit(
                                pageIndex = pageApprox,
                                snippet = pageText.substring(start, end).trim()
                            )
                        )
                    }
                    pageApprox++
                }
            }
            results.take(30)
        }

    private fun tryInflate(compressed: ByteArray): String? {
        return try {
            val inflater = Inflater()
            inflater.setInput(compressed)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                bos.write(buf, 0, count)
                if (bos.size() > 2 * 1024 * 1024) break
            }
            inflater.end()
            val out = bos.toByteArray()
            if (out.isEmpty()) null else String(out, Charsets.ISO_8859_1)
        } catch (_: Exception) {
            null
        }
    }

    fun printDecryptedPdf(context: Context, decryptedPdfFile: File, documentTitle: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("$documentTitle.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    FileInputStream(decryptedPdfFile).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { out ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                if (cancellationSignal?.isCanceled == true) {
                                    callback.onWriteCancelled()
                                    return
                                }
                                val read = input.read(buf)
                                if (read == -1) break
                                out.write(buf, 0, read)
                            }
                            out.flush()
                        }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        printManager.print("PDFVault_$documentTitle", adapter, PrintAttributes.Builder().build())
    }
}
