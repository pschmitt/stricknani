package blue.anika.wolle.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Material Design Icons that are not included in Compose's extended icon set. */
object MdiIcons {
    /** Material Design Icons 7.4.47 `mdi:sheep` icon. */
    val Sheep: ImageVector by lazy {
        ImageVector.Builder(
                name = "MdiSheep",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
            .apply {
                addPath(
                    pathData =
                        PathParser()
                            .parsePathString(
                                "M20,8.5A2.5,2.5 0 0,1 17.5,11C16.42,11 15.5,10.31 " +
                                    "15.16,9.36C14.72,9.75 14.14,10 13.5,10C12.94,10 " +
                                    "12.42,9.81 12,9.5C11.58,9.81 11.07,10 10.5,10C9.86,10 " +
                                    "9.28,9.75 8.84,9.36C8.5,10.31 7.58,11 6.5,11A2.5,2.5 " +
                                    "0 0,1 4,8.5C4,7.26 4.91,6.23 6.1,6.04C6.04,5.87 " +
                                    "6,5.69 6,5.5A1.5,1.5 0 0,1 7.5,4C7.7,4 7.89,4.04 " +
                                    "8.06,4.11C8.23,3.47 8.81,3 9.5,3C9.75,3 10,3.07 " +
                                    "10.18,3.17C10.5,2.5 11.19,2 12,2C12.81,2 13.5,2.5 " +
                                    "13.82,3.17C14,3.07 14.25,3 14.5,3C15.19,3 15.77,3.47 " +
                                    "15.94,4.11C16.11,4.04 16.3,4 16.5,4A1.5,1.5 0 0,1 " +
                                    "18,5.5C18,5.69 17.96,5.87 17.9,6.04C19.09,6.23 20,7.26 " +
                                    "20,8.5M10,12A1,1 0 0,0 9,13A1,1 0 0,0 10,14A1,1 0 0,0 " +
                                    "11,13A1,1 0 0,0 10,12M14,12A1,1 0 0,0 13,13A1,1 0 0,0 " +
                                    "14,14A1,1 0 0,0 15,13A1,1 0 0,0 14,12M20.23,10.66C19.59,11.47 " +
                                    "18.61,12 17.5,12C17.05,12 16.62,11.9 16.21,11.73C16.2,14.28 " +
                                    "15.83,17.36 14.45,18.95C13.93,19.54 13.3,19.86 12.5,19.96V18H11.5V19.96 " +
                                    "C10.7,19.86 10.07,19.55 9.55,18.95C8.16,17.35 7.79,14.29 7.78,11.74 " +
                                    "C7.38,11.9 6.95,12 6.5,12C5.39,12 4.41,11.47 3.77,10.66C2.88,11.55 " +
                                    "2,12 2,12C2,12 3,14 5,14C5.36,14 5.64,13.96 5.88,13.91C6.22,17.73 " +
                                    "7.58,22 12,22C16.42,22 17.78,17.73 18.12,13.91C18.36,13.96 18.64,14 " +
                                    "19,14C21,14 22,12 22,12C22,12 21.12,11.55 20.23,10.66Z"
                            )
                            .toNodes(),
                    fill = SolidColor(Color.Black),
                )
            }
            .build()
    }

    /** Material Design Icons 7.4.47 `mdi:language-markdown-outline` icon. */
    val Markdown: ImageVector by lazy {
        ImageVector.Builder(
                name = "MdiMarkdown",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
            .apply {
                addPath(
                    pathData =
                        PathParser()
                            .parsePathString(
                                "M20.56 18H3.44C2.65 18 2 17.37 2 16.59V7.41C2 6.63 2.65 6 3.44 6H20.56" +
                                    "C21.35 6 22 6.63 22 7.41V16.59C22 17.37 21.35 18 20.56 18M3.44 6.94C3." +
                                    "18 6.94 2.96 7.15 2.96 7.41V16.6C2.96 16.85 3.18 17.06 3.44 17.06H20.5" +
                                    "6C20.82 17.06 21.04 16.85 21.04 16.6V7.41C21.04 7.15 20.82 6.94 20.56 " +
                                    "6.94H3.44M4.89 15.19V8.81H6.81L8.73 11.16L10.65 8.81H12.57V15.19H10.65" +
                                    "V11.53L8.73 13.88L6.81 11.53V15.19H4.89M16.88 15.19L14 12.09H15.92V8.8" +
                                    "1H17.84V12.09H19.76L16.88 15.19Z"
                            )
                            .toNodes(),
                    fill = SolidColor(Color.Black),
                )
            }
            .build()
    }

    /** Ball of yarn: Phosphor Icons "yarn", kept under this name for its many callers. */
    val YarnBall: ImageVector
        get() = PhosphorIcons.Yarn
}
