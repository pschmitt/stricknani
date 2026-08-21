(() => {
	const endpoint = "/__dev__/reload-token";
	const pollIntervalMs = 1500;
	let initialToken = null;

	const pollToken = async () => {
		try {
			const response = await fetch(endpoint, {
				cache: "no-store",
				headers: { Accept: "application/json" },
			});

			if (!response.ok) {
				return;
			}

			const payload = await response.json();
			const nextToken = payload?.token;
			if (typeof nextToken !== "string" || !nextToken) {
				return;
			}

			if (initialToken === null) {
				initialToken = nextToken;
				return;
			}

			if (nextToken !== initialToken) {
				window.location.reload();
			}
		} catch {
			// Ignore transient network errors while the dev server is restarting.
		}
	};

	pollToken();
	window.setInterval(pollToken, pollIntervalMs);
})();
