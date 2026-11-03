package blue.anika.wolle.ui.gauge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.util.GaugeCalculator
import blue.anika.wolle.data.util.GaugeResult
import blue.anika.wolle.data.util.SwatchSize
import blue.anika.wolle.data.util.formatCm
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.stitchedBorder

/** The most stitches/rows drawn in a swatch preview; denser gauges just look "very dense". */
private const val MAX_DRAWN_STITCHES = 32
private const val MAX_DRAWN_ROWS = 40

/**
 * Gauge calculator (SNA-11, cute pass SNA-89): two swatch cards - the pattern's and yours - each
 * with a little knitted square that gets denser or looser as you type, then the pattern's counts.
 * Results appear as soon as there is enough to calculate (no Calculate button), with how far they
 * are from the pattern. Pure client-side maths via [GaugeCalculator], so it works offline.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GaugeCalculatorScreen(onBack: () -> Unit) {
    var patternStitches by rememberSaveable { mutableStateOf("") }
    var patternRows by rememberSaveable { mutableStateOf("") }
    var userStitches by rememberSaveable { mutableStateOf("") }
    var userRows by rememberSaveable { mutableStateOf("") }
    var castOnStitches by rememberSaveable { mutableStateOf("") }
    var rowCount by rememberSaveable { mutableStateOf("") }
    // Patterns almost always give gauge per 10 × 10 cm; the size sits behind the "10 × 10 cm"
    // label under each swatch for the times it doesn't (or you measured a smaller swatch).
    var patternWidth by rememberSaveable { mutableStateOf(SwatchSize.DEFAULT.widthCm) }
    var patternHeight by rememberSaveable { mutableStateOf(SwatchSize.DEFAULT.heightCm) }
    var userWidth by rememberSaveable { mutableStateOf(SwatchSize.DEFAULT.widthCm) }
    var userHeight by rememberSaveable { mutableStateOf(SwatchSize.DEFAULT.heightCm) }
    val patternSwatch = SwatchSize(patternWidth, patternHeight)
    val userSwatch = SwatchSize(userWidth, userHeight)

    val result =
        gaugeResultOrNull(
            patternStitches,
            patternRows,
            userStitches,
            userRows,
            castOnStitches,
            rowCount,
            patternSwatch,
            userSwatch,
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gauge_calculator_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        // With the keyboard open the result card is hidden underneath it, so a compact copy of
        // the result rides on top of the keyboard while typing.
        bottomBar = {
            AnimatedVisibility(
                visible = result != null && WindowInsets.isImeVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                result?.let { CompactResultBar(it) }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { MascotIntro() }
            item {
                SwatchCard(
                    title = stringResource(R.string.gauge_calculator_pattern_gauge_title),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
                    yarn = MaterialTheme.colorScheme.tertiary,
                    stitches = patternStitches,
                    onStitchesChange = { patternStitches = it },
                    rows = patternRows,
                    onRowsChange = { patternRows = it },
                    swatch = patternSwatch,
                    onSwatchChange = {
                        patternWidth = it.widthCm
                        patternHeight = it.heightCm
                    },
                )
            }
            item {
                // Same marigold as the pattern's swatch above: both come from the pattern.
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.gauge_calculator_pattern_counts_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        NumberField(
                            value = castOnStitches,
                            onValueChange = { castOnStitches = it },
                            label = stringResource(R.string.gauge_calculator_cast_on_label),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        NumberField(
                            value = rowCount,
                            onValueChange = { rowCount = it },
                            label = stringResource(R.string.gauge_calculator_rows_to_knit_label),
                            modifier = Modifier.fillMaxWidth(),
                            last = true,
                        )
                    }
                }
            }
            item {
                SwatchCard(
                    title = stringResource(R.string.gauge_calculator_your_gauge_title),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
                    yarn = MaterialTheme.colorScheme.secondary,
                    stitches = userStitches,
                    onStitchesChange = { userStitches = it },
                    rows = userRows,
                    onRowsChange = { userRows = it },
                    swatch = userSwatch,
                    onSwatchChange = {
                        userWidth = it.widthCm
                        userHeight = it.heightCm
                    },
                )
            }
            item {
                AnimatedContent(
                    targetState = result,
                    transitionSpec = {
                        (fadeIn() +
                            scaleIn(
                                initialScale = 0.92f,
                                animationSpec =
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            )) togetherWith fadeOut()
                    },
                    contentKey = { it != null },
                    label = "gauge-result",
                ) { shown ->
                    if (shown != null) ResultCard(shown) else WaitingHint()
                }
            }
        }
    }
}

/** The result for the typed values, or null while something required is missing or zero. */
internal fun gaugeResultOrNull(
    patternStitches: String,
    patternRows: String,
    userStitches: String,
    userRows: String,
    castOn: String,
    rowCount: String,
    patternSwatch: SwatchSize = SwatchSize.DEFAULT,
    userSwatch: SwatchSize = SwatchSize.DEFAULT,
): GaugeResult? {
    val ps = patternStitches.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val pr = patternRows.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val us = userStitches.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val ur = userRows.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val co = castOn.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val rows =
        if (rowCount.isBlank()) null else rowCount.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return GaugeCalculator.calculate(ps, pr, us, ur, co, rows, patternSwatch, userSwatch)
}

/** "+4" / "−3" / "±0" for how far a result is from the pattern. */
internal fun signedDifference(value: Int, reference: Int): String {
    val difference = value - reference
    return when {
        difference > 0 -> "+$difference"
        difference < 0 -> "−${-difference}"
        else -> "±0"
    }
}

@Composable
private fun MascotIntro() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mascot(size = 84.dp, bob = true)
        Surface(
            shape =
                RoundedCornerShape(
                    topStart = 6.dp,
                    topEnd = 22.dp,
                    bottomEnd = 22.dp,
                    bottomStart = 22.dp,
                ),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(R.string.gauge_calculator_description),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SwatchCard(
    title: String,
    container: Color,
    onContainer: Color,
    yarn: Color,
    stitches: String,
    onStitchesChange: (String) -> Unit,
    rows: String,
    onRowsChange: (String) -> Unit,
    swatch: SwatchSize,
    onSwatchChange: (SwatchSize) -> Unit,
) {
    var editingSize by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = onContainer,
        modifier =
            Modifier.fillMaxWidth()
                .stitchedBorder(onContainer.copy(alpha = 0.3f), cornerRadius = 29.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    KnittedSwatch(
                        stitches = stitches.toIntOrNull() ?: 0,
                        rows = rows.toIntOrNull() ?: 0,
                        yarn = yarn,
                        background = yarn.copy(alpha = 0.16f),
                        size = 92.dp,
                        aspect = (swatch.widthCm / swatch.heightCm).toFloat(),
                    )
                    SwatchSizeButton(swatch = swatch, onClick = { editingSize = true })
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NumberField(
                        value = stitches,
                        onValueChange = onStitchesChange,
                        label = stringResource(R.string.gauge_calculator_stitches_label),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NumberField(
                        value = rows,
                        onValueChange = onRowsChange,
                        label = stringResource(R.string.gauge_calculator_rows_label),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    if (editingSize) {
        SwatchSizeDialog(
            title = title,
            current = swatch,
            onDismiss = { editingSize = false },
            onConfirm = {
                onSwatchChange(it)
                editingSize = false
            },
        )
    }
}

/** "10 × 10 cm ✎" under a swatch: shows the measured size and opens [SwatchSizeDialog]. */
@Composable
private fun SwatchSizeButton(swatch: SwatchSize, onClick: () -> Unit) {
    val label = "${formatCm(swatch.widthCm)} \u00d7 ${formatCm(swatch.heightCm)} cm"
    val description = stringResource(R.string.gauge_calculator_swatch_size_change, label)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color =
            if (swatch.isDefault) Color.Transparent
            else LocalContentColor.current.copy(alpha = 0.12f),
        modifier =
            Modifier.padding(top = 2.dp).heightIn(min = 36.dp).semantics {
                contentDescription = description
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(13.dp),
            )
        }
    }
}

/** Width and height the gauge was measured over, with a one-tap way back to 10 × 10 cm. */
@Composable
private fun SwatchSizeDialog(
    title: String,
    current: SwatchSize,
    onDismiss: () -> Unit,
    onConfirm: (SwatchSize) -> Unit,
) {
    var width by rememberSaveable { mutableStateOf(formatCm(current.widthCm)) }
    var height by rememberSaveable { mutableStateOf(formatCm(current.heightCm)) }
    val parsedWidth = SwatchSize.parseCm(width)
    val parsedHeight = SwatchSize.parseCm(height)
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.gauge_calculator_swatch_size_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = {
                            width = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5)
                        },
                        label = { Text(stringResource(R.string.gauge_calculator_swatch_width)) },
                        suffix = { Text("cm") },
                        isError = parsedWidth == null,
                        singleLine = true,
                        keyboardOptions = decimal,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = {
                            height = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5)
                        },
                        label = { Text(stringResource(R.string.gauge_calculator_swatch_height)) },
                        suffix = { Text("cm") },
                        isError = parsedHeight == null,
                        singleLine = true,
                        keyboardOptions = decimal.copy(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(
                    onClick = {
                        width = formatCm(SwatchSize.DEFAULT.widthCm)
                        height = formatCm(SwatchSize.DEFAULT.heightCm)
                    }
                ) {
                    Text(stringResource(R.string.gauge_calculator_swatch_size_reset))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedWidth != null && parsedHeight != null,
                onClick = { onConfirm(SwatchSize(parsedWidth!!, parsedHeight!!)) },
            ) {
                Text(stringResource(R.string.common_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * A tiny stockinette square: one "V" per stitch, as many columns and rows as typed (capped), so a
 * tighter gauge visibly looks denser. Decorative - screen readers get the numbers from the fields.
 */
@Composable
private fun KnittedSwatch(
    stitches: Int,
    rows: Int,
    yarn: Color,
    background: Color,
    size: Dp,
    aspect: Float = 1f,
) {
    val columns = stitches.coerceIn(0, MAX_DRAWN_STITCHES)
    val lines = rows.coerceIn(0, MAX_DRAWN_ROWS)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = background,
        // The longer side is [size]; a 10 × 5 cm swatch is drawn half as tall.
        modifier =
            Modifier.size(
                    width = if (aspect >= 1f) size else size * aspect,
                    height = if (aspect >= 1f) size / aspect else size,
                )
                .clearAndSetSemantics {},
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (columns == 0 || lines == 0) {
                Text("?", style = MaterialTheme.typography.headlineMedium, color = yarn)
            } else {
                Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                    val cellWidth = this.size.width / columns
                    val cellHeight = this.size.height / lines
                    val stroke = (minOf(cellWidth, cellHeight) * 0.28f).coerceIn(0.8f, 5f)
                    for (row in 0 until lines) {
                        // Alternate shades a little so rows read like real knitting.
                        val shade = if (row % 2 == 0) yarn else yarn.copy(alpha = 0.78f)
                        for (column in 0 until columns) {
                            val left = column * cellWidth
                            val top = row * cellHeight
                            val bottom = Offset(left + cellWidth / 2, top + cellHeight * 0.95f)
                            drawLine(
                                shade,
                                Offset(left + cellWidth * 0.12f, top + cellHeight * 0.1f),
                                bottom,
                                stroke,
                                StrokeCap.Round,
                            )
                            drawLine(
                                shade,
                                Offset(left + cellWidth * 0.88f, top + cellHeight * 0.1f),
                                bottom,
                                stroke,
                                StrokeCap.Round,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    last: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = if (last) ImeAction.Done else ImeAction.Next,
            ),
    )
}

@Composable
private fun WaitingHint() {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.gauge_calculator_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun ResultCard(result: GaugeResult) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier =
            Modifier.fillMaxWidth()
                .stitchedBorder(
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.35f),
                    cornerRadius = 29.dp,
                ),
    ) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 22.dp)) {
            Text(
                stringResource(R.string.gauge_calculator_results_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ResultStat(
                    label = stringResource(R.string.gauge_calculator_adjusted_stitches_label),
                    value = result.adjustedStitches.toString(),
                    difference =
                        signedDifference(result.adjustedStitches, result.patternCastOnStitches),
                    modifier = Modifier.weight(1f),
                )
                ResultStat(
                    label = stringResource(R.string.gauge_calculator_adjusted_rows_label),
                    value =
                        result.adjustedRows?.toString()
                            ?: stringResource(R.string.gauge_calculator_not_available),
                    difference =
                        result.adjustedRows?.let { rows ->
                            result.patternRowCount?.let { signedDifference(rows, it) }
                        },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                result.patternRowCount?.let {
                    stringResource(
                        R.string.gauge_calculator_summary_with_rows,
                        result.patternCastOnStitches,
                        it,
                    )
                }
                    ?: stringResource(
                        R.string.gauge_calculator_summary_without_rows,
                        result.patternCastOnStitches,
                    ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun ResultStat(label: String, value: String, difference: String?, modifier: Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (difference != null) {
                Text(
                    stringResource(R.string.gauge_calculator_difference, difference),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One line of results above the keyboard: "Cast on 90 (−20) · Knit rows 48 (−12)". */
@Composable
private fun CompactResultBar(result: GaugeResult) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        modifier = Modifier.fillMaxWidth().imePadding(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            CompactStat(
                label = stringResource(R.string.gauge_calculator_adjusted_stitches_label),
                value = result.adjustedStitches.toString(),
                difference =
                    signedDifference(result.adjustedStitches, result.patternCastOnStitches),
                modifier = Modifier.weight(1f),
            )
            result.adjustedRows?.let { rows ->
                CompactStat(
                    label = stringResource(R.string.gauge_calculator_adjusted_rows_label),
                    value = rows.toString(),
                    difference = result.patternRowCount?.let { signedDifference(rows, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CompactStat(label: String, value: String, difference: String?, modifier: Modifier) {
    Row(verticalAlignment = Alignment.Bottom, modifier = modifier) {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (difference != null) {
                    Text(
                        difference,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 6.dp, bottom = 5.dp),
                    )
                }
            }
        }
    }
}
