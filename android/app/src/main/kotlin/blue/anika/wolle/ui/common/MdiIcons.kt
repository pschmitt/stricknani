package blue.anika.wolle.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
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

    /**
     * Ball of yarn: Phosphor Icons "yarn" (regular, MIT, see `assets/licenses`), the same path as
     * the web `NavIcon` "yarns" glyph. Padded to a 288 viewport so it sits like the other icons.
     */
    val YarnBall: ImageVector by lazy {
        ImageVector.Builder(
                name = "PhosphorYarn",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 288f,
                viewportHeight = 288f,
            )
            .apply {
                addPath(
                    pathData =
                        PathParser()
                            .parsePathString(
                                "M232,216H183.39A103.95,103.95,0,1,0,128,232l104,0a8,8,0,1,0,0-16ZM128,40a87.51,8" +
                                    "7.51,0,0,1,43.93,11.77,222.06,222.06,0,0,0-27.88,15.09,222.23,222.23,0,0,0-45-22" +
                                    "A87.52,87.52,0,0,1,128,40ZM78.56,55.24a206,206,0,0,1,51.11,21.57A225.76,225.76,0" +
                                    ",0,0,110.1,93.36,181.54,181.54,0,0,0,57.73,75.09,88.67,88.67,0,0,1,78.56,55.24ZM" +
                                    "48.72,89.82a165.82,165.82,0,0,1,49.67,15.51A228,228,0,0,0,82.76,124.5,142.65,142" +
                                    ".65,0,0,0,41.28,113,87.5,87.5,0,0,1,48.72,89.82ZM40,129a126.07,126.07,0,0,1,33.6" +
                                    "3,9,222.36,222.36,0,0,0-19.07,38.45A87.51,87.51,0,0,1,40,129Zm26.42,61.81A209.36" +
                                    ",209.36,0,0,1,187,62.74a89,89,0,0,1,16.22,19.57A183.89,183.89,0,0,0,87,205.82,88" +
                                    ".56,88.56,0,0,1,66.43,190.81ZM125.66,216A87.66,87.66,0,0,1,101.83,212,167.84,167" +
                                    ".84,0,0,1,210.28,96.79a87.35,87.35,0,0,1,5.38,23.55A144.59,144.59,0,0,0,125.66,2" +
                                    "16Zm89.82-78.44a88.19,88.19,0,0,1-72.67,77.22A128.64,128.64,0,0,1,215.48,137.53Z"
                            )
                            .toNodes(),
                    fill = SolidColor(Color.Black),
                    pathFillType = PathFillType.NonZero,
                )
            }
            .build()
    }
}
