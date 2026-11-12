/** The public home of Stricknani, linked from the "set up your own" card. */
export const REPOSITORY_URL = "https://github.com/pschmitt/stricknani";

/**
 * A ready-made prompt for Claude Code, Codex or opencode. It is the same text
 * as the README section "Set it up with an AI agent" and the Android string
 * `setup_prompt`; `setup-prompt.test.ts` keeps the three in sync.
 */
export const SETUP_PROMPT = `Set up a private Stricknani instance for me. Stricknani is a self-hosted knitting project manager: https://github.com/pschmitt/stricknani

1. Clone the repository and read README.md and docs/install.md first. Follow them.
2. Deploy it with Docker (pull the published GHCR image and keep the data volume), or with the NixOS module services.stricknani if this machine runs NixOS.
3. Create the first administrator with "stricknani-server create-admin EMAIL" (stricknani-cli on NixOS). Pass the password on standard input, at least 12 characters, never on the command line.
4. Set TRUSTED_HOSTS to the exact host name people will open, for example stricknani.example.com.
5. Put it behind an HTTPS reverse proxy and set SESSION_COOKIE_SECURE=true. Do not expose port 7674 directly.
6. Leave public registration off (SIGNUP_ENABLED unset). Create accounts for other people with "create-user EMAIL".
7. Check /healthz, sign in at https://stricknani.example.com/app/, then tell me what you did and what is left for me (DNS, backups).`;
