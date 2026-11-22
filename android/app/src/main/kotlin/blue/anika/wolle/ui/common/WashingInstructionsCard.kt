package blue.anika.wolle.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.Drying
import blue.anika.wolle.data.api.dto.WashTemperature
import blue.anika.wolle.data.api.dto.WashingSymbols

@Composable
fun WashingInstructionsCard(
    value: String,
    symbols: WashingSymbols?,
    onSymbolsChange: ((WashingSymbols) -> Unit)? = null,
    onValueChange: ((String) -> Unit)? = null,
) {
    val label = stringResource(R.string.washing_instructions)
    val handWash = stringResource(R.string.washing_hand_wash_only)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.secondaryContainer.copy(alpha = 0.28f),
        contentColor = colors.onSurface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(shape = CircleShape, color = colors.secondaryContainer) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            PhosphorIcons.Laundry,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.onSecondaryContainer,
                        )
                    }
                }
                Text(
                    label,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            val selected = symbols ?: WashingSymbols()
            if (onSymbolsChange == null && !selected.isEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    selected.temperature?.let {
                        WashingSymbolLabel(
                            null,
                            washTemperatureLabel(it, handWash),
                            handWash = it == WashTemperature.HAND_WASH,
                        )
                    }
                    selected.drying?.let { WashingSymbolLabel(it, stringResource(dryingLabel(it))) }
                }
            } else if (onSymbolsChange != null) {
                CareSelect(
                    value = selected.temperature,
                    options = WashTemperature.entries,
                    label = stringResource(R.string.washing_temperature),
                    optionLabel = { washTemperatureLabel(it, handWash) },
                    optionIcon = { option ->
                        WashingSymbol(
                            null,
                            handWash = option == WashTemperature.HAND_WASH,
                            clear = option == null,
                        )
                    },
                    onChange = { onSymbolsChange(selected.copy(temperature = it)) },
                )
                CareSelect(
                    value = selected.drying,
                    options = Drying.entries,
                    label = stringResource(R.string.washing_drying),
                    optionLabel = { stringResource(dryingLabel(it)) },
                    optionIcon = { option -> WashingSymbol(option, clear = option == null) },
                    onChange = { onSymbolsChange(selected.copy(drying = it)) },
                )
            }
            if (onValueChange != null) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                    minLines = 3,
                )
            } else if (value.isNotBlank()) {
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

fun washTemperatureLabel(temperature: WashTemperature, handWash: String): String =
    temperature.degrees?.let { "$it °C" } ?: handWash

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Any> CareSelect(
    value: T?,
    options: List<T>,
    label: String,
    optionLabel: @Composable (T) -> String,
    optionIcon: @Composable (T?) -> Unit,
    onChange: (T?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val blank = stringResource(R.string.washing_no_selection)
    val colors = MaterialTheme.colorScheme
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (value == null) blank else optionLabel(value),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            label = { Text(label) },
            leadingIcon = { CareIconChip { optionIcon(value) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier.fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.large,
            containerColor = colors.surfaceContainerHigh,
        ) {
            (listOf(null) + options).forEach { option ->
                val selected = option == value
                DropdownMenuItem(
                    text = { Text(if (option == null) blank else optionLabel(option)) },
                    leadingIcon = { CareIconChip { optionIcon(option) } },
                    trailingIcon = {
                        if (selected)
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = colors.secondary,
                            )
                    },
                    modifier =
                        Modifier.background(
                                if (selected) colors.secondaryContainer.copy(alpha = 0.4f)
                                else colors.surfaceContainerHigh
                            )
                            .semantics { this.selected = selected },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun CareIconChip(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { content() }
    }
}

fun dryingLabel(drying: Drying): Int =
    when (drying) {
        Drying.FLAT -> R.string.washing_dry_flat
        Drying.LINE -> R.string.washing_dry_line
        Drying.NO_TUMBLE -> R.string.washing_no_tumble
    }

@Composable
private fun WashingSymbolLabel(drying: Drying?, label: String, handWash: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WashingSymbol(drying, handWash = handWash)
        Text(label)
    }
}

@Composable
private fun WashingSymbol(drying: Drying?, handWash: Boolean = false, clear: Boolean = false) {
    val color = LocalContentColor.current
    val tub =
        remember(handWash) {
            val path =
                if (handWash) {
                    "m3 9 3 11h12l3-11M8 13V7a1 1 0 0 1 2 0v3-6a1 1 0 0 1 2 0v5-3a1 1 0 0 1 2 0v4-2a1 1 0 0 1 2 0v4l-2 4h-3"
                } else {
                    "m3 6 3 13h12l3-13M4.1 10c3.4-4 5.2 4 8.4 0 3.1-4 4.5 4 7.4 0"
                }
            PathParser().parsePathString(path).toPath()
        }
    Canvas(Modifier.size(20.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (clear) {
                drawLine(color, Offset(6f, 12f), Offset(18f, 12f), 1.6f, StrokeCap.Round)
            } else if (drying == null) {
                drawPath(tub, color, style = stroke)
            } else {
                drawRoundRect(
                    color,
                    Offset(4f, 4f),
                    Size(16f, 16f),
                    CornerRadius(1.5f),
                    style = stroke,
                )
                when (drying) {
                    Drying.FLAT ->
                        drawLine(
                            color,
                            Offset(8f, 12f),
                            Offset(16f, 12f),
                            1.6f,
                            StrokeCap.Round,
                        )
                    Drying.LINE ->
                        drawLine(
                            color,
                            Offset(12f, 8f),
                            Offset(12f, 16f),
                            1.6f,
                            StrokeCap.Round,
                        )
                    Drying.NO_TUMBLE -> {
                        drawCircle(color, 5f, Offset(12f, 12f), style = stroke)
                        drawLine(color, Offset(5f, 5f), Offset(19f, 19f), 1.6f, StrokeCap.Round)
                        drawLine(color, Offset(19f, 5f), Offset(5f, 19f), 1.6f, StrokeCap.Round)
                    }
                }
            }
        }
    }
}
