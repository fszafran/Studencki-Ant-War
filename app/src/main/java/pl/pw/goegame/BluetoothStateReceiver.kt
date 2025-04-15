package pl.pw.goegame

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class BluetoothStateReceiver(private val onBluetoothEnabled: () -> Unit, private val onBluetoothDisabled: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when(state) {
                BluetoothAdapter.STATE_OFF -> onBluetoothDisabled()
                BluetoothAdapter.STATE_TURNING_OFF -> this.addToast(context, "BT IS TURNING OFF")
                BluetoothAdapter.STATE_ON -> onBluetoothEnabled()
                BluetoothAdapter.STATE_TURNING_ON -> this.addToast(context, "BT IS TURNING ON")
            }
        }
    }

    private fun addToast(context: Context?, text: String){
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

}