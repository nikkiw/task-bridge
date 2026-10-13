from __future__ import annotations

import asyncio
import copy
from datetime import datetime, timedelta, timezone

from taskbridge.models import TaskActionReceipt, TaskActionReceiptStatus

from app.enterprise_stores import InMemoryActionReceiptStore


def _receipt(*, metadata: dict[str, object] | None = None, expires_at: datetime | None = None):
    return TaskActionReceipt(
        task_id="task-1",
        suspend_id="suspend-1",
        client_action_id="action-1",
        action_type="a2ui_action",
        payload={"name": "submit_input", "context": {"answer": "yes"}},
        metadata=metadata or {},
        actor_id="user-1",
        status=TaskActionReceiptStatus.ACCEPTED,
        created_at=datetime.now(timezone.utc),
        expires_at=expires_at,
    )


def test_save_receipt_round_trips_nested_metadata_as_an_isolated_snapshot() -> None:
    async def scenario() -> None:
        metadata = {"modelSnapshot": {"answer": "yes", "nested": {"selection": ["a"]}}}
        expected_metadata = copy.deepcopy(metadata)
        receipt = _receipt(metadata=metadata)
        store = InMemoryActionReceiptStore()
        expected_raw = receipt.model_dump(mode="json")

        saved = await store.save_receipt(receipt)
        receipt.metadata["modelSnapshot"]["nested"]["selection"][0] = "changed"

        key = (receipt.task_id, receipt.client_action_id)
        assert store.receipts[key] == expected_raw
        assert saved.metadata == expected_metadata

        saved.metadata["modelSnapshot"]["nested"]["selection"][0] = "returned-copy"
        loaded = await store.get_receipt(*key)
        assert loaded is not None
        assert loaded.metadata == expected_metadata

    asyncio.run(scenario())


def test_get_receipt_defaults_metadata_for_legacy_raw_record() -> None:
    async def scenario() -> None:
        receipt = _receipt()
        raw = receipt.model_dump(mode="json")
        del raw["metadata"]
        store = InMemoryActionReceiptStore()
        store.receipts[(receipt.task_id, receipt.client_action_id)] = raw

        loaded = await store.get_receipt(receipt.task_id, receipt.client_action_id)

        assert loaded is not None
        assert loaded.metadata == {}

    asyncio.run(scenario())


def test_delete_expired_receipts_decodes_json_dates_and_keeps_threshold_equal() -> None:
    async def scenario() -> None:
        threshold = datetime.now(timezone.utc)
        store = InMemoryActionReceiptStore()
        expired = _receipt(expires_at=threshold - timedelta(microseconds=1))
        equal = _receipt(expires_at=threshold).model_copy(
            update={"client_action_id": "action-equal"}
        )
        future = _receipt(expires_at=threshold + timedelta(seconds=1)).model_copy(
            update={"client_action_id": "action-future"}
        )
        for receipt in (expired, equal, future):
            store.receipts[(receipt.task_id, receipt.client_action_id)] = receipt.model_dump(
                mode="json"
            )

        deleted = await store.delete_expired_receipts(threshold)

        assert deleted == 1
        assert (equal.task_id, equal.client_action_id) in store.receipts
        assert (future.task_id, future.client_action_id) in store.receipts

    asyncio.run(scenario())
