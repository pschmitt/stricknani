"""Main entry point for Stricknani application."""



def main() -> None:
    """Run the Stricknani application with uvicorn."""
    # Get port from environment or use default
    import os

    import uvicorn

    port = int(os.getenv("BIND_PORT", "7674"))
    host = os.getenv("BIND_HOST", "127.0.0.1")

    # Run the application
    uvicorn.run(
        "stricknani.main:app",
        host=host,
        port=port,
        reload=False,
    )


if __name__ == "__main__":
    main()
