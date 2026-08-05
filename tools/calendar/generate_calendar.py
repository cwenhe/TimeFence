#!/usr/bin/env python3
"""根据已审核的中国官方公告输入生成时界日历 JSON。"""

from __future__ import annotations

import json
from datetime import date, timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_PATH = ROOT / "data/calendar/source/2026.json"
OUTPUT_PATHS = (
    ROOT / "data/calendar/zh-CN.json",
    ROOT / "app/src/main/res/raw/zh_cn_calendar.json",
)


def load_source() -> dict:
    """读取版本化的官方日期输入，供生成过程保持确定性。"""
    return json.loads(SOURCE_PATH.read_text(encoding="utf-8"))


def date_range(start: date, end: date) -> set[date]:
    """展开包含首尾日期的连续区间，避免手工遗漏节假日边界。"""
    days: set[date] = set()
    current = start
    while current <= end:
        days.add(current)
        current += timedelta(days=1)
    return days


def build_days(source: dict) -> list[dict]:
    """按国务院和交易所公告生成全年逐日工作日与交易日状态。"""
    year = source["year"]
    holidays = {
        day
        for item in source["holidayRanges"]
        for day in date_range(date.fromisoformat(item["start"]), date.fromisoformat(item["end"]))
    }
    makeup_workdays = {date.fromisoformat(item) for item in source["makeupWorkdays"]}
    first_day = date(year, 1, 1)
    next_year = date(year + 1, 1, 1)
    days: list[dict] = []
    current = first_day
    while current < next_year:
        weekday = current.weekday() < 5
        statutory_workday = (weekday and current not in holidays) or current in makeup_workdays
        trading_day = weekday and current not in holidays
        days.append(
            {
                "date": current.isoformat(),
                "isStatutoryWorkday": statutory_workday,
                "isAShareTradingDay": trading_day,
            }
        )
        current += timedelta(days=1)
    return days


def build_document(source: dict) -> dict:
    """组装客户端严格校验的日历文档，并断言 2026 官方计数。"""
    days = build_days(source)
    workday_count = sum(day["isStatutoryWorkday"] for day in days)
    trading_day_count = sum(day["isAShareTradingDay"] for day in days)
    if (len(days), workday_count, trading_day_count) != (365, 248, 242):
        raise ValueError(
            f"2026 日历计数错误：天数={len(days)}，工作日={workday_count}，交易日={trading_day_count}"
        )
    return {
        "schemaVersion": 1,
        "revision": 2026080601,
        "locale": "zh-CN",
        "generatedAt": "2026-08-06T00:00:00Z",
        "years": [
            {
                "year": source["year"],
                "complete": True,
                "sources": source["sources"],
                "days": days,
            }
        ],
    }


def write_outputs(document: dict) -> None:
    """将同一份格式化 JSON 写入远程源文件和 APK 内置资源。"""
    serialized = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    for output_path in OUTPUT_PATHS:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(serialized, encoding="utf-8")


def main() -> None:
    """执行日历读取、校验和双目标输出。"""
    write_outputs(build_document(load_source()))


if __name__ == "__main__":
    main()
