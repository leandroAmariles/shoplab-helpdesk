import dataclasses
import shutil

from olist_loader.cli import run_pipeline

from .test_download import client_returning, client_without_network, zip_bytes
from .test_idempotence import counts


def test_a_second_run_with_the_files_on_disk_never_touches_the_network(conn, settings, tmp_path, fixtures_dir):
    shutil.copytree(fixtures_dir, tmp_path / "olist")
    local = dataclasses.replace(settings, data_dir=tmp_path)

    run_pipeline(local, None, False, False, client=client_without_network())
    first = counts(conn)
    run_pipeline(local, None, False, False, client=client_without_network())

    assert counts(conn) == first and first["orders.orders"] == 3


def test_the_download_happens_once_and_the_rerun_reuses_it(conn, settings, tmp_path, fixtures_dir):
    seen = []
    online = dataclasses.replace(settings, data_dir=tmp_path, kaggle_username="someone", kaggle_key="not-a-real-key")

    run_pipeline(online, None, False, False, client=client_returning(zip_bytes(fixtures_dir), seen=seen))
    run_pipeline(online, None, False, False, client=client_without_network())

    assert len(seen) == 1
    assert counts(conn)["catalog.products"] == 3
