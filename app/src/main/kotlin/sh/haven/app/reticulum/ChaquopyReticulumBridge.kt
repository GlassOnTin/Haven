package sh.haven.app.reticulum

import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import sh.haven.core.reticulum.ReticulumBridge
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReticulumBridge"

/**
 * Chaquopy-backed implementation of [ReticulumBridge].
 *
 * Wraps calls to `haven_reticulum.py`. All methods must be called
 * from a background thread (Chaquopy requirement).
 */
@Singleton
class ChaquopyReticulumBridge @Inject constructor() : ReticulumBridge {

    private val module: PyObject by lazy {
        Python.getInstance().getModule("haven_reticulum")
    }

    @Volatile
    private var initialised = false

    override fun isInitialised(): Boolean = initialised

    override fun initReticulum(
        configDir: String,
        rpcKey: String?,
        host: String,
        port: Int,
    ): String {
        val result = module.callAttr(
            "init_reticulum",
            configDir,
            host,
            port,
            rpcKey,
        )
        initialised = true
        Log.d(TAG, "Reticulum initialised, identity: ${result.toString()}")
        return result.toString()
    }

    override fun resolveDestination(destinationHashHex: String): Boolean {
        return module.callAttr("resolve_destination", destinationHashHex)
            .toBoolean()
    }

    override fun createSession(destinationHashHex: String, sessionId: String): String {
        return module.callAttr("create_session", destinationHashHex, sessionId)
            .toString()
    }

    override fun readOutput(sessionId: String, timeoutMs: Int): ByteArray? {
        val result = module.callAttr("read_output", sessionId, timeoutMs)
        if (result == null || result.toJava(Any::class.java) == null) {
            return null
        }
        return result.toJava(ByteArray::class.java)
    }

    override fun sendInput(sessionId: String, data: ByteArray): Boolean {
        return module.callAttr("send_input", sessionId, data).toBoolean()
    }

    override fun resizeSession(sessionId: String, cols: Int, rows: Int) {
        module.callAttr("resize_session", sessionId, cols, rows)
    }

    override fun isConnected(sessionId: String): Boolean {
        return module.callAttr("is_connected", sessionId).toBoolean()
    }

    override fun closeSession(sessionId: String) {
        module.callAttr("close_session", sessionId)
    }

    override fun closeAll() {
        module.callAttr("close_all")
        // Don't reset initialised — RNS cannot be restarted once running
    }

    override fun getInitMode(): String? {
        val result = module.callAttr("get_init_mode")
        return result?.toString()
    }
}
