# Source definition language (schemaVersion 1)

A ROM source is one immutable JSON file. The host validates it with
`DefinitionValidator` (`:source-runtime`) before install; every
network request it triggers runs through `SourceHttp` policy checks.
Definitions are data: they cannot set destinations, volumes, file
paths, or cookie values.

Validator source of truth:
`source-runtime/src/main/kotlin/com/gamebex/source/`.

## Document

```jsonc
{
  "schemaVersion": 1,
  "id": "example-source",          // [a-z][a-z0-9._-]*, not builtin*
  "name": "Example Source",        // nonblank, display only
  "author": "Example",             // nonblank, shown as UNVERIFIED
  "version": "1.2.0",              // nonblank, display only
  "requires": ["http-v1", "expressions-v1", "catalog-v1"],
  "origins": {
    "catalog": ["https://site.example"],
    "download": ["https://dl.example"],
    "artwork": ["https://art.example"],
    "assist": []
  },
  "defaults": {
    "headers": {"Accept-Language": "en-US"},
    "preferIpv4": false
  },
  "platforms": {"site-slug": "hostFolder"},
  "assist": {
    "enabled": false,
    "triggerStatuses": [403],
    "javascript": false,
    "domStorage": false
  },
  "operations": {"search": {...}, "...": {...}}
}
```

Limits: file ≤ 1 MiB, strict UTF-8, unknown JSON fields rejected.

- `requires`: must include `http-v1`, `expressions-v1`, `catalog-v1`;
  `assist-v1` additionally required when `assist.enabled` is true.
  Unknown capabilities are rejected (marking a definition
  incompatible), as are `requires` the host does not implement.
- `origins`: per-purpose HTTPS origin allowlists
  (`https://host`, default port, no credentials). Every request URL,
  including each redirect hop (max 20), must match its purpose list;
  DNS answers must be public IPs.
- `defaults.headers`: merged under per-request headers. Denied names:
  `cookie`, `authorization`, `host`, `content-length`, `connection`,
  `range`. No CR/LF in names or values.
- `platforms`: site platform alias → host folder id. The `platform`
  expression resolves through this map.
- `assist`: browser verification is never implicit — it runs only
  when enabled here and a transfer answers with a declared
  `triggerStatuses` entry.

## Operations

Required keys in `operations` (see
`DefinitionValidator.PUBLIC_OPERATIONS`): `consoles`, `types`,
`latest`, `browse`, `search`, `detail`, `variants`,
`resolveDownload`. Each is a workflow:

```jsonc
"search": {
  "inputs": ["query", "platformId", "cursor"],
  "steps": [
    {"name": "page", "request": {
      "method": "GET", "purpose": "catalog",
      "url": {"op": "concat", ...},
      "query": {"q": {"op": "ref", "path": ["input", "query"]}},
      "form": {}, "headers": {}
    }},
    {"name": "fixed", "value": {"answer": 42}}
  ],
  "result": {"op": "map", ...},
  "fallback": {"operation": "latest", "onEmpty": true,
               "onFailures": ["NETWORK", "HTTP"]},
  "cache": false
}
```

- Step names are unique identifiers; a step has exactly one of
  `request` or `value` (a literal JSON object bound to the name).
- `request.method` is GET or POST (POST sends `form` as a form body).
  `purpose` selects the origin allowlist.
- `url`, `query`, `form`, `headers` entries are expressions evaluated
  against prior steps + `input`.
- `result` is one expression producing the operation value.
- `fallback` optionally names another operation on empty results or
  NETWORK/HTTP/INVALID_RESPONSE failures.
- Metadata bodies are capped at 8 MiB; non-2xx metadata responses
  fail the operation (transfers map declared assist statuses to
  browser verification instead).

## Expressions

Every expression is a JSON object with an `op` string (max depth
64). Values flow as JSON, HTML nodes, or node lists; `ref` reads
`input.*`, prior step names, and `source.*` (`id`, `name`,
`platforms`, `headers` only).

| op | meaning |
|---|---|
| `literal` | `value` verbatim |
| `ref` | path lookup (`["input","query"]`, `["page","body"]`) |
| `object` / `array` | build values from sub-expressions |
| `json` | parse a JSON string |
| `get` | traverse JSON by path segments |
| `html` | parse an HTML string |
| `select` | CSS-select nodes (no `:matches`/regex selectors) |
| `text` / `attr` | node text / attribute |
| `map` / `filter` / `first` / `coalesce` | collection plumbing |
| `if` / `eq` / `lt` / `not` / `contains` | conditions |
| `concat` / `trim` / `replace` / `before` / `after` | text |
| `int` / `double` / `digits` / `add` | numbers |
| `size` / `distinct` / `sort` | arrays |
| `platform` | site alias → host folder via `platforms` |
| `lookup` | first array element matching field=key |
| `invoke` | call another operation by name |

CSS selectors go through jsoup's `QueryParser`; `:matches*` and
`[attr~=regex]` forms are rejected at validation.

## Results

Operations return catalog models (`ConsoleItem`, `TypeItem`,
`CatalogPage`, `RomDetail`, `DownloadFile`) or transfer bindings
(`SourceRef`, `TransferRequest`); see `SourceModels.kt`. The host —
never the definition — chooses files, folders, and volumes, and
re-resolved transfer URLs are single-attempt only.
