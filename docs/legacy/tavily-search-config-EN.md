# Tavily Search MCP Service Configuration

## Prerequisites

1. Register an account at https://tavily.com
2. Obtain your API Key from the Dashboard (format: `tvly-xxxxx`)
3. Free tier: 1,000 calls per month

## Client Configuration

### Streamable HTTP (Recommended)

Add the following JSON configuration to your MCP client:

```json
{
  "transport": "streamable_http",
  "url": "http://localhost:8075/mcp/tavily_search",
  "headers": {
    "X-Tavily-Api-Key": "tvly-your-api-key"
  },
  "timeout": 5,
  "sse_read_timeout": 300
}
```

### SSE

```json
{
  "transport": "sse",
  "url": "http://localhost:8075/mcp/tavily_search/sse",
  "headers": {
    "X-Tavily-Api-Key": "tvly-your-api-key"
  },
  "timeout": 5,
  "sse_read_timeout": 300
}
```

## Available Tools

### 1. tavily_search — Smart Search

Search the web and return a structured list of results.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | Yes | Search query |
| `max_results` | integer | No | Number of results, default 5, max 20 |
| `search_depth` | string | No | `"basic"` or `"advanced"`, default `"basic"` |
| `topic` | string | No | `"general"` or `"news"`, default `"general"` |
| `include_answer` | boolean | No | Whether to return AI summary, default `true` |
| `days` | integer | No | Time range in days, default 3 |

**Example:**

```json
{
  "query": "Java MCP protocol",
  "max_results": 5,
  "search_depth": "basic",
  "topic": "general"
}
```

### 2. tavily_extract — Webpage Content Extraction

Extract the main body content from one or more webpages.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `urls` | string | Yes | Comma-separated list of URLs |
| `extract_depth` | string | No | `"basic"` or `"advanced"`, default `"basic"` |

**Example:**

```json
{
  "urls": "https://example.com,https://other.com",
  "extract_depth": "basic"
}
```

## Typical Workflow

1. Call `tavily_search` with a search query
2. Select URLs of interest from the search results
3. Call `tavily_extract` to retrieve full content from target pages
4. Use the extracted content for further analysis or Q&A

## Notes

- The API Key is passed via the `X-Tavily-Api-Key` HTTP header and will not appear in logs
- The `content` field in search results is a summary; use `tavily_extract` for full content
- `advanced` depth mode takes longer but returns more accurate results
- Free accounts are subject to rate limits; if you receive a 429 error, wait and retry
