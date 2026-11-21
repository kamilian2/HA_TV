package pl.kamil.hatv

import android.app.Application
import android.util.Log
import fi.iki.elonen.NanoHTTPD

/** Wspólne obiekty aplikacji: ustawienia, połączenie z HA, serwer stron ustawień. */
class App : Application() {

    companion object {
        lateinit var instance: App
            private set
    }

    lateinit var config: Config
        private set
    lateinit var ha: HaClient
        private set
    private var server: ApiServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger(this)
        Mdi.init(this)
        config = Config(this)
        ha = HaClient(config)

        var lastUrl = config.url
        var lastToken = config.token
        config.addListener {
            // nowy adres albo token -> połącz od nowa
            if (config.url != lastUrl || config.token != lastToken) {
                lastUrl = config.url; lastToken = config.token
                ha.connect()
            }
        }
        ha.connect()
        startServer()
    }

    fun startServer() {
        if (server?.isAlive == true) return
        server = try {
            val v = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
            ApiServer(API_PORT, this, v).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        } catch (e: Exception) {
            Log.e("HaTv", "Serwer ustawień nie wystartował", e)
            null
        }
    }
}
