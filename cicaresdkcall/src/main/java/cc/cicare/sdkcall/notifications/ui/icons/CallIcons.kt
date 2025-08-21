package cc.cicare.sdkcall.notifications.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val SpeakerBluetooth: ImageVector
    get() = ImageVector.Builder(
        name = "SpeakerBluetooth",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        group(
            scaleX = 1.5f,
            scaleY = 1.5f,
            pivotX = 0f,
            pivotY = 0f
        ) {

            path(
                fill = SolidColor(Color(0xFF808080)),
                pathFillType = PathFillType.Companion.NonZero
            ) {
                moveTo(7f, 1.334f)
                lineTo(3.5f, 5f)
                lineTo(0.871f, 5f)
                curveTo(0f, 5.894f, 0f, 8.002f, 0f, 8.002f)
                curveTo(0f, 10.11f, 0.871f, 11f, 0.871f, 11f)
                lineTo(3.5f, 11f)
                lineTo(7f, 14.666f)
                close()
            }

            path(
                fill = SolidColor(Color(0xFF808080)),
                pathFillType = PathFillType.Companion.NonZero
            ) {
                moveTo(12f, 1.303f)
                lineTo(15.989f, 5.541f)
                lineTo(13.3f, 8.01f)
                lineTo(15.988f, 10.478f)
                lineTo(12f, 14.717f)
                lineTo(12f, 9.103f)
                lineTo(9.572f, 11.143f)
                lineTo(8.928f, 10.377f)
                lineTo(11.746f, 8.01f)
                lineTo(8.928f, 5.643f)
                lineTo(9.572f, 4.877f)
                lineTo(12f, 6.916f)
                close()
                moveTo(13f, 3.717f)
                lineTo(13f, 6.957f)
                lineTo(14.508f, 5.477f)
                close()
                moveTo(13f, 9.062f)
                lineTo(13f, 12.302f)
                lineTo(14.508f, 10.542f)
                close()
            }

            path(
                fill = SolidColor(Color(0xFF808080)),
                pathFillType = PathFillType.Companion.NonZero
            ) {
                moveTo(7f, 1.334f)
                lineTo(3.5f, 5f)
                lineTo(0.871f, 5f)
                curveTo(0f, 5.894f, 0f, 8.002f, 0f, 8.002f)
                curveTo(0f, 10.11f, 0.871f, 11f, 0.871f, 11f)
                lineTo(3.5f, 11f)
                lineTo(7f, 14.666f)
                close()
                moveTo(6f, 3.828f)
                lineTo(6f, 12.172f)
                lineTo(3.928f, 10f)
                lineTo(1.414f, 10f)
                curveTo(1.382f, 9.941f, 1.394f, 9.984f, 1.348f, 9.883f)
                curveTo(1.185f, 9.52f, 1f, 8.918f, 1f, 8.002f)
                curveTo(1f, 7.085f, 1.185f, 6.482f, 1.348f, 6.117f)
                curveTo(1.393f, 6.016f, 1.382f, 6.059f, 1.414f, 6f)
                lineTo(3.928f, 6f)
                close()
            }
        }
    }.build()