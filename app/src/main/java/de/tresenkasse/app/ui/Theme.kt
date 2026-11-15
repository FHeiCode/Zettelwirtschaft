package de.tresenkasse.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

val Amber = Color(0xFFE0A030)
val Wood = Color(0xFF3B2A1E)

private val Light = lightColorScheme(
    primary = Color(0xFF8A5A12), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0), onPrimaryContainer = Color(0xFF2C1800),
    secondary = Color(0xFF4F6B3A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3EDBE), onSecondaryContainer = Color(0xFF102000),
    background = Color(0xFFFFF8F2), surface = Color(0xFFFFF8F2),
)
private val Dark = darkColorScheme(
    primary = Amber, onPrimary = Color(0xFF2C1800),
    primaryContainer = Color(0xFF6A4300), onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFB7D1A3), onSecondary = Color(0xFF233517),
    secondaryContainer = Color(0xFF394F28), onSecondaryContainer = Color(0xFFD3EDBE),
    background = Color(0xFF1C1410), surface = Color(0xFF1C1410),
)

@Composable
fun TresenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** QR-Code als Bitmap (schwarz auf weiß, mit Ruhezone). */
fun qrBitmap(text: String, size: Int = 800): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
    )
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val px = IntArray(m.width * m.height)
    for (y in 0 until m.height) for (x in 0 until m.width) {
        px[y * m.width + x] = if (m[x, y]) AColor.BLACK else AColor.WHITE
    }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}
