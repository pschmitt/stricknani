package blue.anika.wolle.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R

/**
 * A playful full-screen error: the mascot with a mood, a status chip, a headline, a body and a
 * footnote joke, then the ways out. Use it where a whole screen has nothing else to show (a missing
 * project, no access, the server or the network down); keep small inline notices for the rest. The
 * copy is the web app's.
 *
 * [seed] picks the footnote joke and stays stable across recompositions (use an id or a route).
 * Exactly one button is the primary one: [onRetry] if given, else [onBack], else [onHome].
 */
@Composable
fun ErrorState(
    kind: ErrorKind,
    modifier: Modifier = Modifier,
    seed: String = kind.name,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = stringResource(R.string.error_go_back),
    onHome: (() -> Unit)? = null,
) {
    val quipRes = remember(kind, seed) { pickQuip(seed, kind.quips) }
    val actions =
        buildList<ErrorAction> {
            onRetry?.let { add(ErrorAction(stringResource(R.string.error_try_again), it)) }
            onBack?.let { add(ErrorAction(backLabel, it)) }
            onHome?.let { add(ErrorAction(stringResource(R.string.error_back_home), it)) }
        }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Mascot(size = 132.dp, reactive = false, mood = kind.mood)
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(
                    text = stringResource(kind.kicker),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Text(
                text = stringResource(kind.title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
            Text(
                text = stringResource(kind.body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 360.dp),
            )
            if (quipRes != null) {
                Text(
                    text = stringResource(quipRes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp).widthIn(max = 320.dp),
                )
            }
            actions.forEachIndexed { index, action ->
                val buttonModifier = Modifier.padding(top = if (index == 0) 24.dp else 8.dp)
                if (index == 0) {
                    Button(onClick = action.onClick, modifier = buttonModifier) {
                        Text(action.label)
                    }
                } else {
                    OutlinedButton(onClick = action.onClick, modifier = buttonModifier) {
                        Text(action.label)
                    }
                }
            }
        }
    }
}

private class ErrorAction(val label: String, val onClick: () -> Unit)

/**
 * The last refresh failure as a panel kind, kept until a refresh succeeds: the refresh state itself
 * is cleared as soon as its snackbar is dismissed, which would make a full-screen panel flip back
 * to the "missing" copy for no reason.
 */
@Composable
fun rememberRefreshFailure(state: RefreshState): ErrorKind? {
    var failure by remember { mutableStateOf<ErrorKind?>(null) }
    LaunchedEffect(state) {
        val kind = state.errorKind()
        when {
            kind != null -> failure = kind
            state is RefreshState.Finished -> failure = null
        }
    }
    return failure
}
