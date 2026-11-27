"""Expose two loopback-only test endpoints for the RProbeDesk_QA35 emulator."""
import asyncio
import subprocess
import sys

adb = sys.argv[1]
assert "RProbeDesk_QA35" in subprocess.check_output([adb,"-s","emulator-5594","emu","avd","name"],text=True)
async def relay(reader, writer):
    other = None
    try:
        peer, other = await asyncio.wait_for(asyncio.open_connection("127.0.0.1",5595),3)
        async def copy(source, destination):
            while data := await source.read(65536):
                destination.write(data)
                await destination.drain()
        tasks = [asyncio.create_task(copy(reader, other)), asyncio.create_task(copy(peer,writer))]
        try:
            await asyncio.wait(tasks,return_when=asyncio.FIRST_COMPLETED)
        finally:
            for task in tasks: task.cancel()
            await asyncio.gather(*tasks,return_exceptions=True)
    except (OSError,TimeoutError):
        pass
    finally:
        writer.close()
        if other: other.close()

async def main():
    async with await asyncio.start_server(relay,"127.0.0.1",48481) as first:
        async with await asyncio.start_server(relay,"127.0.0.2",48482) as second:
            print("QA35 endpoints ready",flush=True)
            await asyncio.gather(first.serve_forever(),second.serve_forever())

asyncio.run(main())
