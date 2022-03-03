package five.ec1cff.intentforwarder

import android.util.Log
import de.robv.android.xposed.XposedHelpers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

val VAL_STRING: Int by lazy {
    try {
        return@lazy XposedHelpers.getStaticIntField(
            Class.forName("android.os.Parcel"),
            "VAL_STRING"
        )
    } catch (e: Throwable) {

    }
    return@lazy 0
}

fun ByteArray.indexOf(target: ByteArray, from: Int = 0): Int {
    outer@ for (i in from..this.size - target.size) {
        for (j in target.indices) {
            if (this[i + j] != target[j]) continue@outer
        }
        return i
    }
    return -1
}

fun ByteArray.readString(from: Int): String? {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    buffer.position(from)
    Log.d("IntentForwardParcelableHelper", "readString pos=${buffer.position()}")
    if (buffer.remaining() < 4) return null
    val len = buffer.int
    Log.d("IntentForwardParcelableHelper", "readString pos=${buffer.position()}")
    if (buffer.remaining() < (1 + len) * 2) return null
    Log.d("IntentForwardParcelableHelper", "readString pos=${buffer.position()}")
    val pos = buffer.position()
    buffer.position(pos + len * 2)
    if (buffer.char != '\u0000') return null
    return String(this, pos, len * 2, StandardCharsets.UTF_16LE)
}

fun ByteArray.hexDump(): String {
    return this.joinToString(separator = " ") { ((it + 256) % 256).toString(16).padStart(2, '0') }
}

fun ByteArray.searchKeyAndValue(key: String, cond: (String) -> Boolean): String? {
    Log.d("IntentForwardParcelableHelper", "search $key in ${this.size} bytes ${this.hexDump()}")
    val target =
        ByteBuffer.allocate(4 + key.length * 2 + 2 + 4).order(ByteOrder.LITTLE_ENDIAN).let {
            it.putInt(key.length)
            key.forEach { ch -> it.putChar(ch) }
            it.putChar('\u0000')
            it.putInt(VAL_STRING)
            it.array()
        }
    val array = this
    var index = -1
    do {
        index = array.indexOf(target, index + 1)
        Log.d("IntentForwardParcelableHelper", "found $key at $index")
        array.readString(index + target.size)?.let {
            Log.d("IntentForwardParcelableHelper", "found $key at $index, value=$it")
            if (cond(it)) return it
        }
    } while (index != -1)
    return null
}
