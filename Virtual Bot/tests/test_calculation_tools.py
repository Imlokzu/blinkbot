"""Execute actual isolated Python workers, not a mocked calculation response."""
import asyncio
import json

import pytest

from calculation_schema import SCHEMA
from tools import calculation_tools, registry
import tools_mcp


def calculate(code):
    return asyncio.run(calculation_tools.python_calculate(code))


@pytest.mark.parametrize(("code", "expected"), [
    ("sum(i*i for i in range(10))", "285"),
    ("import math\nmath.sqrt(144)", "12.0"),
    ("import math\nmath.comb(52, 5)", "2598960"),
    ("import math\nround(math.sin(math.pi/2), 8)", "1.0"),
    ("(2 + 3j) * (2 - 3j)", "(13+0j)"),
    ("def f(x):\n    return x*x\nf(12)", "144"),
])
def test_numeric_python_returns_actual_results(code, expected):
    result = calculate(code)
    assert result == {"ok": True, "stdout": "", "result": expected}


def test_print_and_final_expression_are_both_returned():
    assert calculate("print(120 * .15)\n120 * 1.15") == {"ok": True, "stdout": "18.0\n", "result": "138.0"}


@pytest.mark.parametrize("code", ["", "   ", None, 12, "x" * (calculation_tools.MAX_CODE + 1)])
def test_invalid_source_does_not_start_a_worker(code):
    assert calculate(code) == {"error": "invalid_calculation_code"}


def test_syntax_and_arithmetic_errors_are_not_reported_as_success():
    for code in ["1 / 0", "if:"]:
        result = calculate(code)
        assert result["error"] == "calculation_failed"
        assert "ok" not in result


def test_variables_cannot_cross_invocations():
    assert calculate("private_number = 42")["ok"]
    assert calculate("private_number")["error"] == "calculation_failed"


def test_no_files_environment_or_network_are_exposed(tmp_path, monkeypatch):
    secret = "isolated-fixture-only-marker"
    target = tmp_path / "private.txt"
    target.write_text(secret)
    monkeypatch.setenv("CALCULATION_TEST_SECRET", secret)
    for code in [f"open({str(target)!r}).read()", "import socket\nsocket.socket()",
                 "import os\nos.getenv('CALCULATION_TEST_SECRET')"]:
        result = calculate(code)
        assert secret not in json.dumps(result)
        assert result.get("error") or result.get("result") in ("", "None")


def test_memory_time_and_output_are_bounded(monkeypatch):
    monkeypatch.setattr(calculation_tools, "RUN_SECONDS", 0.05)
    assert calculate("while True: pass")["error"] == "calculation_timeout"
    assert calculate("[0] * 10**10")["error"] == "calculation_memory_limit"
    assert calculate("print('x' * 9000)")["error"] == "calculation_output_limit"
    assert calculate("'x' * 9000")["error"] == "calculation_output_limit"


def test_cancellation_finishes_worker_scope_and_next_request_still_works():
    async def check():
        task = asyncio.create_task(calculation_tools.python_calculate("while True: pass"))
        await asyncio.sleep(0.05)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 3)
        assert (await calculation_tools.python_calculate("6 * 7"))["result"] == "42"
    asyncio.run(check())


def test_registry_and_mcp_offer_the_same_executable_contract():
    local = next(tool["function"] for tool in registry.list_tools() if tool["function"]["name"] == SCHEMA["name"])
    remote = next(tool for tool in tools_mcp.TOOLS if tool["name"] == SCHEMA["name"])
    assert local["parameters"] == remote["inputSchema"] == SCHEMA["inputSchema"]
    assert asyncio.run(registry.execute_tool("python_calculate", {"code": "2 ** 20"}))["result"] == "1048576"


def test_large_integer_formatting_stays_in_the_sandbox():
    result = calculate("10**5000")
    # Monty mirrors Python's integer-string digit limit; surface its bounded
    # diagnostic instead of raising ValueError during host-side formatting.
    assert result["error"] == "calculation_failed"
    assert "ValueError" in result["detail"]
    assert calculate("10**1000")["result"] == "1" + "0" * 1000
    assert calculate("list(range(100000))")["error"] == "calculation_output_limit"


def test_shadowing_str_does_not_change_trusted_result_formatting():
    assert calculate("str = lambda x: 'wrong'\n6 * 7")["result"] == "42"
