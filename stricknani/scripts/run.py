"""Main entry point for Stricknani application."""

import sys


def main() -> None:
    """Run the Stricknani application with uvicorn."""
    import uvicorn

    # Get port from environment or use default
    import os

    port = int(os.getenv("PORT", "7674"))
    host = os.getenv("HOST", "0.0.0.0")

    # Run the application
    uvicorn.run(
        "stricknani.main:app",
        host=host,
        port=port,
        reload=False,
    )


if __name__ == "__main__":
    main()
