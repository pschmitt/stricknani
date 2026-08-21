package blue.anika.wolle.ui.gauge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.util.GaugeCalculator
import blue.anika.wolle.data.util.GaugeResult

/**
 * Ports `stricknani/routes/gauge.py`'s calculator UI (see `templates/gauge/calculator.html`) - pure
 * client-side math via `GaugeCalculator`, no ViewModel/network call needed (SNA-11).
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

    val patternStitchesValue = patternStitches.toIntOrNull()
    val patternRowsValue = patternRows.toIntOrNull()
    val userStitchesValue = userStitches.toIntOrNull()
    val userRowsValue = userRows.toIntOrNull()
    val castOnStitchesValue = castOnStitches.toIntOrNull()
    val rowCountValue = rowCount.toIntOrNull()

    val canCalculate =
        (patternStitchesValue ?: 0) > 0 &&
            (patternRowsValue ?: 0) > 0 &&
            (userStitchesValue ?: 0) > 0 &&
            (userRowsValue ?: 0) > 0 &&
            (castOnStitchesValue ?: 0) > 0 &&
            (rowCount.isBlank() || (rowCountValue ?: 0) > 0)

    var result by remember { mutableStateOf<GaugeResult?>(null) }

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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.gauge_calculator_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Text(
                    stringResource(R.string.gauge_calculator_pattern_gauge_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item {
                GaugeRow(
                    stitches = patternStitches,
                    onStitchesChange = { patternStitches = it },
                    rows = patternRows,
                    onRowsChange = { patternRows = it },
                )
            }
            item {
                Text(
                    stringResource(R.string.gauge_calculator_your_gauge_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item {
                GaugeRow(
                    stitches = userStitches,
                    onStitchesChange = { userStitches = it },
                    rows = userRows,
                    onRowsChange = { userRows = it },
                )
            }
            item {
                Text(
                    stringResource(R.string.gauge_calculator_pattern_counts_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item {
                OutlinedTextField(
                    value = castOnStitches,
                    onValueChange = { castOnStitches = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.gauge_calculator_cast_on_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            item {
                OutlinedTextField(
                    value = rowCount,
                    onValueChange = { rowCount = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.gauge_calculator_rows_to_knit_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            item {
                Button(
                    onClick = {
                        result =
                            GaugeCalculator.calculate(
                                patternGaugeStitches = patternStitchesValue!!,
                                patternGaugeRows = patternRowsValue!!,
                                userGaugeStitches = userStitchesValue!!,
                                userGaugeRows = userRowsValue!!,
                                patternCastOnStitches = castOnStitchesValue!!,
                                patternRowCount = rowCountValue,
                            )
                    },
                    enabled = canCalculate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.gauge_calculator_calculate_button))
                }
            }
            result?.let { r -> item { GaugeResultCard(r) } }
        }
    }
}

@Composable
private fun GaugeRow(
    stitches: String,
    onStitchesChange: (String) -> Unit,
    rows: String,
    onRowsChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = stitches,
            onValueChange = { onStitchesChange(it.filter(Char::isDigit)) },
            label = { Text(stringResource(R.string.gauge_calculator_stitches_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = rows,
            onValueChange = { onRowsChange(it.filter(Char::isDigit)) },
            label = { Text(stringResource(R.string.gauge_calculator_rows_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
private fun GaugeResultCard(result: GaugeResult) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.gauge_calculator_results_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                GaugeStat(
                    label = stringResource(R.string.gauge_calculator_adjusted_stitches_label),
                    value = result.adjustedStitches.toString(),
                )
                GaugeStat(
                    label = stringResource(R.string.gauge_calculator_adjusted_rows_label),
                    value =
                        result.adjustedRows?.toString()
                            ?: stringResource(R.string.gauge_calculator_not_available),
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
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun GaugeStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
