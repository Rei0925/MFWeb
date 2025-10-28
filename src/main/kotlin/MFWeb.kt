package com.github.rei0925.mfweb

import com.github.rei0925.kotlincli.commands.CommandManager
import com.github.rei0925.magufinance.api.FinancePlugin
import org.slf4j.LoggerFactory

class MFWeb : FinancePlugin {
    private val logger = LoggerFactory.getLogger("MFWeb")
    override fun onEnable() {
        RealTimeCast.start()
        val manager = CommandManager()
        manager.registerCommand(CommandListener())

        logger.info("MFWebを起動しました")
    }

    override fun onDisable() {
        RealTimeCast.stopInstance()
        logger.info("MW終了")
    }
}