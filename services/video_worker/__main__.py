import asyncio
import os

from .config import Settings
from .runner import run


if __name__ == "__main__":
    role=os.environ.get('WORKER_ROLE','')
    if os.environ.get('ANALYSIS_ONLY_IMAGE')=='1' and role not in {'analyze','modules','specialist'}:
        raise ValueError('this image only runs analysis consumers')
    asyncio.run(run(role, Settings.from_env()))
