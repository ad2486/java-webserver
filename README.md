# java-http-server

A small HTTP/1.1 server written from scratch in Java, using only the standard library (no Spring, no frameworks). It started as a way to learn Java by building something real, so the code favors clarity over cleverness.

## Features

- Hand-written HTTP/1.1 request parser (request line, headers, body), working on bytes and decoding headers as ISO-8859-1
- Keep-alive connections with a 5 second idle timeout and `Connection: close` support
- One virtual thread per connection
- Routing through a `Map<String, Handler>`, with lambdas as handlers
- Static file serving with `Content-Type` by extension and protection against path traversal
- Proper error responses: `400`, `404`, `405` (with an `Allow` header) and `500`
- Size limits on request lines, headers and bodies

## Requirements

- JDK 25 or newer. The entry point is a no-argument `static void main()`, which is standard from Java 25 on. (Developed on JDK 27.)
- To run the tests: JUnit 6 (Jupiter). The project has no build tool, so the tests were run from IntelliJ IDEA.

## Running

```bash
mkdir -p public                      # static files are served from ./public (git-ignored)
echo '<h1>hello</h1>' > public/index.html

javac -d out src/Server.java
java -cp out Server                  # listens on port 8080
```

The `public/` directory is resolved relative to the working directory you start the server from.

## Trying it

```bash
curl -v localhost:8080/hello                       # 200, "Hello" (a registered route)
curl -v localhost:8080/html                        # 200, serves public/index.html
curl -v localhost:8080/index.html                  # 200, same file by its real name
curl -v localhost:8080/does-not-exist              # 404
curl -v -X POST localhost:8080/hello               # 405, Allow: GET
curl -v localhost:8080/hello localhost:8080/hello  # two requests, one connection (keep-alive)
curl -v -H "Connection: close" localhost:8080/hello

# path traversal is refused (--path-as-is stops curl from normalizing the ..)
curl --path-as-is -v localhost:8080/../src/Server.java   # 404, never the file contents

# malformed request
printf 'GET /a\r\n\r\n' | nc localhost 8080        # 400
```

## How it works

Everything lives in `src/Server.java`.

| Piece | Role |
|---|---|
| `readLine(in, maxLen)` | Reads one line as raw bytes up to `\n`, strips the trailing `\r`, gives up past `maxLen` |
| `parseRequest(in)` | Builds a `Request` from an `InputStream`; returns `null` when the client closed cleanly between requests |
| `writeResponse(out, res)` | Writes status line, headers and body; always computes `Content-Length` from the body |
| `Handler` | `Response handle(Request req)`, a functional interface |
| `safeResolve(root, urlPath)` | Strips the leading `/`, resolves, normalizes, and checks the result still starts with the root |
| `staticFile(...)` | Serves a regular file from `public/`, or returns `null` so the caller can answer 404/405 |
| `BadRequest` | An `IOException` for client mistakes; the server answers `400` and closes the connection |

A few design choices worth knowing:

- `parseRequest` takes an `InputStream` instead of a `Socket`, so it is tested with `ByteArrayInputStream`.
- The socket's stream is wrapped in one `BufferedInputStream` per connection. Creating a new one per request would throw away bytes already buffered.
- A truncated request (the client left halfway) is a plain `IOException`, not a `BadRequest`: there is nobody left to receive a `400`.
- Header names live in a `TreeMap` with `String.CASE_INSENSITIVE_ORDER`.

## Limits

| Limit | Value |
|---|---|
| Request line or single header line | 8 KB |
| Number of header lines | 100 |
| Request body | 1 MB |

Exceeding any of them gets a `400` and the connection is closed.

## Tests

`test/ServerTest.java` covers the parser (happy paths, malformed input, truncation, limits), `writeResponse`, `safeResolve`, `allowedMethods` and the `400`/`405` behavior. There are no tests yet for `contentType` or `staticFile`.

## Known limitations

- No access log; the server prints request details to stdout.
- Every limit violation answers `400`; `413` and `431` would be more precise.
- A non-`GET` request to an existing static file answers `404` instead of `405`.
- URLs are not percent-decoded (`%20`, `%2e%2e`). If decoding is added, it must happen before `normalize`/`startsWith`.
- Symbolic links inside `public/` can point outside it; `safeResolve` only inspects the path text.
- Static files are read fully into memory, and only a handful of content types are known (no `charset`).
- No HEAD support, no chunked bodies, no TLS.
