import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Need URL")
        return
    }
    val url = URL(args[0])
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
    // ExoPlayer's range
    conn.setRequestProperty("Range", "bytes=0-2477")
    
    conn.connect()
    println("Response Code: ${conn.responseCode}")
    println("Headers: ${conn.headerFields}")
    
    val input: InputStream = conn.inputStream
    val bytes = input.readBytes()
    println("Read ${bytes.size} bytes")
    
    val hex = bytes.take(100).joinToString(" ") { String.format("%02X", it) }
    println("First 100 bytes: $hex")
}
