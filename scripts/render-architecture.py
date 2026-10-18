#!/usr/bin/env python3
"""Render the checked-in logical views through TerraVision's local MCP server."""

import argparse
import asyncio
import json
from pathlib import Path
import xml.etree.ElementTree as ET

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def render(output_dir):
    root = Path(__file__).resolve().parents[1]
    output_dir.mkdir(parents=True, exist_ok=True)
    server = StdioServerParameters(
        command="terravision",
        args=["mcp", "--output-dir", str(output_dir)],
    )
    async with stdio_client(server) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()
            guide = await client.call_tool("diagram_guide", {"provider": "azure"})
            if guide.is_error:
                raise RuntimeError(f"TerraVision setup failed: {guide.content}")
            for source in sorted((root / "docs/architecture").glob("*.view.json")):
                arguments = json.loads(source.read_text())
                result = await client.call_tool(
                    "render_graph",
                    {**arguments, "format": "svg", "preview": False},
                )
                if result.is_error:
                    raise RuntimeError(f"TerraVision failed for {source.name}: {result.content}")
                details = result.structured_content
                if details is None:
                    raise RuntimeError(f"TerraVision returned no render details for {source.name}")
                if details.get("warnings"):
                    raise RuntimeError(f"{source.name}: {details['warnings']}")
                svg = output_dir / f"{arguments['outfile']}.dot.svg"
                if not svg.is_file() or svg.stat().st_size == 0:
                    raise RuntimeError(f"TerraVision did not create {svg}")
                tree = ET.parse(svg)
                images = tree.findall(".//{http://www.w3.org/2000/svg}image")
                if not images or any(
                    not image.attrib.get("{http://www.w3.org/1999/xlink}href", "").startswith("data:image/")
                    for image in images
                ):
                    raise RuntimeError(f"{svg} has missing or non-portable icons")
                if any(
                    (text.text or "").endswith("...")
                    for text in tree.findall(".//{http://www.w3.org/2000/svg}text")
                ):
                    raise RuntimeError(f"{svg} has truncated labels; shorten the view's labels")
                print(svg)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "artifacts/architecture",
    )
    args = parser.parse_args()
    asyncio.run(render(args.output_dir.resolve()))
