from __future__ import annotations

import asyncio
import os

from temporalio.client import Client
from temporalio.worker import Worker

from .temporal_workflows import (
    DemoTaskWorkflow,
    EnterpriseDocumentWorkflow,
    run_demo_task_activity,
)


async def run_worker() -> None:
    target = os.getenv("TEMPORAL_ADDRESS", "127.0.0.1:7233")
    namespace = os.getenv("TEMPORAL_NAMESPACE", "default")
    task_queue = os.getenv("TEMPORAL_TASK_QUEUE", "taskbridge-demo")

    client = None
    for attempt in range(10):
        try:
            client = await Client.connect(target, namespace=namespace)
            break
        except Exception as e:
            print(f"Failed to connect to Temporal (attempt {attempt+1}/10): {e}")
            await asyncio.sleep(2)
    
    if not client:
        print("Could not connect to Temporal. Exiting.")
        return

    worker = Worker(
        client,
        task_queue=task_queue,
        workflows=[DemoTaskWorkflow, EnterpriseDocumentWorkflow],
        activities=[run_demo_task_activity],
    )
    await worker.run()


if __name__ == "__main__":
    asyncio.run(run_worker())
