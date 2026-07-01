package systems.sieber.fsclock;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fetches the current weather without requiring location permission:
 * 1) approximate location from the device IP (ip-api.com, free, no key)
 * 2) current temperature + weather code from Open-Meteo (free, no key)
 * The result (e.g. "☀ 28°") is delivered on the main thread.
 */
public class Weather {

    public interface WeatherCallback {
        void onResult(String text); // null on failure
    }

    public static void fetch(final boolean celsius, final WeatherCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result = null;
                try {
                    // 1) geolocate by IP
                    JSONObject loc = new JSONObject(httpGet("http://ip-api.com/json/?fields=lat,lon"));
                    double lat = loc.getDouble("lat");
                    double lon = loc.getDouble("lon");

                    // 2) current weather
                    String unit = celsius ? "celsius" : "fahrenheit";
                    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                            + "&longitude=" + lon
                            + "&current=temperature_2m,weather_code&temperature_unit=" + unit;
                    JSONObject root = new JSONObject(httpGet(url));
                    JSONObject current = root.getJSONObject("current");
                    double temp = current.getDouble("temperature_2m");
                    int code = current.optInt("weather_code", 0);
                    result = emoji(code) + " " + describe(code) + " " + Math.round(temp) + "°";
                } catch(Exception ignored) { }

                final String fr = result;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if(cb != null) cb.onResult(fr);
                    }
                });
            }
        }).start();
    }

    private static String describe(int code) {
        if(code == 0) return "مشمس";
        if(code >= 1 && code <= 3) return "غيوم جزئية";
        if(code == 45 || code == 48) return "ضباب";
        if(code >= 51 && code <= 67) return "ممطر";
        if(code >= 71 && code <= 77) return "ثلوج";
        if(code >= 80 && code <= 82) return "زخات مطر";
        if(code >= 95) return "عاصفة";
        return "غائم";
    }

    private static String emoji(int code) {
        if(code == 0) return "☀";                 // ☀ clear
        if(code >= 1 && code <= 3) return "⛅";    // ⛅ partly cloudy
        if(code == 45 || code == 48) return "☁";  // ☁ fog
        if(code >= 51 && code <= 67) return "🌧"; // 🌧 rain
        if(code >= 71 && code <= 77) return "❄";  // ❄ snow
        if(code >= 80 && code <= 82) return "🌦"; // 🌦 showers
        if(code >= 95) return "⛈";                // ⛈ thunderstorm
        return "☁";                               // ☁ default
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setInstanceFollowRedirects(true);
        StringBuilder sb = new StringBuilder();
        InputStreamReader reader = new InputStreamReader(conn.getInputStream());
        char[] buf = new char[2048];
        int n;
        while((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
        reader.close();
        conn.disconnect();
        return sb.toString();
    }
}
