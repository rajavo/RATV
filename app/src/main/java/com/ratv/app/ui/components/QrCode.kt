package com.ratv.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** [text] as a QR code: black on white with a quiet border, so any phone camera reads it. */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            )
        }.getOrNull()
    }
    Canvas(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(10.dp)
            .semantics { contentDescription = description }
    ) {
        val m = matrix ?: return@Canvas
        val cell = size.minDimension / maxOf(m.width, m.height)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m.get(x, y)) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
                }
            }
        }
    }
}
