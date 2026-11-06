# Project timer API (v2)

The stopwatch is shared state on a project, not a personal timer. Every
authorized reader sees the same accumulated total and running state. The
server stores a whole-second total and an optional UTC start time; while
running, responses include elapsed time through `server_time` without requiring
periodic writes.

## Routes

`GET /api/v2/projects/{id}/timer` reads the timer. `PUT` on the same route
accepts one of these bodies:

```json
{"action":"start"}
{"action":"stop"}
{"action":"set_total","total_seconds":3600}
```

All successful responses use the standard envelope:

```json
{"data":{"total_seconds":3600,"started_at":null,"server_time":"2026-11-05T10:00:00Z"}}
```

`total_seconds` is a nonnegative integer, including elapsed time while the
timer runs. `started_at` is the active baseline in UTC RFC 3339 form, or
`null` when stopped. `server_time` is the UTC RFC 3339 instant used to compute
the returned total. The maximum total is 315,576,000 seconds (ten 365.25-day
years). Values outside `0..=315576000` are rejected with `422 validation_error`.

`start` and `stop` are idempotent, so retrying a request does not restart an
already-running timer or add an interval twice. `set_total` replaces the
accumulated value; if the timer is running it resets the active baseline to
the current server time and keeps the timer running.

## Access and client behaviour

Any project reader may `GET` the timer. Mutations require project edit access:
the owner, an active administrator, or an editor share. Viewers receive
`403 forbidden` when mutating. Browser cookie mutations require the usual
same-origin and CSRF checks; API-purpose Bearer PATs use the standard v2
authentication rules.

The server-side timer keeps time while the app is closed. The web reminder is
shown only while the project page is open. Android schedules a background
notification and checks this API before showing it. These reminders are client
notifications; neither client owns or advances a separate timer.
