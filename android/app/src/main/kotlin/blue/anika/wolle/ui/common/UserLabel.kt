package blue.anika.wolle.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R

/*
 * THE way to show a person: wherever a user's name appears, render it with [UserLabel] (avatar +
 * name). The web has the same component (`UserLabel.svelte`, `user-label.ts`), so both read alike.
 */

/**
 * Who a name on screen stands for.
 * - [Self]: the signed-in viewer ("You").
 * - [User]: another account, by display name (never an email).
 * - [Admin]: an administrator whose identity is hidden from this viewer ("an administrator").
 * - [System]: no account caused it: the purge job, a restore the server ran ("System").
 * - [Unknown]: a legacy row without a readable actor ("Unknown").
 * - [Deleted]: an account that no longer exists ("a deleted account").
 */
enum class UserKind {
    Self,
    User,
    Admin,
    System,
    Unknown,
    Deleted,
}

/**
 * A person to show. [name] is the display name for [UserKind.User] (and [UserKind.Self], where the
 * label says "You" instead); [avatarUrl] is a server path or URL, resolved by [UserLabel].
 */
data class UserRef(val kind: UserKind, val name: String? = null, val avatarUrl: String? = null)

/** Maps the server's `actor.kind` string; null for a value this app version does not know. */
fun parseUserKind(value: String?): UserKind? =
    when (value) {
        "self" -> UserKind.Self
        "user" -> UserKind.User
        "admin" -> UserKind.Admin
        "system" -> UserKind.System
        "unknown" -> UserKind.Unknown
        else -> null
    }

/** The text of a label before it is resolved to a string resource. */
sealed interface UserLabelText {
    data object You : UserLabelText

    data object Admin : UserLabelText

    data object System : UserLabelText

    data object Unknown : UserLabelText

    data object DeletedAccount : UserLabelText

    data class Name(val name: String) : UserLabelText
}

/** "You", the display name, "an administrator", "System", "Unknown" or "a deleted account". */
fun userLabelText(user: UserRef): UserLabelText =
    when (user.kind) {
        UserKind.Self -> UserLabelText.You
        UserKind.Admin -> UserLabelText.Admin
        UserKind.System -> UserLabelText.System
        UserKind.Unknown -> UserLabelText.Unknown
        UserKind.Deleted -> UserLabelText.DeletedAccount
        UserKind.User ->
            user.name?.trim()?.takeIf { it.isNotEmpty() }?.let { UserLabelText.Name(it) }
                ?: UserLabelText.Unknown
    }

/** The string resource of an author phrase ("by You"), or null for a name ("by %1$s"). */
@StringRes
fun userByRes(text: UserLabelText): Int? =
    when (text) {
        UserLabelText.You -> R.string.user_by_you
        UserLabelText.Admin -> R.string.user_by_admin
        UserLabelText.System -> R.string.user_by_system
        UserLabelText.Unknown -> R.string.user_by_unknown
        UserLabelText.DeletedAccount -> R.string.user_by_deleted
        is UserLabelText.Name -> null
    }

@StringRes
private fun userNameRes(text: UserLabelText): Int? =
    when (text) {
        UserLabelText.You -> R.string.user_label_you
        UserLabelText.Admin -> R.string.user_label_admin
        UserLabelText.System -> R.string.user_label_system
        UserLabelText.Unknown -> R.string.user_label_unknown
        UserLabelText.DeletedAccount -> R.string.history_deleted_account
        is UserLabelText.Name -> null
    }

/** The label as a string: "You", "Anika Bergmann", "an administrator", "System", "Unknown". */
@Composable
fun userLabelString(user: UserRef): String {
    val text = userLabelText(user)
    return userNameRes(text)?.let { stringResource(it) } ?: (text as UserLabelText.Name).name
}

/** "by You", "by Anika Bergmann", "by an administrator", "by System": an author phrase. */
@Composable
fun userByString(user: UserRef): String {
    val text = userLabelText(user)
    return userByRes(text)?.let { stringResource(it) }
        ?: stringResource(R.string.history_by, (text as UserLabelText.Name).name)
}

/**
 * The size of a [UserLabel]: [Inline] in a meta line, [Compact] in list rows, [Regular] in headers.
 */
enum class UserLabelSize(val avatar: Dp) {
    Inline(18.dp),
    Compact(24.dp),
    Regular(36.dp),
}

/**
 * A person with their picture: the avatar (the profile picture, else the initial; a shield for an
 * administrator, a gear for the system, "?" when unknown) followed by the name.
 *
 * Parameters:
 * - [user]: who; see [UserRef] and [UserKind].
 * - [size]: [UserLabelSize.Inline] (18 dp avatar, meta lines), [UserLabelSize.Compact] (24 dp, the
 *   default, list rows) or [UserLabelSize.Regular] (36 dp, headers and cards).
 * - [by]: word it as an author ("by You", "by Anika Bergmann", "by an administrator").
 * - [style], [color]: the text; defaults to the body style in the content colour.
 *
 * The text is never an email: names come from the server's display-name helper.
 */
@Composable
fun UserLabel(
    user: UserRef,
    modifier: Modifier = Modifier,
    size: UserLabelSize = UserLabelSize.Compact,
    by: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    val text = if (by) userByString(user) else userLabelString(user)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            Arrangement.spacedBy(if (size == UserLabelSize.Regular) 10.dp else 6.dp),
    ) {
        UserAvatar(user, size.avatar)
        Text(text, style = style, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Just the avatar of a [UserRef], at [size]. */
@Composable
fun UserAvatar(user: UserRef, size: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val glyph =
        when (user.kind) {
            UserKind.Admin -> PhosphorIcons.ShieldCheck
            UserKind.System -> PhosphorIcons.GearSix
            else -> null
        }
    if (glyph != null) {
        Box(
            modifier.size(size).clip(CircleShape).background(colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                glyph,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(size * 0.62f),
            )
        }
        return
    }
    val known = user.kind == UserKind.User || user.kind == UserKind.Self
    AccountAvatar(
        url = if (known) LocalOwnerContext.current.resolveUrl(user.avatarUrl) else null,
        email = if (known) user.name else "?",
        size = size,
        modifier = modifier,
    )
}
