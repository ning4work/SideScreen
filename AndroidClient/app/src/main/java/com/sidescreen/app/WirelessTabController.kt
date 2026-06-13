package com.sidescreen.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

class WirelessTabController(
    private val activity: Activity,
    private val views: Views,
    private val storage: PairedHostStorage,
    private val cameraPerm: CameraPermissionManager,
    private val onConnectRequested: (
        host: String,
        port: Int,
        token: ByteArray,
        deviceName: String,
        macName: String,
    ) -> Unit,
) {
    data class Views(
        val connecting: View,
        val firstTime: View,
        val connected: View,
        val pairedIdle: View,
        val repair: View,
        val permDenied: View,
        val scanButton: Button,
        val rescanButton: Button,
        val disconnectButton: Button,
        val forgetButton: Button,
        val reconnectButton: Button,
        val idleForgetButton: Button,
        val openSettingsButton: Button,
        val connectedMacName: TextView,
        val connectedMacIp: TextView,
        val connectingLabel: TextView,
        val connectingSubtitle: TextView,
        val idleMacName: TextView,
        val idleMacIp: TextView,
        val repairTitle: TextView,
        val repairMessage: TextView,
        val repairReconnectButton: Button,
        val historyContainer: LinearLayout,
        val historyList: LinearLayout,
        val manualInputButton: Button,
    )

    enum class State { FIRST_TIME, CONNECTING, CONNECTED, PAIRED_IDLE, REPAIR_NEEDED, PERM_DENIED }

    private var state: State = State.FIRST_TIME
    private var lastFailedEntry: PairedHostStorage.Entry? = null

    fun bind() {
        views.scanButton.setOnClickListener { triggerScan() }
        views.rescanButton.setOnClickListener { triggerScan() }
        views.openSettingsButton.setOnClickListener { cameraPerm.openAppSettings() }
        views.forgetButton.setOnClickListener {
            val entry = storage.load() ?: run { transition(State.FIRST_TIME); return@setOnClickListener }
            storage.remove(entry)
            refreshAfterForget()
        }
        views.idleForgetButton.setOnClickListener {
            val entry = storage.load() ?: run { transition(State.FIRST_TIME); return@setOnClickListener }
            storage.remove(entry)
            refreshAfterForget()
        }
        views.reconnectButton.setOnClickListener {
            val entry = storage.load() ?: run {
                transition(State.FIRST_TIME)
                return@setOnClickListener
            }
            showConnecting("Reconnecting to ${entry.macName}", "${entry.host}:${entry.port}")
            attemptAutoConnect(entry)
        }
        views.repairReconnectButton.setOnClickListener {
            val entry = lastFailedEntry ?: storage.load() ?: run {
                transition(State.FIRST_TIME)
                return@setOnClickListener
            }
            showConnecting("Reconnecting to ${entry.macName}", "${entry.host}:${entry.port}")
            attemptAutoConnect(entry)
        }
        views.manualInputButton.setOnClickListener { showManualInputDialog() }
    }

    private fun refreshAfterForget() {
        val remaining = storage.loadAll()
        if (remaining.isEmpty()) {
            transition(State.FIRST_TIME)
        } else {
            val top = remaining.first()
            views.idleMacName.text = top.macName
            views.idleMacIp.text = "${top.host}:${top.port}"
            transition(State.PAIRED_IDLE)
        }
        populateHistory()
    }

    fun onStreamDisconnected() {
        android.util.Log.i(
            "WirelessTabController",
            "onStreamDisconnected called, current state=$state, storage entry exists=${storage.load() != null}",
        )
        val entry = storage.load() ?: run {
            transition(State.FIRST_TIME)
            return
        }
        views.idleMacName.text = entry.macName
        views.idleMacIp.text = "${entry.host}:${entry.port}"
        transition(State.PAIRED_IDLE)
        populateHistory()
    }

    private fun transition(next: State) {
        android.util.Log.i("WirelessTabController", "transition $state -> $next")
        state = next
        views.connecting.visibility = if (next == State.CONNECTING) View.VISIBLE else View.GONE
        views.firstTime.visibility = if (next == State.FIRST_TIME) View.VISIBLE else View.GONE
        views.connected.visibility = if (next == State.CONNECTED) View.VISIBLE else View.GONE
        views.pairedIdle.visibility = if (next == State.PAIRED_IDLE) View.VISIBLE else View.GONE
        views.repair.visibility = if (next == State.REPAIR_NEEDED) View.VISIBLE else View.GONE
        views.permDenied.visibility = if (next == State.PERM_DENIED) View.VISIBLE else View.GONE
        val showHistory = next != State.CONNECTING && next != State.CONNECTED
        views.historyContainer.visibility = if (showHistory && storage.loadAll().size > 1) View.VISIBLE else View.GONE
        views.manualInputButton.visibility = if (showHistory) View.VISIBLE else View.GONE
    }

    fun show() {
        when {
            cameraPerm.isPermanentlyDenied() -> transition(State.PERM_DENIED)
            storage.load() == null -> transition(State.FIRST_TIME)
            else -> {
                val entry = storage.load()!!
                views.idleMacName.text = entry.macName
                views.idleMacIp.text = "${entry.host}:${entry.port}"
                transition(State.PAIRED_IDLE)
            }
        }
        populateHistory()
    }

    fun onScanResult(url: String) {
        val parsed = PairingURL.parse(url) ?: return
        val deviceName = (android.os.Build.MODEL ?: "Android").take(64)
        storage.save(PairedHostStorage.Entry(parsed.host, parsed.port, parsed.token, parsed.macName))
        showConnecting("Connecting to ${parsed.macName}", "${parsed.host}:${parsed.port}")
        onConnectRequested(parsed.host, parsed.port, parsed.token, deviceName, parsed.macName)
    }

    fun onConnectError(error: StreamClient.WirelessConnectError) {
        val cached = storage.load()
        lastFailedEntry = cached
        when (error) {
            is StreamClient.WirelessConnectError.NetworkUnreachable -> {
                views.repairTitle.text = "Couldn't reach Mac"
                views.repairMessage.text =
                    if (cached != null) {
                        "No response from ${cached.macName} at ${cached.host}:${cached.port}.\n\n" +
                            "The Mac may have switched WiFi networks, changed its port, or is not " +
                            "running. You can retry, scan a new QR, or enter the address manually."
                    } else {
                        "No response from your Mac. Make sure both devices are on the same WiFi " +
                            "and the Mac app is running."
                    }
                views.repairReconnectButton.visibility = if (cached != null) View.VISIBLE else View.GONE
                transition(State.REPAIR_NEEDED)
            }
            is StreamClient.WirelessConnectError.TokenRejected -> {
                views.repairTitle.text = "Re-pair required"
                views.repairMessage.text =
                    if (cached != null) {
                        "${cached.macName} reset its pairing token (e.g. Reset Token clicked, or " +
                            "reinstalled). Scan the new QR or enter new credentials manually."
                    } else {
                        "The Mac reset its pairing token. Scan the new QR to pair again."
                    }
                views.repairReconnectButton.visibility = View.GONE
                transition(State.REPAIR_NEEDED)
            }
            is StreamClient.WirelessConnectError.ProtocolError -> {
                views.repairTitle.text = "Connection error"
                views.repairMessage.text = "Couldn't complete the secure handshake with the Mac."
                views.repairReconnectButton.visibility = if (cached != null) View.VISIBLE else View.GONE
                transition(State.REPAIR_NEEDED)
            }
        }
        populateHistory()
    }

    private fun showConnecting(title: String, subtitle: String) {
        views.connectingLabel.text = title
        views.connectingSubtitle.text = subtitle
        transition(State.CONNECTING)
    }

    fun onConnectSuccess(macName: String, ip: String) {
        views.connectedMacName.text = macName
        views.connectedMacIp.text = ip
        lastFailedEntry = null
        transition(State.CONNECTED)
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            launchScanner()
        } else if (cameraPerm.isPermanentlyDenied()) {
            transition(State.PERM_DENIED)
        }
    }

    private fun triggerScan() {
        if (cameraPerm.isPermanentlyDenied()) {
            transition(State.PERM_DENIED)
            return
        }
        if (!cameraPerm.isGranted()) {
            cameraPerm.request(REQ_CAMERA)
            return
        }
        launchScanner()
    }

    private fun launchScanner() {
        val intent = Intent(activity, QRScannerActivity::class.java)
        activity.startActivityForResult(intent, REQ_SCAN)
    }

    private fun attemptAutoConnect(entry: PairedHostStorage.Entry) {
        val deviceName = (android.os.Build.MODEL ?: "Android").take(64)
        onConnectRequested(entry.host, entry.port, entry.token, deviceName, entry.macName)
    }

    private fun populateHistory() {
        val all = storage.loadAll()
        views.historyList.removeAllViews()
        if (all.size <= 1) {
            views.historyContainer.visibility = View.GONE
            return
        }
        // Skip the first entry (it's the "current" one shown in idle/repair state)
        val others = all.drop(1)
        for (entry in others) {
            val row = LayoutInflater.from(activity).inflate(R.layout.item_host_history, views.historyList, false)
            row.findViewById<TextView>(R.id.historyMacName).text = entry.macName
            row.findViewById<TextView>(R.id.historyMacAddr).text = "${entry.host}:${entry.port}"
            row.findViewById<View>(R.id.historyConnectButton).setOnClickListener {
                storage.save(entry)
                showConnecting("Connecting to ${entry.macName}", "${entry.host}:${entry.port}")
                attemptAutoConnect(entry)
            }
            row.findViewById<View>(R.id.historyDeleteButton).setOnClickListener {
                storage.remove(entry)
                populateHistory()
            }
            views.historyList.addView(row)
        }
        if (state != State.CONNECTING && state != State.CONNECTED) {
            views.historyContainer.visibility = View.VISIBLE
        }
    }

    private fun showManualInputDialog() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_manual_wireless, null)
        val hostInput = dialogView.findViewById<EditText>(R.id.manualHost)
        val portInput = dialogView.findViewById<EditText>(R.id.manualPort)
        val passphraseInput = dialogView.findViewById<EditText>(R.id.manualPassphrase)

        val cached = storage.load()
        if (cached != null) {
            hostInput.setText(cached.host)
            portInput.setText(cached.port.toString())
        }

        AlertDialog.Builder(activity, R.style.DarkDialogTheme)
            .setTitle("Connect with Passphrase")
            .setView(dialogView)
            .setPositiveButton("Connect") { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull() ?: 54321
                val passphrase = passphraseInput.text.toString()

                if (host.isEmpty() || passphrase.isEmpty()) {
                    AlertDialog.Builder(activity, R.style.DarkDialogTheme)
                        .setMessage("IP address and passphrase are required.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setPositiveButton
                }

                val token = MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray(Charsets.UTF_8))
                val name = "Mac"
                val entry = PairedHostStorage.Entry(host, port, token, name)
                storage.save(entry)
                showConnecting("Connecting to $host", "$host:$port")
                attemptAutoConnect(entry)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val REQ_SCAN = 1001
        const val REQ_CAMERA = 1002
    }
}
