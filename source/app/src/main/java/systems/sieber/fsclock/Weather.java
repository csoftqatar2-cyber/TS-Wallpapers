package systems.sieber.fsclock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Fetches the current weather. The location is resolved in this priority order:
 * 1) real device GPS/network coordinates (gpsLat/gpsLon) when the caller supplies
 *    them (weather follows the car), otherwise
 * 2) a user-entered city name (Open-Meteo geocoding, free, no key), otherwise
 * 3) approximate from the device IP (ip-api.com).
 * Then the current temperature + weather code is read from Open-Meteo (free, no key)
 * and the result (e.g. "☀ مشمس 28°") is delivered on the main thread.
 */
public class Weather {

    public interface WeatherCallback {
        void onResult(String text); // null on failure
    }

    public static void fetch(final Context context, final boolean celsius, final String city, final WeatherCallback cb) {
        fetch(context, celsius, null, null, city, cb);
    }

    public static void fetch(final Context context, final boolean celsius, final Double gpsLat, final Double gpsLon,
                             final String city, final WeatherCallback cb) {
        // Application context so the worker thread cannot hold an Activity, but wrapped, because
        // the raw application context resolves strings in the *system* locale and would ignore
        // the in-app language switch. wrap() re-reads the pref, so this is right at fetch time.
        final Context appContext = LocaleHelper.wrap(context.getApplicationContext());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result = null;
                try {
                    double lat, lon;
                    if(gpsLat != null && gpsLon != null) {
                        // 1) real device location -> weather follows the car
                        lat = gpsLat;
                        lon = gpsLon;
                    } else if(city != null && !city.trim().isEmpty()) {
                        // 2) geocode the user-entered city name
                        String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=ar&name="
                                + URLEncoder.encode(city.trim(), "UTF-8");
                        JSONObject geo = new JSONObject(httpGet(geoUrl));
                        JSONArray results = geo.optJSONArray("results");
                        if(results == null || results.length() == 0) throw new Exception("city not found");
                        JSONObject place = results.getJSONObject(0);
                        lat = place.getDouble("latitude");
                        lon = place.getDouble("longitude");
                    } else {
                        // 3) geolocate by IP
                        JSONObject loc = new JSONObject(httpGet("http://ip-api.com/json/?fields=lat,lon"));
                        lat = loc.getDouble("lat");
                        lon = loc.getDouble("lon");
                    }

                    // 2) current weather (is_day lets us tell clear day from clear night)
                    String unit = celsius ? "celsius" : "fahrenheit";
                    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                            + "&longitude=" + lon
                            + "&current=temperature_2m,weather_code,is_day&temperature_unit=" + unit;
                    JSONObject root = new JSONObject(httpGet(url));
                    JSONObject current = root.getJSONObject("current");
                    double temp = current.getDouble("temperature_2m");
                    int code = current.optInt("weather_code", 0);
                    boolean day = current.optInt("is_day", 1) == 1;
                    result = emoji(code, day) + " " + describe(appContext, code, day) + " " + Math.round(temp) + "°";
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

    private static String describe(Context context, int code, boolean day) {
        if(code == 0) return context.getString(day ? R.string.weather_sunny : R.string.weather_clear_night);
        if(code >= 1 && code <= 3) return context.getString(R.string.weather_partly_cloudy);
        if(code == 45 || code == 48) return context.getString(R.string.weather_fog);
        if(code >= 51 && code <= 67) return context.getString(R.string.weather_rainy);
        if(code >= 71 && code <= 77) return context.getString(R.string.weather_snowy);
        if(code >= 80 && code <= 82) return context.getString(R.string.weather_showers);
        if(code >= 95) return context.getString(R.string.weather_storm);
        return context.getString(R.string.weather_cloudy);
    }

    private static String emoji(int code, boolean day) {
        if(code == 0) return day ? "☀" : "🌙";     // ☀ clear day / 🌙 clear night
        if(code >= 1 && code <= 3) return day ? "⛅" : "☁"; // partly cloudy
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
