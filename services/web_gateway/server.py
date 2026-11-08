"""Serve HTTPS and reload cert-manager's atomically rotated leaf certificate."""
import asyncio
import hashlib
import logging
from pathlib import Path
import ssl
import tempfile

import uvicorn


async def main(application='services.web_gateway.app:app',port=8445):
    cert,key=Path('/tls/tls.crt'),Path('/tls/tls.key')
    config=uvicorn.Config(application,host='0.0.0.0',port=port,
                         ssl_certfile=str(cert),ssl_keyfile=str(key),access_log=False)
    config.load()
    current=hashlib.sha256(cert.read_bytes()+key.read_bytes()).digest()
    async def reload_cert():
        nonlocal current
        while True:
            await asyncio.sleep(60)
            try:
                candidate=hashlib.sha256(cert.read_bytes()+key.read_bytes()).digest()
                if candidate!=current:
                    # Pin one Kubernetes Secret generation, then validate a
                    # stable copy before updating the active server context.
                    directory=(cert.parent/'..data').resolve()
                    if not directory.is_dir():directory=cert.parent
                    cert_bytes=(directory/cert.name).read_bytes();key_bytes=(directory/key.name).read_bytes()
                    with tempfile.TemporaryDirectory(prefix='pv-tls-') as temp:
                        leaf,private=Path(temp)/'cert.pem',Path(temp)/'key.pem'
                        leaf.write_bytes(cert_bytes);private.write_bytes(key_bytes)
                        check=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                        check.load_cert_chain(str(leaf),str(private))
                        config.ssl.load_cert_chain(str(leaf),str(private))
                    current=hashlib.sha256(cert_bytes+key_bytes).digest()
                    logging.getLogger('uvicorn.error').info('Reloaded renewed origin certificate')
            except (OSError,ValueError):
                logging.getLogger('uvicorn.error').error('Origin certificate reload failed; retained current TLS context')
    watcher=asyncio.create_task(reload_cert())
    try:await uvicorn.Server(config).serve()
    finally:
        watcher.cancel()
        await asyncio.gather(watcher,return_exceptions=True)


if __name__=='__main__':asyncio.run(main())
