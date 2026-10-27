"""Errors of the loader; each one maps to a process exit code (see specs/LAB-17 contracts/loader-cli.md)."""


class LoaderError(Exception):
    """Base of the loader errors; the message is shown to the user as is."""

    exit_code = 1


class DownloadError(LoaderError):
    """The dataset cannot be obtained: no network, no credentials or a corrupt archive."""

    exit_code = 3


class DatabaseNotReadyError(LoaderError):
    """Schemas, tables or migrations are missing; the backend must start first to apply them."""

    exit_code = 4


class LoadError(LoaderError):
    """The load failed; the transaction of the affected schema was rolled back."""

    exit_code = 5
