import os
from pathlib import Path
from zoneinfo import ZoneInfo

DB_PATH = Path(os.environ.get("PLANJ_DB", Path(__file__).resolve().parent.parent / "data" / "planj.db"))
AW_URL = os.environ.get("PLANJ_AW_URL", "http://localhost:5600")
TZ = ZoneInfo(os.environ.get("PLANJ_TZ", "Asia/Kuala_Lumpur"))
LAT = float(os.environ.get("PLANJ_LAT", "3.1390"))
LON = float(os.environ.get("PLANJ_LON", "101.6869"))
ICS_URL = os.environ.get("PLANJ_ICS_URL")
