package com.example.hammerdroid.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager

private class GtFlashWindow : JFrame("GT FLASH") {
    private val transport = SerialTransport()
    private val client = ObdxLinuxClient(transport)
    private val executor = Executors.newSingleThreadExecutor()
    private var activeTask: Future<*>? = null
    private var currentVin: String? = null
    private var vinVisible = false

    private val portModel = DefaultComboBoxModel<PortChoice>()
    private val ports = JComboBox(portModel)
    private val refresh = JButton("Refresh")
    private val connect = JButton("Connect")
    private val disconnect = JButton("Disconnect")
    private val identify = JButton("Identify PCM")
    private val reset = JButton("Reset adapter")
    private val revealVin = JButton("Show VIN")
    private val status = JLabel("Disconnected")
    private val adapter = JLabel("—")
    private val voltage = JLabel("—")
    private val osId = JLabel("—")
    private val vin = JLabel("—")
    private val logArea = JTextArea(9, 60)

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(760, 560)
        contentPane = buildUi()
        wireActions()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                activeTask?.cancel(true)
                transport.close()
                executor.shutdownNow()
                dispose()
            }
        })
        refreshPorts()
        setLocationRelativeTo(null)
    }

    private fun buildUi(): JPanel {
        val root = JPanel(BorderLayout(16, 16))
        root.border = BorderFactory.createEmptyBorder(18, 18, 18, 18)

        val title = JLabel("GT FLASH")
        title.font = title.font.deriveFont(Font.BOLD, 28f)
        val subtitle = JLabel("OBDX Pro GT · Bluetooth RFCOMM · J1850 VPW")
        subtitle.foreground = Color(115, 125, 140)

        val header = JPanel()
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        header.add(title)
        header.add(Box.createVerticalStrut(4))
        header.add(subtitle)
        root.add(header, BorderLayout.NORTH)

        val center = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = Insets(7, 7, 7, 7)
        }

        var row = 0
        addRow(center, constraints, row++, "Serial device", ports)
        val connectControls = JPanel().apply {
            add(refresh)
            add(connect)
            add(disconnect)
            add(reset)
        }
        addRow(center, constraints, row++, "Connection", connectControls)
        addRow(center, constraints, row++, "Status", status)
        addRow(center, constraints, row++, "Adapter", adapter)
        addRow(center, constraints, row++, "Vehicle voltage", voltage)
        addRow(center, constraints, row++, "OS ID", osId)

        val vinPanel = JPanel().apply {
            add(vin)
            add(revealVin)
        }
        addRow(center, constraints, row++, "VIN", vinPanel)

        val actionPanel = JPanel().apply {
            add(identify)
        }
        addRow(center, constraints, row++, "PCM", actionPanel)

        val guidance = JLabel(
            "<html><b>No RFCOMM device?</b> Pair the GT in Bluetooth settings, then run " +
                "<code>sudo rfcomm bind /dev/rfcomm0 &lt;ADAPTER_MAC&gt; 1</code>. " +
                "If Bluetooth is disabled, enable it before refreshing.</html>",
        )
        constraints.gridx = 0
        constraints.gridy = row
        constraints.gridwidth = 2
        constraints.weightx = 1.0
        center.add(guidance, constraints)
        root.add(center, BorderLayout.CENTER)

        logArea.isEditable = false
        logArea.lineWrap = true
        logArea.wrapStyleWord = true
        logArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        val logPanel = JPanel(BorderLayout(0, 6))
        logPanel.add(JLabel("Activity (last 50 entries; VIN and adapter address are not logged)"), BorderLayout.NORTH)
        logPanel.add(JScrollPane(logArea), BorderLayout.CENTER)
        root.add(logPanel, BorderLayout.SOUTH)

        setConnected(false)
        return root
    }

    private fun addRow(
        panel: JPanel,
        constraints: GridBagConstraints,
        row: Int,
        label: String,
        component: java.awt.Component,
    ) {
        constraints.gridy = row
        constraints.gridx = 0
        constraints.gridwidth = 1
        constraints.weightx = 0.0
        panel.add(JLabel(label), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        panel.add(component, constraints)
    }

    private fun wireActions() {
        refresh.addActionListener { refreshPorts() }
        connect.addActionListener {
            val selected = ports.selectedItem as? PortChoice
            if (selected == null) {
                showError("No serial device selected. Pair and bind the adapter, then press Refresh.")
                return@addActionListener
            }
            runTask("Connecting…") {
                transport.open(selected)
                try {
                    val info = client.initialize(::appendLog)
                    onUi {
                        adapter.text = info.name + " · FW " + info.firmware + " · HW " + info.hardware
                        voltage.text = "%.2f V".format(info.voltage)
                        status.text = "Connected"
                        setConnected(true)
                    }
                    appendLog("Adapter connected and initialized")
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            }
        }
        disconnect.addActionListener { disconnectNow("Disconnected by user") }
        reset.addActionListener {
            runTask("Resetting adapter…") {
                client.resetAdapter()
                transport.close()
                onUi {
                    status.text = "Adapter reset; reconnect required"
                    setConnected(false)
                }
                appendLog("Adapter reset")
            }
        }
        identify.addActionListener {
            runTask("Identifying PCM…") {
                val identity = client.identifyPcm(::appendLog)
                currentVin = identity.vin
                vinVisible = false
                onUi {
                    osId.text = identity.osId.toString()
                    vin.text = maskedVin(identity.vin)
                    revealVin.text = "Show VIN"
                    status.text = "PCM identified"
                }
                appendLog("PCM identified; sensitive values shown only in fields")
            }
        }
        revealVin.addActionListener {
            val value = currentVin ?: return@addActionListener
            vinVisible = !vinVisible
            vin.text = if (vinVisible) value else maskedVin(value)
            revealVin.text = if (vinVisible) "Hide VIN" else "Show VIN"
        }
    }

    private fun refreshPorts() {
        val previous = (ports.selectedItem as? PortChoice)?.path
        val found = runCatching { transport.ports() }.getOrElse {
            showError(it.message ?: "Unable to enumerate serial devices")
            emptyList()
        }
        portModel.removeAllElements()
        found.forEach(portModel::addElement)
        if (previous != null) {
            found.indexOfFirst { it.path == previous }.takeIf { it >= 0 }?.let { ports.selectedIndex = it }
        }
        appendLog(
            if (found.isEmpty()) {
                "No serial devices found. Enable Bluetooth, pair the GT, bind /dev/rfcomm0, then refresh."
            } else {
                "Found " + found.size + " serial device(s)"
            },
        )
    }

    private fun runTask(message: String, work: () -> Unit) {
        if (activeTask?.isDone == false) return
        setBusy(true)
        status.text = message
        activeTask = executor.submit {
            try {
                work()
            } catch (_: InterruptedException) {
                onUi { status.text = "Operation cancelled" }
                appendLog("Operation cancelled")
            } catch (error: Throwable) {
                val messageText = error.message ?: error.javaClass.simpleName
                appendLog("Error: " + messageText)
                onUi {
                    status.text = "Error"
                    showError(messageText)
                    if (!transport.isOpen) setConnected(false)
                }
            } finally {
                onUi { setBusy(false) }
            }
        }
    }

    private fun disconnectNow(message: String) {
        activeTask?.cancel(true)
        transport.close()
        currentVin = null
        vinVisible = false
        setConnected(false)
        status.text = "Disconnected"
        adapter.text = "—"
        voltage.text = "—"
        osId.text = "—"
        vin.text = "—"
        appendLog(message)
    }

    private fun setBusy(busy: Boolean) {
        refresh.isEnabled = !busy
        connect.isEnabled = !busy && !transport.isOpen
        disconnect.isEnabled = busy || transport.isOpen
        identify.isEnabled = !busy && transport.isOpen
        reset.isEnabled = !busy && transport.isOpen
        ports.isEnabled = !busy && !transport.isOpen
        revealVin.isEnabled = currentVin != null
    }

    private fun setConnected(connected: Boolean) {
        connect.isEnabled = !connected
        disconnect.isEnabled = connected
        identify.isEnabled = connected
        reset.isEnabled = connected
        ports.isEnabled = !connected
        revealVin.isEnabled = currentVin != null
    }

    private fun appendLog(message: String) {
        onUi {
            val lines = (logArea.text.lines().filter { it.isNotBlank() } + message).takeLast(50)
            logArea.text = lines.joinToString("\n")
            logArea.caretPosition = logArea.document.length
        }
    }

    private fun maskedVin(value: String): String =
        if (value.length >= 4) "•••••••••••••" + value.takeLast(4) else "••••"

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(this, message, "GT FLASH", JOptionPane.ERROR_MESSAGE)
    }

    private fun onUi(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }
}

fun main() {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    SwingUtilities.invokeLater { GtFlashWindow().isVisible = true }
}
