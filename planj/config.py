import os
import subprocess
from pathlib import Path
from zoneinfo import ZoneInfo


def _windows_home() -> Path | None:
    """The current Windows user's profile folder, as seen from WSL."""
    if env := os.environ.get("PLANJ_WIN_HOME"):
        return Path(env)
    try:
        # cwd on a Windows drive stops cmd.exe warning about UNC paths.
        win = subprocess.run(
            ["cmd.exe", "/c", "echo %USERPROFILE%"], capture_output=True, text=True, timeout=10, cwd="/mnt/c"
        ).stdout.strip()
        home = subprocess.run(["wslpath", win], capture_output=True, text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    return Path(home) if home and Path(home).is_dir() else None


DB_PATH = Path(os.environ.get("PLANJ_DB", Path(__file__).resolve().parent.parent / "data" / "planj.db"))
WIN_HOME = _windows_home()

TRACKER_DIR = Path(os.environ["PLANJ_TRACKER_DIR"]) if "PLANJ_TRACKER_DIR" in os.environ else (
    WIN_HOME / "AppData" / "Local" / "planj" / "activity" if WIN_HOME else None
)
if TRACKER_DIR and not TRACKER_DIR.is_dir():
    TRACKER_DIR = None

# Phone exports can be dropped in data/phone, or simply left in the Windows Downloads folder.
PHONE_DIRS = [d for d in (DB_PATH.parent / "phone", WIN_HOME and WIN_HOME / "Downloads") if d and d.is_dir()]

TZ = ZoneInfo(os.environ.get("PLANJ_TZ", "Asia/Kuala_Lumpur"))
LAT = float(os.environ.get("PLANJ_LAT", "3.1390"))
LON = float(os.environ.get("PLANJ_LON", "101.6869"))
ICS_URL = os.environ.get("PLANJ_ICS_URL")
