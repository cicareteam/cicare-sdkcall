package cc.cicare.sdkcall.notifications.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ErrorAlertDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    withIcon: Boolean, // opsional
    message: String? = null,
    content: @Composable (() -> Unit)? = null
) {
    if (showDialog) {
        Dialog(
            onDismissRequest = {
                onDismiss()
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(Color.White, shape = RoundedCornerShape(5.dp))
                    .padding(all = 10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // icon opsional di atas tengah
                    if (withIcon) {
                        Icon(
                            painter = painterResource(id = cc.cicare.sdkcall.R.drawable.error),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.height(24.dp).width(20.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    message?.let {
                        Text(
                            text = it,
                            color = Color.Black,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Jika ada custom composable content
                    content?.invoke()
                }
            }
        }
    }
}
