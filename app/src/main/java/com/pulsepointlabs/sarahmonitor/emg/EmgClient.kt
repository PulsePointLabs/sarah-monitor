package com.pulsepointlabs.sarahmonitor.emg

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class EmgLink(val label: String) {
    NoDevice("No Arduino on USB"), NeedsPermission("Allow USB access"), Connecting("Connecting…"),
    Streaming("Receiving"), Silent("Connected, no data"), Error("Error")
}

/** Channel names and calibration, kept between launches. */
object EmgSettings {
    private var prefs: android.content.SharedPreferences? = null
    val names = MutableStateFlow(listOf("Perineum", "Sensor 2"))

    @Synchronized
    fun load(c: Context, processor: EmgProcessor) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences("sarahmonitor_emg", Context.MODE_PRIVATE)
        prefs = p
        names.value = listOf(p.getString("name0", "Perineum")!!, p.getString("name1", "Sensor 2")!!)
        processor.setNames(names.value)
        for (i in 0..1) {
            val r = p.getFloat("rest$i", -1f).takeIf { it >= 0 }?.toDouble()
            val h = p.getFloat("hold$i", -1f).takeIf { it >= 0 }?.toDouble()
            processor.setCalibration(i, r, h)
        }
        processor.onCalibrated = { ch, r, h -> p.edit().putFloat("rest$ch", r.toFloat()).putFloat("hold$ch", h.toFloat()).apply() }
    }

    fun setName(i: Int, name: String, processor: EmgProcessor) {
        names.value = names.value.toMutableList().also { it[i] = name }
        processor.setNames(names.value)
        prefs?.edit()?.putString("name$i", name)?.apply()
    }
}

/**
 * Reads Sarah's Arduino EMG sketch ("A0,A1" at 115200 baud, 100 Hz MyoWare ENV) over USB: plug the Arduino
 * into the phone (USB-C OTG) or the DeX dock. Works with Uno/Leonardo (CDC), Nano clones (CH340), FTDI and CP210x.
 */
class EmgClient(context: Context, private val onSamples: (Long, List<IntArray>) -> Unit) {
    private val app = context.applicationContext
    private val usb = app.getSystemService(UsbManager::class.java)
    private val parser = EmgLineParser()
    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private val _state = MutableStateFlow(EmgLink.NoDevice)
    val state: StateFlow<EmgLink> = _state
    private val _detail = MutableStateFlow<String?>(null)
    val detail: StateFlow<String?> = _detail
    @Volatile private var lastData = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect() else {
                    _state.value = EmgLink.NeedsPermission; _detail.value = "USB access was declined"
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { disconnect(); _state.value = EmgLink.NoDevice }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun driver(): UsbSerialDriver? {
        val known = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()
        if (known != null) return known
        // Any other device with a CDC serial interface (e.g. a newer Arduino board).
        return usb.deviceList.values.firstOrNull { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 2 || d.getInterface(it).interfaceClass == 10 } }
            ?.let { CdcAcmSerialDriver(it) }
    }

    fun connect() {
        if (port != null) return
        val d = driver()
        if (d == null) { _state.value = EmgLink.NoDevice; _detail.value = "Plug the Arduino into the phone or DeX dock (USB)"; return }
        val device: UsbDevice = d.device
        if (!usb.hasPermission(device)) {
            _state.value = EmgLink.NeedsPermission
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(device, PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags))
            return
        }
        _state.value = EmgLink.Connecting
        runCatching {
            val conn = usb.openDevice(device) ?: error("Couldn't open the USB device")
            val p = d.ports.first()
            p.open(conn)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { p.dtr = true }
            port = p
            lastData = System.currentTimeMillis()
            io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val now = System.currentTimeMillis()
                    lastData = now
                    val rows = parser.feed(data)
                    if (rows.isNotEmpty()) {
                        _state.value = EmgLink.Streaming
                        onSamples(now, rows)
                    }
                }

                override fun onRunError(e: Exception) {
                    _detail.value = "Serial stopped: ${e.message}"
                    disconnect()
                    _state.value = EmgLink.Error
                }
            }).also { it.start() }
            _detail.value = "${device.productName ?: "USB serial"} · ${d.javaClass.simpleName.removeSuffix("SerialDriver")} · 115200"
        }.onFailure {
            disconnect()
            _state.value = EmgLink.Error
            _detail.value = it.message ?: it.javaClass.simpleName
        }
    }

    /** Called every few seconds: an open port with nothing arriving (wrong sketch, Serial Monitor open on a PC…). */
    fun check() {
        if (port != null && _state.value != EmgLink.Connecting && System.currentTimeMillis() - lastData > 3_000L) {
            _state.value = EmgLink.Silent
            _detail.value = "Connected, but no A0,A1 lines at 115200 — check the sketch"
        }
    }

    fun disconnect() {
        runCatching { io?.stop() }
        io = null
        runCatching { port?.close() }
        port = null
        if (_state.value == EmgLink.Streaming || _state.value == EmgLink.Silent || _state.value == EmgLink.Connecting) _state.value = EmgLink.NoDevice
    }

    companion object {
        const val ACTION_PERMISSION = "com.pulsepointlabs.sarahmonitor.USB_PERMISSION"

        /** Sarah's sketch (unchanged): 100 Hz "A0,A1" at 115200 baud. */
        const val SKETCH = """// Sarah / Sarah Monitor: one or two MyoWare ENV outputs (10-bit ADC, e.g. Uno/Nano).
// Sensor 1 ENV -> A0; Sensor 2 ENV -> A1.
const unsigned long SAMPLE_US = 10000; // 100 envelope samples/second
unsigned long nextSample;
void setup() {
  Serial.begin(115200);
  nextSample = micros();
}
void loop() {
  unsigned long now = micros();
  if ((long)(now - nextSample) >= 0) {
    nextSample = now + SAMPLE_US;
    int sensor1 = analogRead(A0);
    int sensor2 = analogRead(A1);
    Serial.print(sensor1);
    Serial.print(',');
    Serial.println(sensor2);
  }
}
"""
    }
}
