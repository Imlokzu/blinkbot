"""One calculation contract for local function calls and the tools MCP bridge."""

SCHEMA = {
    "name": "python_calculate",
    "description": (
        "Calculate numeric results by running isolated Python. Use this for arithmetic, "
        "percentages, powers, roots, trigonometry, sums, numerical methods and checking answers. "
        "Python integers, floats, complex numbers, loops, functions and the math module are supported. "
        "Use print() or a final expression to return results. Each call starts fresh. "
        "No files, network, host functions, NumPy or SymPy are available. "
        "Explain the computed result and show formulas with LaTeX $...$ or $$...$$ when appropriate; "
        "never claim a calculation succeeded if the tool returned an error."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {"code": {"type": "string", "minLength": 1, "maxLength": 16000,
                                 "description": "Python code for the calculation."}},
        "required": ["code"],
        "additionalProperties": False,
    },
}
