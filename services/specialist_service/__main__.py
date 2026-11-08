import asyncio
from services.web_gateway.server import main

if __name__=='__main__':asyncio.run(main('services.specialist_service.app:app',8446))
