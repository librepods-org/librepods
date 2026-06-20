package me.kavishdevar.librepods.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object MaterialIcons {
    val notifications: ImageVector
        get() {
            if (_notifications != null) {
                return _notifications!!
            }
            _notifications =
                ImageVector.Builder(
                    name = "notifications",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(4f, 19f)
                            verticalLineTo(17f)
                            horizontalLineTo(6f)
                            verticalLineTo(10f)
                            quadTo(6f, 7.93f, 7.25f, 6.31f)
                            reflectiveQuadTo(10.5f, 4.2f)
                            verticalLineTo(3.5f)
                            quadToRelative(0f, -0.63f, 0.44f, -1.06f)
                            reflectiveQuadTo(12f, 2f)
                            reflectiveQuadToRelative(1.06f, 0.44f)
                            reflectiveQuadTo(13.5f, 3.5f)
                            verticalLineTo(4.2f)
                            quadToRelative(2f, 0.5f, 3.25f, 2.11f)
                            reflectiveQuadTo(18f, 10f)
                            verticalLineToRelative(7f)
                            horizontalLineToRelative(2f)
                            verticalLineToRelative(2f)
                            horizontalLineTo(4f)
                            close()
                            moveToRelative(8f, -7.5f)
                            close()
                            moveTo(12f, 22f)
                            quadToRelative(-0.82f, 0f, -1.41f, -0.59f)
                            reflectiveQuadTo(10f, 20f)
                            horizontalLineToRelative(4f)
                            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                            reflectiveQuadTo(12f, 22f)
                            close()
                            moveTo(8f, 17f)
                            horizontalLineToRelative(8f)
                            verticalLineTo(10f)
                            quadTo(16f, 8.35f, 14.83f, 7.18f)
                            reflectiveQuadTo(12f, 6f)
                            reflectiveQuadTo(9.18f, 7.18f)
                            reflectiveQuadTo(8f, 10f)
                            verticalLineToRelative(7f)
                            close()
                        }
                    }
                    .build()
            return _notifications!!
        }

    private var _notifications: ImageVector? = null

    val headset_off: ImageVector
        get() {
            if (_headset_off != null) {
                return _headset_off!!
            }
            _headset_off =
                ImageVector.Builder(
                    name = "headset_off",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(21f, 18.15f)
                            lineToRelative(-2f, -2f)
                            verticalLineTo(14f)
                            horizontalLineTo(16.85f)
                            lineToRelative(-2f, -2f)
                            horizontalLineTo(19f)
                            verticalLineTo(11f)
                            quadTo(19f, 8.05f, 16.95f, 6.02f)
                            reflectiveQuadTo(12f, 4f)
                            quadTo(10.9f, 4f, 9.91f, 4.31f)
                            reflectiveQuadTo(8.1f, 5.2f)
                            lineTo(6.65f, 3.8f)
                            quadTo(7.78f, 2.92f, 9.14f, 2.46f)
                            reflectiveQuadTo(12f, 2f)
                            quadToRelative(1.85f, 0f, 3.49f, 0.7f)
                            reflectiveQuadToRelative(2.86f, 1.93f)
                            reflectiveQuadToRelative(1.94f, 2.86f)
                            reflectiveQuadTo(21f, 11f)
                            verticalLineToRelative(7.15f)
                            close()
                            moveTo(12f, 23f)
                            verticalLineTo(21f)
                            horizontalLineToRelative(6.18f)
                            lineToRelative(-1f, -1f)
                            horizontalLineTo(15f)
                            verticalLineTo(17.83f)
                            lineTo(5.53f, 8.35f)
                            quadTo(5.3f, 8.95f, 5.15f, 9.64f)
                            reflectiveQuadTo(5f, 11f)
                            verticalLineToRelative(1f)
                            horizontalLineTo(9f)
                            verticalLineToRelative(8f)
                            horizontalLineTo(5f)
                            quadTo(4.18f, 20f, 3.59f, 19.41f)
                            reflectiveQuadTo(3f, 18f)
                            verticalLineTo(11f)
                            quadTo(3f, 9.88f, 3.26f, 8.82f)
                            reflectiveQuadToRelative(0.76f, -2f)
                            lineTo(0.68f, 3.5f)
                            lineTo(2.1f, 2.1f)
                            lineTo(21.88f, 21.9f)
                            verticalLineTo(23f)
                            horizontalLineTo(12f)
                            close()
                            moveTo(5f, 18f)
                            horizontalLineTo(7f)
                            verticalLineTo(14f)
                            horizontalLineTo(5f)
                            verticalLineToRelative(4f)
                            close()
                            moveTo(5f, 14f)
                            horizontalLineTo(7f)
                            horizontalLineTo(5f)
                            close()
                            moveToRelative(11.85f, 0f)
                            horizontalLineTo(19f)
                            horizontalLineTo(16.85f)
                            close()
                        }
                    }
                    .build()
            return _headset_off!!
        }

    private var _headset_off: ImageVector? = null

    val pause: ImageVector
        get() {
            if (_pause != null) {
                return _pause!!
            }
            _pause =
                ImageVector.Builder(
                    name = "pause",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(13f, 19f)
                            verticalLineTo(5f)
                            horizontalLineToRelative(6f)
                            verticalLineTo(19f)
                            horizontalLineTo(13f)
                            close()
                            moveTo(5f, 19f)
                            verticalLineTo(5f)
                            horizontalLineToRelative(6f)
                            verticalLineTo(19f)
                            horizontalLineTo(5f)
                            close()
                            moveTo(15f, 17f)
                            horizontalLineToRelative(2f)
                            verticalLineTo(7f)
                            horizontalLineTo(15f)
                            verticalLineTo(17f)
                            close()
                            moveTo(7f, 17f)
                            horizontalLineTo(9f)
                            verticalLineTo(7f)
                            horizontalLineTo(7f)
                            verticalLineTo(17f)
                            close()
                            moveTo(7f, 7f)
                            verticalLineTo(17f)
                            verticalLineTo(7f)
                            close()
                            moveToRelative(8f, 0f)
                            verticalLineTo(17f)
                            verticalLineTo(7f)
                            close()
                        }
                    }
                    .build()
            return _pause!!
        }

    private var _pause: ImageVector? = null

    val bluetooth: ImageVector
        get() {
            if (_bluetooth != null) {
                return _bluetooth!!
            }
            _bluetooth =
                ImageVector.Builder(
                    name = "bluetooth",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(11f, 22f)
                            verticalLineTo(14.4f)
                            lineTo(6.4f, 19f)
                            lineTo(5f, 17.6f)
                            lineTo(10.6f, 12f)
                            lineTo(5f, 6.4f)
                            lineTo(6.4f, 5f)
                            lineTo(11f, 9.6f)
                            verticalLineTo(2f)
                            horizontalLineToRelative(1f)
                            lineToRelative(5.7f, 5.7f)
                            lineTo(13.4f, 12f)
                            lineToRelative(4.3f, 4.3f)
                            lineTo(12f, 22f)
                            horizontalLineTo(11f)
                            close()
                            moveTo(13f, 9.6f)
                            lineTo(14.9f, 7.7f)
                            lineTo(13f, 5.85f)
                            verticalLineTo(9.6f)
                            close()
                            moveToRelative(0f, 8.55f)
                            lineTo(14.9f, 16.3f)
                            lineTo(13f, 14.4f)
                            verticalLineToRelative(3.75f)
                            close()
                        }
                    }
                    .build()
            return _bluetooth!!
        }

    private var _bluetooth: ImageVector? = null

    val bluetooth_searching: ImageVector
        get() {
            if (_bluetooth_searching != null) {
                return _bluetooth_searching!!
            }
            _bluetooth_searching =
                ImageVector.Builder(
                    name = "bluetooth_searching",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(9f, 22f)
                            verticalLineTo(14.4f)
                            lineTo(4.4f, 19f)
                            lineTo(3f, 17.6f)
                            lineTo(8.6f, 12f)
                            lineTo(3f, 6.4f)
                            lineTo(4.4f, 5f)
                            lineTo(9f, 9.6f)
                            verticalLineTo(2f)
                            horizontalLineToRelative(1f)
                            lineToRelative(5.7f, 5.7f)
                            lineTo(11.4f, 12f)
                            lineToRelative(4.3f, 4.3f)
                            lineTo(10f, 22f)
                            horizontalLineTo(9f)
                            close()
                            moveTo(11f, 9.6f)
                            lineTo(12.9f, 7.7f)
                            lineTo(11f, 5.85f)
                            verticalLineTo(9.6f)
                            close()
                            moveToRelative(0f, 8.55f)
                            lineTo(12.9f, 16.3f)
                            lineTo(11f, 14.4f)
                            verticalLineToRelative(3.75f)
                            close()
                            moveToRelative(5.55f, -3.8f)
                            lineTo(14.25f, 12f)
                            lineToRelative(2.3f, -2.3f)
                            quadToRelative(0.23f, 0.55f, 0.36f, 1.13f)
                            reflectiveQuadTo(17.05f, 12f)
                            reflectiveQuadToRelative(-0.14f, 1.19f)
                            quadToRelative(-0.14f, 0.59f, -0.36f, 1.16f)
                            close()
                            moveTo(19.5f, 17.2f)
                            lineTo(18.25f, 16f)
                            quadToRelative(0.5f, -0.93f, 0.78f, -1.94f)
                            reflectiveQuadTo(19.3f, 12f)
                            reflectiveQuadTo(19.03f, 9.94f)
                            quadTo(18.75f, 8.92f, 18.25f, 8f)
                            lineTo(19.5f, 6.75f)
                            quadToRelative(0.73f, 1.2f, 1.11f, 2.52f)
                            reflectiveQuadTo(21f, 12f)
                            reflectiveQuadToRelative(-0.39f, 2.71f)
                            quadTo(20.23f, 16.02f, 19.5f, 17.2f)
                            close()
                        }
                    }
                    .build()
            return _bluetooth_searching!!
        }

    private var _bluetooth_searching: ImageVector? = null

    val call: ImageVector
        get() {
            if (_call != null) {
                return _call!!
            }
            _call =
                ImageVector.Builder(
                    name = "call",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.Companion.NonZero,
                        ) {
                            moveTo(19.95f, 21f)
                            quadToRelative(-3.13f, 0f, -6.18f, -1.36f)
                            reflectiveQuadTo(8.23f, 15.78f)
                            quadTo(5.73f, 13.27f, 4.36f, 10.23f)
                            reflectiveQuadTo(3f, 4.05f)
                            quadTo(3f, 3.6f, 3.3f, 3.3f)
                            reflectiveQuadTo(4.05f, 3f)
                            horizontalLineTo(8.1f)
                            quadTo(8.45f, 3f, 8.73f, 3.24f)
                            reflectiveQuadTo(9.05f, 3.8f)
                            lineTo(9.7f, 7.3f)
                            quadTo(9.75f, 7.7f, 9.68f, 7.97f)
                            reflectiveQuadTo(9.4f, 8.45f)
                            lineTo(6.98f, 10.9f)
                            quadToRelative(0.5f, 0.93f, 1.19f, 1.79f)
                            reflectiveQuadToRelative(1.51f, 1.66f)
                            quadToRelative(0.78f, 0.78f, 1.63f, 1.44f)
                            reflectiveQuadTo(13.1f, 17f)
                            lineToRelative(2.35f, -2.35f)
                            quadToRelative(0.22f, -0.23f, 0.59f, -0.34f)
                            reflectiveQuadToRelative(0.71f, -0.06f)
                            lineToRelative(3.45f, 0.7f)
                            quadToRelative(0.35f, 0.1f, 0.57f, 0.36f)
                            reflectiveQuadTo(21f, 15.9f)
                            verticalLineToRelative(4.05f)
                            quadToRelative(0f, 0.45f, -0.3f, 0.75f)
                            reflectiveQuadTo(19.95f, 21f)
                            close()
                            moveTo(6.03f, 9f)
                            lineTo(7.68f, 7.35f)
                            lineTo(7.25f, 5f)
                            horizontalLineTo(5.03f)
                            quadTo(5.15f, 6.02f, 5.38f, 7.02f)
                            reflectiveQuadTo(6.03f, 9f)
                            close()
                            moveToRelative(8.95f, 8.95f)
                            quadToRelative(0.97f, 0.43f, 1.99f, 0.68f)
                            reflectiveQuadTo(19f, 18.95f)
                            verticalLineToRelative(-2.2f)
                            lineTo(16.65f, 16.27f)
                            lineToRelative(-1.68f, 1.68f)
                            close()
                            moveTo(6.03f, 9f)
                            close()
                            moveToRelative(8.95f, 8.95f)
                            close()
                        }
                    }
                    .build()
            return _call!!
        }

    private var _call: ImageVector? = null

    val stack: ImageVector
        get() {
            if (_stack != null) {
                return _stack!!
            }
            _stack =
                ImageVector.Builder(
                    name = "stack",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.Companion.NonZero,
                        ) {
                            moveTo(6f, 14f)
                            verticalLineToRelative(2f)
                            horizontalLineTo(4f)
                            quadTo(3.18f, 16f, 2.59f, 15.41f)
                            reflectiveQuadTo(2f, 14f)
                            verticalLineTo(4f)
                            quadTo(2f, 3.17f, 2.59f, 2.59f)
                            reflectiveQuadTo(4f, 2f)
                            horizontalLineTo(14f)
                            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
                            reflectiveQuadTo(16f, 4f)
                            verticalLineTo(6f)
                            horizontalLineTo(14f)
                            verticalLineTo(4f)
                            horizontalLineTo(4f)
                            verticalLineTo(14f)
                            horizontalLineTo(6f)
                            close()
                            moveToRelative(4f, 8f)
                            quadTo(9.18f, 22f, 8.59f, 21.41f)
                            reflectiveQuadTo(8f, 20f)
                            verticalLineTo(10f)
                            quadTo(8f, 9.17f, 8.59f, 8.59f)
                            reflectiveQuadTo(10f, 8f)
                            horizontalLineTo(20f)
                            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
                            reflectiveQuadTo(22f, 10f)
                            verticalLineTo(20f)
                            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                            reflectiveQuadTo(20f, 22f)
                            horizontalLineTo(10f)
                            close()
                            moveToRelative(0f, -2f)
                            horizontalLineTo(20f)
                            verticalLineTo(10f)
                            horizontalLineTo(10f)
                            verticalLineTo(20f)
                            close()
                            moveToRelative(5f, -5f)
                            close()
                        }
                    }
                    .build()
            return _stack!!
        }

    private var _stack: ImageVector? = null
}
