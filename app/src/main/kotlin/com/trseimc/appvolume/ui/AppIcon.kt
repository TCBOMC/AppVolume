package com.trseimc.appvolume.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

@Composable
fun AppIcon(packageName: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap: Bitmap? = remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName).toBitmap()
        } catch (e: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size)
        )
    } else {
        Spacer(modifier = modifier.size(size))
    }
}
