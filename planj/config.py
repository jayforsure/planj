import os
from glob import glob
from pathlib import Path
from zoneinfo import ZoneInfo


def _tracker_dir() -> Path | None:
    if env := os.environ.get("PLANJ_TRACKER_DIR"):
        return Path(env)
    # From WSL, the Windows tracker's %LOCALAPPDATA% is reachable under /mnt/c.
    found = glob("/mnt/c/Users/*/AppData/Local/planj/activity")
    return Path(found[0]) if len(found) == 1 else None


DB_PATH = Path(os.environ.get("PLANJ_DB", Path(__file__).resolve().parent.parent / "data" / "planj.db"))
TRACKER_DIR = _tracker_dir()
TZ = ZoneInfo(os.environ.get("PLANJ_TZ", "Asia/Kuala_Lumpur"))
LAT = float(os.environ.get("PLANJ_LAT", "3.1390"))
LON = float(os.environ.get("PLANJ_LON", "101.6869"))
ICS_URL = os.environ.get("PLANJ_ICS_URL")
