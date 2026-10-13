package com.soundshare

/** La elección del enlace es independiente del transporte PLAYER por USB. */
enum class LinkChoice(val wire: String) {
    AUTO("auto"), USB("usb"), WIFI("wifi"), BLUETOOTH("bluetooth");
    companion object {
        fun parse(value: String?): LinkChoice = entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

object LinkSelection {
    fun native(choice: LinkChoice) = choice != LinkChoice.BLUETOOTH
    fun tryUsb(choice: LinkChoice) = choice == LinkChoice.AUTO || choice == LinkChoice.USB
    fun tryWifi(choice: LinkChoice, paired: Boolean) = (choice == LinkChoice.AUTO || choice == LinkChoice.WIFI) && paired
}
