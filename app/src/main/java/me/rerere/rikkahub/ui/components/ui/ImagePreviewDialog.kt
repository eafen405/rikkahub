package me.rerere.rikkahub.ui.components.ui

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.compose.rememberAsyncImagePainter
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.koinInject

@Composable
fun ImagePreviewDialog(
    images: List<String>,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val client: OkHttpClient = koinInject()
    var imageToSave by remember { mutableStateOf<String?>(null) }
    val state = rememberZoomablePagerState { images.size }
    var notice by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val source = imageToSave
        imageToSave = null
        if (uri != null && source != null) lifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = checkNotNull(context.contentResolver.openOutputStream(uri))
                    output.use { target ->
                        when {
                            source.startsWith("data:") -> target.write(Base64.decode(source.substringAfter("base64,"), Base64.DEFAULT))
                            source.startsWith("http://") || source.startsWith("https://") -> {
                                client.newCall(Request.Builder().url(source).build()).execute().use { response ->
                                    check(response.isSuccessful) { "图片下载失败：${response.code}" }
                                    response.body.byteStream().use { it.copyTo(target) }
                                }
                            }
                            source.startsWith("/") -> File(source).inputStream().use { it.copyTo(target) }
                            else -> checkNotNull(context.contentResolver.openInputStream(source.toUri())).use { it.copyTo(target) }
                        }
                    }
                }
                notice = "已保存图片"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice = error.message ?: "图片保存失败"
            }
        }
    }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box {
            ImagePager(
                modifier = Modifier.fillMaxSize(),
                pagerState = state,
                imageLoader = { index ->
                    val painter = rememberAsyncImagePainter(images[index])
                    return@ImagePager Pair(painter, painter.intrinsicSize)
                },
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = {
                        notice = null
                        imageToSave = images[state.currentPage]
                        saveImage.launch("image")
                    }
                ) {
                    Icon(HugeIcons.Download01, "保存图片", tint = Color.White)
                }
                notice?.let { Text(it, color = Color.White) }
            }
        }
    }
}
