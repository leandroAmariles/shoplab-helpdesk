import base64
import io
import shutil
import zipfile
from pathlib import Path

import httpx
import pytest

from olist_loader.config import Settings
from olist_loader.download import REQUIRED_FILES, obtain_dataset
from olist_loader.errors import DownloadError


def zip_bytes(source: Path, skip: tuple[str, ...] = ()) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name in REQUIRED_FILES:
            if name not in skip:
                archive.write(source / name, arcname=name)
    return buffer.getvalue()


def client_returning(body: bytes, status: int = 200, seen: list[httpx.Request] | None = None) -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append(request)
        return httpx.Response(status, content=body)

    return httpx.Client(transport=httpx.MockTransport(handler), follow_redirects=True)


def client_without_network() -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError(f"unexpected request to {request.url}")

    return httpx.Client(transport=httpx.MockTransport(handler))


def settings_for(tmp_path: Path, **overrides) -> Settings:
    return Settings(data_dir=tmp_path, **overrides)


def test_the_required_files_are_the_eight_csv_of_the_dataset():
    assert len(REQUIRED_FILES) == 8
    assert "olist_geolocation_dataset.csv" not in REQUIRED_FILES


def test_existing_csv_files_are_reused_without_touching_the_network(tmp_path, fixtures_dir):
    shutil.copytree(fixtures_dir, tmp_path / "olist")

    folder = obtain_dataset(settings_for(tmp_path), None, False, client=client_without_network())

    assert folder == tmp_path / "olist"


def test_source_accepts_a_folder_without_the_network(tmp_path, fixtures_dir):
    folder = obtain_dataset(settings_for(tmp_path), fixtures_dir, False, client=client_without_network())

    assert all((folder / name).is_file() for name in REQUIRED_FILES)


def test_source_accepts_a_zip_and_extracts_it_into_the_data_dir(tmp_path, fixtures_dir):
    archive = tmp_path / "given.zip"
    archive.write_bytes(zip_bytes(fixtures_dir))

    folder = obtain_dataset(settings_for(tmp_path / "data"), archive, False, client=client_without_network())

    assert folder == tmp_path / "data" / "olist"
    assert all((folder / name).is_file() for name in REQUIRED_FILES)


def test_download_without_credentials_fails_naming_the_variables(tmp_path):
    with pytest.raises(DownloadError, match="KAGGLE_USERNAME"):
        obtain_dataset(settings_for(tmp_path), None, False, client=client_without_network())


def test_download_authenticates_with_basic_auth_and_extracts_the_archive(tmp_path, fixtures_dir):
    seen: list[httpx.Request] = []
    client = client_returning(zip_bytes(fixtures_dir), seen=seen)
    settings = settings_for(tmp_path, kaggle_username="someone", kaggle_key="not-a-real-key")

    folder = obtain_dataset(settings, None, False, client=client)

    expected = "Basic " + base64.b64encode(b"someone:not-a-real-key").decode()
    assert len(seen) == 1 and seen[0].headers["Authorization"] == expected
    assert "olistbr/brazilian-ecommerce" in str(seen[0].url)
    assert (tmp_path / "olist.zip").is_file()
    assert all((folder / name).is_file() for name in REQUIRED_FILES)


@pytest.mark.parametrize("status", [401, 403, 404, 500])
def test_a_failing_http_status_is_reported_with_its_code(tmp_path, status):
    settings = settings_for(tmp_path, kaggle_username="someone", kaggle_key="not-a-real-key")

    with pytest.raises(DownloadError, match=str(status)):
        obtain_dataset(settings, None, False, client=client_returning(b"", status=status))


def test_a_corrupt_archive_is_reported_and_leaves_no_partial_csv(tmp_path):
    settings = settings_for(tmp_path, kaggle_username="someone", kaggle_key="not-a-real-key")

    with pytest.raises(DownloadError, match="corrupt|corrupto"):
        obtain_dataset(settings, None, False, client=client_returning(b"this is not a zip"))

    assert not list((tmp_path / "olist").glob("*.csv"))


def test_an_archive_missing_a_file_names_the_missing_file(tmp_path, fixtures_dir):
    missing = "olist_sellers_dataset.csv"
    settings = settings_for(tmp_path, kaggle_username="someone", kaggle_key="not-a-real-key")

    with pytest.raises(DownloadError, match=missing):
        obtain_dataset(settings, None, False, client=client_returning(zip_bytes(fixtures_dir, skip=(missing,))))


def test_skip_download_fails_when_the_csv_files_are_not_there(tmp_path):
    with pytest.raises(DownloadError, match="--skip-download"):
        obtain_dataset(settings_for(tmp_path), None, True, client=client_without_network())


def test_the_credentials_never_appear_in_the_settings_repr():
    text = repr(Settings(kaggle_username="someone", kaggle_key="not-a-real-key", postgres_password="hidden-value"))

    assert "not-a-real-key" not in text and "hidden-value" not in text
