"""Typed errors of the analysis. Messages are in Spanish: they are read by whoever runs the notebook."""


class OlistEdaError(Exception):
    """Base class of every error this package raises on purpose."""


class PreflightError(OlistEdaError):
    """The database is not ready to be analysed; the message says what is missing and how to fix it."""


class PolicyParseError(OlistEdaError):
    """A canonical table of the policy corpus could not be found or has no values."""


class MissingActionError(OlistEdaError):
    """A finding has neither a specific nor a per-kind action in discrepancy-actions.toml."""
