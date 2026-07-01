import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class ManifestServer {
    public static void main(String[] args) throws Exception {
        int port = 8085;
        final String manifest = "{\"wallpapers\":["
            + "{\"type\":\"image\",\"url\":\"https://picsum.photos/seed/ts1/1080/1920\"},"
            + "{\"type\":\"image\",\"url\":\"https://picsum.photos/seed/ts2/1080/1920\"},"
            + "{\"type\":\"gif\",\"url\":\"https://upload.wikimedia.org/wikipedia/commons/2/2c/Rotating_earth_%28large%29.gif\"},"
            + "{\"type\":\"video\",\"url\":\"https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4\"}"
            + "]}";
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/manifest.json", exchange -> {
            byte[] b = manifest.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            OutputStream os = exchange.getResponseBody();
            os.write(b);
            os.close();
        });
        server.setExecutor(null);
        server.start();
        System.out.println("Serving manifest on http://localhost:" + port + "/manifest.json");
    }
}
