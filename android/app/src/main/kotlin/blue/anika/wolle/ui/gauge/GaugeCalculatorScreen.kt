package blue.anika.wolle.ui.gauge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.util.GaugeCalculator
import blue.anika.wolle.data.util.GaugeResult
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GaugeCalculatorScreen(onBack: () -> Unit) {
    var patternStitches by rememberSaveable { mutableStateOf("") }
    var patternRows by rememberSaveable { mutableStateOf("") }
    var userStitches by rememberSaveable { mutableStateOf("") }
    var userRows by rememberSaveable { mutableStateOf("") }
    var castOnStitches by rememberSaveable { mutableStateOf("") }
    var rowCount by rememberSaveable { mutableStateOf("") }

    val result =
        gaugeResultOrNull(
            patternStitches,
            patternRows,
            userStitches,
            userRows,
            castOnStitches,
            rowCount,
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
        }
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
                )
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
                )
            }
            item {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
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
): GaugeResult? {
    val ps = patternStitches.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val pr = patternRows.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val us = userStitches.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val ur = userRows.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val co = castOn.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val rows =
        if (rowCount.isBlank()) null else rowCount.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return GaugeCalculator.calculate(ps, pr, us, ur, co, rows)
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
) {
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
                        background = MaterialTheme.colorScheme.surfaceContainerLowest,
                        size = 92.dp,
                    )
                    Text(
                        "10 × 10 cm",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
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
}

/**
 * A tiny stockinette square: one "V" per stitch, as many columns and rows as typed (capped), so a
 * tighter gauge visibly looks denser. Decorative - screen readers get the numbers from the fields.
 */
@Composable
private fun KnittedSwatch(stitches: Int, rows: Int, yarn: Color, background: Color, size: Dp) {
    val columns = stitches.coerceIn(0, MAX_DRAWN_STITCHES)
    val lines = rows.coerceIn(0, MAX_DRAWN_ROWS)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = background,
        modifier = Modifier.size(size).clearAndSetSemantics {},
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
