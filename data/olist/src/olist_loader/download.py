"""Obtains the Olist CSV files: reuses what is already on disk, takes a given archive, or downloads from Kaggle."""

import shutil
import zipfile
from pathlib import Path

import httpx

from olist_loader.config import Settings
from olist_loader.errors import DownloadError

DATASET = "olistbr/brazilian-ecommerce"
DOWNLOAD_URL = f"https://www.kaggle.com/api/v1/datasets/download/{DATASET}"
ARCHIVE_NAME = "olist.zip"
FOLDER_NAME = "olist"

# The geolocation file is left out on purpose (specs/LAB-17, FR-016).
REQUIRED_FILES = (
    "olist_customers_dataset.csv",
    "olist_orders_dataset.csv",
    "olist_order_items_dataset.csv",
    "olist_order_payments_dataset.csv",
    "olist_order_reviews_dataset.csv",
    "olist_products_dataset.csv",
    "product_category_name_translation.csv",
    "olist_sellers_dataset.csv",
)


def obtain_dataset(
    settings: Settings, source: Path | None, skip_download: bool, client: httpx.Client | None = None
) -> Path:
    """Returns the folder that holds every required CSV file, fetching them only when they are not already there."""
    target = settings.data_dir / FOLDER_NAME
    if source is not None:
        return _from_source(source, target)
    if not _missing(target):
        return target
    if skip_download:
        raise DownloadError(
            f"Faltan los archivos CSV en {target} y se indicó --skip-download; "
            "quita la opción o copia el dataset a esa carpeta."
        )
    if not settings.kaggle_username or not settings.kaggle_key:
        raise DownloadError(
            "No se puede descargar el dataset: faltan las variables KAGGLE_USERNAME y KAGGLE_KEY. "
            "Defínelas, o usa --source con un ZIP o una carpeta ya descargados."
        )
    archive = settings.data_dir / ARCHIVE_NAME
    _download(settings.kaggle_username, settings.kaggle_key, archive, client)
    _extract(archive, target)
    return target


def _missing(folder: Path) -> list[str]:
    return [name for name in REQUIRED_FILES if not (folder / name).is_file()]


def _from_source(source: Path, target: Path) -> Path:
    if source.is_dir():
        missing = _missing(source)
        if missing:
            raise DownloadError(f"Faltan archivos en {source}: {', '.join(missing)}.")
        return source
    if source.is_file():
        _extract(source, target)
        return target
    raise DownloadError(f"No existe --source {source}.")


def _download(username: str, key: str, archive: Path, client: httpx.Client | None) -> None:
    archive.parent.mkdir(parents=True, exist_ok=True)
    partial = archive.with_suffix(".part")
    owned = client is None
    http = client or httpx.Client(follow_redirects=True, timeout=httpx.Timeout(60.0, read=300.0))
    try:
        with http.stream("GET", DOWNLOAD_URL, auth=(username, key)) as response:
            if response.status_code != 200:
                raise DownloadError(_describe_status(response.status_code))
            with partial.open("wb") as handle:
                for chunk in response.iter_bytes():
                    handle.write(chunk)
        partial.replace(archive)
    except httpx.HTTPError as error:
        raise DownloadError(f"No se pudo descargar el dataset de Kaggle: {error.__class__.__name__}.") from error
    finally:
        partial.unlink(missing_ok=True)
        if owned:
            http.close()


def _describe_status(status: int) -> str:
    if status in (401, 403):
        return (
            f"Kaggle rechazó las credenciales (HTTP {status}); revisa KAGGLE_USERNAME y KAGGLE_KEY "
            f"y que hayas aceptado las condiciones del dataset {DATASET}."
        )
    return f"Kaggle respondió con HTTP {status} al descargar el dataset {DATASET}."


def _extract(archive: Path, target: Path) -> None:
    """Extracts only the required files, and only once all of them are known to be in the archive."""
    try:
        with zipfile.ZipFile(archive) as bundle:
            members = {Path(name).name: name for name in bundle.namelist() if not name.endswith("/")}
            absent = [name for name in REQUIRED_FILES if name not in members]
            if absent:
                raise DownloadError(f"El archivo {archive.name} no contiene: {', '.join(absent)}.")
            target.mkdir(parents=True, exist_ok=True)
            for name in REQUIRED_FILES:
                with bundle.open(members[name]) as origin, (target / name).open("wb") as destination:
                    shutil.copyfileobj(origin, destination)
    except zipfile.BadZipFile as error:
        raise DownloadError(f"El archivo {archive.name} está corrupto o no es un ZIP válido.") from error
