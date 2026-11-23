import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public class Server {

    static void main() {
        try (ServerSocket server = new ServerSocket(8080)) {
            Response notFound = new Response(404, Map.of("Content-Type", "text/plain"),
                    "Not Found".getBytes(StandardCharsets.ISO_8859_1));
            Response serverError = new Response(500, Map.of("Content-Type", "text/plain"),
                    "Server Error".getBytes(StandardCharsets.ISO_8859_1));

            Response badRequest = new Response(400, Map.of("Content-Type", "text/plain", "Connection", "close"),
                    "Bad Request".getBytes(StandardCharsets.ISO_8859_1));

            Map<String, Handler> routes = new HashMap<>();
            routes.put("GET /hello", req -> new Response(200, Map.of("Content-Type", "text/plain"),
                    "Hello".getBytes(StandardCharsets.UTF_8)));
            Path root = Path.of("public").toAbsolutePath().normalize();

            while (true) {
                Socket socket = server.accept();
                Thread.startVirtualThread(() -> {
                    try (socket) {
                        InputStream in = new BufferedInputStream(socket.getInputStream());
                        OutputStream out = socket.getOutputStream();
                        socket.setSoTimeout(5000);
                        Request req;
                        while (true) {
                            try {
                                req = parseRequest(in);
                            } catch (BadRequest e) {
                                System.out.println("bad request: " + e.getMessage());
                                writeResponse(out, badRequest);
                                out.flush();
                                break;
                            }
                            if (req == null) break;
                            Handler h = routes.get(req.method() + " " + req.path());
                            Response res;
                            try {
                                if (h != null) {
                                    res = h.handle(req);
                                } else {
                                    res = "GET".equals(req.method()) ? staticFile(root, req, serverError) : null;
                                    if (res == null) {
                                        String allow = allowedMethods(routes, req.path());
                                        res = allow.isEmpty() ? notFound : methodNotAllowed(allow);
                                    }
                                }
                            } catch (RuntimeException ex) {
                                res = serverError;
                                ex.printStackTrace();
                            }

                            System.out.println(req.method() + " " + req.path() + " " + req.query());
                            System.out.println(req.headers());
                            System.out.println(new String(req.body(), StandardCharsets.UTF_8));

                            writeResponse(out, res);
                            out.flush();
                            String headerCheck = req.headers().get("Connection");
                            if ("close".equalsIgnoreCase(headerCheck)) break;
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("socket error: " + e.getMessage());
                    } catch (IOException e) {
                        System.out.println("connection error: " + e.getMessage());
                    }
                });
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    static String readLine(InputStream in, int maxLen) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (buf.size() >= maxLen) throw new BadRequest("exceeded maximum line length");
            buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        String line = buf.toString(StandardCharsets.ISO_8859_1);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    static Request parseRequest(InputStream in) throws IOException {
        String requestLine = readLine(in, MAX_LINE);
        if (requestLine == null) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length != 3) {
            throw new BadRequest("invalid request line: " + requestLine);
        }
        String method = parts[0];
        String target = parts[1];

        int q = target.indexOf('?');
        String path = (q == -1) ? target : target.substring(0, q);
        String query = (q == -1) ? "" : target.substring(q + 1);

        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String line;
        int headerCount = 0;
        while ((line = readLine(in, MAX_LINE)) != null && !line.isEmpty()) {
            if (++headerCount > MAX_HEADERS) {
                throw new BadRequest("too many headers");
            }
            int colon = line.indexOf(':');
            if (colon == -1) {
                throw new BadRequest("invalid header: " + line);
            }

            String name = line.substring(0, colon);
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }

        if (line == null) {
            throw new IOException("unexpected end of headers");
        }

        String contentLength = headers.get("Content-Length");
        byte[] body;
        if (contentLength == null) {
            body = new byte[0];
        } else {
            int n;
            try {
                n = Integer.parseInt(contentLength.trim());
            } catch (NumberFormatException ex) {
                throw new BadRequest("error while parsing body ", ex);
            }
            if (n < 0) {
                throw new BadRequest("invalid content length");
            }
            if (n > MAX_BODY) {
                throw new BadRequest("exceeded maximum body length");
            }
            body = in.readNBytes(n);
            if (body.length != n) {
                throw new IOException("truncated body");
            }

        }
        return new Request(method, path, query, headers, body);
    }

    static void writeResponse(OutputStream out, Response res) throws IOException {
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            StringBuilder sb = new StringBuilder();
            headers.putAll(res.headers());
            headers.put("Content-Length", String.valueOf(res.body().length));
            sb.append("HTTP/1.1 ").append(res.statusCode()).append(' ')
                    .append(statusText(res.statusCode())).append("\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            sb.append("\r\n");

            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(res.body());
    }

    static Path safeResolve(Path root, String urlPath) {
        String relative = urlPath.substring(1);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) return null;
        return target;
    }

    static String contentType(Path target) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = (dot == -1) ? "" : name.substring(dot + 1).toLowerCase();
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    static Response staticFile(Path root, Request req, Response err) {
        String path = req.path().equals("/html") ? "/index.html" : req.path();
        Path target = safeResolve(root, path);
        if (target == null) return null;
        if (!Files.isRegularFile(target)) return null;

        try {
            byte[] bytes = Files.readAllBytes(target);
            return new Response(200, Map.of("Content-Type", contentType(target)), bytes);
        } catch (IOException e) {
            e.printStackTrace();
            return err;
        }
    }

    static String allowedMethods(Map<String, Handler> routes, String path) {
        TreeSet<String> methods = new TreeSet<>();
        for (String key : routes.keySet()) {
            int space = key.indexOf(' ');
            if (key.substring(space + 1).equals(path)) methods.add(key.substring(0, space));
        }
        return String.join(", ", methods);
    }

    static Response methodNotAllowed(String allow) {
        return new Response(405, Map.of("Content-Type", "text/plain", "Allow", allow),
                "Method Not Allowed".getBytes(StandardCharsets.ISO_8859_1));
    }

    static String statusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default -> "Unknown";
        };
    }

    static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html",
            "css", "text/css",
            "js", "text/javascript",
            "txt", "text/plain",
            "json", "application/json"
    );

    static final int MAX_BODY = 1_048_576;
    static final int MAX_LINE = 8192;
    static final int MAX_HEADERS = 100;

    static class BadRequest extends IOException {
        BadRequest(String message) { super(message); }
        BadRequest(String message, Throwable cause) { super(message, cause); }
    }

    record Request(
            String method,
            String path,
            String query,
            Map<String, String> headers,
            byte[] body
            ) {}

    record Response (
            int statusCode,
            Map<String, String> headers,
            byte[] body
    ) {}

    interface Handler {
        Response handle(Request req);
    }
}