package fold.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/**
 * fold-server: GET /route /snap /search /health. Configuration by environment:
 *   PORT         listen port (8080)
 *   PACK_DIR     where graphs are cached on disk (./packs-cache)
 *   CATALOG_URL  the pack catalog (https://hondoroadbook.com/packs/catalog.json)
 *   PRELOAD      space-separated pack ids to load before serving (none)
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dir = File(System.getenv("PACK_DIR") ?: "packs-cache")
    val catalogUrl = System.getenv("CATALOG_URL") ?: "https://hondoroadbook.com/packs/catalog.json"
    val packs = PackSet(catalogUrl, dir)
    val api = Api(packs)
    System.getenv("PRELOAD")?.split(' ')?.filter { it.isNotBlank() }?.forEach { id ->
        try { packs.catalog().firstOrNull { it.id == id }?.let { packs.ensure(it) } ?: System.err.println("preload: no pack $id") } catch (e: Throwable) { System.err.println("preload $id failed: ${e.message}") }
    }
    val server = HttpServer.create(InetSocketAddress(port), 16)
    // eight routing workers and a short wait: beyond that a request is answered 503 at once rather than queued for
    // minutes. The admission check runs inside the handler (a rejected executor task would only reset the connection),
    // so the HTTP threads are plentiful and cheap and the routing permits are what is scarce.
    server.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "http").apply { isDaemon = true } }
    server.createContext("/") { ex -> handle(ex, api) }
    server.start()
    System.err.println("fold-server on :$port, cache ${dir.absolutePath}")
}

/** eight at a time, sixteen waiting up to two seconds; the rest are told to come back */
private val permits = java.util.concurrent.Semaphore(8, true)
private val waiting = java.util.concurrent.atomic.AtomicInteger()

private fun handle(ex: HttpExchange, api: Api) {
    val t0 = System.currentTimeMillis()
    var status = 200
    var held = false
    try {
        if (ex.requestMethod == "OPTIONS") { cors(ex); ex.sendResponseHeaders(204, -1); return }
        if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") throw ApiError(405, "GET only")
        val q = query(ex.requestURI.rawQuery)
        val path = ex.requestURI.path.trimEnd('/')
        if (path != "/health" && path != "") {
            if (waiting.incrementAndGet() > 16) { waiting.decrementAndGet(); throw ApiError(503, "The router is busy; try again in a moment", mapOf("retry" to "2")) }
            try { held = permits.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS) } finally { waiting.decrementAndGet() }
            if (!held) throw ApiError(503, "The router is busy; try again in a moment", mapOf("retry" to "2"))
        }
        val body = when (path) {
            "/health", "" -> api.health()
            "/warm" -> api.warm(q)
            "/near" -> api.near(q)
            "/route" -> api.route(q)
            "/snap" -> api.snap(q)
            "/search" -> api.search(q)
            else -> throw ApiError(404, "no such endpoint")
        }
        send(ex, 200, body)
    } catch (e: ApiError) {
        status = e.status
        send(ex, e.status, Json.obj(*(listOf("error" to Json.str(e.message ?: "error")) + e.extra.map { it.key to Json.str(it.value) }).toTypedArray()))
    } catch (e: java.util.concurrent.RejectedExecutionException) {
        status = 503
        send(ex, 503, Json.obj("error" to Json.str("The router is busy; try again in a moment")))
    } catch (e: Throwable) {
        status = 500
        // the class of the failure, never the request: query strings carry people's routes
        System.err.println("500 ${ex.requestURI.path}: ${e.javaClass.simpleName}")
        send(ex, 500, Json.obj("error" to Json.str("Router error (" + e.javaClass.simpleName + ")")))
    } finally {
        if (held) permits.release()
        System.err.println("${ex.requestMethod} ${ex.requestURI.path} $status ${System.currentTimeMillis() - t0}ms")
        ex.close()
    }
}

private fun cors(ex: HttpExchange) {
    ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
    ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, OPTIONS")
    ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
}

private fun send(ex: HttpExchange, status: Int, body: String) {
    cors(ex)
    ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    ex.responseHeaders.add("Cache-Control", "no-store")
    if (status == 503) ex.responseHeaders.add("Retry-After", "2")
    var bytes = body.toByteArray(Charsets.UTF_8)
    if (bytes.size > 1024 && (ex.requestHeaders.getFirst("Accept-Encoding") ?: "").contains("gzip")) {
        val bo = ByteArrayOutputStream(bytes.size / 3); GZIPOutputStream(bo).use { it.write(bytes) }; bytes = bo.toByteArray()
        ex.responseHeaders.add("Content-Encoding", "gzip")
    }
    if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(status, -1); return }
    ex.sendResponseHeaders(status, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun query(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val m = HashMap<String, String>()
    for (part in raw.split('&')) { if (part.isEmpty()) continue; val i = part.indexOf('='); val k = if (i < 0) part else part.substring(0, i); val v = if (i < 0) "" else part.substring(i + 1)
        m[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8") }
    return m
}
