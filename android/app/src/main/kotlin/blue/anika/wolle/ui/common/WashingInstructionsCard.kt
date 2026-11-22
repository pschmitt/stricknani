package blue.anika.wolle.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
                    if (selected.handWashOnly == true) {
                        WashingSymbolLabel(null, handWash)
                    } else {
                        selected.temperature?.let {
                            WashingSymbolLabel(null, washTemperatureLabel(it))
                        }
                    }
                    selected.drying?.let { WashingSymbolLabel(it, stringResource(dryingLabel(it))) }
                }
            } else if (onSymbolsChange != null) {
                WashTemperatureSelect(selected) { temperature, only ->
                    onSymbolsChange(selected.copy(temperature = temperature, handWashOnly = only))
                }
                Text(
                    stringResource(R.string.washing_drying),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Drying.entries.forEach { option ->
                        FilterChip(
                            selected = selected.drying == option,
                            onClick = {
                                onSymbolsChange(
                                    selected.copy(
                                        drying = option.takeUnless { it == selected.drying }
                                    )
                                )
                            },
                            label = {
                                WashingSymbolLabel(option, stringResource(dryingLabel(option)))
                            },
                        )
                    }
                }
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

fun washTemperatureLabel(temperature: WashTemperature): String = "${temperature.degrees} °C"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WashTemperatureSelect(
    value: WashingSymbols,
    onChange: (WashTemperature?, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val blank = stringResource(R.string.washing_no_selection)
    val handWash = stringResource(R.string.washing_hand_wash_only)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value =
                if (value.handWashOnly == true) handWash
                else value.temperature?.let(::washTemperatureLabel) ?: blank,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.washing_temperature)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier.fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(blank) },
                onClick = {
                    onChange(null, false)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(handWash) },
                onClick = {
                    onChange(null, true)
                    expanded = false
                },
            )
            WashTemperature.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(washTemperatureLabel(option)) },
                    onClick = {
                        onChange(option, false)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

fun dryingLabel(drying: Drying): Int =
    when (drying) {
        Drying.FLAT -> R.string.washing_dry_flat
        Drying.LINE -> R.string.washing_dry_line
        Drying.NO_TUMBLE -> R.string.washing_no_tumble
    }

@Composable
private fun WashingSymbolLabel(drying: Drying?, label: String) {
    val color = LocalContentColor.current
    val tub = remember {
        PathParser()
            .parsePathString("m3 6 3 13h12l3-13M4.1 10c3.4-4 5.2 4 8.4 0 3.1-4 4.5 4 7.4 0")
            .toPath()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(20.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                val stroke = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                if (drying == null) {
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
        Text(label)
    }
}
