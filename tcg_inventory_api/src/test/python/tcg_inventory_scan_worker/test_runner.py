"""Pytest entrypoint for the scan worker tests."""

import os

import pytest


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    raise SystemExit(pytest.main(["-q", here]))
