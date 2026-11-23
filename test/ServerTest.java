import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

public class ServerTest {
    @Test
    void getWithNoBody() throws IOException {
        byte[] raw = "GET /a?x=1 HTTP/1.1\r\nHost: h\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        Server.Request r = Server.parseRequest(new ByteArrayInputStream(raw));

        assertEquals("GET", r.method());
        assertEquals("/a", r.path());
        assertEquals("x=1", r.query());
        assertArrayEquals(new byte[0], r.body());
    }

    @Test
    void getWithNoQuery() throws IOException {
        byte[] raw = "GET /a HTTP/1.1\r\nHost: h\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        Server.Request r = Server.parseRequest(new ByteArrayInputStream(raw));

        assertEquals("GET", r.method());
        assertEquals("/a", r.path());
        assertEquals("", r.query());
    }

    @Test
    void postWithBody() throws IOException {
        String body = "{\"name\":\"ab\"}";
        byte[] raw = ("POST /users HTTP/1.1\r\n" +
                "Host: h\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body)
                .getBytes(StandardCharsets.ISO_8859_1);

        Server.Request r = Server.parseRequest(new ByteArrayInputStream(raw));
        assertEquals("POST", r.method());
        assertEquals("/users", r.path());
        assertEquals("", r.query());
        assertEquals("h", r.headers().get("Host"));
        assertEquals("application/json", r.headers().get("Content-Type"));
        assertEquals("13", r.headers().get("Content-Length"));
        assertArrayEquals(body.getBytes(StandardCharsets.ISO_8859_1), r.body());
    }

    @Test
    void caseInsensitiveHeaders() throws IOException {
        String body = "{\"name\":\"ab\"}";
        byte[] raw = ("POST /users HTTP/1.1\r\n" +
                "host: h\r\n" +
                "content-type: application/json\r\n" +
                "content-length: " + body.length() + "\r\n" +
                "\r\n" +
                body)
                .getBytes(StandardCharsets.ISO_8859_1);

        Server.Request r = Server.parseRequest(new ByteArrayInputStream(raw));
        assertEquals("application/json", r.headers().get("CONTENT-TYPE"));
        assertEquals("h", r.headers().get("HOST"));
        assertEquals("13", r.headers().get("CONTENT-LENGTH"));
        assertArrayEquals(body.getBytes(StandardCharsets.ISO_8859_1), r.body());
    }

    private static IOException parseExpectingError(String request) {
        byte[] raw = request.getBytes(StandardCharsets.ISO_8859_1);
        return assertThrows(IOException.class,
                () -> Server.parseRequest(new ByteArrayInputStream(raw)));
    }

    @Test
    void contentLengthNotNumeric() {
        IOException ex = parseExpectingError("POST / HTTP/1.1\r\nContent-Length: abc\r\n\r\n");
        assertTrue(ex.getMessage().contains("error while parsing body"));
        assertInstanceOf(NumberFormatException.class, ex.getCause());
    }

    @Test
    void contentLengthNegative() {
        IOException ex = parseExpectingError("POST / HTTP/1.1\r\nContent-Length: -5\r\n\r\n");
        assertEquals("invalid content length", ex.getMessage());
    }

    @Test
    void invalidRequestLine() {
        IOException ex = parseExpectingError("GET /a\r\n\r\n");
        assertTrue(ex.getMessage().startsWith("invalid request line"));
    }

    @Test
    void headerWithoutColon() {
        IOException ex = parseExpectingError("GET / HTTP/1.1\r\nXXX\r\n\r\n");
        assertTrue(ex.getMessage().startsWith("invalid header"));
    }

    @Test
    void writeResponseFormat() throws IOException {
        var out = new ByteArrayOutputStream();
        Server.Response res = new Server.Response(200,
                Map.of("Content-Type", "text/plain"),
                "Hello".getBytes(StandardCharsets.UTF_8));

        Server.writeResponse(out, res);

        String s = out.toString(StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(s.contains("Content-Type: text/plain\r\n"));
        assertTrue(s.contains("Content-Length: 5\r\n"));
        assertTrue(s.endsWith("\r\n\r\nHello"));
    }

    @Test
    void writeResponseOverridesContentLength() throws IOException {
        var out = new ByteArrayOutputStream();
        Server.Response res = new Server.Response(404,
                Map.of("content-length", "999"),
                "Not Found".getBytes(StandardCharsets.UTF_8));

        Server.writeResponse(out, res);

        String s = out.toString(StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("HTTP/1.1 404 Not Found\r\n"));
        assertTrue(s.toLowerCase().contains("content-length: 9\r\n"));
        assertFalse(s.contains("999"));
        assertFalse(s.toLowerCase().indexOf("content-length") != s.toLowerCase().lastIndexOf("content-length"),
                "Content-Length duplicado");
    }

    @Test
    void emptyStreamReturnsNull() throws IOException {
        assertNull(Server.parseRequest(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void truncatedHeaders() {
        IOException ex = parseExpectingError("GET / HTTP/1.1\r\nHost: h\r\n");
        assertEquals("unexpected end of headers", ex.getMessage());
    }

    @Test
    void truncatedBody() {
        IOException ex = parseExpectingError("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc");
        assertTrue(ex.getMessage().contains("truncated body"));
    }

    private static final Path ROOT = Path.of("/srv/public");

    @Test
    void resolvesFileInsideRoot() {
        assertEquals(ROOT.resolve("index.html"), Server.safeResolve(ROOT, "/index.html"));
    }

    @Test
    void resolvesNestedFile() {
        assertEquals(ROOT.resolve("css/site.css"), Server.safeResolve(ROOT, "/css/site.css"));
    }

    @Test
    void allowsDotDotThatStaysInsideRoot() {
        assertEquals(ROOT.resolve("index.html"), Server.safeResolve(ROOT, "/a/../index.html"));
    }

    @Test
    void rejectsDotDotEscapingRoot() {
        assertNull(Server.safeResolve(ROOT, "/../x"));
    }

    @Test
    void rejectsDeepDotDotEscape() {
        assertNull(Server.safeResolve(ROOT, "/a/../../x"));
    }

    @Test
    void rejectsSiblingWithSamePrefix() {
        assertNull(Server.safeResolve(ROOT, "/../public2/x"));
    }

    @Test
    void rootPathReturnsRootItself() {
        assertEquals(ROOT, Server.safeResolve(ROOT, "/"));
    }


    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void readLineAtLimitPasses() throws IOException {
        assertEquals("abcde", Server.readLine(bytes("abcde\n"), 5));
    }

    @Test
    void readLineOverLimitFails() {
        IOException ex = assertThrows(IOException.class,
                () -> Server.readLine(bytes("abcdef\n"), 5));
        assertEquals("exceeded maximum line length", ex.getMessage());
    }

    @Test
    void readLineCountsCarriageReturn() throws IOException {
        assertEquals("abcd", Server.readLine(bytes("abcd\r\n"), 5));
    }

    @Test
    void readLineGivesUpWithoutReadingEverything() {
        var in = bytes("a".repeat(1000));
        assertThrows(IOException.class, () -> Server.readLine(in, 5));
        assertTrue(in.available() > 900, "não devia ter consumido a linha toda");
    }

    @Test
    void headerLineTooLong() {
        IOException ex = parseExpectingError(
                "GET / HTTP/1.1\r\nX: " + "a".repeat(Server.MAX_LINE + 1) + "\r\n\r\n");
        assertEquals("exceeded maximum line length", ex.getMessage());
    }

    @Test
    void bodyOverLimitRejectedBeforeReading() {
        IOException ex = parseExpectingError(
                "POST / HTTP/1.1\r\nContent-Length: " + (Server.MAX_BODY + 1) + "\r\n\r\n");
        assertEquals("exceeded maximum body length", ex.getMessage());
    }

    @Test
    void bodyExactlyAtLimitPasses() throws IOException {
        String body = "a".repeat(Server.MAX_BODY);
        Server.Request r = Server.parseRequest(
                bytes("POST / HTTP/1.1\r\nContent-Length: " + body.length() + "\r\n\r\n" + body));
        assertEquals(Server.MAX_BODY, r.body().length);
    }

    private static String requestWithHeaders(int n) {
        StringBuilder sb = new StringBuilder("GET / HTTP/1.1\r\n");
        for (int i = 0; i < n; i++) sb.append("H").append(i).append(": v\r\n");
        return sb.append("\r\n").toString();
    }

    @Test
    void exactlyMaxHeadersPasses() throws IOException {
        Server.Request r = Server.parseRequest(bytes(requestWithHeaders(Server.MAX_HEADERS)));
        assertEquals(Server.MAX_HEADERS, r.headers().size());
    }

    @Test
    void tooManyHeadersFails() {
        IOException ex = parseExpectingError(requestWithHeaders(Server.MAX_HEADERS + 1));
        assertEquals("too many headers", ex.getMessage());
    }


    @Test
    void malformedRequestIsBadRequest() {
        assertInstanceOf(Server.BadRequest.class, parseExpectingError("GET /a\r\n\r\n"));
        assertInstanceOf(Server.BadRequest.class, parseExpectingError("GET / HTTP/1.1\r\nXXX\r\n\r\n"));
        assertInstanceOf(Server.BadRequest.class,
                parseExpectingError("POST / HTTP/1.1\r\nContent-Length: abc\r\n\r\n"));
        assertInstanceOf(Server.BadRequest.class,
                parseExpectingError("POST / HTTP/1.1\r\nContent-Length: -5\r\n\r\n"));
    }

    @Test
    void exceededLimitsAreBadRequest() {
        assertInstanceOf(Server.BadRequest.class, parseExpectingError(
                "GET / HTTP/1.1\r\nX: " + "a".repeat(Server.MAX_LINE + 1) + "\r\n\r\n"));
        assertInstanceOf(Server.BadRequest.class, parseExpectingError(
                "POST / HTTP/1.1\r\nContent-Length: " + (Server.MAX_BODY + 1) + "\r\n\r\n"));
        assertInstanceOf(Server.BadRequest.class,
                parseExpectingError(requestWithHeaders(Server.MAX_HEADERS + 1)));
    }

    @Test
    void truncationIsNotBadRequest() {
        assertFalse(parseExpectingError("GET / HTTP/1.1\r\nHost: h\r\n") instanceof Server.BadRequest);
        assertFalse(parseExpectingError("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc")
                instanceof Server.BadRequest);
    }

    @Test
    void allowedMethodsListsMethodsForPath() {
        Server.Handler h = req -> null;
        Map<String, Server.Handler> routes = Map.of("GET /a", h, "POST /a", h, "GET /b", h);
        assertEquals("GET, POST", Server.allowedMethods(routes, "/a"));
        assertEquals("GET", Server.allowedMethods(routes, "/b"));
        assertEquals("", Server.allowedMethods(routes, "/c"));
    }

    @Test
    void methodNotAllowedHasStatusAndAllowHeader() {
        Server.Response res = Server.methodNotAllowed("GET, POST");
        assertEquals(405, res.statusCode());
        assertEquals("GET, POST", res.headers().get("Allow"));
    }
}
